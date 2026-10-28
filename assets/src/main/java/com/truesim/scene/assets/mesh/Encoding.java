package com.truesim.scene.assets.mesh;

/**
 * The vertex encodings of the mesh format, as shaders decode them: unorm and snorm integers, float16 and octahedral
 * unit vectors. Encoding rounds to nearest; decoding is exact, so decode(encode(v)) is the GPU's value.
 */
public final class Encoding {

    private Encoding() {
    }

    /** {@code value} in [0, 1] as unorm with {@code bits} bits. */
    public static int unorm(double value, int bits) {
        int max = (1 << bits) - 1;
        return (int) Math.round(Math.clamp(value, 0, 1) * max);
    }

    public static double fromUnorm(int value, int bits) {
        return value / (double) ((1 << bits) - 1);
    }

    /** {@code value} in [-1, 1] as snorm with {@code bits} bits. */
    public static int snorm(double value, int bits) {
        int max = (1 << (bits - 1)) - 1;
        return (int) Math.round(Math.clamp(value, -1, 1) * max);
    }

    /** As the GPU decodes snorm: the most negative value also gives -1. */
    public static double fromSnorm(int value, int bits) {
        int max = (1 << (bits - 1)) - 1;
        return Math.max(value / (double) max, -1);
    }

    /**
     * The unit vector ({@code x}, {@code y}, {@code z}) in octahedral form: two values in [-1, 1], written to
     * {@code out[0..1]}. The vector need not be normalized; a zero vector encodes as +z.
     */
    public static void octahedral(double x, double y, double z, double[] out) {
        double sum = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (sum == 0) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        double u = x / sum;
        double v = y / sum;
        if (z < 0) {
            double wrapU = (1 - Math.abs(v)) * sign(u);
            double wrapV = (1 - Math.abs(u)) * sign(v);
            u = wrapU;
            v = wrapV;
        }
        out[0] = u;
        out[1] = v;
    }

    /** The unit vector of octahedral ({@code u}, {@code v}), normalized, written to {@code out[0..2]}. */
    public static void fromOctahedral(double u, double v, double[] out) {
        double z = 1 - Math.abs(u) - Math.abs(v);
        double x = u;
        double y = v;
        if (z < 0) {
            x = (1 - Math.abs(v)) * sign(u);
            y = (1 - Math.abs(u)) * sign(v);
        }
        double length = Math.sqrt(x * x + y * y + z * z);
        out[0] = x / length;
        out[1] = y / length;
        out[2] = z / length;
    }

    private static double sign(double value) {
        return value >= 0 ? 1 : -1;
    }

    /** {@code value} as float16 bits. */
    public static short half(double value) {
        return Float.floatToFloat16((float) value);
    }

    public static double fromHalf(short bits) {
        return Float.float16ToFloat(bits);
    }
}
