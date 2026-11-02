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
}
