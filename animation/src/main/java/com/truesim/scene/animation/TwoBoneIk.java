package com.truesim.scene.animation;

/**
 * A two-bone chain solved to reach a target: an upper joint (a thigh, an upper arm), a middle one (a knee, an elbow) and
 * an end (a foot, a hand), each under the one before (with joints between them, if any, carried along). Set up on a rig
 * ({@link Rig.Builder#twoBoneIk}); its target, pole and weight are each animator's ({@link Animator#ik}).
 *
 * <p>The solution is Holden's, by the law of cosines: the reach is clamped short of straight (or stretched up to
 * {@link #stretch}), the middle joint bends in the chain's own plane and is then turned about the line to the target
 * into the pole's, and the end takes the target's rotation if given.
 *
 * <pre>{@code
 * TwoBoneIk leftLeg = r.twoBoneIk(r.joint("thigh_l"), r.joint("calf_l"), r.joint("foot_l")).stretch(1.1);
 * a.ik(leftLeg).target(x, y, z).pole(px, py, pz).weight(1);
 * }</pre>
 */
public final class TwoBoneIk {

    private final Rig rig;
    final int index;
    final int upper;
    final int middle;
    final int end;
    private double stretch = 1;

    TwoBoneIk(Rig rig, int index, int upper, int middle, int end) {
        this.rig = rig;
        this.index = index;
        this.upper = upper;
        this.middle = middle;
        this.end = end;
    }

    public Rig rig() {
        return rig;
    }

    /** The end joint, whose position reaches for the target. */
    public Joint end() {
        return new Joint(end, rig.skeleton().name(end));
    }

    /** How far the bones may stretch past their length to reach, 1 (not at all) and up; while the rig is set up. */
    public TwoBoneIk stretch(double max) {
        rig.checkSettingUp();
        if (!(max >= 1) || max == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("A stretch of 1 or more: " + max);
        }
        stretch = max;
        return this;
    }

    public double stretch() {
        return stretch;
    }
}
