package com.truesim.scene.animation;

import java.util.Objects;

/**
 * Feet on uneven ground: the animation assumes flat ground at the instance's height; this puts each foot as high above
 * the real ground under it as the animation has it above the flat one, lowering the pelvis so the lower foot can reach
 * and bending each leg by two-bone inverse kinematics. A {@link PoseModifier}; its weight is each animator's
 * ({@link Animator#weight(PoseModifier, double)}). Stateless and thread-safe; its {@link Ground} need not be: in
 * {@link Animators} the ground is asked in the serial part of the update, animator by animator, while the rest of
 * the modifier (and the sampling around it) runs in the parallel part (decision A13).
 *
 * <pre>{@code
 * r.modifier(new FootPlacement(leftLeg, rightLeg, r.joint("pelvis"), terrain::height));
 * }</pre>
 */
public final class FootPlacement implements PoseModifier {

    private final TwoBoneIk left;
    private final TwoBoneIk right;
    private final Joint pelvis;
    private final Ground ground;

    /** Feet at the ends of {@code left} and {@code right}, the legs hanging from {@code pelvis}, on {@code ground}. */
    public FootPlacement(TwoBoneIk left, TwoBoneIk right, Joint pelvis, Ground ground) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        this.pelvis = Objects.requireNonNull(pelvis, "pelvis");
        this.ground = Objects.requireNonNull(ground, "ground");
    }

    @Override
    public void modify(Pose pose) {
        probe(pose);
        answer(pose);
        finish(pose);
    }

    /** Where the feet are: the points to ask the ground at (an animator's parallel part). */
    void probe(Pose pose) {
        double[] p = pose.scratch();
        double[] probes = pose.probes();
        pose.worldPosition(left.end, p);
        probes[0] = p[0];
        probes[1] = p[1];
        probes[2] = p[2];
        pose.worldPosition(right.end, p);
        probes[3] = p[0];
        probes[4] = p[1];
        probes[5] = p[2];
    }

    /**
     * How far each foot's ground is above the flat ground the animation assumes (at the instance's height): the only
     * calls of {@link Ground}, on the updating thread in animator order (the serial part; decision A13).
     */
    void answer(Pose pose) {
        double[] probes = pose.probes();
        double base = pose.groundHeight();
        probes[6] = ground.height(probes[0], probes[2]) - base;
        probes[7] = ground.height(probes[3], probes[5]) - base;
    }

    /** The pelvis lowered and the legs bent to the ground {@link #answer} found (the parallel part again). */
    void finish(Pose pose) {
        double weight = pose.weight();
        double[] p = pose.scratch();
        double[] probes = pose.probes();
        double leftY = probes[1];
        double rightY = probes[4];
        double leftOffset = probes[6];
        double rightOffset = probes[7];
        double lowest = Math.min(Double.isNaN(leftOffset) ? 0 : leftOffset, Double.isNaN(rightOffset) ? 0 : rightOffset);
        if (lowest < 0) {
            pose.moveWorld(pelvis.index(), 0, weight * lowest, 0);
        }
        if (!Double.isNaN(leftOffset)) {
            pose.worldPosition(left.end, p);
            pose.reach(left, p[0], leftY + leftOffset, p[2], weight);
        }
        if (!Double.isNaN(rightOffset)) {
            pose.worldPosition(right.end, p);
            pose.reach(right, p[0], rightY + rightOffset, p[2], weight);
        }
    }
}
