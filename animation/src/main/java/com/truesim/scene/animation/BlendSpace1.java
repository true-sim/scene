package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * Clips along one parameter (speed, say), blended by where the parameter is ({@link Animator#input}): the two around
 * it, by how near each is, at one shared phase, so a walk and a run blended keep their steps together. Set up on a rig
 * ({@link Rig.Builder#blendSpace1}); played as a clip is.
 *
 * <pre>{@code
 * BlendSpace1 move = r.blendSpace1().at(0, idle).at(1.6, walk).at(5.0, run);
 * a.layer(base).play(move);
 * a.input(move, speed);      // each frame
 * }</pre>
 */
public final class BlendSpace1 implements Motion {

    private final Rig rig;
    final int index; // its parameter's in an animator's
    private double[] at = new double[0];
    private Clip[] clips = new Clip[0];
    private SyncGroup group;

    BlendSpace1(Rig rig, int index) {
        this.rig = rig;
        this.index = index;
    }

    @Override
    public Rig rig() {
        return rig;
    }

    /** Places {@code clip} (of its rig, not additive) at {@code parameter}; while the rig is set up. */
    @AllowAllocation("setting up a rig")
    public BlendSpace1 at(double parameter, Clip clip) {
        rig.checkSettingUp();
        Objects.requireNonNull(clip, "clip");
        if (clip.rig() != rig || clip.additive()) {
            throw new IllegalArgumentException("A blend space blends clips of its rig, not additive: " + clip);
        }
        if (!Double.isFinite(parameter)) {
            throw new IllegalArgumentException("Not a parameter: " + parameter);
        }
        int i = 0;
        while (i < at.length && at[i] < parameter) {
            i++;
        }
        if (i < at.length && at[i] == parameter) {
            throw new IllegalArgumentException("A clip is at " + parameter + " already");
        }
        double[] places = new double[at.length + 1];
        Clip[] placed = new Clip[clips.length + 1];
        System.arraycopy(at, 0, places, 0, i);
        System.arraycopy(clips, 0, placed, 0, i);
        places[i] = parameter;
        placed[i] = clip;
        System.arraycopy(at, i, places, i + 1, at.length - i);
        System.arraycopy(clips, i, placed, i + 1, clips.length - i);
        at = places;
        clips = placed;
        return this;
    }

    /** Has it join {@code group} whenever it plays (null: none); while the rig is set up. */
    public BlendSpace1 sync(SyncGroup group) {
        rig.checkSettingUp();
        this.group = group;
        return this;
    }

    /** The sync group it joins by default, or null. */
    @Override
    public SyncGroup group() {
        return group;
    }

    /** Its two clips around the parameter's place, their durations blended by how near each is. */
    @Override
    public double duration(Animator animator) {
        double parameter = animator.parameter(this);
        int first = first(parameter);
        double a = weight(parameter, first);
        double d = a * clips[first].duration();
        if (a < 1) {
            d += (1 - a) * clips[first + 1].duration();
        }
        return d;
    }

    /** The two clips around the animator's parameter, at the same phase, by how near each is. */
    @Override
    public void contents(Animator animator, double phase, Contents out) {
        double parameter = animator.parameter(this);
        int first = first(parameter);
        double a = weight(parameter, first);
        out.add(clips[first], phase * clips[first].duration(), a);
        if (a < 1) {
            out.add(clips[first + 1], phase * clips[first + 1].duration(), 1 - a);
        }
    }

    /** How many clips it blends. */
    public int size() {
        return clips.length;
    }

    public Clip clip(int i) {
        return clips[i];
    }

    public double parameter(int i) {
        return at[i];
    }

    /** The first of the clips {@code parameter} blends (the next, if any, is the other); clamped to the ends. */
    private int first(double parameter) {
        int last = at.length - 1;
        if (last == 0 || parameter <= at[0]) {
            return 0;
        }
        if (parameter >= at[last]) {
            return last;
        }
        int i = 0;
        while (at[i + 1] <= parameter) {
            i++;
        }
        return i;
    }

    /** The weight of clip {@code first} at {@code parameter}: the next weighs the rest. */
    private double weight(double parameter, int first) {
        if (first == at.length - 1 || parameter <= at[first]) {
            return 1;
        }
        return (at[first + 1] - parameter) / (at[first + 1] - at[first]);
    }

    @Override
    public String toString() {
        return "BlendSpace1" + Arrays.toString(at);
    }
}
