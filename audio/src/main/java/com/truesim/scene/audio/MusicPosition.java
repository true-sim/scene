package com.truesim.scene.audio;

/**
 * Where the music is as heard: the segment, bar, beat and fraction of the beat. Filled by {@link Music#position}, so
 * keep one and reuse it.
 */
public final class MusicPosition {

    String segment;
    int bar;
    int beat;
    double fraction;

    /** The segment heard; {@code null} when nothing plays. */
    public String segment() {
        return segment;
    }

    /** The bar, from 1, counted from the segment's entry. */
    public int bar() {
        return bar;
    }

    /** The beat in the bar, from 1. */
    public int beat() {
        return beat;
    }

    /** How far into the beat, 0 to 1. */
    public double fraction() {
        return fraction;
    }
}
