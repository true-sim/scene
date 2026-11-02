package com.truesim.scene.effects;

/**
 * Updaters accelerating particles every frame: gravity and drag. Forces act in the world: a local-space emitter's
 * particles fall down, not along its rotated frame.
 *
 * <pre>{@code
 * e.update(Forces.gravity(), Forces.drag(0.6f))
 * }</pre>
 */
public final class Forces {

    /** Gravity's acceleration, in metres per second squared. */
    public static final float G = 9.81f;
    /** A downward acceleration of {@link #G} times a scale. */
    public static final String GRAVITY = "force.gravity";
    /** Linear drag: velocity falls by a coefficient per second, towards rest. */
    public static final String DRAG = "force.drag";

    private Forces() {
    }

    /** Gravity: particles fall at {@link #G}. */
    public static Module gravity() {
        return gravity(Bind.constant(1));
    }

    /** Gravity scaled by {@code scale}: 0.3 for floating embers, -0.1 for rising smoke. */
    public static Module gravity(Binding scale) {
        return Module.of(GRAVITY).with("scale", scale);
    }

    /**
     * Linear drag of {@code coefficient} per second: velocity decays as {@code exp(-coefficient t)}, and with gravity
     * approaches a terminal velocity of {@code g / coefficient}.
     */
    public static Module drag(Binding coefficient) {
        return Module.of(DRAG).with("coefficient", coefficient);
    }

    /** Linear drag of {@code coefficient} per second. */
    public static Module drag(float coefficient) {
        return drag(Bind.constant(coefficient));
    }
}
