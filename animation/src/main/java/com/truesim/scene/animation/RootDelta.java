package com.truesim.scene.animation;

/**
 * An animator's root motion of the last update ({@link Animator#rootDelta}): how far its clips moved the root joint,
 * blended as they are, in the instance's frame as it faced at the update's start (x right, y up, z forward as the
 * model's), and how far they turned it about y, radians. Move the instance by it: rotate (dx, dy, dz) by the instance's
 * own yaw, then add {@code yaw()} to it. Only the axes of the rig's {@link RootMotion} are set; the rest are 0. The
 * same object each update.
 */
public final class RootDelta {

    double dx;
    double dy;
    double dz;
    double yaw;

    RootDelta() {
    }

    public double dx() {
        return dx;
    }

    public double dy() {
        return dy;
    }

    public double dz() {
        return dz;
    }

    /** Radians about y, counterclockwise seen from above. */
    public double yaw() {
        return yaw;
    }

    void clear() {
        dx = 0;
        dy = 0;
        dz = 0;
        yaw = 0;
    }

    @Override
    public String toString() {
        return "RootDelta[" + dx + ", " + dy + ", " + dz + ", yaw " + yaw + "]";
    }
}
