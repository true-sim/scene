package com.truesim.scene.audio;

/**
 * A length in beats: cross-fades and offsets in musical time.
 *
 * @param count how many beats
 */
public record Beats(double count) {

    public Beats {
        if (!(count >= 0) || Double.isInfinite(count)) {
            throw new IllegalArgumentException("Not a number of beats: " + count);
        }
    }

    /** {@code count} beats. */
    public static Beats of(double count) {
        return new Beats(count);
    }
}
