package com.truesim.scene.animation;

/**
 * What of a rig's root joint's motion (x and z, along the ground, always) is taken out of the pose and handed to the
 * game ({@link Rig.Builder#rootMotion}, {@link Animator#rootDelta}): the clips play in place on those axes, and the game
 * moves the instance by what they would have moved it, through its own movement and collision.
 */
public enum RootMotion {
    /** Moves along the ground: x and z. */
    XZ(false, false),
    /** Moves along the ground and turns about the vertical: x, z and yaw. */
    XZ_YAW(false, true),
    /** Moves on every axis: x, y and z (jumps, climbs). */
    XYZ(true, false),
    /** Moves on every axis and turns about the vertical. */
    XYZ_YAW(true, true);

    private final boolean height;
    private final boolean yaw;

    RootMotion(boolean height, boolean yaw) {
        this.height = height;
        this.yaw = yaw;
    }

    /** Whether it takes y. */
    public boolean height() {
        return height;
    }

    /** Whether it takes the turn about y. */
    public boolean yaw() {
        return yaw;
    }
}
