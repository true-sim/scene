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

    /**
     * The clips {@code parameter} blends: the index of the first in {@code out[0]}, its weight in {@code weights[0]}
     * (the second, the next clip, weighs the rest); clamped to the ends.
     */
    int blend(double parameter, double[] weights) {
        int last = at.length - 1;
        if (last == 0 || parameter <= at[0]) {
            weights[0] = 1;
            return 0;
        }
        if (parameter >= at[last]) {
            weights[0] = 1;
            return last;
        }
        int i = 0;
        while (at[i + 1] <= parameter) {
            i++;
        }
        weights[0] = (at[i + 1] - parameter) / (at[i + 1] - at[i]);
        return i;
    }

    @Override
    public String toString() {
        return "BlendSpace1" + Arrays.toString(at);
    }
}
