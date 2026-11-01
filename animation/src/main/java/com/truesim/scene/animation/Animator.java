package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.common.math.TransformArray;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * Poses one instance's scene nodes from its rig's layers ({@link Animators#add}). Each layer plays motions (clips and
 * blend spaces), cross-fading between them; {@link Animators#update} moves them on, samples and blends them (layers
 * over each other by weight and mask, additive layers adding their moves) and writes the pose to the scene. Nodes no
 * clip of the rig moves are left as they are; a scale keyed to zero poses at a millionth, as the scene keeps nodes from
 * vanishing on an axis. What a frame changes takes primitives and allocates nothing.
 *
 * <pre>{@code
 * Animator a = animators.add(rig, nodes);
 * a.layer(base).play(move);
 * // each frame
 * a.input(move, speed);
 * if (attack) a.layer(upper).playOnce(slash, 0.1, 0.2);
 * double blink = a.curve(BLINK);
 * }</pre>
 *
 * <p>After the layers, in order: overrides of joints ({@link #override}), two-bone inverse kinematics ({@link #ik}),
 * look-ats ({@link #lookAt}) and the rig's pose modifiers; targets in world coordinates. Joints the game takes over
 * ({@link #release}) are left to it.
 *
 * <p>The quick way, on a rig of one layer binding clips as they play ({@link Animators#add(Skeleton, long[])}):
 * {@code animator.play(clip)}.
 */
public final class Animator {

    /** Motions a layer blends at once: playing one more drops the lightest. */
    public static final int STATES = 4;
    private static final double[] IDENTITY = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};

    private final Rig rig;
    private final Skeleton skeleton;
    private final SceneGraph scene;
    private final long[] nodes;
    private final Playback[] layers;
    private final double[] parameters;
    private final double[] blendWeight = new double[1];
    private long starts; // motions started, to tell a layer's newest
    // The pose and its scratch: 10 values per node; morph weights and curves after the rig's layout.
    private final double[] pose;
    private final double[] below;
    private final double[] acc;
    private final double[] sample;
    private double[] scalars = new double[0];
    private double[] belowScalars = new double[0];
    private double[] accScalars = new double[0];
    private double[] sampleScalars = new double[0];
    private int[] cursors = new int[0];
    private double[] scratch = new double[4];
    private int rigVersion = -1;
    // Events passed this update, dispatched in order after the poses are written.
    private AnimationEvent[] events = new AnimationEvent[8];
    private float[] eventWeights = new float[8];
    private int eventCount;
    // Root motion: the update's, and scratch for working it out.
    private final RootDelta rootDelta = new RootDelta();
    private final double[] layerDelta = new double[4];
    private final double[] stateDelta = new double[4];
    private final double[] segment = new double[4];
    private final double[] rootA = new double[7];
    private final double[] rootB = new double[7];
    private final int[] rootCursor = new int[1];
    // Solvers: their targets; the model pose (in the instance's frame) they work on, the instance's world transform,
    // which joints they moved this update and which the game took over.
    private final IkTarget[] iks;
    private final AimTarget[] aims;
    private final JointOverride[] overrides;
    private final double[] modifierWeights;
    private final Pose view = new Pose(this);
    private final double[] model;
    private final double[] world = new double[Skeleton.TRANSFORM];
    private final double[] link = new double[Skeleton.TRANSFORM];
    private final boolean[] touched;
    private final boolean[] released;
    private final boolean[] solvedLast; // posed by solvers alone last update: they start from rest again
    private final boolean[] reset;      // and are written at rest, whether solved again or not
    private boolean anyTouched;
    private final double[] v0 = new double[3];
    private final double[] v1 = new double[3];
    private final double[] v2 = new double[3];
    private final double[] v3 = new double[3];
    private final double[] q0 = new double[4];
    private final double[] q1 = new double[4];
    private final double[] saved = new double[8];
    private final double[] goal = new double[3];

    @AllowAllocation("animators are made when models spawn")
    Animator(Rig rig, long[] nodes, SceneGraph scene) {
        this.rig = Objects.requireNonNull(rig, "rig");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.skeleton = rig.skeleton();
        if (nodes.length != skeleton.size()) {
            throw new IllegalArgumentException("A scene node per skeleton node: " + nodes.length + " for "
                + skeleton.size());
        }
        this.nodes = nodes.clone();
        int n = Skeleton.TRANSFORM * nodes.length;
        this.pose = new double[n];
        this.below = new double[n];
        this.acc = new double[n];
        this.sample = new double[n];
        skeleton.restPose(pose);
        this.layers = new Playback[rig.layerCount()];
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new Playback(rig.layer(i));
        }
        this.parameters = new double[rig.blendSpaceCount()];
        this.model = new double[n];
        this.touched = new boolean[nodes.length];
        this.released = new boolean[nodes.length];
        this.solvedLast = new boolean[nodes.length];
        this.reset = new boolean[nodes.length];
        this.overrides = new JointOverride[nodes.length];
        this.iks = new IkTarget[rig.ikCount()];
        for (int i = 0; i < iks.length; i++) {
            iks[i] = new IkTarget(rig.ik(i));
        }
        this.aims = new AimTarget[rig.lookCount()];
        for (int i = 0; i < aims.length; i++) {
            aims[i] = new AimTarget(rig.look(i));
        }
        this.modifierWeights = new double[rig.modifierCount()];
        Arrays.fill(modifierWeights, 1);
        fit();
    }

    /** Makes room for what the rig binds: an open rig binds clips as they first play. */
    @AllowAllocation("a rig binding a clip the first time it plays")
    private void fit() {
        if (rigVersion == rig.version()) {
            return;
        }
        rigVersion = rig.version();
        int scalarCount = rig.scalarCount();
        scalars = Arrays.copyOf(scalars, scalarCount);
        belowScalars = new double[scalarCount];
        accScalars = new double[scalarCount];
        sampleScalars = new double[scalarCount];
        cursors = new int[rig.cursorCount()];
        int widest = 4;
        for (int c = 0; c < rig.clipCount(); c++) {
            for (Track track : rig.clip(c).clip().tracks()) {
                widest = Math.max(widest, track.components());
            }
        }
        scratch = new double[widest];
    }

    public Rig rig() {
        return rig;
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    /** The scene node posed for skeleton node {@code node}. */
    public long node(int node) {
        return nodes[node];
    }

    /** Playback on {@code layer}, a layer of its rig. */
    public Playback layer(Rig.Layer layer) {
        rig.checkOwn(layer, layer.rig());
        return layers[layer.index()];
    }

    /** Sets {@code space}'s parameter: where among its clips it blends. */
    public void input(BlendSpace1 space, double parameter) {
        rig.checkOwn(space, space.rig());
        if (!Double.isFinite(parameter)) {
            throw new IllegalArgumentException("Not a parameter: " + parameter);
        }
        parameters[space.index] = parameter;
    }

    /** {@code space}'s parameter. */
    public double parameter(BlendSpace1 space) {
        rig.checkOwn(space, space.rig());
        return parameters[space.index];
    }

    /** The target of {@code ik}, a chain of its rig: off (weight 0) until given one. */
    public IkTarget ik(TwoBoneIk ik) {
        rig.checkOwn(ik, ik.rig());
        return iks[ik.index];
    }

    /** The target of {@code look}, a look-at of its rig: off (weight 0) until given one. */
    public AimTarget lookAt(LookAt look) {
        rig.checkOwn(look, look.rig());
        return aims[look.index];
    }

    /** An override of {@code joint}'s rotation in {@code space}: off (weight 0) until given one. */
    @AllowAllocation("an override, the first time a joint has one")
    public JointOverride override(Joint joint, Space space) {
        int j = rig.jointIndex(joint);
        Objects.requireNonNull(space, "space");
        if (overrides[j] == null) {
            overrides[j] = new JointOverride(j);
        }
        overrides[j].space = space;
        return overrides[j];
    }

    /** Leaves {@code joint} to the game from now on: the animator neither poses nor reads it but as the scene has it. */
    public void release(Joint joint) {
        released[rig.jointIndex(joint)] = true;
    }

    /** Takes {@code joint} back from the game. */
    public void hold(Joint joint) {
        released[rig.jointIndex(joint)] = false;
    }

    /** Whether the game has {@code joint} ({@link #release}). */
    public boolean released(Joint joint) {
        return released[rig.jointIndex(joint)];
    }

    /** How much {@code modifier}, one of the rig's, changes this animator's pose: 0 to 1, 1 at first. */
    public void weight(PoseModifier modifier, double weight) {
        int i = rig.modifierIndex(modifier);
        if (i < 0) {
            throw new IllegalArgumentException("Not a modifier of the rig: " + modifier);
        }
        modifierWeights[i] = checkWeight(weight);
    }

    private static double checkWeight(double weight) {
        if (!(weight >= 0 && weight <= 1)) {
            throw new IllegalArgumentException("A weight from 0 to 1: " + weight);
        }
        return weight;
    }

    /**
     * The rig's root motion of the last update: what its clips moved the root joint by on the axes it takes, which the
     * pose leaves out. All 0 for a rig without root motion.
     */
    public RootDelta rootDelta() {
        return rootDelta;
    }

    /** {@code curve}'s value as of the last update, blended as the clips keying it are; 0 if none does. */
    public double curve(AnimationCurve curve) {
        int slot = rig.curveSlot(curve);
        return slot < 0 ? 0 : scalars[rig.curveOffset() + slot];
    }

    /**
     * Plays {@code clip} from its start on the rig's first layer, in place of what played there: the quick way, on a
     * rig binding clips as they first play ({@link Rig#of}).
     */
    public ClipState play(AnimationClip clip) {
        return layers[0].play(rig.clipOf(clip));
    }

    /** Stops every layer at once: the nodes keep their last pose. */
    public void stop() {
        for (Playback layer : layers) {
            layer.stop(0);
        }
    }

    /** The newest motion of the first layer, or null. */
    public ClipState playing() {
        return layers[0].playing();
    }

    /** How many morph weights the rig's clips set on skeleton node {@code node}: 0 when none. */
    public int weightCount(int node) {
        return rig.weightCount(node);
    }

    /** Morph weight {@code target} of skeleton node {@code node}, as of the last update. */
    public double weight(int node, int target) {
        return scalars[rig.weightOffset(node) + Objects.checkIndex(target, rig.weightCount(node))];
    }

    /**
     * Playback on one layer: the motions playing there, cross-fading, and the layer's weight. From
     * {@link Animator#layer}.
     */
    public final class Playback {

        private final Rig.Layer layer;
        private final ClipState[] states = new ClipState[STATES];
        private int count;
        private double weight = 1;

        @AllowAllocation("animators are made when models spawn")
        private Playback(Rig.Layer layer) {
            this.layer = layer;
            for (int i = 0; i < STATES; i++) {
                states[i] = new ClipState(Animator.this);
            }
        }

        public Rig.Layer layer() {
            return layer;
        }

        /** Plays {@code motion} from its start, looping, in place of what played here. */
        public ClipState play(Motion motion) {
            return start(motion, 0, true, -1);
        }

        /** Cross-fades to {@code motion} over {@code fade} seconds, looping: what played fades out meanwhile. */
        public ClipState play(Motion motion, double fade) {
            return start(motion, fade, true, -1);
        }

        /**
         * Plays {@code motion} once, fading in over {@code fadeIn} seconds (what played fading out) and out over the
         * last {@code fadeOut} seconds; then it leaves the layer, and what is below shows again.
         */
        public ClipState playOnce(Motion motion, double fadeIn, double fadeOut) {
            checkFade(fadeOut);
            return start(motion, fadeIn, false, fadeOut);
        }

        /** Fades every motion here out over {@code fade} seconds (0: at once). */
        public void stop(double fade) {
            checkFade(fade);
            for (int i = 0; i < count; i++) {
                states[i].fadeOutOver(fade);
            }
            drop();
        }

        /** How much the layer counts, 0 to 1: times its mask's weights, over the layers below. */
        public Playback weight(double weight) {
            if (!(weight >= 0 && weight <= 1)) {
                throw new IllegalArgumentException("A weight from 0 to 1: " + weight);
            }
            this.weight = weight;
            return this;
        }

        public double weight() {
            return weight;
        }

        /** How many motions play here, fading ones too. */
        public int size() {
            return count;
        }

        /** Motion {@code i} playing here, oldest first. */
        public ClipState state(int i) {
            return states[Objects.checkIndex(i, count)];
        }

        /** The newest motion here, or null. */
        public ClipState playing() {
            return count == 0 ? null : states[count - 1];
        }

        private ClipState start(Motion motion, double fade, boolean loop, double fadeOut) {
            Objects.requireNonNull(motion, "motion");
            rig.checkOwn(motion, motion.rig());
            checkFade(fade);
            if (motion instanceof Clip clip && clip.additive() != layer.isAdditive()) {
                throw new IllegalArgumentException((clip.additive() ? "An additive clip plays on additive layers: "
                    : "An additive layer plays additive clips: ") + clip);
            }
            fit();
            for (int i = 0; i < count; i++) {
                states[i].fadeOutOver(fade);
            }
            drop();
            if (count == STATES) { // the lightest makes room
                int lightest = 0;
                for (int i = 1; i < count; i++) {
                    if (states[i].effectiveWeight() < states[lightest].effectiveWeight()) {
                        lightest = i;
                    }
                }
                remove(lightest);
            }
            ClipState state = states[count++]; // the newest last: they stay in the order they started
            state.start(motion, fade, loop, fadeOut, starts++);
            return state;
        }

        private void checkFade(double fade) {
            if (!(fade >= 0) || fade == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("Not a fade: " + fade);
            }
        }

        /** Removes the motions that are gone. */
        private void drop() {
            for (int i = count - 1; i >= 0; i--) {
                if (states[i].gone()) {
                    remove(i);
                }
            }
        }

        private void remove(int i) {
            ClipState gone = states[i];
            System.arraycopy(states, i + 1, states, i, count - i - 1);
            states[--count] = gone; // kept for reuse
        }

        /** Moves its motions on, but for followers' phases; queues the events of those that moved. */
        private void advance(double seconds) {
            for (int i = 0; i < count; i++) {
                ClipState state = states[i];
                boolean fresh = state.fresh;
                state.fresh = false;
                boolean follows = state.leader != null && state.leader != state;
                double before = state.advance(seconds, follows);
                double weightNow = weight * state.effectiveWeight();
                if (weightNow > 0 && !follows) {
                    events(state, before, weightNow, fresh);
                }
            }
        }

        /** Its followers take their leaders' phases. */
        private void follow() {
            for (int i = 0; i < count; i++) {
                ClipState state = states[i];
                if (state.leader != null && state.leader != state) {
                    Animator.this.follow(state, state.leader);
                }
            }
        }

        private void sample() {
            Animator.this.sample(this);
        }
    }

    /** Queues the events {@code state} passed from phase {@code before} to its phase now, across a loop's end too. */
    private void events(ClipState state, double before, double weight, boolean fresh) {
        if (state.motion instanceof Clip clip) {
            events(clip, before, state.phase, state.speed(), weight, fresh);
            return;
        }
        BlendSpace1 space = (BlendSpace1) state.motion;
        int first = space.blend(parameters[space.index], blendWeight);
        double a = blendWeight[0];
        events(space.clip(first), before, state.phase, state.speed(), weight * a, fresh);
        if (a < 1) {
            events(space.clip(first + 1), before, state.phase, state.speed(), weight * (1 - a), fresh);
        }
    }

    private void events(Clip clip, double from, double to, double speed, double weight, boolean fresh) {
        double duration = clip.duration();
        double t0 = from * duration;
        double t1 = to * duration;
        boolean wrapped = speed >= 0 ? to < from : to > from;
        for (int e = 0; e < clip.eventCount(); e++) {
            double t = clip.eventTime(e);
            boolean passed = fresh && t == t0; // where it starts
            if (speed >= 0) {
                passed |= wrapped ? t > t0 || t <= t1 : t > t0 && t <= t1;
            } else {
                passed |= wrapped ? t < t0 || t >= t1 : t < t0 && t >= t1;
            }
            if (passed) {
                queue(clip.event(e), weight);
            }
        }
    }

    @AllowAllocation("growing the event queue")
    private void queue(AnimationEvent event, double weight) {
        if (eventCount == events.length) {
            events = Arrays.copyOf(events, 2 * eventCount);
            eventWeights = Arrays.copyOf(eventWeights, 2 * eventCount);
        }
        events[eventCount] = event;
        eventWeights[eventCount++] = (float) weight;
    }

    /**
     * Moves every layer's motions on by {@code seconds}, queueing the events they pass: each sync group's leader first,
     * then its followers to the leader's phase.
     */
    void advance(double seconds) {
        lead();
        for (Playback layer : layers) {
            layer.advance(seconds);
        }
        for (Playback layer : layers) {
            layer.follow();
        }
        for (Playback layer : layers) {
            layer.drop();
        }
    }

    /** Picks each sync group's leader: the newest made to lead, else the heaviest (the oldest of equals). */
    private void lead() {
        for (Playback layer : layers) {
            for (int i = 0; i < layer.count; i++) {
                ClipState state = layer.states[i];
                state.leader = null;
                if (state.group() == null) {
                    continue;
                }
                ClipState best = null;
                double bestWeight = -1;
                for (Playback other : layers) {
                    for (int k = 0; k < other.count; k++) {
                        ClipState candidate = other.states[k];
                        if (candidate.group() != state.group()) {
                            continue;
                        }
                        double weight = other.weight * candidate.effectiveWeight();
                        boolean better;
                        if (best == null) {
                            better = true;
                        } else if (candidate.leads() != best.leads()) {
                            better = candidate.leads();
                        } else if (candidate.leads()) {
                            better = candidate.started > best.started;
                        } else {
                            better = weight > bestWeight;
                        }
                        if (better) {
                            best = candidate;
                            bestWeight = weight;
                        }
                    }
                }
                state.leader = best;
            }
        }
    }

    /**
     * Puts {@code follower} at {@code leader}'s phase: marker by marker where their clips share the leader's markers
     * around its time (between the same two markers, as far between them), else as far through.
     */
    private void follow(ClipState follower, ClipState leader) {
        double phase = leader.phase;
        Clip lead = markerClip(leader);
        Clip clip = markerClip(follower);
        int markers = lead.markerCount();
        int own = clip.markerCount();
        if (markers > 0 && own > 0) {
            double duration = lead.duration();
            double time = leader.phase * duration;
            int p = markers - 1; // the last marker at or before its time; before the first: the last, a loop ago
            for (int m = 0; m < markers; m++) {
                if (lead.markerTime(m) <= time) {
                    p = m;
                }
            }
            int n = (p + 1) % markers;
            double span = lead.markerTime(n) - lead.markerTime(p) + (n <= p ? duration : 0);
            double into = time - lead.markerTime(p) + (time < lead.markerTime(p) ? duration : 0);
            double fraction = span > 0 ? into / span : 0;
            for (int k = 0; k < own; k++) {
                int next = (k + 1) % own;
                if (clip.marker(k) == lead.marker(p) && clip.marker(next) == lead.marker(n)) {
                    double ownDuration = clip.duration();
                    double ownSpan = clip.markerTime(next) - clip.markerTime(k) + (next <= k ? ownDuration : 0);
                    double t = clip.markerTime(k) + fraction * ownSpan;
                    phase = ownDuration > 0 ? (t % ownDuration) / ownDuration : 0;
                    break;
                }
            }
        }
        follower.phase = follower.looping() ? phase : Math.min(Math.max(phase, 0), 1);
        follower.moved(leader.backwards);
    }

    /** The clip whose markers {@code state} goes by: its clip, or its blend space's heavier one. */
    private Clip markerClip(ClipState state) {
        if (state.motion instanceof Clip clip) {
            return clip;
        }
        BlendSpace1 space = (BlendSpace1) state.motion;
        int first = space.blend(parameters[space.index], blendWeight);
        return blendWeight[0] >= 0.5 ? space.clip(first) : space.clip(first + 1);
    }

    /** Samples and blends the layers into the pose; touches nothing shared, so animators sample in parallel. */
    void sample() {
        fit();
        for (int j = 0; j < nodes.length; j++) {
            if (rig.animated(j)) {
                for (int i = 0; i < Skeleton.TRANSFORM; i++) {
                    pose[Skeleton.TRANSFORM * j + i] = skeleton.rest(j, i);
                }
            }
        }
        Arrays.fill(scalars, 0);
        for (Playback playback : layers) {
            if (playback.count > 0 && playback.weight > 0) {
                playback.sample();
            }
        }
        rootDelta.clear();
        if (rig.rootJoint() >= 0) {
            rootMotion();
        }
        Arrays.fill(touched, false);
        anyTouched = false;
        for (int j = 0; j < nodes.length; j++) {
            reset[j] = solvedLast[j] && !released[j];
            if (reset[j]) {
                for (int i = 0; i < Skeleton.TRANSFORM; i++) {
                    pose[Skeleton.TRANSFORM * j + i] = skeleton.rest(j, i);
                }
                anyTouched = true;
            }
        }
        if (solving()) {
            solve();
        }
    }

    /**
     * Works out the update's root motion, blended as the override layers blend (additive layers add none), and takes
     * it out of the pose: the root joint at rest on the axes taken.
     */
    private void rootMotion() {
        int root = rig.rootJoint();
        RootMotion mode = rig.rootMotion();
        double dx = 0;
        double dy = 0;
        double dz = 0;
        double dyaw = 0;
        for (Playback playback : layers) {
            if (playback.count == 0 || playback.weight == 0 || playback.layer.isAdditive()) {
                continue;
            }
            Arrays.fill(layerDelta, 0);
            double total = 0;
            for (int i = 0; i < playback.count; i++) {
                ClipState state = playback.states[i];
                double w = state.effectiveWeight();
                total += w;
                if (w == 0) {
                    continue;
                }
                if (state.motion instanceof Clip clip) {
                    moveOf(clip, state, root, mode, w);
                } else {
                    BlendSpace1 space = (BlendSpace1) state.motion;
                    int first = space.blend(parameters[space.index], blendWeight);
                    double a = blendWeight[0];
                    moveOf(space.clip(first), state, root, mode, w * a);
                    if (a < 1) {
                        moveOf(space.clip(first + 1), state, root, mode, w * (1 - a));
                    }
                }
            }
            if (total == 0) {
                continue;
            }
            double norm = Math.max(1, total);
            double k = playback.weight * playback.layer.weight(root);
            dx += k * (layerDelta[0] / norm - dx);
            dy += k * (layerDelta[1] / norm - dy);
            dz += k * (layerDelta[2] / norm - dz);
            dyaw += k * (layerDelta[3] / norm - dyaw);
        }
        rootDelta.dx = dx;
        rootDelta.dy = mode.height() ? dy : 0;
        rootDelta.dz = dz;
        rootDelta.yaw = mode.yaw() ? dyaw : 0;
        if (!rig.animated(root)) {
            return;
        }
        int b = Skeleton.TRANSFORM * root;
        pose[b] = skeleton.rest(root, 0);
        pose[b + 2] = skeleton.rest(root, 2);
        if (mode.height()) {
            pose[b + 1] = skeleton.rest(root, 1);
        }
        if (mode.yaw()) {
            // The rotation without its turn about y (its twist), with the rest pose's instead
            double ty = pose[b + 4];
            double tw = pose[b + 6];
            double length = Maths.sqrt(ty * ty + tw * tw);
            if (length > 0) {
                ty /= length;
                tw /= length;
                double ry = skeleton.rest(root, 4);
                double rw = skeleton.rest(root, 6);
                double rl = Maths.sqrt(ry * ry + rw * rw);
                ry = rl > 0 ? ry / rl : 0;
                rw = rl > 0 ? rw / rl : 1;
                // twist' = conjugate(twist) * restTwist: both about y
                double cy = tw * ry - ty * rw;
                double cw = tw * rw + ty * ry;
                multiplyBy(pose, b + 3, 0, cy, 0, cw);
            }
        }
    }

    /** {@code q} (at {@code at}) times (x, y, z, w), in place. */
    private static void multiplyBy(double[] q, int at, double x, double y, double z, double w) {
        double ax = q[at];
        double ay = q[at + 1];
        double az = q[at + 2];
        double aw = q[at + 3];
        q[at] = aw * x + ax * w + ay * z - az * y;
        q[at + 1] = aw * y - ax * z + ay * w + az * x;
        q[at + 2] = aw * z + ax * y - ay * x + az * w;
        q[at + 3] = aw * w - ax * x - ay * y - az * z;
    }

    /**
     * Adds to {@link #layerDelta}, weighing {@code w}, how far {@code clip} moved the root over {@code state}'s last
     * update: from its phase before to its phase now, across its end (its start, backwards) if it passed it.
     */
    private void moveOf(Clip clip, ClipState state, int root, RootMotion mode, double w) {
        double duration = clip.duration();
        double t0 = state.before * duration;
        double t1 = state.phase * duration;
        if (!state.wrapped) {
            move(clip, root, mode, t0, t1, stateDelta);
        } else {
            move(clip, root, mode, t0, state.backwards ? 0 : duration, stateDelta);
            move(clip, root, mode, state.backwards ? duration : 0, t1, segment);
            // The second part as the first left the root facing
            double c = Maths.cos(stateDelta[3]);
            double s = Maths.sin(stateDelta[3]);
            stateDelta[0] += c * segment[0] + s * segment[2];
            stateDelta[1] += segment[1];
            stateDelta[2] += -s * segment[0] + c * segment[2];
            stateDelta[3] += segment[3];
        }
        for (int i = 0; i < 4; i++) {
            layerDelta[i] += w * stateDelta[i];
        }
    }

    /**
     * How far {@code clip} moves the root from {@code from} to {@code to} seconds into {@code out}: (x, y, z) in its
     * frame at {@code from} when the turn is taken (else its parent's), and the turn about y.
     */
    private void move(Clip clip, int root, RootMotion mode, double from, double to, double[] out) {
        clip.root(root, from, rootCursor, rootA, scratch);
        clip.root(root, to, rootCursor, rootB, scratch);
        double x = rootB[0] - rootA[0];
        double z = rootB[2] - rootA[2];
        out[1] = rootB[1] - rootA[1];
        if (mode.yaw()) {
            double yawA = 2 * Maths.atan2(rootA[4], rootA[6]);
            double yawB = 2 * Maths.atan2(rootB[4], rootB[6]);
            double turn = yawB - yawA;
            turn -= 2 * Math.PI * Math.floor((turn + Math.PI) / (2 * Math.PI)); // the short way: -pi to pi
            double c = Maths.cos(yawA);
            double s = Maths.sin(yawA);
            out[0] = c * x - s * z;
            out[2] = s * x + c * z;
            out[3] = turn;
        } else {
            out[0] = x;
            out[2] = z;
            out[3] = 0;
        }
    }

    /** Blends {@code playback}'s motions, then them over the pose by its weight and mask (or adds their moves). */
    private void sample(Playback playback) {
        int n = nodes.length;
        boolean additive = playback.layer.isAdditive();
        // What the layer blends from: the pose so far, or no move for an additive layer
        if (additive) {
            for (int j = 0; j < n; j++) {
                System.arraycopy(IDENTITY, 0, below, Skeleton.TRANSFORM * j, Skeleton.TRANSFORM);
            }
            Arrays.fill(belowScalars, 0);
        } else {
            System.arraycopy(pose, 0, below, 0, pose.length);
            System.arraycopy(scalars, 0, belowScalars, 0, scalars.length);
        }
        Arrays.fill(acc, 0);
        Arrays.fill(accScalars, 0);
        double total = 0;
        for (int i = 0; i < playback.count; i++) {
            ClipState state = playback.states[i];
            double w = state.effectiveWeight();
            total += w;
            if (w == 0) {
                continue;
            }
            if (state.motion instanceof Clip clip) {
                accumulate(clip, state.phase * clip.duration(), w);
            } else {
                BlendSpace1 space = (BlendSpace1) state.motion;
                int first = space.blend(parameters[space.index], blendWeight);
                double a = blendWeight[0];
                Clip one = space.clip(first);
                accumulate(one, state.phase * one.duration(), w * a);
                if (a < 1) {
                    Clip two = space.clip(first + 1);
                    accumulate(two, state.phase * two.duration(), w * (1 - a));
                }
            }
        }
        if (total == 0) {
            return;
        }
        // What the layer blends from fills in what its motions weigh less than 1; more than 1 is normalized
        double rest = Math.max(0, 1 - total);
        double norm = Math.max(1, total);
        for (int j = 0; j < n; j++) {
            if (!rig.animated(j)) {
                continue;
            }
            int b = Skeleton.TRANSFORM * j;
            for (int i = 0; i < Skeleton.TRANSFORM; i++) {
                acc[b + i] = (acc[b + i] + rest * below[b + i]) / norm;
            }
            normalize(acc, b + 3);
            double k = playback.weight * playback.layer.weight(j);
            if (additive) {
                apply(j, k);
            } else {
                blend(j, k);
            }
        }
        for (int s = 0; s < scalars.length; s++) {
            double value = (accScalars[s] + rest * belowScalars[s]) / norm;
            scalars[s] += playback.weight * (additive ? value : value - scalars[s]);
        }
    }

    /** Adds {@code clip} at {@code time}, weighing {@code w}, over what the layer blends from, into the sums. */
    private void accumulate(Clip clip, double time, double w) {
        System.arraycopy(below, 0, sample, 0, below.length);
        System.arraycopy(belowScalars, 0, sampleScalars, 0, belowScalars.length);
        clip.sample(time, cursors, sample, sampleScalars, scratch);
        for (int j = 0; j < nodes.length; j++) {
            if (rig.animated(j)) {
                int b = Skeleton.TRANSFORM * j;
                // Rotations summed on the side of what they blend from
                double dot = sample[b + 3] * below[b + 3] + sample[b + 4] * below[b + 4]
                    + sample[b + 5] * below[b + 5] + sample[b + 6] * below[b + 6];
                double sign = dot < 0 ? -w : w;
                for (int i = 0; i < Skeleton.TRANSFORM; i++) {
                    acc[b + i] += (i >= 3 && i <= 6 ? sign : w) * sample[b + i];
                }
            }
        }
        for (int s = 0; s < sampleScalars.length; s++) {
            accScalars[s] += w * sampleScalars[s];
        }
    }

    /**
     * The pose's joint {@code j} toward the layer's by {@code k}: straight, normalized (the layer's rotations were summed
     * on the pose's side, so this is the short way).
     */
    private void blend(int j, double k) {
        int b = Skeleton.TRANSFORM * j;
        for (int i = 0; i < Skeleton.TRANSFORM; i++) {
            pose[b + i] += k * (acc[b + i] - pose[b + i]);
        }
        normalize(pose, b + 3);
    }

    /** Adds the layer's moves of joint {@code j}, by {@code k}, to the pose. */
    private void apply(int j, double k) {
        int b = Skeleton.TRANSFORM * j;
        for (int i = 0; i < 3; i++) {
            pose[b + i] += k * acc[b + i];
            pose[b + 7 + i] *= 1 + k * (acc[b + 7 + i] - 1);
        }
        // The rotation's move, from none by k (summed on no move's side: the short way), before the pose's
        double dx = k * acc[b + 3];
        double dy = k * acc[b + 4];
        double dz = k * acc[b + 5];
        double dw = 1 - k + k * acc[b + 6];
        double px = pose[b + 3];
        double py = pose[b + 4];
        double pz = pose[b + 5];
        double pw = pose[b + 6];
        pose[b + 3] = dw * px + dx * pw + dy * pz - dz * py;
        pose[b + 4] = dw * py - dx * pz + dy * pw + dz * px;
        pose[b + 5] = dw * pz + dx * py - dy * px + dz * pw;
        pose[b + 6] = dw * pw - dx * px - dy * py - dz * pz;
        normalize(pose, b + 3);
    }

    private static void normalize(double[] q, int at) {
        double length = Maths.sqrt(q[at] * q[at] + q[at + 1] * q[at + 1] + q[at + 2] * q[at + 2]
            + q[at + 3] * q[at + 3]);
        if (length > 0) {
            for (int i = 0; i < 4; i++) {
                q[at + i] /= length;
            }
        } else {
            q[at + 3] = 1;
        }
    }

    // ---------------------------------------------------------------- solvers

    /** Whether anything after the layers changes the pose this update. */
    private boolean solving() {
        for (JointOverride o : overrides) {
            if (o != null && o.weight > 0) {
                return true;
            }
        }
        for (IkTarget ik : iks) {
            if (ik.weight > 0) {
                return true;
            }
        }
        for (AimTarget aim : aims) {
            if (aim.weight > 0) {
                return true;
            }
        }
        for (double weight : modifierWeights) {
            if (weight > 0) {
                return true;
            }
        }
        return false;
    }

    /** Overrides, inverse kinematics, look-ats and modifiers, in that order, on the model pose. */
    private void solve() {
        // Joints no clip poses are as the scene has them (the game's), but those only the solvers posed last update
        // start from rest, as clips' joints do: solutions do not build on each other.
        TransformArray locals = scene.localTransforms();
        MemorySegment segment = locals.segment();
        for (int j = 0; j < nodes.length; j++) {
            int b = Skeleton.TRANSFORM * j;
            if ((!rig.animated(j) || released[j]) && !reset[j]) {
                long offset = NodeHandle.index(nodes[j]) * locals.stride();
                for (int i = 0; i < Skeleton.TRANSFORM; i++) {
                    pose[b + i] = segment.get(ValueLayout.JAVA_DOUBLE, offset + 8L * i);
                }
            }
        }
        instanceWorld(segment, locals.stride());
        Kinematics.forward(skeleton, pose, model);
        for (JointOverride o : overrides) {
            if (o != null && o.weight > 0 && !released[o.joint]) {
                override(o);
            }
        }
        for (IkTarget ik : iks) {
            if (ik.weight > 0) {
                toModel(ik.target, v3);
                solve(ik.ik, v3, ik.hasPole ? ik.pole : null, ik.hasRotation ? ik.rotation : null, ik.weight);
            }
        }
        for (AimTarget aim : aims) {
            if (aim.weight > 0) {
                aim(aim);
            }
        }
        for (int i = 0; i < modifierWeights.length; i++) {
            if (modifierWeights[i] > 0) {
                view.weight = modifierWeights[i];
                rig.modifier(i).modify(view);
            }
        }
    }

    /** The instance's world transform (the skeleton root's parent's), from the scene's local transforms as they are. */
    private void instanceWorld(MemorySegment segment, long stride) {
        System.arraycopy(IDENTITY, 0, world, 0, Skeleton.TRANSFORM);
        for (long node = scene.parent(nodes[0]); node != NodeHandle.NONE; node = scene.parent(node)) {
            long offset = NodeHandle.index(node) * stride;
            for (int i = 0; i < Skeleton.TRANSFORM; i++) {
                link[i] = segment.get(ValueLayout.JAVA_DOUBLE, offset + 8L * i);
            }
            Kinematics.compose(link, 0, world, 0, world, 0); // the ancestor's transform, then what is below it
        }
    }

    /** World point {@code p} into the instance's frame, into {@code out}. */
    private void toModel(double[] p, double[] out) {
        out[0] = p[0] - world[0];
        out[1] = p[1] - world[1];
        out[2] = p[2] - world[2];
        Kinematics.unrotate(world, 3, out);
        out[0] /= world[7];
        out[1] /= world[8];
        out[2] /= world[9];
    }

    /** World rotation {@code q} into the instance's frame, into {@code out}. */
    private void toModelRotation(double[] q, double[] out) {
        out[0] = -world[3];
        out[1] = -world[4];
        out[2] = -world[5];
        out[3] = world[6];
        Kinematics.multiply(out, 0, q[0], q[1], q[2], q[3]);
    }

    /** Joint {@code j}'s world position, into {@code out}. */
    void worldPosition(int j, double[] out) {
        int b = Skeleton.TRANSFORM * j;
        out[0] = model[b] * world[7];
        out[1] = model[b + 1] * world[8];
        out[2] = model[b + 2] * world[9];
        Kinematics.rotate(world, 3, out);
        out[0] += world[0];
        out[1] += world[1];
        out[2] += world[2];
    }

    /** The world height of the instance's frame's origin. */
    double groundHeight() {
        return world[1];
    }

    /** Moves joint {@code j} by world (dx, dy, dz): its translation in its parent's frame changed to match. */
    void moveWorld(int j, double dx, double dy, double dz) {
        v0[0] = dx;
        v0[1] = dy;
        v0[2] = dz;
        Kinematics.unrotate(world, 3, v0);
        v0[0] /= world[7];
        v0[1] /= world[8];
        v0[2] /= world[9];
        int parent = skeleton.parent(j);
        if (parent >= 0) {
            int p = Skeleton.TRANSFORM * parent;
            Kinematics.unrotate(model, p + 3, v0);
            v0[0] /= model[p + 7];
            v0[1] /= model[p + 8];
            v0[2] /= model[p + 9];
        }
        int b = Skeleton.TRANSFORM * j;
        pose[b] += v0[0];
        pose[b + 1] += v0[1];
        pose[b + 2] += v0[2];
        touch(j);
        Kinematics.forward(skeleton, pose, model);
    }

    /** Brings {@code ik}'s end to world (x, y, z) by {@code weight}, bending as the chain is. */
    void reach(TwoBoneIk ik, double x, double y, double z, double weight) {
        v2[0] = x;
        v2[1] = y;
        v2[2] = z;
        toModel(v2, v3);
        solve(ik, v3, null, null, weight);
    }

    private void touch(int j) {
        touched[j] = true;
        anyTouched = true;
    }

    /** Sets joint {@code j}'s model rotation to {@code q}, through its local one. */
    private void setModelRotation(int j, double[] q, int at) {
        int b = Skeleton.TRANSFORM * j;
        System.arraycopy(q, at, pose, b + 3, 4);
        int parent = skeleton.parent(j);
        if (parent >= 0) {
            int p = Skeleton.TRANSFORM * parent;
            Kinematics.premultiply(pose, b + 3, -model[p + 3], -model[p + 4], -model[p + 5], model[p + 6]);
        }
        Kinematics.normalizeQuaternion(pose, b + 3);
    }

    private void override(JointOverride o) {
        int b = Skeleton.TRANSFORM * o.joint;
        System.arraycopy(pose, b + 3, saved, 0, 4);
        if (o.space == Space.LOCAL) {
            System.arraycopy(o.rotation, 0, pose, b + 3, 4);
        } else {
            setModelRotation(o.joint, o.rotation, 0);
        }
        Kinematics.nlerp(saved, 0, pose, b + 3, o.weight);
        touch(o.joint);
        Kinematics.forward(skeleton, pose, model);
    }

    /**
     * Holden's two-bone solution in the model pose: the reach clamped (or stretched), the angles at the upper and middle
     * joints set by the law of cosines in the chain's plane, the chain turned to the target, then about the line to it
     * so the middle joint is in the pole's plane; the end given {@code rotation} (model frame) if not null; all by
     * {@code weight} from the pose as it was.
     */
    private void solve(TwoBoneIk ik, double[] point, double[] poleWorld, double[] rotationWorld, double weight) {
        double[] target = goal; // its own: the scratch vectors are used below
        System.arraycopy(point, 0, target, 0, 3);
        int a = Skeleton.TRANSFORM * ik.upper;
        int b = Skeleton.TRANSFORM * ik.middle;
        int c = Skeleton.TRANSFORM * ik.end;
        System.arraycopy(pose, a + 3, saved, 0, 4);
        System.arraycopy(pose, b + 3, saved, 4, 4);
        double lab = distance(model, a, b);
        double lcb = distance(model, b, c);
        double lat = Maths.sqrt(square(target[0] - model[a]) + square(target[1] - model[a + 1])
            + square(target[2] - model[a + 2]));
        if (ik.stretch() > 1 && lat > lab + lcb) {
            double k = 1 + weight * (Math.min(lat / (lab + lcb), ik.stretch()) - 1);
            for (int i = 0; i < 3; i++) {
                pose[b + i] *= k;
                pose[c + i] *= k;
            }
            touch(ik.end);
            Kinematics.forward(skeleton, pose, model);
            lab *= k;
            lcb *= k;
        }
        double eps = 1e-6;
        lat = Math.max(eps, Math.min(lat, lab + lcb - eps));
        // c - a, b - a, a - b, c - b, t - a
        difference(model, c, a, v0);
        difference(model, b, a, v1);
        double acab0 = angleBetween(v0, v1);
        double[] ba = v2;
        ba[0] = -v1[0];
        ba[1] = -v1[1];
        ba[2] = -v1[2];
        difference(model, c, b, v3);
        double babc0 = angleBetween(ba, v3);
        double acab1 = Maths.acos(clamp((lcb * lcb - lab * lab - lat * lat) / (-2 * lab * lat)));
        double babc1 = Maths.acos(clamp((lat * lat - lab * lab - lcb * lcb) / (-2 * lab * lcb)));
        // The bend's axis: across the chain's plane (or any across a straight chain)
        Kinematics.cross(v0, v1, v2);
        if (!(Kinematics.normalize(v2) > 1e-9)) {
            Kinematics.normalize(v0);
            Kinematics.across(v0, v2);
        }
        // The upper joint by the bend at it, the middle by its own; both about the axis in their own frames
        v1[0] = v2[0];
        v1[1] = v2[1];
        v1[2] = v2[2];
        Kinematics.unrotate(model, a + 3, v1);
        Kinematics.axisAngle(v1, acab1 - acab0, q0);
        Kinematics.multiply(pose, a + 3, q0[0], q0[1], q0[2], q0[3]);
        Kinematics.unrotate(model, b + 3, v2);
        Kinematics.axisAngle(v2, babc1 - babc0, q0);
        Kinematics.multiply(pose, b + 3, q0[0], q0[1], q0[2], q0[3]);
        Kinematics.forward(skeleton, pose, model);
        // Then the chain turned so its end points at the target
        difference(model, c, a, v0);
        v1[0] = target[0] - model[a];
        v1[1] = target[1] - model[a + 1];
        v1[2] = target[2] - model[a + 2];
        Kinematics.normalize(v0);
        Kinematics.normalize(v1);
        Kinematics.across(v0, v2);
        turn(ik.upper, v0, v1, v2);
        if (poleWorld != null) {
            // About the line to the target, so the middle joint is in the plane of the pole
            toModel(poleWorld, v3);
            v0[0] = target[0] - model[a];
            v0[1] = target[1] - model[a + 1];
            v0[2] = target[2] - model[a + 2];
            Kinematics.normalize(v0);
            difference(model, b, a, v1);
            v2[0] = v3[0] - model[a];
            v2[1] = v3[1] - model[a + 1];
            v2[2] = v3[2] - model[a + 2];
            across(v1, v0);
            across(v2, v0);
            if (Math.min(Kinematics.normalize(v1), Kinematics.normalize(v2)) > 1e-9) { // else no plane to turn into
                turn(ik.upper, v1, v2, v0); // right round, if opposite: about the line to the target
            }
        }
        // By the weight from where they were
        Kinematics.nlerp(saved, 0, pose, a + 3, weight);
        Kinematics.nlerp(saved, 4, pose, b + 3, weight);
        touch(ik.upper);
        touch(ik.middle);
        Kinematics.forward(skeleton, pose, model);
        if (rotationWorld != null) {
            System.arraycopy(pose, c + 3, saved, 0, 4);
            toModelRotation(rotationWorld, q1);
            setModelRotation(ik.end, q1, 0);
            Kinematics.nlerp(saved, 0, pose, c + 3, weight);
            touch(ik.end);
            Kinematics.forward(skeleton, pose, model);
        }
    }

    /**
     * Turns joint {@code j} (model frame) by the rotation taking unit vector {@code from} to {@code to}: about
     * {@code opposite} when they are opposite.
     */
    private void turn(int j, double[] from, double[] to, double[] opposite) {
        double angle = Kinematics.angle(from, to);
        Kinematics.cross(from, to, v3);
        if (!(Kinematics.normalize(v3) > 1e-12)) {
            if (angle < Math.PI / 2) {
                return; // the same way already
            }
            System.arraycopy(opposite, 0, v3, 0, 3);
        }
        Kinematics.unrotate(model, Skeleton.TRANSFORM * j + 3, v3);
        Kinematics.axisAngle(v3, angle, q0);
        Kinematics.multiply(pose, Skeleton.TRANSFORM * j + 3, q0[0], q0[1], q0[2], q0[3]);
        Kinematics.forward(skeleton, pose, model);
    }

    /** Points {@code aim}'s joint's axis at its target: the chain from its top down, each its share, the joint the rest. */
    private void aim(AimTarget aim) {
        LookAt look = aim.look;
        int h = Skeleton.TRANSFORM * look.joint;
        toModel(aim.target, v2); // the target in the instance's frame
        // Where the animation points it, and the target within the limit of that
        pointing(look, v0);
        v1[0] = v2[0] - model[h];
        v1[1] = v2[1] - model[h + 1];
        v1[2] = v2[2] - model[h + 2];
        double distance = Kinematics.normalize(v1);
        double angle = Kinematics.angle(v0, v1);
        if (angle > look.limit) {
            // v1 turned back toward v0, to the limit (about any axis across v0 if v1 is right behind it)
            Kinematics.cross(v0, v1, v3);
            if (!(Kinematics.normalize(v3) > 1e-9)) {
                Kinematics.across(v0, v3);
            }
            Kinematics.axisAngle(v3, look.limit, q1);
            v1[0] = v0[0];
            v1[1] = v0[1];
            v1[2] = v0[2];
            Kinematics.rotate(q1, 0, v1);
        }
        for (int i = 0; i < 3; i++) {
            v2[i] = model[h + i] + distance * v1[i]; // the target point, limited
        }
        for (int step = look.chain.length; step >= 0; step--) {
            int j = step == 0 ? look.joint : look.chain[step - 1];
            double share = (step == 0 ? 1 : look.shares[step - 1]) * aim.weight;
            pointing(look, v0);
            v1[0] = v2[0] - model[h];
            v1[1] = v2[1] - model[h + 1];
            v1[2] = v2[2] - model[h + 2];
            Kinematics.normalize(v1);
            double turn = Kinematics.angle(v0, v1) * share;
            Kinematics.cross(v0, v1, v3);
            if (Kinematics.normalize(v3) > 1e-12 && turn > 0) {
                Kinematics.unrotate(model, Skeleton.TRANSFORM * j + 3, v3);
                Kinematics.axisAngle(v3, turn, q0);
                Kinematics.multiply(pose, Skeleton.TRANSFORM * j + 3, q0[0], q0[1], q0[2], q0[3]);
                touch(j);
                Kinematics.forward(skeleton, pose, model);
            }
        }
    }

    /** Where {@code look}'s joint points its axis, in the model frame: into {@code out}. */
    private void pointing(LookAt look, double[] out) {
        out[0] = look.axis[0];
        out[1] = look.axis[1];
        out[2] = look.axis[2];
        Kinematics.rotate(model, Skeleton.TRANSFORM * look.joint + 3, out);
    }

    /** {@code v} less its part along unit {@code axis}. */
    private static void across(double[] v, double[] axis) {
        double d = Kinematics.dot(v, axis);
        v[0] -= d * axis[0];
        v[1] -= d * axis[1];
        v[2] -= d * axis[2];
    }

    private static double distance(double[] m, int a, int b) {
        return Maths.sqrt(square(m[b] - m[a]) + square(m[b + 1] - m[a + 1]) + square(m[b + 2] - m[a + 2]));
    }

    private static void difference(double[] m, int to, int from, double[] out) {
        out[0] = m[to] - m[from];
        out[1] = m[to + 1] - m[from + 1];
        out[2] = m[to + 2] - m[from + 2];
    }

    /** The angle between two vectors, normalizing copies of neither: they are normalized in place. */
    private static double angleBetween(double[] a, double[] b) {
        double la = Math.max(1e-12, Maths.sqrt(Kinematics.dot(a, a)));
        double lb = Math.max(1e-12, Maths.sqrt(Kinematics.dot(b, b)));
        return Maths.acos(clamp(Kinematics.dot(a, b) / (la * lb)));
    }

    private static double clamp(double cos) {
        return Math.max(-1, Math.min(1, cos));
    }

    private static double square(double x) {
        return x * x;
    }

    /** A two-bone chain's target on this animator: a point, optionally a pole and the end's rotation, and a weight. */
    public final class IkTarget {

        private final TwoBoneIk ik;
        private final double[] target = new double[3];
        private final double[] pole = new double[3];
        private final double[] rotation = {0, 0, 0, 1};
        private boolean hasPole;
        private boolean hasRotation;
        private double weight;

        private IkTarget(TwoBoneIk ik) {
            this.ik = ik;
        }

        /** Reaches the chain's end for world (x, y, z). */
        public IkTarget target(double x, double y, double z) {
            set(target, x, y, z);
            return this;
        }

        /** Bends the middle joint toward world (x, y, z): a knee forward, an elbow out. */
        public IkTarget pole(double x, double y, double z) {
            set(pole, x, y, z);
            hasPole = true;
            return this;
        }

        /** Bends as the chain bends now. */
        public IkTarget noPole() {
            hasPole = false;
            return this;
        }

        /** Gives the end this world rotation (a foot flat on a slope, a hand on a handle). */
        public IkTarget rotation(double x, double y, double z, double w) {
            rotation[0] = x;
            rotation[1] = y;
            rotation[2] = z;
            rotation[3] = w;
            Kinematics.normalizeQuaternion(rotation, 0);
            hasRotation = true;
            return this;
        }

        /** Leaves the end's rotation to the chain. */
        public IkTarget noRotation() {
            hasRotation = false;
            return this;
        }

        /** How far toward the solution, 0 (off) to 1. */
        public IkTarget weight(double weight) {
            this.weight = checkWeight(weight);
            return this;
        }

        public double weight() {
            return weight;
        }
    }

    /** A look-at's target on this animator: a point and a weight. */
    public final class AimTarget {

        private final LookAt look;
        private final double[] target = new double[3];
        private double weight;

        private AimTarget(LookAt look) {
            this.look = look;
        }

        /** Looks at world (x, y, z). */
        public AimTarget target(double x, double y, double z) {
            set(target, x, y, z);
            return this;
        }

        /** How far toward it, 0 (off) to 1. */
        public AimTarget weight(double weight) {
            this.weight = checkWeight(weight);
            return this;
        }

        public double weight() {
            return weight;
        }
    }

    /** A joint's rotation set by the game, over the animation by a weight. */
    public final class JointOverride {

        private final int joint;
        private final double[] rotation = {0, 0, 0, 1};
        private Space space = Space.LOCAL;
        private double weight;

        private JointOverride(int joint) {
            this.joint = joint;
        }

        /** The rotation, in its space. */
        public JointOverride rotation(double x, double y, double z, double w) {
            rotation[0] = x;
            rotation[1] = y;
            rotation[2] = z;
            rotation[3] = w;
            Kinematics.normalizeQuaternion(rotation, 0);
            return this;
        }

        /** How far over the animation, 0 (off) to 1. */
        public JointOverride weight(double weight) {
            this.weight = checkWeight(weight);
            return this;
        }

        public double weight() {
            return weight;
        }

        public Space space() {
            return space;
        }
    }

    private static void set(double[] v, double x, double y, double z) {
        if (!Double.isFinite(x + y + z)) {
            throw new IllegalArgumentException("Not a point: " + x + ", " + y + ", " + z);
        }
        v[0] = x;
        v[1] = y;
        v[2] = z;
    }

    /** Writes the pose to the scene: on the scene's thread, one animator after another. */
    void write(SceneGraph scene, Animators.MorphSink morphs) {
        boolean playing = anyTouched;
        for (Playback layer : layers) {
            playing |= layer.count > 0;
        }
        if (!playing) {
            return;
        }
        for (int j = 0; j < nodes.length; j++) {
            solvedLast[j] = touched[j] && !rig.animated(j) && !released[j];
            if ((rig.animated(j) || touched[j] || reset[j]) && !released[j]) {
                int b = Skeleton.TRANSFORM * j;
                scene.setLocal(nodes[j], pose[b], pose[b + 1], pose[b + 2], pose[b + 3], pose[b + 4], pose[b + 5],
                    pose[b + 6], visible(pose[b + 7]), visible(pose[b + 8]), visible(pose[b + 9]));
            }
            if (rig.weightCount(j) > 0 && morphs != null) {
                morphs.weights(nodes[j], scalars, rig.weightOffset(j), rig.weightCount(j));
            }
        }
    }

    /** The smallest scale a node is posed at: clips shrink nodes to nothing, which the scene refuses. */
    static final double MIN_SCALE = 1e-6;

    /** {@code scale}, or at least {@link #MIN_SCALE} on its side of zero. */
    static double visible(double scale) {
        return Math.abs(scale) >= MIN_SCALE ? scale : Math.copySign(MIN_SCALE, scale);
    }

    /** Hands the events queued this update to {@code animators}' listeners, in order, then forgets them. */
    void dispatch(Animators animators) {
        for (int i = 0; i < eventCount; i++) {
            animators.fire(nodes[0], events[i], eventWeights[i]);
            events[i] = null;
        }
        eventCount = 0;
    }
}
