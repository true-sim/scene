package com.truesim.scene.animation;

import java.util.Objects;

/**
 * Feet on uneven ground: the animation assumes flat ground at the instance's height; this puts each foot as high above
 * the real ground under it as the animation has it above the flat one, lowering the pelvis so the lower foot can reach
 * and bending each leg by two-bone inverse kinematics. A {@link PoseModifier}; its weight is each animator's
 * ({@link Animator#weight(PoseModifier, double)}). Stateless and thread-safe.
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
        double weight = pose.weight();
        double[] p = pose.scratch();
        // How far each foot's ground is above the flat ground the animation assumes (at the instance's height)
        double base = pose.groundHeight();
        pose.worldPosition(left.end, p);
        double leftOffset = ground.height(p[0], p[2]) - base;
        double leftY = p[1];
        pose.worldPosition(right.end, p);
        double rightOffset = ground.height(p[0], p[2]) - base;
        double rightY = p[1];
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
