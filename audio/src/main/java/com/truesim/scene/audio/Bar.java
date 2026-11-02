package com.truesim.scene.audio;

/**
 * A place in a segment in bars and beats, both from 1, counted from its clip's start: {@code Bar.of(1)} is the clip's
 * first beat, and an entry at {@code Bar.of(2)} leaves a bar of pre-entry (a pickup) before it.
 *
 * @param bar  the bar, from 1
 * @param beat the beat in the bar, from 1
 */
public record Bar(int bar, int beat) {

    public Bar {
        if (bar < 1 || beat < 1) {
            throw new IllegalArgumentException("Bars and beats count from 1: bar " + bar + " beat " + beat);
        }
    }

    /** The first beat of bar {@code bar}. */
    public static Bar of(int bar) {
        return new Bar(bar, 1);
    }

    /** Beat {@code beat} of bar {@code bar}. */
    public static Bar of(int bar, int beat) {
        return new Bar(bar, beat);
    }

    /** Beats from the clip's start in {@code tempo}'s meter. */
    public double beats(Tempo tempo) {
        return (bar - 1) * tempo.beatsPerBar() + beat - 1;
    }
}
