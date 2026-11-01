package com.truesim.scene.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadianceImporterTest {

    private static final ValueLayout.OfShort HALF = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @TempDir
    Path dir;

    /** A file of {@code header}'s lines, a blank one, the resolution line, then {@code body}. */
    private static byte[] file(String resolution, int... body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(("#?RADIANCE\n# made by a test\nFORMAT=32-bit_rle_rgbe\nEXPOSURE=1\n\n" + resolution + "\n")
            .getBytes(StandardCharsets.US_ASCII));
        for (int b : body) {
            out.write(b);
        }
        return out.toByteArray();
    }

    private static TextureData read(byte[] bytes) throws IOException {
        return RadianceImporter.read(new ByteArrayInputStream(bytes), "test.hdr");
    }

    /** Channel {@code c} of texel ({@code x}, {@code y}). */
    private static float texel(TextureData t, int x, int y, int c) {
        return Float.float16ToFloat(t.texels().get(HALF, 8L * (y * t.width() + x) + 2L * c));
    }

    @Test
    void flatScanlinesBecomeLinearHalfFloats() throws IOException {
        // (128, 64, 32) at exponent 129: about 1, 0.5, 0.25; a zero exponent is black; a bright texel past the
        // half-float range is clamped to it.
        TextureData t = read(file("-Y 1 +X 3", 128, 64, 32, 129, 200, 200, 200, 0, 255, 1, 1, 160));
        assertThat(t.width()).isEqualTo(3);
        assertThat(t.height()).isEqualTo(1);
        assertThat(t.format()).isEqualTo(TextureData.Format.RGBA16F);
        assertThat(t.colorSpace()).isEqualTo(TextureData.ColorSpace.LINEAR);
        assertThat(t.bytes()).isEqualTo(24);
        assertThat(texel(t, 0, 0, 0)).isCloseTo(128.5f / 128, within(1e-3f));
        assertThat(texel(t, 0, 0, 1)).isCloseTo(64.5f / 128, within(1e-3f));
        assertThat(texel(t, 0, 0, 2)).isCloseTo(32.5f / 128, within(1e-3f));
        assertThat(texel(t, 0, 0, 3)).isEqualTo(1);
        assertThat(texel(t, 1, 0, 0)).as("exponent 0: black").isZero();
        assertThat(texel(t, 2, 0, 0)).as("clamped").isEqualTo(65504);
    }

    @Test
    void runLengthScanlinesAndBottomUpRows() throws IOException {
        // 8 texels a row, 2 rows bottom to top. Each channel: a run, or literals.
        int[] row = {2, 2, 0, 8,
            128 + 8, 64,                         // red: 8 x 64
            4, 1, 2, 3, 4, 128 + 4, 9,           // green: 1, 2, 3, 4, then 4 x 9
            128 + 8, 0,                          // blue: 0
            128 + 8, 136};                       // exponent 136: value + 0.5
        int[] other = row.clone();
        other[5] = 100;
        int[] body = new int[row.length * 2];
        System.arraycopy(other, 0, body, 0, row.length); // first in the file: the bottom row
        System.arraycopy(row, 0, body, row.length, row.length);
        TextureData t = read(file("+Y 2 +X 8", body));
        assertThat(texel(t, 0, 0, 0)).as("the top row: last in the file").isEqualTo(64.5f);
        assertThat(texel(t, 0, 1, 0)).isEqualTo(100.5f);
        assertThat(texel(t, 2, 0, 1)).isEqualTo(3.5f);
        assertThat(texel(t, 7, 0, 1)).isEqualTo(9.5f);
        assertThat(texel(t, 5, 1, 2)).isEqualTo(0.5f);
    }

    @Test
    void theOldRepeatForm() throws IOException {
        // A texel, then (1, 1, 1, 3): it three times more.
        TextureData t = read(file("-Y 1 +X 4", 10, 20, 30, 136, 1, 1, 1, 3));
        for (int x = 0; x < 4; x++) {
            assertThat(texel(t, x, 0, 1)).isEqualTo(20.5f);
        }
    }

    @Test
    void brokenFilesFailWithReasons() {
        assertThatThrownBy(() -> RadianceImporter.read(new ByteArrayInputStream("P6\n".getBytes()), "x.hdr"))
            .hasMessageContaining("Not a Radiance HDR image: x.hdr");
        assertThatThrownBy(() -> read(file("-Y 1 +X 2", 1, 2, 3)))
            .isInstanceOf(EOFException.class).hasMessageContaining("ends early");
        assertThatThrownBy(() -> read(file("-Y 1 +X 8", 2, 2, 0, 8, 128 + 9, 1)))
            .hasMessageContaining("a run past the scanline's end");
        assertThatThrownBy(() -> read(file("-Y 1 +X 8", 2, 2, 0, 9)))
            .hasMessageContaining("a scanline of 9 texels in a row of 8");
        assertThatThrownBy(() -> read(file("-Y 1 +X 8", 2, 2, 0, 8, 0)))
            .hasMessageContaining("a run of 0 values");
        assertThatThrownBy(() -> read(file("-Y 1 +X 2", 1, 1, 1, 3)))
            .hasMessageContaining("a repeat with no texel before it");
        assertThatThrownBy(() -> read(file("-Y 99999 +X 99999")))
            .hasMessageContaining("99999 x 99999 texels");
        assertThatThrownBy(() -> read(file("+X 2 -Y 1")))
            .hasMessageContaining("rows of +X texels");
        assertThatThrownBy(() -> read(file("-Y a +X 2")))
            .hasMessageContaining("a size of");
        assertThatThrownBy(() -> RadianceImporter.read(new ByteArrayInputStream(
            "#?RADIANCE\nFORMAT=32-bit_rle_xyze\n\n-Y 1 +X 1\n".getBytes()), "x.hdr"))
            .hasMessageContaining("RGBE only");
    }

    @Test
    void everyLimitIsChecked() throws IOException {
        for (String size : new String[] {"-Y 0 +X 1", "-Y 1 +X 0", "-Y 1 +X 40000", "-Y 40000 +X 1",
            "-Y 16384 +X 32768"}) {
            assertThatThrownBy(() -> read(file(size))).as(size).hasMessageContaining("texels, not 1 to 32768");
        }
        assertThatThrownBy(() -> read(file("-Y 1"))).hasMessageContaining("rows of +X texels");
        assertThatThrownBy(() -> read(file("-Y 1 -X 2"))).hasMessageContaining("rows of +X texels");
        assertThatThrownBy(() -> read(file("-Y 1 +X 8", 2, 2, 0, 8, 9, 1)))
            .as("literals past the end").hasMessageContaining("a run of 9 values at 0 of 8");
        assertThatThrownBy(() -> read(file("-Y 1 +X 2", 5, 5, 5, 130, 1, 1, 1, 2)))
            .hasMessageContaining("a repeat past the scanline's end");
        assertThatThrownBy(() -> RadianceImporter.read(new ByteArrayInputStream(
            ("#?RADIANCE\n" + "#".repeat(5000) + "\n").getBytes()), "x.hdr"))
            .hasMessageContaining("longer than 4096 bytes");
        // Flat scanlines that start like run-length ones: too narrow, the second byte not 2, the width's top bit set,
        // the widest row (run-length rows are narrower); and texels that start like the old repeat but are not.
        assertThat(texel(read(file("-Y 1 +X 2", 2, 2, 0, 136, 3, 3, 3, 136)), 1, 0, 0)).isEqualTo(3.5f);
        int[] eight = new int[32];
        eight[0] = 5;
        eight[3] = 136;
        assertThat(texel(read(file("-Y 1 +X 8", eight)), 0, 0, 0)).as("wide and flat").isEqualTo(5.5f);
        eight[0] = 2;
        eight[1] = 3;
        eight[3] = 136;
        assertThat(texel(read(file("-Y 1 +X 8", eight)), 0, 0, 1)).isEqualTo(3.5f);
        eight[1] = 2;
        eight[2] = 0x80;
        assertThat(texel(read(file("-Y 1 +X 8", eight)), 0, 0, 2)).isEqualTo(128.5f);
        int[] widest = new int[4 * 32768];
        widest[0] = 2;
        widest[1] = 2;
        widest[3] = 136;
        assertThat(texel(read(file("-Y 1 +X 32768", widest)), 0, 0, 1)).isEqualTo(2.5f);
        TextureData almost = read(file("-Y 1 +X 3", 1, 2, 1, 136, 1, 1, 2, 136, 9, 9, 9, 136));
        assertThat(texel(almost, 0, 0, 1)).isEqualTo(2.5f);
        assertThat(texel(almost, 1, 0, 2)).isEqualTo(2.5f);
        ByteArrayOutputStream older = new ByteArrayOutputStream();
        older.writeBytes("#?RGBE\n\n-Y 1 +X 1\n".getBytes(StandardCharsets.US_ASCII));
        older.writeBytes(new byte[] {1, 1, 2, (byte) 136});
        assertThat(texel(read(older.toByteArray()), 0, 0, 2)).as("the older magic").isEqualTo(2.5f);
    }

    @Test
    void halfFloatTexelsAreLinearAndSized() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TextureData(1, 1, TextureData.ColorSpace.SRGB,
            TextureData.Format.RGBA16F, Arena.ofAuto().allocate(8))).withMessageContaining("linear");
        assertThatIllegalArgumentException().isThrownBy(() -> new TextureData(2, 1, TextureData.ColorSpace.LINEAR,
            TextureData.Format.RGBA16F, Arena.ofAuto().allocate(8))).withMessageContaining("16 bytes of RGBA16F");
        assertThat(new TextureData(1, 1, TextureData.ColorSpace.SRGB, Arena.ofAuto().allocate(4)).format())
            .isEqualTo(TextureData.Format.RGBA8);
    }

    @Test
    void importedByPath() throws Exception {
        Files.write(dir.resolve("sky.hdr"), file("-Y 1 +X 1", 128, 128, 128, 129));
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            PathAssets paths = new PathAssets(cache);
            paths.mounts().add("game", PathSource.folder(dir));
            paths.importers().add(RenderAssets.TEXTURE, "hdr", RadianceImporter.INSTANCE);
            AssetRef<TextureData> ref = paths.ref(RenderAssets.TEXTURE, "game://sky.hdr");
            cache.acquire(ref);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cache.loadingCount() > 0) {
                tasks.drainCompleted();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
            }
            assertThat(cache.failure(ref)).isNull();
            assertThat(texel(cache.get(ref), 0, 0, 0)).isCloseTo(1, within(0.01f));
        }
        // Read by id from a source of its own: no path to name it.
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            cache.register(RenderAssets.TEXTURE, RadianceImporter.INSTANCE,
                (id, resource) -> new ByteArrayInputStream("broken".getBytes()));
            AssetRef<TextureData> byId = AssetRef.of(RenderAssets.TEXTURE, new AssetId(0, 7));
            cache.acquire(byId);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cache.loadingCount() > 0) {
                tasks.drainCompleted();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
            }
            assertThat(cache.failure(byId)).hasMessageContaining(new AssetId(0, 7).toString());
        }
    }
}
