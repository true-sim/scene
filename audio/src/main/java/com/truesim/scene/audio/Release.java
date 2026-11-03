package com.truesim.scene.audio;

/**
 * How a {@link Segments} loop ends when its instance stops.
 *
 * @param kind    when the outro starts
 * @param seconds the cross-fade from loop to outro, for {@link Kind#CROSSFADE}
 */
public record Release(Kind kind, double seconds) {

    /** When the outro starts. */
    public enum Kind {
        /** After the loop pass playing ends (machines, sirens). */
        AT_LOOP_END,
        /** Now, cross-fading the loop out (automatic weapons). */
        CROSSFADE,
        /** At the loop's next marker. */
        AT_MARKER
    }

    /** Finishes the loop pass, then the outro. */
    public static final Release AT_LOOP_END = new Release(Kind.AT_LOOP_END, 0);
    /** Plays the outro from the loop's next marker. */
    public static final Release AT_MARKER = new Release(Kind.AT_MARKER, 0);

    public Release {
        if (!(seconds >= 0) || Double.isInfinite(seconds)) {
            throw new IllegalArgumentException("Not a cross-fade: " + seconds);
        }
    }

    /** The outro now, cross-fading the loop out over {@code seconds}. */
    public static Release crossfade(double seconds) {
        return new Release(Kind.CROSSFADE, seconds);
    }
}
