package com.truesim.scene.gltf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Imports every Khronos sample model (glTF-Sample-Assets' {@code Models}, given by {@code -PgltfSamples=...}) with its
 * meshes, materials and textures, and reports what fails and why. Skipped without the samples.
 */
class SampleModelsSweep {

    @Test
    void everySampleModelImports() throws Exception {
        String root = System.getProperty("truesim.gltf.samples");
        assumeThat(root).as("no samples given: -PgltfSamples=<glTF-Sample-Assets/Models>").isNotNull();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(root))) {
            files = walk.filter(p -> p.getParent().getFileName().toString().matches("glTF|glTF-Binary"))
                .filter(p -> p.toString().endsWith(".gltf") || p.toString().endsWith(".glb")).sorted().toList();
        }
        TreeMap<String, String> failures = new TreeMap<>();
        int parts = 0;
        for (Path file : files) {
            try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
                PathAssets paths = new PathAssets(cache);
                paths.mounts().add("s", PathSource.folder(file.getParent()));
                new Gltf(paths).register();
                for (String extension : List.of("png", "jpg", "jpeg")) {
                    paths.importers().add(RenderAssets.TEXTURE, extension, ImageImporter.INSTANCE);
                }
                String name = Path.of(root).relativize(file).toString().replace(java.io.File.separatorChar, '/');
                AssetRef<ModelData> modelRef = paths.ref(RenderAssets.MODEL, "s://" + file.getFileName());
                Object model = load(tasks, cache, modelRef, name, failures);
                if (model == null) {
                    continue;
                }
                Set<AssetRef<?>> refs = new LinkedHashSet<>(((ModelData) model).references());
                List<AssetRef<?>> textures = new ArrayList<>();
                for (AssetRef<?> ref : refs) {
                    Object data = load(tasks, cache, ref, name, failures);
                    parts++;
                    if (data instanceof MaterialData material) {
                        textures.addAll(material.textures());
                    }
                }
                for (AssetRef<?> texture : new LinkedHashSet<>(textures)) {
                    load(tasks, cache, texture, name, failures);
                    parts++;
                }
            }
        }
        System.out.println("glTF samples: " + files.size() + " files, " + parts + " parts, " + failures.size()
            + " failures");
        failures.forEach((what, why) -> System.out.println("  FAIL " + what + ": " + why));
        assertThat(files).isNotEmpty();
    }

    private static Object load(TaskScheduler tasks, AssetCache cache, AssetRef<?> ref, String file,
        TreeMap<String, String> failures) throws InterruptedException {
        if (ref.isGenerated()) {
            return null; // inline material data: nothing to import
        }
        cache.acquire(ref);
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (cache.loadingCount() > 0 && System.nanoTime() < deadline) {
            tasks.drainCompleted();
            Thread.sleep(1);
        }
        Throwable failure = cache.failure(ref);
        if (failure != null) {
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            failures.put(file + " " + ref.type().name() + " " + ref.id(), cause.getClass().getSimpleName() + ": "
                + cause.getMessage());
        }
        return cache.get(ref);
    }
}
