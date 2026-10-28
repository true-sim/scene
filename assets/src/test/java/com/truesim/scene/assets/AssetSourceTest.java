package com.truesim.scene.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.jobs.TaskScheduler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sources find bytes, transforms change them on the way, and one loader reads them whatever the source. */
class AssetSourceTest {

    private static final AssetId SCENE = new AssetId(10, 20);
    private static final AssetId MISSING = new AssetId(30, 40);

    /** A "glTF" that names its buffer: the main resource is the buffer's name, the buffer holds the mesh's name. */
    record Mesh(String name) {
    }

    private static final AssetType<Mesh> MESH = AssetType.of("mesh", Mesh.class, mesh -> 1);

    /** One loader for every source: reads the main resource, then the side resource it names. */
    private static final AssetLoader<Mesh> GLTF = input -> {
        String buffer = read(input.open());
        return new Mesh(read(input.open(buffer)));
    };

    @TempDir
    Path dir;

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /** Loads {@code SCENE} with {@code loader} from {@code source} through a cache, as a game would. */
    private static Mesh load(AssetSource source) throws InterruptedException {
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            cache.setSource(source);
            cache.register(MESH, GLTF);
            AssetRef<Mesh> ref = AssetRef.of(MESH, SCENE);
            cache.acquire(ref);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cache.loadingCount() > 0) {
                tasks.drainCompleted();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
            }
            return cache.get(ref);
        }
    }

    @Test
    void oneLoaderReadsFromAnySource() throws Exception {
        // Loose files in a directory: root/<id> and root/<id>.<resource>.
        Files.writeString(dir.resolve(SCENE.toString()), "body.bin");
        Files.writeString(dir.resolve(SCENE + ".body.bin"), "car from files");
        assertThat(load(AssetSource.directory(dir)).name()).isEqualTo("car from files");

        // Compressed files in a directory of your own layout.
        Path packed = Files.createDirectory(dir.resolve("packed"));
        Files.write(packed.resolve("scene.gz"), gzip("body.bin"));
        Files.write(packed.resolve("body.bin.gz"), gzip("car from gzip"));
        AssetSource gzipped = AssetSource.directory(packed, (id, resource) ->
            (resource.isEmpty() ? "scene" : resource) + ".gz").through(AssetSource.GZIP);
        assertThat(load(gzipped).name()).isEqualTo("car from gzip");

        // "Encrypted" bytes from a network-like source, decrypted by a transform that knows the asset.
        AssetSource network = (id, resource) -> new ByteArrayInputStream(xor((resource.isEmpty() ? "body.bin"
            : "car from the network").getBytes(StandardCharsets.UTF_8), (byte) id.low()));
        AssetSource decrypted = network.through((id, resource, in) ->
            new ByteArrayInputStream(xor(in.readAllBytes(), (byte) id.low())));
        assertThat(load(decrypted).name()).isEqualTo("car from the network");

        // Sources chained: the first that has a resource wins, per resource.
        AssetSource patch = (id, resource) -> resource.equals("body.bin")
            ? new ByteArrayInputStream("patched car".getBytes(StandardCharsets.UTF_8)) : null;
        assertThat(load(AssetSource.firstOf(patch, AssetSource.directory(dir))).name()).isEqualTo("patched car");
    }

    private static byte[] xor(byte[] bytes, byte key) {
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] ^= key;
        }
        return bytes;
    }

    @Test
    void missingResourcesFailTheAsset() throws Exception {
        AssetSource source = AssetSource.firstOf(AssetSource.directory(dir).through(AssetSource.GZIP));
        assertThat(source.open(MISSING, AssetSource.MAIN)).isNull(); // transforms leave missing resources missing
        Files.writeString(dir.resolve(SCENE.toString()), "nowhere.bin"); // names a side resource nobody has
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            cache.register(MESH, GLTF, AssetSource.directory(dir));
            AssetRef<Mesh> ref = AssetRef.of(MESH, SCENE);
            cache.acquire(ref);
            while (cache.loadingCount() > 0) {
                tasks.drainCompleted();
                Thread.sleep(1);
            }
            assertThat(cache.state(ref)).isEqualTo(AssetState.FAILED);
            assertThat(cache.failure(ref)).isInstanceOf(FileNotFoundException.class)
                .hasMessage("No source has nowhere.bin of asset " + SCENE);
        }
        assertThatNullPointerException().isThrownBy(() -> AssetSource.directory(null));
        assertThatNullPointerException().isThrownBy(() -> AssetSource.directory(dir, null));
        assertThatNullPointerException().isThrownBy(() -> source.through(null));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> AssetSource.firstOf(source, null));
    }
}
