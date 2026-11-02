package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A curve from a value to a gain or factor: piecewise linear through points ({@code Curve.points(x0, y0, x1, y1,
 * ...)}), clamped at both ends, or for attenuation one of the distance laws ({@link #INVERSE}, {@link #LINEAR}),
 * which run from 1 at the minimum distance to 0 at the maximum. Immutable, equal by value; evaluating allocates
 * nothing.
 */
public final class Curve {

    /** How a curve maps its input. */
    public enum Kind {
        /** Through its points. */
        POINTS,
        /** Inverse distance, min / d, rolled off to reach 0 at the maximum distance. */
        INVERSE,
        /** Linear from 1 at the minimum distance to 0 at the maximum. */
        LINEAR
    }

    /** Inverse distance, rolled off to 0 at the maximum distance: the default attenuation. */
    public static final Curve INVERSE = new Curve(Kind.INVERSE, new float[0]);
    /** Linear in distance from the minimum to the maximum. */
    public static final Curve LINEAR = new Curve(Kind.LINEAR, new float[0]);

    private final Kind kind;
    private final float[] points;

    private Curve(Kind kind, float[] points) {
        this.kind = kind;
        this.points = points;
    }

    /**
     * The curve through ({@code x0}, {@code y0}), ({@code x1}, {@code y1}), ...: at least one point, x increasing;
     * constant before the first and after the last.
     */
    @AllowAllocation("set up once")
    public static Curve points(double... xy) {
        if (xy.length < 2 || xy.length % 2 != 0) {
            throw new IllegalArgumentException("A curve takes x, y pairs: " + xy.length + " numbers given");
        }
        float[] p = new float[xy.length];
        for (int i = 0; i < xy.length; i++) {
            p[i] = (float) xy[i];
            if (i % 2 == 0 && i > 0 && !(p[i] > p[i - 2])) {
                throw new IllegalArgumentException("A curve's x must increase: " + Arrays.toString(xy));
            }
        }
        return new Curve(Kind.POINTS, p);
    }

    public Kind kind() {
        return kind;
    }

    /** The value at {@code x}, for a curve through points. */
    public float at(float x) {
        float[] p = points;
        if (x <= p[0]) {
            return p[1];
        }
        for (int i = 2; i < p.length; i += 2) {
            if (x < p[i]) {
                float t = (x - p[i - 2]) / (p[i] - p[i - 2]);
                return p[i - 1] + t * (p[i + 1] - p[i - 1]);
            }
        }
        return p[p.length - 1];
    }

    /** The gain at {@code distance} between {@code min} and {@code max}: the law's, or through the points in metres. */
    public float gain(float distance, float min, float max) {
        if (kind == Kind.POINTS) {
            return at(distance);
        }
        if (distance <= min) {
            return 1;
        }
        if (distance >= max) {
            return 0;
        }
        if (kind == Kind.LINEAR) {
            return 1 - (distance - min) / (max - min);
        }
        float floor = min / max;
        return (min / distance - floor) / (1 - floor);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Curve c && kind == c.kind && Arrays.equals(points, c.points);
    }

    @Override
    public int hashCode() {
        return 31 * kind.hashCode() + Arrays.hashCode(points);
    }

    @Override
    @AllowAllocation("formatting")
    public String toString() {
        return kind == Kind.POINTS ? "curve " + Arrays.toString(points) : kind.name().toLowerCase(java.util.Locale.ROOT);
    }
}
