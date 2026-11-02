package com.truesim.scene.effects;

/**
 * Curl noise (Bridson et al., "Curl-noise for procedural fluid flow", 2007), as the GPU kernel computes it: three
 * channels of value noise on the integer lattice make a vector potential, whose curl is a divergence-free field.
 * Lattice values are {@link Hash} values of their coordinates and the channel's seed, so Java and Slang agree up to
 * float rounding; quintic interpolation, so the field is smooth. In floats, as the GPU. Allocates nothing.
 */
final class CurlNoise {

    private CurlNoise() {
    }

    /** The lattice value at ({@code x}, {@code y}, {@code z}) for {@code seed}: in [-1, 1). */
    static float lattice(int x, int y, int z, int seed) {
        return Hash.unit(Hash.combine(Hash.combine(Hash.combine(seed, x), y), z)) * 2 - 1;
    }

    /**
     * Value noise for {@code seed} at ({@code x}, {@code y}, {@code z}) and its gradient: the value into
     * {@code out[0]}, its derivatives along x, y and z into {@code out[1..3]}.
     */
    static void noise(float x, float y, float z, int seed, float[] out) {
        float fx = (float) Math.floor(x);
        float fy = (float) Math.floor(y);
        float fz = (float) Math.floor(z);
        int ix = (int) fx;
        int iy = (int) fy;
        int iz = (int) fz;
        float tx = x - fx;
        float ty = y - fy;
        float tz = z - fz;
        float u = fade(tx);
        float v = fade(ty);
        float w = fade(tz);
        float du = slope(tx);
        float dv = slope(ty);
        float dw = slope(tz);
        float a = lattice(ix, iy, iz, seed);
        float b = lattice(ix + 1, iy, iz, seed);
        float c = lattice(ix, iy + 1, iz, seed);
        float d = lattice(ix + 1, iy + 1, iz, seed);
        float e = lattice(ix, iy, iz + 1, seed);
        float f = lattice(ix + 1, iy, iz + 1, seed);
        float g = lattice(ix, iy + 1, iz + 1, seed);
        float h = lattice(ix + 1, iy + 1, iz + 1, seed);
        float k1 = b - a;
        float k2 = c - a;
        float k3 = e - a;
        float k4 = a - b - c + d;
        float k5 = a - c - e + g;
        float k6 = a - b - e + f;
        float k7 = -a + b + c - d + e - f - g + h;
        out[0] = a + k1 * u + k2 * v + k3 * w + k4 * u * v + k5 * v * w + k6 * w * u + k7 * u * v * w;
        out[1] = du * (k1 + k4 * v + k6 * w + k7 * v * w);
        out[2] = dv * (k2 + k5 * w + k4 * u + k7 * w * u);
        out[3] = dw * (k3 + k6 * u + k5 * v + k7 * u * v);
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float slope(float t) {
        return 30 * t * t * (t - 1) * (t - 1);
    }

    /**
     * The curl of the potential of seeds {@code seed}, {@code seed + 1} and {@code seed + 2} at ({@code x}, {@code y},
     * {@code z}) times {@code frequency}, drifted by {@code drift} along each axis, over {@code octaves} (each twice
     * the frequency and half the weight of the one before), into {@code out[0..2]}; {@code scratch} holds 4 floats.
     */
    static void curl(float x, float y, float z, float frequency, float drift, int octaves, int seed, float[] scratch,
            float[] out) {
        float cx = 0;
        float cy = 0;
        float cz = 0;
        float scale = frequency;
        float weight = 1;
        for (int o = 0; o < octaves; o++) {
            float sx = x * scale + drift;
            float sy = y * scale + drift;
            float sz = z * scale + drift;
            noise(sx, sy, sz, seed, scratch);
            float d0y = scratch[2];
            float d0z = scratch[3];
            noise(sx, sy, sz, seed + 1, scratch);
            float d1x = scratch[1];
            float d1z = scratch[3];
            noise(sx, sy, sz, seed + 2, scratch);
            float d2x = scratch[1];
            float d2y = scratch[2];
            cx += weight * (d2y - d1z);
            cy += weight * (d0z - d2x);
            cz += weight * (d1x - d0y);
            scale *= 2;
            weight *= 0.5f;
        }
        out[0] = cx;
        out[1] = cy;
        out[2] = cz;
    }
}
