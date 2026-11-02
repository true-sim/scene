package com.truesim.scene.audio;

/**
 * When something musical may happen: at once, or on the next beat, bar, group of bars, marker, exit cue or end of the
 * segment playing. The exit cue is always allowed, so a quantized change never waits past it.
 *
 * @param kind which boundary
 * @param bars for {@link Kind#BARS}, the group of bars counted from the segment's entry
 */
public record Quantize(Kind kind, int bars) {

    /** Which boundary. */
    public enum Kind {
        IMMEDIATE,
        NEXT_BEAT,
        NEXT_BAR,
        BARS,
        NEXT_MARKER,
        EXIT_CUE,
        END
    }

    /** At once (the scheduling latency apart). */
    public static final Quantize IMMEDIATE = new Quantize(Kind.IMMEDIATE, 0);
    /** On the next beat. */
    public static final Quantize NEXT_BEAT = new Quantize(Kind.NEXT_BEAT, 0);
    /** On the next bar line. */
    public static final Quantize NEXT_BAR = new Quantize(Kind.NEXT_BAR, 0);
    /** At the segment's next marker, else its exit cue. */
    public static final Quantize NEXT_MARKER = new Quantize(Kind.NEXT_MARKER, 0);
    /** At the segment's exit cue. */
    public static final Quantize EXIT_CUE = new Quantize(Kind.EXIT_CUE, 0);
    /** At the end of the segment's clip (its exit cue while the length is not known). */
    public static final Quantize END = new Quantize(Kind.END, 0);

    public Quantize {
        if (kind == Kind.BARS ? bars < 1 : bars != 0) {
            throw new IllegalArgumentException("Groups of bars are 1 or more, and only for BARS: " + kind + " " + bars);
        }
    }

    /** On the next group of {@code n} bars from the segment's entry. */
    public static Quantize bars(int n) {
        return new Quantize(Kind.BARS, n);
    }
}
