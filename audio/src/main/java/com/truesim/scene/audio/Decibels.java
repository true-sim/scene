package com.truesim.scene.audio;

import com.truesim.common.math.Maths;

/** Decibels and linear gains. Allocates nothing; uses {@link Maths}, so call it at changes, not per sample. */
public final class Decibels {

    private static final double LN10 = Maths.log(10);

    private Decibels() {
    }

    /** The linear gain of {@code db} decibels; 0 for negative infinity. */
    public static float gain(float db) {
        return db == Float.NEGATIVE_INFINITY ? 0 : (float) Maths.exp(db * LN10 / 20);
    }

    /** {@code gain} in decibels; negative infinity for 0 or less. */
    public static float of(float gain) {
        return gain <= 0 ? Float.NEGATIVE_INFINITY : (float) (20 * Maths.log(gain) / LN10);
    }
}
