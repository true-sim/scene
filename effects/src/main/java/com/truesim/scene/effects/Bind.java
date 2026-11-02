package com.truesim.scene.effects;

import java.util.Objects;

/**
 * Makes the {@link Binding}s and {@link ColorBinding}s that module inputs and spawn amounts take.
 *
 * <pre>{@code
 * Init.size(Bind.random(0.01f, 0.02f))
 * Init.color(Bind.param(TINT))
 * Spawn.rate(Bind.param(INTENSITY, FloatCurve.of(0, 0, 1, 200)))   // 0 to 200 per second as intensity goes 0 to 1
 * }</pre>
 */
public final class Bind {

    private Bind() {
    }

    /** The constant {@code value}. */
    public static Binding constant(float value) {
        return new Binding(Binding.Kind.CONSTANT, value, null, null, null, 1);
    }

    /** User parameter {@code parameter}'s value. */
    public static Binding param(EffectParameter.Float parameter) {
        return new Binding(Binding.Kind.PARAMETER, 0, parameter.name(), null, null, 1);
    }

    /** {@code curve} of user parameter {@code parameter}'s value. */
    public static Binding param(EffectParameter.Float parameter, FloatCurve curve) {
        return new Binding(Binding.Kind.PARAMETER_CURVE, 0, parameter.name(), Objects.requireNonNull(curve, "curve"),
            null, 1);
    }

    /** {@code curve} over the particle's normalized age. */
    public static Binding age(FloatCurve curve) {
        return new Binding(Binding.Kind.AGE, 0, null, Objects.requireNonNull(curve, "curve"), null, 1);
    }

    /** {@code curve} of the particle's speed in metres per second. */
    public static Binding speed(FloatCurve curve) {
        return new Binding(Binding.Kind.SPEED, 0, null, Objects.requireNonNull(curve, "curve"), null, 1);
    }

    /** A random value between {@code min} and {@code max}. */
    public static Binding random(float min, float max) {
        return random(Range.of(min, max));
    }

    /** A random value in {@code range}. */
    public static Binding random(Range range) {
        return new Binding(Binding.Kind.RANDOM, 0, null, null, Objects.requireNonNull(range, "range"), 1);
    }

    /** The constant linear RGBA color. */
    public static ColorBinding color(float r, float g, float b, float a) {
        return new ColorBinding(ColorBinding.Kind.CONSTANT, r, g, b, a, null, null);
    }

    /** Color user parameter {@code parameter}'s value. */
    public static ColorBinding param(EffectParameter.Color parameter) {
        return new ColorBinding(ColorBinding.Kind.PARAMETER, 0, 0, 0, 0, parameter.name(), null);
    }

    /** {@code gradient} over the particle's normalized age. */
    public static ColorBinding age(ColorGradient gradient) {
        return new ColorBinding(ColorBinding.Kind.AGE, 0, 0, 0, 0, null,
            Objects.requireNonNull(gradient, "gradient"));
    }

    /** A random point of {@code gradient}, per particle. */
    public static ColorBinding random(ColorGradient gradient) {
        return new ColorBinding(ColorBinding.Kind.RANDOM, 0, 0, 0, 0, null,
            Objects.requireNonNull(gradient, "gradient"));
    }
}
