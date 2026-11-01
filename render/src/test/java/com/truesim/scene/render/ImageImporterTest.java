package com.truesim.scene.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetPath;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import java.awt.image.BufferedImage;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageImporterTest {

    @TempDir
    Path dir;

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
    void pngsImportAsRgbaTexturesInTheirColorSpace() throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80FF2010); // A 0x80, R 0xFF, G 0x20, B 0x10
        image.setRGB(1, 0, 0xFF000000);
        ImageIO.write(image, "png", dir.resolve("rock.png").toFile());
        Files.writeString(dir.resolve("broken.png"), "not an image");
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            PathAssets paths = new PathAssets(cache);
            paths.mounts().add("game", PathSource.folder(dir));
            paths.importers().add(RenderAssets.TEXTURE, "png", ImageImporter.INSTANCE);
            TextureData rock = load(tasks, cache, paths.ref(RenderAssets.TEXTURE, "game://rock.png"));
            assertThat(rock.width()).isEqualTo(2);
            assertThat(rock.height()).isEqualTo(1);
            assertThat(rock.colorSpace()).isEqualTo(TextureData.ColorSpace.SRGB);
            assertThat(rock.texels().toArray(ValueLayout.JAVA_BYTE))
                .containsExactly((byte) 0xFF, (byte) 0x20, (byte) 0x10, (byte) 0x80, 0, 0, 0, (byte) 0xFF);
            TextureData normals = load(tasks, cache, paths.ref(RenderAssets.TEXTURE, "game://rock.png#linear"));
            assertThat(normals.colorSpace()).isEqualTo(TextureData.ColorSpace.LINEAR);
            AssetRef<TextureData> broken = paths.ref(RenderAssets.TEXTURE, "game://broken.png");
            load(tasks, cache, broken);
            assertThat(cache.failure(broken)).hasMessageContaining("Not an image the JDK reads: game://broken.png");
        }
        // Read by id from a source of its own: no path, colors.
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            cache.register(RenderAssets.TEXTURE, ImageImporter.INSTANCE,
                (id, resource) -> Files.newInputStream(dir.resolve(id.low() == 1 ? "rock.png" : "broken.png")));
            AssetRef<TextureData> byId = AssetRef.of(RenderAssets.TEXTURE, new AssetId(0, 1));
            assertThat(load(tasks, cache, byId).colorSpace()).isEqualTo(TextureData.ColorSpace.SRGB);
            AssetRef<TextureData> brokenById = AssetRef.of(RenderAssets.TEXTURE, new AssetId(0, 2));
            load(tasks, cache, brokenById);
            assertThat(cache.failure(brokenById)).hasMessageContaining(new AssetId(0, 2).toString());
        }
        assertThat(ImageImporter.colorSpace(AssetPath.parse("game://a.png#other")))
            .isEqualTo(TextureData.ColorSpace.SRGB);
    }
    @Test
    void webpImportsThroughTheImageIoPlugin() throws Exception {
        // A 1 x 1 lossless WebP.
        Files.write(dir.resolve("dot.webp"), java.util.Base64.getDecoder().decode(
            "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA=="));
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            PathAssets paths = new PathAssets(cache);
            paths.mounts().add("game", PathSource.folder(dir));
            paths.importers().add(RenderAssets.TEXTURE, "webp", ImageImporter.INSTANCE);
            AssetRef<TextureData> dot = paths.ref(RenderAssets.TEXTURE, "game://dot.webp");
            TextureData texture = load(tasks, cache, dot);
            assertThat(cache.failure(dot)).isNull();
            assertThat(texture.width()).isEqualTo(1);
            assertThat(texture.height()).isEqualTo(1);
        }
    }
}
