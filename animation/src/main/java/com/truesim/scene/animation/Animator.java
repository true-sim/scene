package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Poses one skeleton's scene nodes from the clip it plays. {@link Animators#add} makes one; {@link Animators#update}
 * moves every animator's clip on, samples it and writes the pose to the scene. Tracks find their nodes by index when
 * the clip was made for this skeleton's model, else by name, so one clip plays on every model of the same rig; tracks
 * of nodes it lacks are skipped. Nodes no track moves are left as they are; a scale keyed to zero poses at a
 * millionth, as the scene keeps nodes from vanishing on an axis.
 *
 * <pre>{@code
 * Animator animator = animators.add(model.skeleton(), nodes);
 * animator.play(walk);
 * }</pre>
 */
public final class Animator {

    private final Skeleton skeleton;
    private final long[] nodes;
    private final double[] pose;
    /** Which nodes the playing clip moves, so only those are written. */
    private final boolean[] animated;
    /** Morph weights by node, from {@link #weightOffset}; empty until a clip moves some. */
    private double[] weights = new double[0];
    private final int[] weightOffset;
    private final int[] weightCount;

    private ClipState playing;
    /** By track of the playing clip: its skeleton node, or -1 to skip it. */
    private int[] binding = new int[0];
    private int[] cursor = new int[0];
    private double[] scratch = new double[4];

    @AllowAllocation("animators are made when models spawn")
    Animator(Skeleton skeleton, long[] nodes) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        if (nodes.length != skeleton.size()) {
            throw new IllegalArgumentException("A scene node per skeleton node: " + nodes.length + " for "
                + skeleton.size());
        }
        this.nodes = nodes.clone();
        this.pose = new double[Skeleton.TRANSFORM * nodes.length];
        this.animated = new boolean[nodes.length];
        this.weightOffset = new int[nodes.length];
        this.weightCount = new int[nodes.length];
        skeleton.restPose(pose);
    }

    public Skeleton skeleton() {
        return skeleton;
    }

    /** The scene node posed for skeleton node {@code node}. */
    public long node(int node) {
        return nodes[node];
    }

    /**
     * Plays {@code clip} from its start, in place of what played; returns its state to set speed, looping and time on.
     */
    @AllowAllocation("binding a clip happens when it starts, not each frame")
    public ClipState play(AnimationClip clip) {
        ClipState state = new ClipState(clip);
        List<Track> tracks = clip.tracks();
        int[] bound = new int[tracks.size()];
        int[] counts = new int[nodes.length];
        int widest = 4;
        Arrays.fill(animated, false);
        for (int t = 0; t < bound.length; t++) {
            Track track = tracks.get(t);
            int node = bind(track);
            bound[t] = node;
            if (node < 0) {
                continue;
            }
            if (track.property() == Track.Property.WEIGHTS) {
                counts[node] = Math.max(counts[node], track.components());
                widest = Math.max(widest, track.components());
            } else {
                animated[node] = true;
            }
        }
        int total = 0;
        for (int n = 0; n < nodes.length; n++) {
            weightOffset[n] = total;
            weightCount[n] = counts[n];
            total += counts[n];
        }
        weights = new double[total];
        scratch = new double[widest];
        binding = bound;
        cursor = new int[bound.length];
        playing = state;
        return state;
    }

    /** Stops playing: the nodes keep their last pose. */
    public void stop() {
        playing = null;
        binding = new int[0];
        cursor = binding;
        Arrays.fill(animated, false);
        Arrays.fill(weightCount, 0);
    }

    /** What plays, or null. */
    public ClipState playing() {
        return playing;
    }

    /** How many morph weights the playing clip sets on skeleton node {@code node}: 0 when none. */
    public int weightCount(int node) {
        return weightCount[node];
    }

    /** Morph weight {@code target} of skeleton node {@code node}, as of the last update. */
    public double weight(int node, int target) {
        return weights[weightOffset[node] + Objects.checkIndex(target, weightCount[node])];
    }

    private int bind(Track track) {
        int index = track.node();
        if (index >= 0 && index < skeleton.size() && skeleton.name(index).equals(track.nodeName())) {
            return index;
        }
        return track.nodeName().isEmpty() ? -1 : skeleton.find(track.nodeName());
    }

    /** Moves the clip on by {@code seconds}. */
    void advance(double seconds) {
        if (playing != null) {
            playing.advance(seconds);
        }
    }

    /** Samples the clip into the pose and weights; touches nothing shared, so animators sample in parallel. */
    void sample() {
        if (playing == null) {
            return;
        }
        double time = playing.time();
        List<Track> tracks = playing.clip().tracks();
        for (int n = 0; n < nodes.length; n++) {
            if (animated[n]) {
                for (int i = 0; i < Skeleton.TRANSFORM; i++) {
                    pose[Skeleton.TRANSFORM * n + i] = skeleton.rest(n, i);
                }
            }
        }
        for (int t = 0; t < binding.length; t++) {
            int node = binding[t];
            if (node < 0) {
                continue;
            }
            Track track = tracks.get(t);
            track.sample(time, cursor, t, scratch);
            int base = Skeleton.TRANSFORM * node;
            switch (track.property()) {
                case TRANSLATION -> System.arraycopy(scratch, 0, pose, base, 3);
                case ROTATION -> System.arraycopy(scratch, 0, pose, base + 3, 4);
                case SCALE -> System.arraycopy(scratch, 0, pose, base + 7, 3);
                default -> System.arraycopy(scratch, 0, weights, weightOffset[node], track.components());
            }
        }
    }

    /** The smallest scale a node is posed at: clips shrink nodes to nothing, which the scene refuses. */
    static final double MIN_SCALE = 1e-6;

    /** {@code scale}, or at least {@link #MIN_SCALE} on its side of zero. */
    static double visible(double scale) {
        return Math.abs(scale) >= MIN_SCALE ? scale : Math.copySign(MIN_SCALE, scale);
    }

    /** Writes the pose to the scene: on the scene's thread, one animator after another. */
    void write(SceneGraph scene, Animators.MorphSink morphs) {
        if (playing == null) {
            return;
        }
        for (int n = 0; n < nodes.length; n++) {
            if (animated[n]) {
                int b = Skeleton.TRANSFORM * n;
                scene.setLocal(nodes[n], pose[b], pose[b + 1], pose[b + 2], pose[b + 3], pose[b + 4], pose[b + 5],
                    pose[b + 6], visible(pose[b + 7]), visible(pose[b + 8]), visible(pose[b + 9]));
            }
            if (weightCount[n] > 0 && morphs != null) {
                morphs.weights(nodes[n], weights, weightOffset[n], weightCount[n]);
            }
        }
    }
}
