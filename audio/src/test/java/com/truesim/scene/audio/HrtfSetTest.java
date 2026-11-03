package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetInput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** HRTF sets (§2.9): the {@code .hrtf} format written and read back, the importer, validation. */
class HrtfSetTest {

    /** Two directions of 3 taps: ahead and to the right (given unnormalised). */
    static HrtfSet small() {
        return HrtfSet.of(48_000, 3, "test set, CC0", new float[] {0, 0, -2, 3, 0, 0}, new float[] {1.2f, 1.2f},
            new float[] {0, 0, 20.5f, 0}, new float[] {1, 0.5f, 0.25f, 1, 0.5f, 0.25f, 0.2f, 0.1f, 0, 1, 0.3f, 0});
    }

    @Test
    void aSetSaysWhatItHolds() {
        HrtfSet set = small();
        assertThat(set.rate()).isEqualTo(48_000);
        assertThat(set.taps()).isEqualTo(3);
        assertThat(set.count()).isEqualTo(2);
        assertThat(set.credit()).isEqualTo("test set, CC0");
        assertThat(set.direction(0, 2)).isEqualTo(-1);
        assertThat(set.direction(1, 0)).isEqualTo(1);
        assertThat(set.distance(1)).isEqualTo(1.2f);
        assertThat(set.delay(1, HrtfSet.LEFT)).isEqualTo(20.5f);
        assertThat(set.delay(1, HrtfSet.RIGHT)).isZero();
        assertThat(set.tap(1, HrtfSet.RIGHT, 1)).isEqualTo(0.3f);
        float[] out = new float[4];
        set.filter(1, HrtfSet.LEFT, out, 1);
        assertThat(out).containsExactly(0, 0.2f, 0.1f, 0);
        assertThat(set.bytes()).isEqualTo(4L * (6 + 2 + 4 + 12));
    }

    @Test
    void writtenAndReadBackItIsTheSame() throws IOException {
        HrtfSet set = small();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        set.write(out);
        HrtfSet back = HrtfSet.read(new ByteArrayInputStream(out.toByteArray()));
        assertThat(back.credit()).isEqualTo(set.credit());
        assertThat(back.count()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            for (int axis = 0; axis < 3; axis++) {
                assertThat(back.direction(i, axis)).isEqualTo(set.direction(i, axis));
            }
            assertThat(back.distance(i)).isEqualTo(set.distance(i));
            for (int ear = 0; ear < 2; ear++) {
                assertThat(back.delay(i, ear)).isEqualTo(set.delay(i, ear));
                for (int t = 0; t < 3; t++) {
                    assertThat(back.tap(i, ear, t)).isEqualTo(set.tap(i, ear, t));
                }
            }
        }
    }

    @Test
    void theImporterReadsTheAssetsMainResource() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        small().write(bytes);
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
                return new ByteArrayInputStream(bytes.toByteArray());
            }
        };
        assertThat(HrtfImporter.INSTANCE.load(input).direction(1, 0)).isCloseTo(1, within(1e-6f));
    }

    @Test
    void badSetsSayEveryProblem() {
        assertThatThrownBy(() -> HrtfSet.of(0, 0, "x", new float[0], new float[0], new float[0], new float[0]))
            .isInstanceOf(InvalidAudioException.class)
            .hasMessageContaining("rate is 0 Hz")
            .hasMessageContaining("at least one")
            .hasMessageContaining("no directions");
        assertThatThrownBy(() -> HrtfSet.of(48_000, 2, "x", new float[] {0, 0, 0}, new float[] {1}, new float[2],
            new float[4])).hasMessageContaining("direction 0 has no direction");
        assertThatThrownBy(() -> HrtfSet.of(48_000, 2, "x", new float[] {1, 0, 0}, new float[] {0}, new float[2],
            new float[4])).hasMessageContaining("no distance");
        assertThatThrownBy(() -> HrtfSet.of(48_000, 2, "x", new float[] {1, 0}, new float[] {1}, new float[1],
            new float[3])).hasMessageContaining("arrays do not match 1 directions of 2 taps");
        assertThatThrownBy(() -> HrtfSet.of(48_000, -1, "x", new float[] {1, 0, 0}, new float[] {1}, new float[2],
            new float[0])).hasMessageContaining("-1 taps");
        assertThatThrownBy(() -> HrtfSet.of(48_000, 2, "x", new float[] {1, 0, 0}, new float[] {1}, new float[3],
            new float[4])).hasMessageContaining("arrays do not match");
        assertThatThrownBy(() -> HrtfSet.of(48_000, 2, "x", new float[] {1, 0, 0}, new float[] {1}, new float[2],
            new float[5])).hasMessageContaining("arrays do not match");
    }

    @Test
    void brokenFilesSayWhatIsWrong() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        small().write(out);
        byte[] good = out.toByteArray();
        broken(new byte[4], "Not an HRTF set");
        byte[] magic = good.clone();
        magic[0] = 'X';
        broken(magic, "Not an HRTF set");
        byte[] version = good.clone();
        ByteBuffer.wrap(version).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2);
        broken(version, "version 2");
        broken(java.util.Arrays.copyOf(good, good.length - 4), "header says");
        for (int field : new int[] {12, 16, 20}) { // taps, count, credit length negative
            byte[] negative = good.clone();
            ByteBuffer.wrap(negative).order(ByteOrder.LITTLE_ENDIAN).putInt(field, -1);
            broken(negative, "header says");
        }
    }

    private static void broken(byte[] file, String message) {
        assertThatThrownBy(() -> HrtfSet.read(new ByteArrayInputStream(file))).isInstanceOf(IOException.class)
            .hasMessageContaining(message);
    }
}
