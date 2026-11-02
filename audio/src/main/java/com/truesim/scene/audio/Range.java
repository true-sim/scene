package com.truesim.scene.audio;

import com.truesim.common.math.Maths;

/**
 * A random range of volume (in decibels) or pitch (in semitones), drawn once per play from the instance's seed, so the
 * same seed draws the same value everywhere.
 *
 * <pre>{@code
 * .volume(Range.db(-2, 0)).pitch(Range.semitones(-0.5, 0.5))
 * }</pre>
 *
 * @param min      the least value
 * @param max      the greatest value
 * @param decibels whether the values are decibels of volume; else semitones of pitch
 */
public record Range(float min, float max, boolean decibels) {

    /** No change of volume. */
    public static final Range UNITY_DB = new Range(0, 0, true);
    /** No change of pitch. */
    public static final Range UNITY_SEMITONES = new Range(0, 0, false);

    public Range {
        if (!(min <= max) || Float.isInfinite(max) || Float.isInfinite(min)) {
            throw new IllegalArgumentException("Not a range: " + min + " to " + max);
        }
    }

    /** Volume between {@code min} and {@code max} decibels. */
    public static Range db(double min, double max) {
        return new Range((float) min, (float) max, true);
    }

    /** Pitch between {@code min} and {@code max} semitones. */
    public static Range semitones(double min, double max) {
        return new Range((float) min, (float) max, false);
    }

    /** The value at {@code u} in [0, 1) of the range, as a linear gain or a playback-rate ratio. */
    public float factor(float u) {
        float value = min + (max - min) * u;
        return decibels ? Decibels.gain(value) : (float) Maths.pow(2, value / 12.0);
    }
}
