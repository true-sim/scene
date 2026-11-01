package com.truesim.scene.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.common.jobs.TaskScheduler;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Assets by path: mounts find files, importers read them by extension, redirects survive renames. */
class PathAssetsTest {

    /** What a test "model" holds: its text, its side resource's text, and where it was found. */
    record Model(String main, String side, AssetPath path, int part, AssetId id) {
    }

    private static final AssetType<Model> MODEL = AssetType.of("model", Model.class, model -> 1);
    private static final AssetType<String> TEXT = AssetType.of("text", String.class, String::length);

    /** A "glTF": its main resource names a side resource, read relative to its folder. */
    private static final AssetLoader<Model> GLTF = input -> {
        String main = read(input.open());
        return new Model(main, read(input.open(main)), input.path(), input.part(), input.id());
    };

    @TempDir
    Path dir;

    private static String read(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Loads {@code ref} through {@code cache} to its end. */
    private static <T> T load(TaskScheduler tasks, AssetCache cache, AssetRef<T> ref) throws InterruptedException {
        cache.acquire(ref);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cache.loadingCount() > 0) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
        return cache.get(ref);
    }

    @Test
    void modelsLoadByPathFromMountsWithOverrides() throws Exception {
        Path game = Files.createDirectory(dir.resolve("game"));
        Path mod = Files.createDirectory(dir.resolve("mod"));
        write(game, "models/rock.gltf", "rock.bin");
        write(game, "models/rock.bin", "granite");
        write(mod, "models/rock.bin", "marble"); // the mod overrides one file, path by path
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            PathAssets assets = new PathAssets(cache);
            assets.mounts().add("game", PathSource.folder(game)).addFirst("game", PathSource.folder(mod));
            assets.importers().add(MODEL, "gltf", GLTF).add(MODEL, "GLB", GLTF);
            assertThat(assets.importers().find(MODEL, "glb")).isSameAs(GLTF);
            assertThat(assets.importers().find(MODEL, "png")).isNull();
            assertThat(assets.importers().find(TEXT, "txt")).isNull();
            assertThat(assets.mounts().has("game")).isTrue();
            assertThat(assets.mounts().has("dlc")).isFalse();

            AssetRef<Model> rock = assets.ref(MODEL, "game://models/rock.gltf");
            assertThat(rock.id()).isEqualTo(AssetPath.parse("game://models/rock.gltf").id());
            assertThat(assets.pathOf(rock.id())).hasToString("game://models/rock.gltf");
            Model model = load(tasks, cache, rock.part(1));
            assertThat(model).isEqualTo(new Model("rock.bin", "marble", AssetPath.parse("game://models/rock.gltf"),
                1, rock.id()));
            assertThat(assets.ref(MODEL, AssetPath.parse("game://models/rock.gltf"))).isEqualTo(rock);
        }
    }

    @Test
    void redirectsKeepRenamedAssetsLoading() throws Exception {
        write(dir, "models/rocks/granite.gltf", "granite.bin");
        write(dir, "models/rocks/granite.bin", "moved");
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            PathAssets assets = new PathAssets(cache);
            assets.mounts().add("game", PathSource.folder(dir))
                .redirect("game://models/rock.gltf", "game://models/old/granite.gltf")
                .redirect("game://models/old/granite.gltf", "game://models/rocks/granite.gltf");
            assets.importers().add(MODEL, "gltf", GLTF);
            AssetRef<Model> old = assets.ref(MODEL, "game://models/rock.gltf");
            assertThat(load(tasks, cache, old).side()).as("side resources next to where it is now").isEqualTo("moved");
            AssetId now = assets.canonical(old.id());
            assertThat(now).isEqualTo(AssetPath.parse("game://models/rocks/granite.gltf").id());
            assertThat(assets.pathOf(now)).hasToString("game://models/rocks/granite.gltf");
            assertThat(assets.canonical(now)).as("not redirected: itself").isEqualTo(now);
            assertThat(assets.canonical(new AssetId(1, 2))).as("no path: itself").isEqualTo(new AssetId(1, 2));
            assertThatIllegalArgumentException().isThrownBy(() -> assets.mounts()
                .redirect("game://models/rocks/granite.gltf", "game://models/rock.gltf"))
                .withMessageContaining("cycle");
            assertThatIllegalArgumentException().isThrownBy(() -> assets.mounts().add("Game", path -> null));
        }
    }

    @Test
    void whatCannotLoadFails() throws Exception {
        write(dir, "notes/readme.txt", "hello");
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            PathAssets assets = new PathAssets(cache);
            assets.mounts().add("game", path -> null).add("game", PathSource.folder(dir));
            assets.importers().add(MODEL, "gltf", GLTF);
            // No importer for the extension.
            AssetRef<Model> notes = assets.ref(MODEL, "game://notes/readme.txt");
            load(tasks, cache, notes);
            assertThat(cache.failure(notes)).hasMessageContaining("No importer for .txt");
            // An id no path made: nothing knows where it is.
            AssetRef<Model> unknown = AssetRef.of(MODEL, new AssetId(1, 2));
            load(tasks, cache, unknown);
            assertThat(cache.failure(unknown)).hasMessageContaining("has no path");
            assertThat(assets.source().open(new AssetId(1, 2), AssetSource.MAIN)).isNull();
            // A file no location has, and a mount with none.
            assertThat(assets.mounts().open(AssetPath.parse("game://missing.gltf"))).isNull();
            assertThat(assets.mounts().open(AssetPath.parse("dlc://missing.gltf"))).isNull();
            // Registration mistakes.
            assertThatIllegalArgumentException().isThrownBy(() -> assets.importers().add(MODEL, "", GLTF));
            assertThatIllegalArgumentException().isThrownBy(() -> assets.importers().add(MODEL, ".glb", GLTF));
            assertThatIllegalStateException().isThrownBy(() -> assets.importers().add(MODEL, "GLTF", GLTF))
                .withMessageContaining("has an importer for .gltf already");
            cache.register(TEXT, input -> String.valueOf(input.path()));
            assertThat(load(tasks, cache, AssetRef.of(TEXT, new AssetId(3, 4)))).as("loaded by id: no path")
                .isEqualTo("null");
            assertThatIllegalStateException().isThrownBy(() -> assets.importers().add(TEXT, "txt", input -> ""))
                .withMessageContaining("has a loader already");
        }
    }

    @Test
    void foldersNeverOpenFilesOutsideThem() throws Exception {
        Path inside = Files.createDirectory(dir.resolve("inside"));
        Files.writeString(dir.resolve("secret.txt"), "secret");
        Files.writeString(inside.resolve("open.txt"), "open");
        PathSource folder = PathSource.folder(inside);
        assertThat(read(folder.open("open.txt"))).isEqualTo("open");
        assertThat(folder.open("../secret.txt")).isNull();
        assertThat(folder.open("missing.txt")).isNull();
        // Any location works the same: here, bytes from memory.
        PathSource memory = path -> new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8));
        assertThat(read(memory.open("a/b"))).isEqualTo("a/b");
    }
}
