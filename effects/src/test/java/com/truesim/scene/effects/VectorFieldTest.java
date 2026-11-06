package com.truesim.scene.effects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Vector fields as assets: the data's layout and checks, and FGA files imported through the asset cache. */
class VectorFieldTest {

    @TempDir
    Path dir;

    private static VectorFieldData read(String text) throws IOException {
        return FgaImporter.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "test.fga");
    }

    @Test
    void cellsAreThreeFloatsXFastest() {
        float[] vectors = new float[3 * 2 * 3 * 4];
        for (int i = 0; i < vectors.length; i++) {
            vectors[i] = i;
        }
        VectorFieldData field = new VectorFieldData(2, 3, 4, vectors);
        assertThat(field.cells()).isEqualTo(24);
        assertThat(field.bytes()).isEqualTo(4L * 72);
        assertThat(field.minX()).isEqualTo(-1);
        assertThat(field.maxZ()).isEqualTo(1);
        // cell (1, 2, 3): index (3 * 3 + 2) * 2 + 1 = 23
        assertThat(field.vector(1, 2, 3, 0)).isEqualTo(69);
        assertThat(field.vector(1, 2, 3, 2)).isEqualTo(71);
        assertThat(field.vector(0, 0, 0, 1)).isEqualTo(1);
        assertThatThrownBy(() -> field.vector(2, 0, 0, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> field.vector(0, 0, 0, 3)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void fieldsOfTheWrongSizeAreRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VectorFieldData(2, 2, 2, new float[23]))
            .withMessageContaining("takes 24 floats: 23");
        assertThatIllegalArgumentException().isThrownBy(() -> new VectorFieldData(0, 2, 2, new float[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> new VectorFieldData(1, 0, 1, new float[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> new VectorFieldData(1, 1, 0, new float[0]));
        assertThatThrownBy(() -> new VectorFieldData(1, 1, 1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void fgaFilesGiveSizeBoxAndVectors() throws IOException {
        VectorFieldData field = read("2, 1, 1,\n-2.5, -1, 0,\n2.5, 1, 3,\n1, 0, 0,\n0, -9.81, 0.5,\n");
        assertThat(field.width()).isEqualTo(2);
        assertThat(field.height()).isEqualTo(1);
        assertThat(field.depth()).isEqualTo(1);
        assertThat(field.minX()).isEqualTo(-2.5f);
        assertThat(field.minY()).isEqualTo(-1);
        assertThat(field.minZ()).isEqualTo(0);
        assertThat(field.maxX()).isEqualTo(2.5f);
        assertThat(field.maxY()).isEqualTo(1);
        assertThat(field.maxZ()).isEqualTo(3);
        assertThat(field.vector(0, 0, 0, 0)).isEqualTo(1);
        assertThat(field.vector(1, 0, 0, 1)).isEqualTo(-9.81f);
        assertThat(field.vector(1, 0, 0, 2)).isEqualTo(0.5f);
        // White space alone separates as well, without a trailing comma
        assertThat(read("1 1 1 0 0 0 1 1 1 4 5 6").vector(0, 0, 0, 2)).isEqualTo(6);
    }

    @Test
    void brokenFgaFilesAreRefusedWithWhatIsWrong() {
        assertThatThrownBy(() -> read("")).isInstanceOf(IOException.class).hasMessageContaining("9 numbers");
        assertThatThrownBy(() -> read("1, 1, 1, 0, 0")).hasMessageContaining("9 numbers");
        assertThatThrownBy(() -> read("x, 1, 1, 0, 0, 0, 1, 1, 1")).hasMessageContaining("a size of x");
        assertThatThrownBy(() -> read("0, 1, 1, 0, 0, 0, 1, 1, 1")).hasMessageContaining("0 cells a side");
        assertThatThrownBy(() -> read("1025, 1, 1, 0, 0, 0, 1, 1, 1")).hasMessageContaining("1025 cells a side");
        assertThatThrownBy(() -> read("1024, 1024, 1024, 0, 0, 0, 1, 1, 1")).hasMessageContaining("more than");
        assertThatThrownBy(() -> read("1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 2"))
            .hasMessageContaining("take 12 numbers: 11");
        assertThatThrownBy(() -> read("1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 2, 3, 4")).hasMessageContaining("13");
        assertThatThrownBy(() -> read("1, 1, 1, 0, 0, z, 1, 1, 1, 1, 2, 3")).hasMessageContaining("not a number: z");
        assertThatThrownBy(() -> read("1, 1, 1, 0, 0, 0, 1, 1, 1, 1, NaN, 3")).hasMessageContaining("finite");
        assertThatThrownBy(() -> read("1, 1, 1, 0, 0, 0, 1, 1, 1, Infinity, 1, 3")).hasMessageContaining("finite");
    }

    @Test
    void importedThroughTheAssetCacheByPathAndById() throws Exception {
        Files.writeString(dir.resolve("swirl.fga"), "1, 1, 2, -1, -1, -1, 1, 1, 1, 0, 1, 0, 0, 2, 0");
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            PathAssets paths = new PathAssets(cache);
            paths.mounts().add("game", PathSource.folder(dir));
            paths.importers().add(EffectAssets.VECTOR_FIELD, "fga", FgaImporter.INSTANCE);
            AssetRef<VectorFieldData> ref = paths.ref(EffectAssets.VECTOR_FIELD, "game://swirl.fga");
            cache.acquire(ref);
            settle(tasks, cache);
            assertThat(cache.failure(ref)).isNull();
            assertThat(cache.get(ref).vector(0, 0, 1, 1)).isEqualTo(2);
        }
        // Read by id from a source of its own: no path to name it.
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            cache.register(EffectAssets.VECTOR_FIELD, FgaImporter.INSTANCE,
                (id, resource) -> new ByteArrayInputStream("broken".getBytes(StandardCharsets.UTF_8)));
            AssetRef<VectorFieldData> byId = AssetRef.of(EffectAssets.VECTOR_FIELD, new AssetId(0, 7));
            cache.acquire(byId);
            settle(tasks, cache);
            assertThat(cache.failure(byId)).hasMessageContaining(new AssetId(0, 7).toString());
        }
    }

    private static void settle(TaskScheduler tasks, AssetCache cache) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cache.loadingCount() > 0) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
    }
}
