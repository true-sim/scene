package com.truesim.scene.animation;

/**
 * An animator's pose as a {@link PoseModifier} changes it: joints' world positions, moves, and two-bone reaches, in
 * world coordinates (the instance's frame as the scene has it this update). One per animator, reused; valid during
 * {@link PoseModifier#modify} only.
 */
public final class Pose {

    private final Animator animator;
    private final double[] scratch = new double[3];
    double weight;

    Pose(Animator animator) {
        this.animator = animator;
    }

    /** The animator posed. */
    public Animator animator() {
        return animator;
    }

    /** The modifier's weight on this animator, 0 to 1 ({@link Animator#weight(PoseModifier, double)}). */
    public double weight() {
        return weight;
    }

    /** {@code joint}'s world position into {@code out[0..3)}. */
    public void worldPosition(Joint joint, double[] out) {
        worldPosition(animator.rig().jointIndex(joint), out);
    }

    /** Moves {@code joint} (and what hangs from it) by world (dx, dy, dz). */
    public void moveWorld(Joint joint, double dx, double dy, double dz) {
        moveWorld(animator.rig().jointIndex(joint), dx, dy, dz);
    }

    /** Bends {@code ik}'s chain to bring its end to world (x, y, z), by {@code weight}; the middle joint bends as it is. */
    public void reach(TwoBoneIk ik, double x, double y, double z, double weight) {
        animator.rig().checkOwn(ik, ik.rig());
        animator.reach(ik, x, y, z, weight);
    }

    /** The world height of the flat ground the animation assumes: the instance's. */
    public double groundHeight() {
        return animator.groundHeight();
    }

    void worldPosition(int joint, double[] out) {
        animator.worldPosition(joint, out);
    }

    void moveWorld(int joint, double dx, double dy, double dz) {
        animator.moveWorld(joint, dx, dy, dz);
    }

    /** Three doubles a modifier of the package may use between calls of its own. */
    double[] scratch() {
        return scratch;
    }
}
