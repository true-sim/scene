package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;

/**
 * A camera as a component on a node (application-api.md D129): it looks along its node's -Z with +Y up, so it moves,
 * parents and animates with the scene graph, and the engine's view sync sets its renderer camera from the node's world
 * transform and this lens each frame. In XR the tracked head is the camera node.
 *
 * <pre>{@code
 * long eye = scene.create(player);
 * scene.components().set(eye, CameraLens.TYPE, CameraLens.perspective(Math.toRadians(70), 0.05));
 * lens.fovY(Math.toRadians(30));                   // zooming: changed in place, the sync follows
 * }</pre>
 *
 * <p>Perspective (a vertical field of view and a near plane; the engine's projection has no far plane) or
 * orthographic (a half height, the width following the view's aspect, and a depth). Changed in place by its setters,
 * each of which counts {@link #version()}, so syncs notice without the store being told; nothing allocates.
 */
public final class CameraLens {

    /** The camera component type, saved as {@code truesim.camera}. */
    public static final ComponentType<CameraLens> TYPE = ComponentType.of("truesim.camera", CameraLens.class);

    private boolean orthographic;
    private double fovY;
    private double near;
    private double halfHeight;
    private double depth;
    private long version;

    private CameraLens(boolean orthographic, double fovY, double near, double halfHeight, double depth) {
        this.orthographic = orthographic;
        this.fovY = fovY;
        this.near = near;
        this.halfHeight = halfHeight;
        this.depth = depth;
    }

    /** A perspective lens of vertical field of view {@code fovY} (radians, 0 to pi) and its near plane at {@code near}. */
    @AllowAllocation("components are made when a scene is built")
    public static CameraLens perspective(double fovY, double near) {
        return new CameraLens(false, 1, 1, 1, 1).perspectiveLens(fovY, near);
    }

    /** An orthographic lens seeing {@code halfHeight} up and down from its axis, {@code depth} ahead. */
    @AllowAllocation("components are made when a scene is built")
    public static CameraLens orthographic(double halfHeight, double depth) {
        return new CameraLens(true, 1, 1, 1, 1).orthographicLens(halfHeight, depth);
    }

    /** Makes it a perspective lens; returns it. */
    public CameraLens perspectiveLens(double fovY, double near) {
        if (!(fovY > 0 && fovY < Math.PI) || !(near > 0) || near == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a perspective lens: fov " + fovY + ", near " + near);
        }
        orthographic = false;
        this.fovY = fovY;
        this.near = near;
        version++;
        return this;
    }

    /** Makes it an orthographic lens; returns it. */
    public CameraLens orthographicLens(double halfHeight, double depth) {
        if (!(halfHeight > 0) || !(depth > 0) || halfHeight == Double.POSITIVE_INFINITY
            || depth == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not an orthographic lens: half height " + halfHeight + ", depth "
                + depth);
        }
        orthographic = true;
        this.halfHeight = halfHeight;
        this.depth = depth;
        version++;
        return this;
    }

    /** Sets a perspective lens's field of view (radians), keeping its near plane; returns it. */
    public CameraLens fovY(double radians) {
        return perspectiveLens(radians, near);
    }

    /** Whether it is orthographic. */
    public boolean orthographic() {
        return orthographic;
    }

    /** A perspective lens's vertical field of view, radians (kept while orthographic). */
    public double fovY() {
        return fovY;
    }

    /** A perspective lens's near plane. */
    public double near() {
        return near;
    }

    /** An orthographic lens's half height. */
    public double halfHeight() {
        return halfHeight;
    }

    /** An orthographic lens's depth. */
    public double depth() {
        return depth;
    }

    /** A count its setters raise: a sync compares it to see a change made in place. */
    public long version() {
        return version;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return orthographic ? "orthographic lens, half height " + halfHeight + ", depth " + depth
            : "perspective lens, fov " + fovY + ", near " + near;
    }
}
