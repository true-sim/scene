package com.truesim.scene.effects;

/**
 * Initializers setting a particle's attributes at its birth: lifetime, velocity, size and color.
 *
 * <pre>{@code
 * e.init(Shape.sphere(0.1f), Init.lifetime(Bind.random(0.3f, 0.8f)), Init.speed(Bind.random(4, 11)),
 *        Init.size(Bind.random(0.01f, 0.02f)), Init.color(Bind.param(TINT)))
 * }</pre>
 */
public final class Init {

    /** How long a particle lives, in seconds. */
    public static final String LIFETIME = "init.lifetime";
    /** A speed along the shape's direction, in metres per second. */
    public static final String SPEED = "init.speed";
    /** A velocity in the emitter's frame, in metres per second. */
    public static final String VELOCITY = "init.velocity";
    /** A particle's size, in metres. */
    public static final String SIZE = "init.size";
    /** A particle's linear RGBA color. */
    public static final String COLOR = "init.color";
    /**
     * What a particle spawned by an event takes of its parent (VFX Graph's source attributes): {@code velocity} times
     * the parent's added to its own, its color times the parent's where {@code color} is 1, its size times the
     * parent's where {@code size} is 1. Every particle an event spawns starts where its parent was (its shapes about
     * that point), with this module or without; on other particles it does nothing.
     */
    public static final String INHERIT = "init.inherit";
    /** A particle's rotation at its birth, in radians: billboards turn by it in their plane. */
    public static final String ROTATION = "init.rotation";
    /**
     * Where a particle's flipbook starts, as a fraction of its frames: particles born together then show different
     * frames (flame tongues that do not repeat).
     */
    public static final String FRAME = "init.frame";

    private Init() {
    }

    /** Particles live {@code seconds}. Without it they live {@link EmitterData#DEFAULT_LIFETIME}. */
    public static Module lifetime(Binding seconds) {
        return Module.of(LIFETIME).with("lifetime", seconds);
    }

    /** Particles live {@code seconds}. */
    public static Module lifetime(float seconds) {
        return lifetime(Bind.constant(seconds));
    }

    /** Particles move along their shape's direction at {@code speed} metres per second. */
    public static Module speed(Binding speed) {
        return Module.of(SPEED).with("speed", speed);
    }

    /** Particles get the velocity ({@code x}, {@code y}, {@code z}) in the emitter's frame, added to what they have. */
    public static Module velocity(Binding x, Binding y, Binding z) {
        return Module.of(VELOCITY).with("x", x).with("y", y).with("z", z);
    }

    /** Particles are {@code size} metres across. */
    public static Module size(Binding size) {
        return Module.of(SIZE).with("size", size);
    }

    /** Particles take {@code color}. */
    public static Module color(ColorBinding color) {
        return Module.of(COLOR).with("color", color);
    }

    /** Particles start turned by {@code radians} in their billboard's plane. */
    public static Module rotation(Binding radians) {
        return Module.of(ROTATION).with("angle", radians);
    }

    /** Particles start turned by a random angle, each its own. */
    public static Module randomRotation() {
        return rotation(Bind.random(0, (float) (2 * Math.PI)));
    }

    /** Particles' flipbooks start {@code fraction} of their frames in (0 to 1, wrapping round). */
    public static Module frame(Binding fraction) {
        return Module.of(FRAME).with("frame", fraction);
    }

    /** Particles' flipbooks start at a random frame, each its own. */
    public static Module randomFrame() {
        return frame(Bind.random(0, 1));
    }

    /** Particles spawned by events take their parent's color and size, and {@code velocity} of its velocity. */
    public static Module inherit(float velocity) {
        return Module.of(INHERIT).with("velocity", velocity).with("color", 1f).with("size", 1f);
    }

    /** Particles spawned by events take {@code velocity} of their parent's velocity, its color and size or not. */
    public static Module inherit(float velocity, boolean color, boolean size) {
        return Module.of(INHERIT).with("velocity", velocity).with("color", color ? 1 : 0).with("size", size ? 1 : 0);
    }
}
