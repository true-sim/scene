package com.truesim.scene.audio.codecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.ClipDecoder;
import com.truesim.scene.audio.WavReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Ogg Opus through libopusfile against ffmpeg's decode of the same file: length (pre-skip), samples, exact seeks. */
class OpusTest {

    @Test
    void aFileDecodesAsTheReferenceDecoderHearsIt() throws IOException {
        try (AudioClip clip = Opus.read(VorbisTest.file("tone.opus"), false);
             AudioClip reference = WavReader.read(new ByteArrayInputStream(VorbisTest.file("tone_opus_ref.wav")))) {
            assertThat(clip.streamed()).isFalse();
            assertThat(clip.rate()).isEqualTo(48_000);
            assertThat(clip.channels()).isEqualTo(2);
            assertThat(clip.frames()).as("the pre-skip trimmed as ffmpeg trims it").isEqualTo(reference.frames());
            for (long f = 1_000; f < 1_000 + 2_000; f++) {
                assertThat(clip.sample(f, 0)).isCloseTo(reference.sample(f, 0), within(1e-3f));
                assertThat(clip.sample(f, 1)).isCloseTo(reference.sample(f, 1), within(1e-3f));
            }
            assertThat((double) clip.loudness()).isBetween(-12.0, -4.0);
        }
    }

    @Test
    void streamedDecodersSeekExactly() throws IOException {
        byte[] bytes = VorbisTest.file("long.opus");
        try (AudioClip whole = Opus.read(bytes, false); AudioClip streamed = Opus.read(bytes, null)) {
            assertThat(streamed.streamed()).isTrue();
            assertThat(streamed.frames()).isEqualTo(whole.frames());
            assertThat(streamed.loudness()).isCloseTo(whole.loudness(), within(0.05f));
            float[] out = new float[256];
            try (ClipDecoder d = streamed.open()) {
                for (long at : new long[] {0, 48_001, 300_000, 77_777}) {
                    d.seek(at);
                    int n = d.read(out, 0, 256);
                    assertThat(n).isPositive();
                    // lossy: the decoder's state after a seek (its pre-roll) differs a little, but the position is
                    // exact: the seeked frames match the whole decode best with no shift
                    double best = error(out, n, whole, at, 0);
                    for (int lag = -3; lag <= 3; lag++) {
                        if (lag != 0 && at + lag >= 0) {
                            assertThat(error(out, n, whole, at, lag)).as("frame %d shifted %d", at, lag)
                                .isGreaterThan(best);
                        }
                    }
                    assertThat(best / n).isLessThan(1e-3); // about 10 % of a 0.3 sine at 32 kb/s, right after a seek
                }
            }
        }
        try (AudioClip big = Opus.read(VorbisTest.file("tone.opus"), null)) {
            assertThat(big.streamed()).as("3 s of stereo floats: over a megabyte").isTrue();
        }
        try (AudioClip silent = Opus.read(VorbisTest.file("silence.opus"), true)) {
            assertThat(silent.gain()).isEqualTo(1);
        }
        assertThatThrownBy(() -> Opus.read(new byte[] {9, 9, 9}, null)).isInstanceOf(IOException.class)
            .hasMessage("Not an Ogg Opus file");
    }

    private static double error(float[] out, int n, AudioClip whole, long at, int lag) {
        double e = 0;
        for (int i = 0; i < n; i++) {
            double d = out[i] - whole.sample(at + i + lag, 0);
            e += d * d;
        }
        return e;
    }

    @Test
    void theImporterTakesOpus() throws Exception {
        java.lang.reflect.Method input = VorbisTest.class.getDeclaredMethod("input", String.class, byte[].class);
        input.setAccessible(true);
        com.truesim.scene.assets.AssetInput in = (com.truesim.scene.assets.AssetInput) input.invoke(null,
            "game://a.opus#stream", VorbisTest.file("tone.opus"));
        try (AudioClip clip = AudioImporters.opus(in)) {
            assertThat(clip.streamed()).isTrue();
        }
    }
}
