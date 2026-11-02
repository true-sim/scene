package com.truesim.scene.effects;

/**
 * A uniformly random value between two floats, drawn per particle (or per loop, for spawn counts) from the particle's
 * seed, so the same seed draws the same value on the CPU and the GPU.
 *
 * @param min the least value
 * @param max the greatest value, not below {@code min}
 */
public record Range(float min, float max) {

    public Range {
        if (!Float.isFinite(min) || !Float.isFinite(max) || max < min) {
            throw new IllegalArgumentException("Not a range: " + min + " to " + max);
        }
    }

    /** Values between {@code min} and {@code max}. */
    public static Range of(float min, float max) {
        return new Range(min, max);
    }

    /** The value {@code u} of the way from min to max, {@code u} in [0, 1). */
    public float at(float u) {
        return min + (max - min) * u;
    }
}
