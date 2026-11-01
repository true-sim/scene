package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.scene.core.SceneGraph;
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
 * <p>The quick way, on a rig of one layer binding clips as they play ({@link Animators#add(Skeleton, long[])}):
 * {@code animator.play(clip)}.
 */
public final class Animator {

    /** Motions a layer blends at once: playing one more drops the lightest. */
    public static final int STATES = 4;
    private static final double[] IDENTITY = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};

    private final Rig rig;
    private final Skeleton skeleton;
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

    @AllowAllocation("animators are made when models spawn")
    Animator(Rig rig, long[] nodes) {
        this.rig = Objects.requireNonNull(rig, "rig");
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

    /** Writes the pose to the scene: on the scene's thread, one animator after another. */
    void write(SceneGraph scene, Animators.MorphSink morphs) {
        boolean playing = false;
        for (Playback layer : layers) {
            playing |= layer.count > 0;
        }
        if (!playing) {
            return;
        }
        for (int j = 0; j < nodes.length; j++) {
            if (rig.animated(j)) {
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
