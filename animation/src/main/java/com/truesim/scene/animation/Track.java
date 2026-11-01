package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.util.Objects;

/**
 * One animated property of one node: key times and values, sampled as glTF's animation samplers are. Values per key:
 * 3 for a translation or a scale, 4 for a rotation (a quaternion x, y, z, w), one per morph target for weights; a cubic
 * spline holds three times as many (in-tangent, value, out-tangent). Immutable; sampling allocates nothing.
 */
public final class Track {

    /** What a track animates. */
    public enum Property {
        TRANSLATION(3),
        ROTATION(4),
        SCALE(3),
        /** Morph target weights: one value per target. */
        WEIGHTS(0);

        private final int components;

        Property(int components) {
            this.components = components;
        }
    }

    /** How a track's values change between keys. */
    public enum Interpolation {
        /** The earlier key's value until the next key. */
        STEP,
        /** Straight between keys; rotations by spherical interpolation, the short way. */
        LINEAR,
        /** Hermite splines through the keys with their in- and out-tangents; rotations normalized after. */
        CUBIC_SPLINE
    }

    private final String nodeName;
    private final int node;
    private final Property property;
    private final Interpolation interpolation;
    private final float[] times;
    private final float[] values;
    private final int components;

    /**
     * A track of node {@code node} (its index in the model it was made for, or -1), called {@code nodeName} (how other
     * skeletons find it; may be empty), animating {@code property} by {@code interpolation} through {@code times}
     * (seconds, ascending) and {@code values}; {@code weights} morph targets for a {@link Property#WEIGHTS} track.
     */
    @AllowAllocation("clips are made when they load")
    public Track(String nodeName, int node, Property property, Interpolation interpolation, float[] times,
        float[] values, int weights) {
        this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
        this.node = node;
        this.property = Objects.requireNonNull(property, "property");
        this.interpolation = Objects.requireNonNull(interpolation, "interpolation");
        this.components = property == Property.WEIGHTS ? weights : property.components;
        if (components < 1) {
            throw new IllegalArgumentException("A weights track animates targets: " + weights);
        }
        if (times.length < 1) {
            throw new IllegalArgumentException("A track has keys");
        }
        for (int k = 1; k < times.length; k++) {
            if (!(times[k] > times[k - 1])) {
                throw new IllegalArgumentException("Key times ascend: " + times[k - 1] + ", then " + times[k]);
            }
        }
        int perKey = components * (interpolation == Interpolation.CUBIC_SPLINE ? 3 : 1);
        if (values.length != perKey * times.length) {
            throw new IllegalArgumentException(times.length + " keys of " + property + " take " + perKey * times.length
                + " values: " + values.length);
        }
        this.times = times.clone();
        this.values = values.clone();
    }

    public String nodeName() {
        return nodeName;
    }

    /** Its node's index in the model it was made for, or -1. */
    public int node() {
        return node;
    }

    public Property property() {
        return property;
    }

    public Interpolation interpolation() {
        return interpolation;
    }

    /** Values per sample: 3, 4, or the morph target count. */
    public int components() {
        return components;
    }

    public int keyCount() {
        return times.length;
    }

    /** When its last key is, seconds. */
    public double end() {
        return times[times.length - 1];
    }

    /** Bytes its keys take. */
    long bytes() {
        return 64L + 4L * times.length + 4L * values.length;
    }

    /**
     * Its value at {@code time} (seconds; before the first key the first, after the last the last) into
     * {@code out[0..components)}. {@code cursor[slot]} remembers the key last found: playing forward, the next search
     * starts there. Allocates nothing.
     */
    public void sample(double time, int[] cursor, int slot, double[] out) {
        int last = times.length - 1;
        if (time <= times[0] || last == 0) {
            key(0, out);
            cursor[slot] = 0;
            return;
        }
        if (time >= times[last]) {
            key(last, out);
            cursor[slot] = last;
            return;
        }
        int k = find(time, cursor[slot]);
        cursor[slot] = k;
        double t0 = times[k];
        double t1 = times[k + 1];
        double dt = t1 - t0;
        double u = (time - t0) / dt;
        switch (interpolation) {
            case STEP -> key(k, out);
            case LINEAR -> linear(k, u, out);
            default -> cubic(k, u, dt, out);
        }
    }

    /** The key {@code k} with {@code times[k] <= time < times[k + 1]}, searching from {@code hint} forward first. */
    private int find(double time, int hint) {
        int k = hint >= 0 && hint < times.length - 1 && times[hint] <= time ? hint : -1;
        if (k >= 0) {
            // Forward: a few steps at most at playing speed
            for (int steps = 0; steps < 4; steps++) {
                if (time < times[k + 1]) {
                    return k;
                }
                k++;
            }
        }
        int lo = 0;
        int hi = times.length - 1; // times[lo] <= time < times[hi]
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= time) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Key {@code k}'s value. */
    private void key(int k, double[] out) {
        int stride = interpolation == Interpolation.CUBIC_SPLINE ? 3 * components : components;
        int offset = k * stride + (interpolation == Interpolation.CUBIC_SPLINE ? components : 0);
        for (int c = 0; c < components; c++) {
            out[c] = values[offset + c];
        }
        if (property == Property.ROTATION) {
            normalize(out);
        }
    }

    private void linear(int k, double u, double[] out) {
        int a = k * components;
        int b = a + components;
        if (property != Property.ROTATION) {
            for (int c = 0; c < components; c++) {
                out[c] = values[a + c] + (values[b + c] - values[a + c]) * u;
            }
            return;
        }
        // Spherical, the short way round: negate the second where the dot product is negative.
        double dot = 0;
        for (int c = 0; c < 4; c++) {
            dot += values[a + c] * values[b + c];
        }
        double sign = dot < 0 ? -1 : 1;
        dot = Math.abs(dot);
        double wa;
        double wb;
        if (dot > 0.9995) {
            wa = 1 - u; // nearly parallel: straight, then normalized
            wb = u;
        } else {
            double theta = Maths.acos(dot);
            double sin = Maths.sin(theta);
            wa = Maths.sin((1 - u) * theta) / sin;
            wb = Maths.sin(u * theta) / sin;
        }
        for (int c = 0; c < 4; c++) {
            out[c] = wa * values[a + c] + sign * wb * values[b + c];
        }
        normalize(out);
    }

    /** glTF's cubic spline: p(u) = (2u³ - 3u² + 1) v0 + (u³ - 2u² + u) dt b0 + (-2u³ + 3u²) v1 + (u³ - u²) dt a1. */
    private void cubic(int k, double u, double dt, double[] out) {
        int stride = 3 * components;
        int v0 = k * stride + components;
        int b0 = k * stride + 2 * components; // the out-tangent of key k
        int a1 = (k + 1) * stride;             // the in-tangent of key k + 1
        int v1 = (k + 1) * stride + components;
        double u2 = u * u;
        double u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1;
        double h10 = (u3 - 2 * u2 + u) * dt;
        double h01 = -2 * u3 + 3 * u2;
        double h11 = (u3 - u2) * dt;
        for (int c = 0; c < components; c++) {
            out[c] = h00 * values[v0 + c] + h10 * values[b0 + c] + h01 * values[v1 + c] + h11 * values[a1 + c];
        }
        if (property == Property.ROTATION) {
            normalize(out);
        }
    }

    private static void normalize(double[] q) {
        double length = Maths.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (length > 0) {
            for (int c = 0; c < 4; c++) {
                q[c] /= length;
            }
        }
    }
}
