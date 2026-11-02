package com.truesim.scene.effects;

/** What curves and gradients share: checking key times, finding a key's segment and interpolating in it. */
final class Keys {

    private Keys() {
    }

    /** Throws unless {@code times} are 1 to {@link FloatCurve#MAX_KEYS} finite times, each after the one before. */
    static void check(float[] times) {
        if (times.length == 0) {
            throw new IllegalArgumentException("A curve has at least one key");
        }
        for (int i = 0; i < times.length; i++) {
            if (!Float.isFinite(times[i]) || i > 0 && !(times[i] > times[i - 1])) {
                throw new IllegalArgumentException("Key times are finite and increase: " + times[i] + " at key " + i);
            }
        }
    }

    /** Throws unless every value is finite. */
    static void checkValues(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Key values are finite: " + value);
            }
        }
    }

    /**
     * The slopes at the keys of component {@code component} of values {@code stride} floats apart: Catmull-Rom inside,
     * one-sided at the ends, zero for a single key.
     */
    static float[] slopes(float[] times, float[] values, int stride, int component) {
        int n = times.length;
        float[] slopes = new float[n];
        for (int i = 0; i < n && n > 1; i++) {
            int before = Math.max(0, i - 1);
            int after = Math.min(n - 1, i + 1);
            slopes[i] = (values[after * stride + component] - values[before * stride + component])
                / (times[after] - times[before]);
        }
        return slopes;
    }

    /**
     * The key starting the segment holding {@code t}: -1 before the first key, the last key's index at or after it (and
     * for NaN), else {@code i} with {@code times[i] <= t < times[i + 1]}. A binary search.
     */
    static int segment(float[] times, float t) {
        if (t < times[0]) {
            return -1;
        }
        if (!(t < times[times.length - 1])) {
            return times.length - 1;
        }
        int low = 0;
        int high = times.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (times[middle] <= t) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** Component {@code component} at {@code t} in segment {@code i} (not the last key), as interpolated. */
    static float interpolate(Interpolation interpolation, float[] times, float[] values, float[] slopes, int stride,
            int component, int i, float t) {
        float v0 = values[i * stride + component];
        float v1 = values[(i + 1) * stride + component];
        float h = times[i + 1] - times[i];
        float s = (t - times[i]) / h;
        return switch (interpolation) {
            case STEP -> v0;
            case LINEAR -> v0 + (v1 - v0) * s;
            case CUBIC -> {
                float s2 = s * s;
                float s3 = s2 * s;
                yield (2 * s3 - 3 * s2 + 1) * v0 + (s3 - 2 * s2 + s) * h * slopes[i * stride + component]
                    + (-2 * s3 + 3 * s2) * v1 + (s3 - s2) * h * slopes[(i + 1) * stride + component];
            }
        };
    }
}
