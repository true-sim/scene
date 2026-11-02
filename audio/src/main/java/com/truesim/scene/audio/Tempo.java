package com.truesim.scene.audio;

/**
 * A tempo and a meter: beats a minute and beats a bar.
 *
 * <pre>{@code
 * Tempo.bpm(112).meter(4, 4)
 * }</pre>
 *
 * @param bpm         beats a minute
 * @param beatsPerBar beats in a bar
 * @param beatUnit    the note value of a beat (4 for a quarter), for notation only
 */
public record Tempo(double bpm, int beatsPerBar, int beatUnit) {

    public Tempo {
        if (!(bpm > 0) || Double.isInfinite(bpm)) {
            throw new IllegalArgumentException("Not a tempo: " + bpm + " bpm");
        }
        if (beatsPerBar < 1 || beatUnit < 1) {
            throw new IllegalArgumentException("Not a meter: " + beatsPerBar + "/" + beatUnit);
        }
    }

    /** {@code bpm} beats a minute in 4/4. */
    public static Tempo bpm(double bpm) {
        return new Tempo(bpm, 4, 4);
    }

    /** A copy in {@code beats}/{@code unit}. */
    public Tempo meter(int beats, int unit) {
        return new Tempo(bpm, beats, unit);
    }

    /** Output frames a beat lasts at {@code rate} frames a second. */
    public double framesPerBeat(int rate) {
        return rate * 60.0 / bpm;
    }
}
