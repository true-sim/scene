package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * A linear RGBA color over time as keys (E18), as {@link FloatCurve} is a float: each key a time, a color and how it
 * goes on to the next; at most {@value FloatCurve#MAX_KEYS} keys. Immutable, compared by value.
 *
 * <pre>{@code
 * static final ColorGradient SPARK = ColorGradient.builder()
 *     .linear(0, 1, 0.9f, 0.6f, 1).linear(0.5f, 1, 0.4f, 0.1f, 1).linear(1, 0.3f, 0.05f, 0, 0).build();
 * }</pre>
 */
public final class ColorGradient {

    private final float[] times;
    /** Four floats per key: red, green, blue, alpha. */
    private final float[] colors;
    private final Interpolation[] interpolations;
    private final float[] slopes;

    @AllowAllocation("gradients are made when effects are set up")
    private ColorGradient(float[] times, float[] colors, Interpolation[] interpolations) {
        Keys.check(times);
        Keys.checkValues(colors);
        this.times = times;
        this.colors = colors;
        this.interpolations = interpolations;
        slopes = new float[colors.length];
        for (int c = 0; c < 4; c++) {
            float[] component = Keys.slopes(times, colors, 4, c);
            for (int i = 0; i < times.length; i++) {
                slopes[4 * i + c] = component[i];
            }
        }
    }

    /** The gradient that is one color throughout. */
    public static ColorGradient constant(float r, float g, float b, float a) {
        return builder().linear(0, r, g, b, a).build();
    }

    /** Sets up a gradient key by key, in increasing time. */
    @AllowAllocation("gradients are made when effects are set up")
    public static Builder builder() {
        return new Builder();
    }

    /** How many keys it has. */
    public int size() {
        return times.length;
    }

    /** Key {@code i}'s time. */
    public float time(int i) {
        return times[i];
    }

    /** Component {@code component} (0 red to 3 alpha) of key {@code i}'s color. */
    public float color(int i, int component) {
        return colors[4 * i + Objects.checkIndex(component, 4)];
    }

    /** How key {@code i} goes on to the next. */
    public Interpolation interpolation(int i) {
        return interpolations[i];
    }

    /** Writes the color at time {@code t} into {@code rgba[offset..offset + 4)}. Allocates nothing. */
    public void evaluate(float t, float[] rgba, int offset) {
        int i = Keys.segment(times, t);
        int n = times.length;
        for (int c = 0; c < 4; c++) {
            if (i < 0) {
                rgba[offset + c] = colors[c];
            } else if (i == n - 1) {
                rgba[offset + c] = colors[4 * i + c];
            } else {
                rgba[offset + c] = Keys.interpolate(interpolations[i], times, colors, slopes, 4, c, i, t);
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ColorGradient g && Arrays.equals(times, g.times) && Arrays.equals(colors, g.colors)
            && Arrays.equals(interpolations, g.interpolations);
    }

    @Override
    public int hashCode() {
        return (31 * Arrays.hashCode(times) + Arrays.hashCode(colors)) * 31 + Arrays.hashCode(interpolations);
    }

    @Override
    public String toString() {
        return "ColorGradient[" + times.length + " keys from " + times[0] + " to " + times[times.length - 1] + "]";
    }

    /** Sets up a {@link ColorGradient}. */
    @AllowAllocation("gradients are made when effects are set up")
    public static final class Builder {
        private final float[] times = new float[FloatCurve.MAX_KEYS];
        private final float[] colors = new float[4 * FloatCurve.MAX_KEYS];
        private final Interpolation[] interpolations = new Interpolation[FloatCurve.MAX_KEYS];
        private int size;

        private Builder() {
        }

        /** A key at {@code time} holding its color until the next. */
        public Builder step(float time, float r, float g, float b, float a) {
            return key(time, r, g, b, a, Interpolation.STEP);
        }

        /** A key at {@code time} going on to the next in a straight line. */
        public Builder linear(float time, float r, float g, float b, float a) {
            return key(time, r, g, b, a, Interpolation.LINEAR);
        }

        /** A key at {@code time} going on to the next smoothly. */
        public Builder cubic(float time, float r, float g, float b, float a) {
            return key(time, r, g, b, a, Interpolation.CUBIC);
        }

        /** A key at {@code time} going on to the next as {@code interpolation} says. */
        public Builder key(float time, float r, float g, float b, float a, Interpolation interpolation) {
            if (size == FloatCurve.MAX_KEYS) {
                throw new IllegalArgumentException("A gradient has at most " + FloatCurve.MAX_KEYS + " keys");
            }
            times[size] = time;
            colors[4 * size] = r;
            colors[4 * size + 1] = g;
            colors[4 * size + 2] = b;
            colors[4 * size + 3] = a;
            interpolations[size] = Objects.requireNonNull(interpolation, "interpolation");
            size++;
            return this;
        }

        public ColorGradient build() {
            return new ColorGradient(Arrays.copyOf(times, size), Arrays.copyOf(colors, 4 * size),
                Arrays.copyOf(interpolations, size));
        }
    }
}
