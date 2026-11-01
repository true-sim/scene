package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * What a layer plays: a {@link Clip}, a {@link BlendSpace1}, or a motion of the game's own (a 2D blend space, motion
 * matching, a procedural source). A motion says how long it lasts and, for any phase (0 to 1 through it), which of its
 * rig's clips it plays at which times and weights; the animator does the rest from that: sampling and blending, events,
 * root motion and sync. Set up once per rig and shared; what changes per instance lives on the animator (its
 * parameters) or in the motion's own per-animator state.
 *
 * <p>Events and root motion pair a clip's time at the phase before an update with its time at the phase after, by its
 * place among the contents: list the same clips in the same order for nearby phases (a clip that changes place moves
 * nothing that update). Sync groups go by the heaviest clip and take a follower's phase as its time over that clip's
 * duration, as clips and blend spaces do. Methods are called from the threads that sample animators: they read only and
 * allocate nothing.
 *
 * <pre>{@code
 * final class Strafe implements Motion {           // a 2D blend of four clips by two parameters
 *     public Rig rig() { return rig; }
 *     public double duration(Animator a) { return walk.duration(); }
 *     public void contents(Animator a, double phase, Contents out) {
 *         double x = a.parameter(sideways), z = a.parameter(forward);
 *         out.add(left, phase * left.duration(), Math.max(0, -x));
 *         ...
 *     }
 * }
 * }</pre>
 */
public interface Motion {

    /** The rig it belongs to. */
    Rig rig();

    /** Seconds it lasts on {@code animator}: its phase goes from 0 to 1 over them at speed 1. */
    double duration(Animator animator);

    /** What it plays on {@code animator} at {@code phase}: its clips with their times and weights, into {@code out}. */
    void contents(Animator animator, double phase, Contents out);

    /** Whether it moves by its clips' differences, for additive layers: only additive clips then. */
    default boolean additive() {
        return false;
    }

    /** The sync group it joins whenever it plays, or null. */
    default SyncGroup group() {
        return null;
    }

    /** The clips a motion plays at a phase: each at a time and a weight. Reused; allocates only to grow. */
    final class Contents {

        private Clip[] clips = new Clip[4];
        private double[] times = new double[4];
        private double[] weights = new double[4];
        private int count;

        Contents() {
        }

        /** Plays {@code clip} (of the motion's rig) at {@code time} seconds into it, weighing {@code weight} (0 to 1). */
        public void add(Clip clip, double time, double weight) {
            Objects.requireNonNull(clip, "clip");
            if (count == clips.length) {
                grow();
            }
            clips[count] = clip;
            times[count] = time;
            weights[count++] = weight;
        }

        @AllowAllocation("growing, the first time a motion plays this many clips")
        private void grow() {
            clips = Arrays.copyOf(clips, 2 * count);
            times = Arrays.copyOf(times, 2 * count);
            weights = Arrays.copyOf(weights, 2 * count);
        }

        public int size() {
            return count;
        }

        public Clip clip(int i) {
            return clips[Objects.checkIndex(i, count)];
        }

        public double time(int i) {
            return times[Objects.checkIndex(i, count)];
        }

        public double weight(int i) {
            return weights[Objects.checkIndex(i, count)];
        }

        /** The heaviest clip's place (the first of equals), or -1 if none. */
        int heaviest() {
            int best = -1;
            for (int i = 0; i < count; i++) {
                if (best < 0 || weights[i] > weights[best]) {
                    best = i;
                }
            }
            return best;
        }

        void clear() {
            for (int i = 0; i < count; i++) {
                clips[i] = null;
            }
            count = 0;
        }
    }
}
