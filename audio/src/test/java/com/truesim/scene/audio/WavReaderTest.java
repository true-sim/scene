package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetInput;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** WAV files of every sample format, channel count and odd chunk, against reference samples. */
class WavReaderTest {

    /** The reference signal ffmpeg wrote: a 441 Hz sine at 0.5 left, an 882 Hz cosine at 0.25 right, 44.1 kHz. */
    private static double reference(int frame, int channel) {
        double t = frame / 44_100.0;
        return channel == 0 ? 0.5 * Math.sin(2 * Math.PI * 441 * t) : 0.25 * Math.cos(2 * Math.PI * 882 * t);
    }

    @ParameterizedTest
    @CsvSource({
        "pcm_u8, PCM16, 0.008", "pcm_s16le, PCM16, 0.0001", "pcm_s24le, FLOAT, 0.000001",
        "pcm_s32le, FLOAT, 0.000001", "pcm_f32le, FLOAT, 0.000001", "pcm_f64le, FLOAT, 0.000001"})
    void filesWrittenByFfmpegReadAsTheirSignal(String codec, AudioClip.Encoding encoding, double tolerance)
            throws IOException {
        try (InputStream in = WavReaderTest.class.getResourceAsStream("ref_" + codec + ".wav");
             AudioClip clip = WavReader.read(in)) {
            assertThat(clip.rate()).isEqualTo(44_100);
            assertThat(clip.channels()).isEqualTo(2);
            assertThat(clip.frames()).isEqualTo(441);
            assertThat(clip.encoding()).isEqualTo(encoding);
            assertThat(clip.looped()).isFalse();
            assertThat(clip.markers()).isEmpty();
            for (int f = 0; f < clip.frames(); f++) {
                for (int c = 0; c < 2; c++) {
                    assertThat((double) clip.sample(f, c)).as("frame %d channel %d", f, c)
                        .isCloseTo(reference(f, c), within(tolerance));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void oneToEightChannelsInterleave(int channels) throws IOException {
        short[] samples = new short[3 * channels];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (1000 * (i % channels) + i / channels);
        }
        try (AudioClip clip = WavReader.read(new Wav().fmt(1, channels, 48_000, 16, channels > 2).data16(samples)
                .bytes())) {
            assertThat(clip.channels()).isEqualTo(channels);
            assertThat(clip.frames()).isEqualTo(3);
            assertThat(clip.sample(2, channels - 1)).isEqualTo((1000 * (channels - 1) + 2) * 0x1p-15f);
        }
    }

    @Test
    void extremesOfEachFormatMapToMinusOneAndJustBelowOne() throws IOException {
        byte[] u8 = {0, (byte) 255, (byte) 128, 0};
        try (AudioClip c = WavReader.read(new Wav().fmt(1, 1, 8_000, 8, false).chunk("data", u8).bytes())) {
            assertThat(c.sample(0, 0)).isEqualTo(-1);
            assertThat(c.sample(1, 0)).isEqualTo(127 / 128f);
            assertThat(c.sample(2, 0)).isZero();
        }
        byte[] s24 = {0, 0, (byte) 0x80, (byte) 0xFF, (byte) 0xFF, 0x7F};
        try (AudioClip c = WavReader.read(new Wav().fmt(1, 1, 8_000, 24, false).chunk("data", s24).bytes())) {
            assertThat(c.sample(0, 0)).isEqualTo(-1);
            assertThat(c.sample(1, 0)).isEqualTo(1 - 0x1p-23f);
        }
        ByteBuffer s32 = Wav.le(8).putInt(Integer.MIN_VALUE).putInt(Integer.MAX_VALUE);
        try (AudioClip c = WavReader.read(new Wav().fmt(1, 1, 8_000, 32, true).chunk("data", s32.array()).bytes())) {
            assertThat(c.sample(0, 0)).isEqualTo(-1);
            assertThat(c.sample(1, 0)).isEqualTo(1);
        }
        ByteBuffer f32 = Wav.le(4).putFloat(0.75f);
        try (AudioClip c = WavReader.read(new Wav().fmt(3, 1, 8_000, 32, true).chunk("data", f32.array()).bytes())) {
            assertThat(c.sample(0, 0)).isEqualTo(0.75f);
        }
    }

    @Test
    void loopsAndMarkersComeFromSmplCueAndLabl() throws IOException {
        byte[] file = new Wav().fmt(1, 1, 48_000, 16, false).data16(new short[100]).smpl(10, 49)
            .cue(1, 20, 2, 5, 3, 200).labels(2, "hit", 9, "unused").bytes();
        try (AudioClip clip = WavReader.read(file)) {
            assertThat(clip.looped()).isTrue();
            assertThat(clip.loopStart()).isEqualTo(10);
            assertThat(clip.loopEnd()).isEqualTo(50);
            assertThat(clip.markers()).containsExactly(new SoundMarker("hit", 5), new SoundMarker("cue1", 20));
        }
    }

    @Test
    void oddChunksAreSkippedWithTheirPadByte() throws IOException {
        byte[] file = new Wav().chunk("junk", new byte[] {1, 2, 3}).fmt(1, 1, 48_000, 16, false)
            .chunk("LIST", "INFOx".getBytes()).chunk("LIST", new byte[] {1}).smpl(-1, -1).chunk("smpl", new byte[8]).chunk("smpl", new byte[60])
            .smpl(2, 200).data16((short) 7, (short) 8).chunk("note", new byte[5]).bytes();
        try (AudioClip clip = WavReader.read(file)) {
            assertThat(clip.frames()).isEqualTo(2);
            assertThat(clip.sample(1, 0)).isEqualTo(8 * 0x1p-15f);
            assertThat(clip.looped()).as("a loop past the end is dropped").isFalse();
        }
    }

    @Test
    void badLabelListsAreReadAsFarAsTheyHold() throws IOException {
        ByteBuffer list = Wav.le(4 + 8 + 2 + 8 + 4 + 8);
        list.put("adtl".getBytes()).put("note".getBytes()).putInt(2).putShort((short) 0) // another kind, skipped
            .put("labl".getBytes()).putInt(2).putShort((short) 0) // too short to hold an id
            .put("labl".getBytes()).putInt(1000); // runs past the list
        byte[] file = new Wav().fmt(1, 1, 48_000, 16, false).data16(new short[4]).cue(1, 1)
            .chunk("LIST", list.array()).bytes();
        try (AudioClip clip = WavReader.read(file)) {
            assertThat(clip.markers()).containsExactly(new SoundMarker("cue1", 1));
        }
        ByteBuffer negative = Wav.le(12).put("adtl".getBytes()).put("labl".getBytes()).putInt(-4);
        try (AudioClip clip = WavReader.read(new Wav().fmt(1, 1, 8_000, 16, false).data16(new short[2])
                .chunk("LIST", negative.array()).bytes())) {
            assertThat(clip.markers()).isEmpty();
        }
        ByteBuffer unterminated = Wav.le(4 + 8 + 6).put("adtl".getBytes()).put("labl".getBytes()).putInt(6)
            .putInt(1).put((byte) 'a').put((byte) 'b');
        try (AudioClip clip = WavReader.read(new Wav().fmt(1, 1, 8_000, 16, false).data16(new short[2]).cue(1, 0)
                .chunk("LIST", unterminated.array()).bytes())) {
            assertThat(clip.markers()).containsExactly(new SoundMarker("ab", 0));
        }
    }

    @Test
    void aDataChunkClaimingMoreThanTheFileHoldsReadsWhatIsThere() throws IOException {
        ByteBuffer samples = Wav.le(6).putShort((short) 1).putShort((short) 2).putShort((short) 3);
        byte[] file = new Wav().fmt(1, 1, 48_000, 16, false).raw("data", 0x7FFFFFF0, samples.array()).bytes();
        try (AudioClip clip = WavReader.read(new ByteArrayInputStream(file))) {
            assertThat(clip.frames()).isEqualTo(3);
        }
    }

    @Test
    void theImporterReadsTheAssetsMainResource() throws Exception {
        byte[] file = new Wav().fmt(1, 1, 22_050, 16, false).data16((short) 1).bytes();
        AssetInput input = new AssetInput() {
            @Override
            public AssetId id() {
                return new AssetId(1, 2);
            }

            @Override
            public int part() {
                return 0;
            }

            @Override
            public InputStream open(String resource) {
                return new ByteArrayInputStream(file);
            }
        };
        try (AudioClip clip = WavImporter.INSTANCE.load(input)) {
            assertThat(clip.rate()).isEqualTo(22_050);
        }
    }

    @Test
    void brokenFilesSayWhatIsWrong() {
        problem(new byte[4], "Not a WAV file");
        problem(Wav.le(12).put("RIFX".getBytes()).putInt(4).put("WAVE".getBytes()).array(), "Not a WAV file");
        problem(Wav.le(12).put("RIFF".getBytes()).putInt(4).put("AVI ".getBytes()).array(), "Not a WAV file");
        problem(new Wav().data16((short) 1).bytes(), "no fmt chunk");
        problem(new Wav().fmt(1, 1, 8_000, 16, false).bytes(), "no data chunk");
        problem(new Wav().chunk("fmt ", new byte[14]).data16().bytes(), "A fmt chunk of 14 bytes");
        problem(new Wav().chunk("fmt ", Wav.le(18).putShort((short) 0xFFFE).array()).data16().bytes(),
            "An extensible fmt chunk of 18 bytes");
        problem(new Wav().fmt(1, 0, 8_000, 16, false).data16().bytes(), "1 to 8 channels are read, not 0");
        problem(new Wav().fmt(1, 9, 8_000, 16, false).data16().bytes(), "1 to 8 channels are read, not 9");
        problem(new Wav().fmt(1, 1, 0, 16, false).data16().bytes(), "Not a sample rate: 0");
        problem(new Wav().fmt(2, 1, 8_000, 4, false).data16().bytes(), "WAV format 2 is not PCM or float");
        problem(new Wav().fmt(1, 1, 8_000, 12, false).data16().bytes(), "PCM samples of 12 bits");
        problem(new Wav().fmt(3, 1, 8_000, 16, false).data16().bytes(), "Float samples of 16 bits");
        ByteBuffer fmt = Wav.le(16).putShort((short) 1).putShort((short) 2).putInt(8_000).putInt(0)
            .putShort((short) 2).putShort((short) 16);
        problem(new Wav().chunk("fmt ", fmt.array()).data16().bytes(), "A block of 2 bytes for 2 channels of 16");
        problem(new Wav().fmt(1, 1, 8_000, 16, false).data16().raw("cue ", 100, new byte[4]).bytes(),
            "cut short in its cue  chunk");
    }

    private static void problem(byte[] file, String message) {
        assertThatThrownBy(() -> WavReader.read(file)).isInstanceOf(IOException.class).hasMessageContaining(message);
    }
}
