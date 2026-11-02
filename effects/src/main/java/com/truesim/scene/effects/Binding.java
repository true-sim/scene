package com.truesim.scene.effects;

import java.util.Objects;

/**
 * Where a float module input comes from: a constant, a user parameter, a curve over normalized age, a curve of a user
 * parameter, a curve of speed, or a random range; times a scale. Made by {@link Bind}; immutable, compared by value.
 *
 * <pre>{@code
 * Init.lifetime(Bind.random(0.3f, 0.8f))
 * Spawn.burst(0, Bind.param(INTENSITY).times(150))
 * }</pre>
 *
 * @param kind where the value comes from
 * @param value the constant, for {@link Kind#CONSTANT}
 * @param parameter the user parameter's name, for {@link Kind#PARAMETER} and {@link Kind#PARAMETER_CURVE}
 * @param curve the curve, for {@link Kind#AGE}, {@link Kind#PARAMETER_CURVE} and {@link Kind#SPEED}
 * @param range the range, for {@link Kind#RANDOM}
 * @param scale what the value is multiplied by
 */
public record Binding(Kind kind, float value, String parameter, FloatCurve curve, Range range, float scale) {

    /** Where a binding's value comes from. */
    public enum Kind {
        /** A constant. */
        CONSTANT,
        /** A float user parameter's value. */
        PARAMETER,
        /** A curve over the particle's normalized age (0 at birth, 1 at death). */
        AGE,
        /** A curve of a float user parameter's value. */
        PARAMETER_CURVE,
        /** A curve of the particle's speed, in metres per second. */
        SPEED,
        /** A random value in a range, drawn per particle from its seed (per loop for spawn counts). */
        RANDOM;

        /** Whether its value is the same for every particle of an instance at a time, so spawn counts may use it. */
        public boolean perInstance() {
            return this == CONSTANT || this == PARAMETER || this == PARAMETER_CURVE || this == RANDOM;
        }
    }

    public Binding {
        Objects.requireNonNull(kind, "kind");
        if (!Float.isFinite(value) || !Float.isFinite(scale)) {
            throw new IllegalArgumentException("Bindings are finite: " + value + " times " + scale);
        }
    }

    /** This binding with its value multiplied by {@code factor}. */
    public Binding times(float factor) {
        return new Binding(kind, value, parameter, curve, range, scale * factor);
    }

    /**
     * The greatest value it takes, as far as the data says (a cubic curve may rise a little above its keys), when its
     * parameter is {@code parameter}: for estimating capacities.
     */
    public float max(float parameter) {
        float low;
        float high;
        switch (kind) {
            case CONSTANT -> {
                low = value;
                high = value;
            }
            case PARAMETER -> {
                low = parameter;
                high = parameter;
            }
            case PARAMETER_CURVE -> {
                low = curve.evaluate(parameter);
                high = low;
            }
            case RANDOM -> {
                low = range.min();
                high = range.max();
            }
            default -> {
                low = curve.min();
                high = curve.max();
            }
        }
        return Math.max(low * scale, high * scale);
    }
}
