package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * Where one sound plays from many places at once (§2.18): points (a crowd, a forest), a polyline (a river, a road) or
 * an area (a polygon in the horizontal plane: a lake, a field of crickets). The engine plays real voices at the
 * {@link #nearest()} nearest points, or at the nearest point on the line or in the area, and the rest as a diffuse
 * bed whose level follows their summed attenuation: one logical instance, a few voices, whatever the number of
 * places. Lines and areas are sampled every {@link #spacing()} metres for the bed. Immutable.
 *
 * <pre>{@code
 * sounds.field(CROWD_MURMUR, SoundField.points(xyz).nearest(4));
 * sounds.field(RIVER, SoundField.line(0, 0, 0,  40, 0, 10,  90, 0, 5));
 * }</pre>
 */
public final class SoundField {

    /** The shape of the places. */
    public enum Kind {
        POINTS,
        LINE,
        AREA
    }

    /** Real voices a field of points plays at most by default. */
    public static final int NEAREST = 4;
    /** Voices any field plays at most. */
    public static final int MAX_NEAREST = 8;

    private final Kind kind;
    private final double[] places;
    private final int nearest;
    private final double spacing;
    private final double[] samples;
    private final double[] centre = new double[3];

    private SoundField(Kind kind, double[] places, int nearest, double spacing) {
        this.kind = kind;
        this.places = places;
        this.nearest = nearest;
        this.spacing = spacing;
        int n = places.length / 3;
        for (int i = 0; i < n; i++) {
            for (int axis = 0; axis < 3; axis++) {
                centre[axis] += places[3 * i + axis] / n;
            }
        }
        samples = kind == Kind.POINTS ? places : sample();
    }

    /** A field of points, x, y, z each; at least one. */
    @AllowAllocation("set up once")
    public static SoundField points(double... xyz) {
        return new SoundField(Kind.POINTS, check(xyz, 1, "points"), NEAREST, 2);
    }

    /** A polyline through x, y, z each; at least two. */
    @AllowAllocation("set up once")
    public static SoundField line(double... xyz) {
        return new SoundField(Kind.LINE, check(xyz, 2, "line"), 1, 2);
    }

    /** A polygon in the horizontal plane through x, y, z each (its height the vertices' mean); at least three. */
    @AllowAllocation("set up once")
    public static SoundField area(double... xyz) {
        return new SoundField(Kind.AREA, check(xyz, 3, "area"), 1, 2);
    }

    private static double[] check(double[] xyz, int least, String what) {
        if (xyz.length % 3 != 0 || xyz.length / 3 < least) {
            throw new IllegalArgumentException("A " + what + " field needs at least " + least
                + " places of three coordinates: " + xyz.length + " numbers given");
        }
        return xyz.clone();
    }

    /** A copy playing {@code voices} real voices at the nearest points (1 to {@value #MAX_NEAREST}; points only). */
    @AllowAllocation("set up once")
    public SoundField nearest(int voices) {
        if (voices < 1 || voices > MAX_NEAREST || kind != Kind.POINTS) {
            throw new IllegalArgumentException("A field of points plays 1 to " + MAX_NEAREST + " voices; "
                + "lines and areas one (" + kind + ", " + voices + " asked)");
        }
        return new SoundField(kind, places, voices, spacing);
    }

    /** A copy sampled every {@code metres} for its bed (lines and areas). */
    @AllowAllocation("set up once")
    public SoundField spacing(double metres) {
        if (!(metres > 0)) {
            throw new IllegalArgumentException("A spacing is positive: " + metres);
        }
        return new SoundField(kind, places, nearest, metres);
    }

    public Kind kind() {
        return kind;
    }

    /** Real voices it plays at most. */
    public int nearest() {
        return nearest;
    }

    /** Metres between the samples of a line or an area. */
    public double spacing() {
        return spacing;
    }

    /** Places given (points, or the line's or area's vertices). */
    public int count() {
        return places.length / 3;
    }

    /** Component {@code axis} of place {@code i}. */
    public double place(int i, int axis) {
        return places[3 * i + axis];
    }

    /** Places the bed is summed over: the points, or the samples of the line or area. */
    public int samples() {
        return samples.length / 3;
    }

    /** Component {@code axis} of sample {@code i}. */
    public double sample(int i, int axis) {
        return samples[3 * i + axis];
    }

    /** Component {@code axis} of the places' mean: where the instance is, for distance and concurrency. */
    public double centre(int axis) {
        return centre[axis];
    }

    /**
     * The nearest point of the line or area to ({@code x}, {@code y}, {@code z}) into {@code out}; inside an area,
     * the point under or over it at the area's height. For points, the nearest point. Allocation-free.
     */
    public void nearestTo(double x, double y, double z, double[] out) {
        int n = count();
        double best = Double.POSITIVE_INFINITY;
        if (kind == Kind.POINTS) {
            for (int i = 0; i < n; i++) {
                double d = distance2(x, y, z, places[3 * i], places[3 * i + 1], places[3 * i + 2]);
                if (d < best) {
                    best = d;
                    out[0] = places[3 * i];
                    out[1] = places[3 * i + 1];
                    out[2] = places[3 * i + 2];
                }
            }
            return;
        }
        if (kind == Kind.AREA && inside(x, z)) {
            out[0] = x;
            out[1] = centre[1];
            out[2] = z;
            return;
        }
        int edges = kind == Kind.LINE ? n - 1 : n;
        for (int e = 0; e < edges; e++) {
            int a = 3 * e;
            int b = 3 * ((e + 1) % n);
            double ax = places[a];
            double ay = kind == Kind.AREA ? centre[1] : places[a + 1];
            double az = places[a + 2];
            double bx = places[b] - ax;
            double by = (kind == Kind.AREA ? centre[1] : places[b + 1]) - ay;
            double bz = places[b + 2] - az;
            double length2 = bx * bx + by * by + bz * bz;
            double t = length2 == 0 ? 0 : ((x - ax) * bx + (y - ay) * by + (z - az) * bz) / length2;
            t = Math.max(0, Math.min(1, t));
            double px = ax + t * bx;
            double py = ay + t * by;
            double pz = az + t * bz;
            double d = distance2(x, y, z, px, py, pz);
            if (d < best) {
                best = d;
                out[0] = px;
                out[1] = py;
                out[2] = pz;
            }
        }
    }

    /** Whether (x, z) is inside the area's polygon (even-odd rule). */
    boolean inside(double x, double z) {
        int n = count();
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double zi = places[3 * i + 2];
            double zj = places[3 * j + 2];
            if ((zi > z) != (zj > z)) {
                double xc = places[3 * j] + (z - zj) / (zi - zj) * (places[3 * i] - places[3 * j]);
                if (x < xc) {
                    in = !in;
                }
            }
        }
        return in;
    }

    private static double distance2(double x, double y, double z, double px, double py, double pz) {
        return (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz);
    }

    /** Samples every {@link #spacing} along the line, or on a grid inside the area. */
    @AllowAllocation("set up once")
    private double[] sample() {
        List<double[]> out = new ArrayList<>();
        int n = count();
        if (kind == Kind.LINE) {
            for (int e = 0; e < n - 1; e++) {
                double length = Math.sqrt(distance2(places[3 * e], places[3 * e + 1], places[3 * e + 2],
                    places[3 * e + 3], places[3 * e + 4], places[3 * e + 5]));
                int steps = Math.max(1, (int) Math.ceil(length / spacing));
                for (int s = 0; s < steps; s++) {
                    double t = (s + 0.5) / steps;
                    out.add(new double[] {places[3 * e] + t * (places[3 * e + 3] - places[3 * e]),
                        places[3 * e + 1] + t * (places[3 * e + 4] - places[3 * e + 1]),
                        places[3 * e + 2] + t * (places[3 * e + 5] - places[3 * e + 2])});
                }
            }
        } else {
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, places[3 * i]);
                maxX = Math.max(maxX, places[3 * i]);
                minZ = Math.min(minZ, places[3 * i + 2]);
                maxZ = Math.max(maxZ, places[3 * i + 2]);
            }
            for (double x = minX + spacing / 2; x < maxX; x += spacing) {
                for (double z = minZ + spacing / 2; z < maxZ; z += spacing) {
                    if (inside(x, z)) {
                        out.add(new double[] {x, centre[1], z});
                    }
                }
            }
            if (out.isEmpty()) { // smaller than a spacing: its centre
                out.add(new double[] {centre[0], centre[1], centre[2]});
            }
        }
        double[] flat = new double[3 * out.size()];
        for (int i = 0; i < out.size(); i++) {
            System.arraycopy(out.get(i), 0, flat, 3 * i, 3);
        }
        return flat;
    }
}
