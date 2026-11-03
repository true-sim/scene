package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Integrated loudness against the EBU Tech 3341 test signals (1 kHz sines at set levels), within 0.1 LU. */
class LoudnessTest {

    static final int RATE = 48_000;

    /** Feeds {@code seconds} of a 1 kHz sine at {@code dbfs} on the channels {@code levels} names (NaN: silent). */
    static void sine(Loudness meter, int channels, double seconds, double... dbfs) {
        int frames = (int) (seconds * RATE);
        float[] chunk = new float[RATE * channels];
        for (int done = 0; done < frames; done += RATE) {
            int n = Math.min(RATE, frames - done);
            for (int f = 0; f < n; f++) {
                double s = Math.sin(2 * Math.PI * 1_000 * (done + f) / RATE);
                for (int c = 0; c < channels; c++) {
                    double db = dbfs[Math.min(c, dbfs.length - 1)];
                    chunk[f * channels + c] = Double.isNaN(db) ? 0 : (float) (s * Math.pow(10, db / 20));
                }
            }
            meter.add(chunk, n);
        }
    }

    @Test
    void steadySinesReadTheirLevel() {
        Loudness a = new Loudness(RATE, 2);
        sine(a, 2, 20, -23);
        assertThat(a.integrated()).isCloseTo(-23, within(0.1));
        Loudness b = new Loudness(RATE, 2);
        sine(b, 2, 20, -33);
        assertThat(b.integrated()).isCloseTo(-33, within(0.1));
    }

    @Test
    void theRelativeGateIgnoresTheQuietParts() {
        Loudness m = new Loudness(RATE, 2);
        sine(m, 2, 10, -36);
        sine(m, 2, 60, -23);
        sine(m, 2, 10, -36);
        assertThat(m.integrated()).isCloseTo(-23, within(0.1));
    }

    @Test
    void theAbsoluteGateIgnoresNearSilence() {
        Loudness m = new Loudness(RATE, 2);
        sine(m, 2, 10, -72);
        sine(m, 2, 20, -36);
        sine(m, 2, 10, -72);
        assertThat(m.integrated()).isCloseTo(-36, within(0.1));
    }

    @Test
    void surroundsWeighMoreAndTheLfeNothing() {
        double n = Double.NaN;
        Loudness front = new Loudness(RATE, 6); // L R C LFE Ls Rs
        sine(front, 6, 10, -30, n, n, n, n, n);
        Loudness back = new Loudness(RATE, 6);
        sine(back, 6, 10, n, n, n, n, -30, n);
        Loudness lfe = new Loudness(RATE, 6);
        sine(lfe, 6, 10, n, n, n, -10, n, n);
        assertThat(back.integrated() - front.integrated()).isCloseTo(10 * Math.log10(1.41), within(0.01));
        assertThat(lfe.integrated()).isEqualTo(Double.NEGATIVE_INFINITY);
        Loudness eight = new Loudness(RATE, 8);
        sine(eight, 8, 10, n, n, n, n, n, n, -30, n);
        assertThat(eight.integrated()).isCloseTo(back.integrated(), within(0.01));
    }

    @Test
    void silenceAndShortPiecesHaveNoLoudness() {
        Loudness quiet = new Loudness(RATE, 1);
        quiet.add(new float[RATE], RATE);
        assertThat(quiet.integrated()).isEqualTo(Double.NEGATIVE_INFINITY);
        Loudness empty = new Loudness(RATE, 1);
        assertThat(empty.integrated()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void clipsMeasureWhenAsked() {
        float[] s = new float[3 * RATE];
        for (int i = 0; i < s.length; i++) {
            s[i] = (float) (Math.sin(2 * Math.PI * 1_000 * i / RATE) * Math.pow(10, -20 / 20.0));
        }
        try (AudioClip clip = AudioClip.builder(RATE, 1).floats(s).measure(-23).build()) {
            // a mono 1 kHz sine at -20 dBFS: -23 LUFS less the 3 dB of the missing second channel
            assertThat((double) clip.loudness()).isCloseTo(-23, within(0.1));
            assertThat((double) clip.gain()).isCloseTo(1, within(0.02));
        }
        short[] pcm = new short[RATE];
        try (AudioClip silent = AudioClip.builder(RATE, 1).pcm16(pcm).measure(-23).build()) {
            assertThat(silent.loudness()).isEqualTo(Float.NEGATIVE_INFINITY);
            assertThat(silent.gain()).isEqualTo(1);
        }
    }
}
