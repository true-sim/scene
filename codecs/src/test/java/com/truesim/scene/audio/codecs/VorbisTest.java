package com.truesim.scene.audio.codecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetPath;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.ClipDecoder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Ogg Vorbis files ffmpeg wrote: decoded whole, streamed, loop comments, exact seeks, and the importers. */
class VorbisTest {

    static byte[] file(String name) throws IOException {
        try (InputStream in = VorbisTest.class.getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void aShortFileDecodesWholeWithItsLoopComments() throws IOException {
        try (AudioClip clip = Vorbis.read(file("tone_loop.ogg"), null)) {
            assertThat(clip.streamed()).isFalse();
            assertThat(clip.rate()).isEqualTo(44_100);
            assertThat(clip.channels()).isEqualTo(2);
            assertThat(clip.frames()).isEqualTo(88_200);
            assertThat(clip.loopStart()).isEqualTo(4_410);
            assertThat(clip.loopEnd()).isEqualTo(48_510);
            for (int f = 1_000; f < 1_100; f++) {
                double t = f / 44_100.0;
                assertThat((double) clip.sample(f, 0)).isCloseTo(0.5 * Math.sin(2 * Math.PI * 441 * t), within(0.02));
                assertThat((double) clip.sample(f, 1)).isCloseTo(0.3 * Math.sin(2 * Math.PI * 882 * t), within(0.02));
            }
        }
    }

    @Test
    void aLongFileStreamsAndItsDecodersSeekExactly() throws IOException {
        byte[] bytes = file("long.ogg");
        try (AudioClip whole = Vorbis.read(bytes, false); AudioClip streamed = Vorbis.read(bytes, null)) {
            assertThat(streamed.streamed()).isTrue();
            assertThat(streamed.bytes()).isEqualTo(bytes.length);
            assertThat(streamed.frames()).isEqualTo(whole.frames()).isEqualTo(12L * 48_000);
            assertThat(streamed.loopStart()).isEqualTo(1_000);
            assertThat(streamed.loopEnd()).isEqualTo(2_000);
            float[] out = new float[64];
            try (ClipDecoder decoder = streamed.open()) {
                for (long at : new long[] {0, 123_457, 300_001, 47_999}) {
                    decoder.seek(at);
                    assertThat(decoder.read(out, 0, 64)).isEqualTo(64);
                    for (int i = 0; i < 64; i++) {
                        assertThat(out[i]).as("frame %d", at + i).isEqualTo(whole.sample(at + i, 0));
                    }
                }
                decoder.seek(whole.frames() - 10);
                assertThat(decoder.read(out, 0, 64)).isEqualTo(10);
                assertThat(decoder.read(out, 0, 64)).isZero();
            }
        }
    }

    @Test
    void oddCommentsAndForcedStreaming() throws IOException {
        try (AudioClip clip = Vorbis.read(file("short.ogg"), true)) {
            assertThat(clip.streamed()).isTrue();
            assertThat(clip.looped()).as("LOOPSTART=x is no number").isFalse();
        }
        try (AudioClip wide = Vorbis.read(file("wide.ogg"), null)) {
            assertThat(wide.streamed()).as("6 s of stereo floats: over a megabyte").isTrue();
            assertThat(wide.loopStart()).isEqualTo(100);
            assertThat(wide.loopEnd()).as("no length or end: to the end").isEqualTo(wide.frames());
        }
        try (AudioClip backwards = Vorbis.read(file("backwards.ogg"), null)) {
            assertThat(backwards.loopEnd()).as("an end before the start: to the end").isEqualTo(backwards.frames());
        }
        try (AudioClip silent = Vorbis.read(file("silence.ogg"), true)) {
            assertThat(silent.loudness()).isEqualTo(Float.NEGATIVE_INFINITY);
            assertThat(silent.gain()).isEqualTo(1);
        }
        try (AudioClip measured = Vorbis.read(file("long.ogg"), true)) {
            assertThat((double) measured.loudness()).as("a 300 Hz sine at 0.4, mono").isBetween(-15.0, -9.0);
        }
        assertThatThrownBy(() -> Vorbis.read(new byte[] {1, 2, 3}, null)).isInstanceOf(IOException.class)
            .hasMessage("Not an Ogg Vorbis file");
    }

    @Test
    void theImporterReadsTheFragment() throws Exception {
        byte[] bytes = file("tone_loop.ogg");
        assertThat(AudioImporters.ogg(input("game://a.ogg#stream", bytes)).streamed()).isTrue();
        assertThat(AudioImporters.ogg(input("game://a.ogg#decode", file("long.ogg"))).streamed()).isFalse();
        assertThat(AudioImporters.ogg(input("game://a.ogg", bytes)).streamed()).isFalse();
        assertThat(AudioImporters.ogg(input(null, bytes)).streamed()).isFalse();
    }

    private static AssetInput input(String path, byte[] bytes) {
        return new AssetInput() {
            @Override
            public AssetId id() {
                return new AssetId(1, 2);
            }

            @Override
            public int part() {
                return 0;
            }

            @Override
            public AssetPath path() {
                return path == null ? null : AssetPath.parse(path);
            }

            @Override
            public InputStream open(String resource) {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    @Test
    void registeringAddsWavAndOgg() {
        try (var tasks = new com.truesim.common.jobs.TaskScheduler();
             var cache = new com.truesim.scene.assets.AssetCache(tasks)) {
            var paths = new com.truesim.scene.assets.PathAssets(cache);
            new AudioImporters(paths).register();
            assertThat(paths.importers().find(com.truesim.scene.audio.AudioAssets.CLIP, "OGG")).isNotNull();
            assertThat(paths.importers().find(com.truesim.scene.audio.AudioAssets.CLIP, "wav")).isNotNull();
        }
    }
}
