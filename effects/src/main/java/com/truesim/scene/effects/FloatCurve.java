package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * A float over time as keys (E18): each key a time, a value and how it goes on to the next ({@link Interpolation}).
 * Searched where it is evaluated, per particle, never baked; so at most {@value #MAX_KEYS} keys. Before the first key
 * it is the first value, after the last the last. Immutable, compared by value.
 *
 * <pre>{@code
 * static final FloatCurve SHRINK = FloatCurve.of(0, 1, 0.7f, 0.8f, 1, 0);         // linear keys: time, value, ...
 * static final FloatCurve PUFF = FloatCurve.builder().cubic(0, 0.2f).cubic(0.3f, 1).linear(1, 1.4f).build();
 * }</pre>
 *
 * <p>Times are usually normalized age (0 at birth, 1 at death), but any float works: a curve of a parameter or of speed
 * takes those as its times. Evaluating allocates nothing.
 */
public final class FloatCurve {

    /** The most keys a curve or gradient has: the shader searches them per particle. */
    public static final int MAX_KEYS = 32;

    private final float[] times;
    private final float[] values;
    private final Interpolation[] interpolations;
    /** Slopes at the keys, for cubic segments. */
    private final float[] slopes;

    @AllowAllocation("curves are made when effects are set up")
    private FloatCurve(float[] times, float[] values, Interpolation[] interpolations) {
        Keys.check(times);
        Keys.checkValues(values);
        this.times = times;
        this.values = values;
        this.interpolations = interpolations;
        this.slopes = Keys.slopes(times, values, 1, 0);
    }

    /** A curve of linear keys, given as time and value pairs: {@code of(t0, v0, t1, v1, ...)}. */
    @AllowAllocation("curves are made when effects are set up")
    public static FloatCurve of(float... timesAndValues) {
        if (timesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Keys are time and value pairs: " + timesAndValues.length + " floats");
        }
        Builder b = builder();
        for (int i = 0; i < timesAndValues.length; i += 2) {
            b.linear(timesAndValues[i], timesAndValues[i + 1]);
        }
        return b.build();
    }

    /** The curve that is {@code value} at every time. */
    public static FloatCurve constant(float value) {
        return builder().linear(0, value).build();
    }

    /** Sets up a curve key by key, in increasing time. */
    @AllowAllocation("curves are made when effects are set up")
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

    /** Key {@code i}'s value. */
    public float value(int i) {
        return values[i];
    }

    /** How key {@code i} goes on to the next. */
    public Interpolation interpolation(int i) {
        return interpolations[i];
    }

    /** The slope at key {@code i} that cubic segments use, in value per unit of time. */
    public float slope(int i) {
        return slopes[i];
    }

    /** The curve's value at time {@code t}. Allocates nothing. */
    public float evaluate(float t) {
        int i = Keys.segment(times, t);
        if (i < 0) {
            return values[0];
        }
        if (i == times.length - 1) {
            return values[i];
        }
        return Keys.interpolate(interpolations[i], times, values, slopes, 1, 0, i, t);
    }

    /** The least key value: cubic segments may dip a little below it between keys. */
    public float min() {
        float min = values[0];
        for (float value : values) {
            min = Math.min(min, value);
        }
        return min;
    }

    /** The greatest key value: cubic segments may rise a little above it between keys. */
    public float max() {
        float max = values[0];
        for (float value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FloatCurve c && Arrays.equals(times, c.times) && Arrays.equals(values, c.values)
            && Arrays.equals(interpolations, c.interpolations);
    }

    @Override
    public int hashCode() {
        return (31 * Arrays.hashCode(times) + Arrays.hashCode(values)) * 31 + Arrays.hashCode(interpolations);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("FloatCurve[");
        for (int i = 0; i < times.length; i++) {
            s.append(i == 0 ? "" : ", ").append(times[i]).append(": ").append(values[i]).append(' ')
                .append(interpolations[i]);
        }
        return s.append(']').toString();
    }

    /** Sets up a {@link FloatCurve}. */
    @AllowAllocation("curves are made when effects are set up")
    public static final class Builder {
        private final float[] times = new float[MAX_KEYS];
        private final float[] values = new float[MAX_KEYS];
        private final Interpolation[] interpolations = new Interpolation[MAX_KEYS];
        private int size;

        private Builder() {
        }

        /** A key at {@code time} holding {@code value} until the next. */
        public Builder step(float time, float value) {
            return key(time, value, Interpolation.STEP);
        }

        /** A key at {@code time} going on to the next in a straight line. */
        public Builder linear(float time, float value) {
            return key(time, value, Interpolation.LINEAR);
        }

        /** A key at {@code time} going on to the next smoothly. */
        public Builder cubic(float time, float value) {
            return key(time, value, Interpolation.CUBIC);
        }

        /** A key at {@code time} going on to the next as {@code interpolation} says. */
        public Builder key(float time, float value, Interpolation interpolation) {
            if (size == MAX_KEYS) {
                throw new IllegalArgumentException("A curve has at most " + MAX_KEYS + " keys");
            }
            times[size] = time;
            values[size] = value;
            interpolations[size] = Objects.requireNonNull(interpolation, "interpolation");
            size++;
            return this;
        }

        public FloatCurve build() {
            return new FloatCurve(Arrays.copyOf(times, size), Arrays.copyOf(values, size),
                Arrays.copyOf(interpolations, size));
        }
    }
}
