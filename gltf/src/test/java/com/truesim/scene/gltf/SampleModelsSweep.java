package com.truesim.scene.gltf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        int skinned = 0;
        for (Path file : files) {
            try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
                PathAssets paths = new PathAssets(cache);
                paths.mounts().add("s", PathSource.folder(file.getParent()));
                new Gltf(paths).register();
                for (String extension : List.of("png", "jpg", "jpeg", "webp")) {
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
                // Each skinned node's mesh names joints of its skin.
                ModelData m = (ModelData) model;
                for (ModelData.Node node : m.nodes()) {
                    if (node.skin() >= 0 && cache.get(node.mesh()) instanceof MeshData mesh) {
                        skinned++;
                        int joints = m.skins().get(node.skin()).jointCount();
                        boolean fits = mesh.jointCount() > 0;
                        for (int j = 0; j < mesh.jointCount(); j++) {
                            fits &= mesh.paletteJoint(j) < joints;
                        }
                        if (!fits) {
                            failures.put(name + " node " + node.name(), "a palette past its skin's " + joints
                                + " joints, or none");
                        }
                    }
                }
            }
        }
        System.out.println("glTF samples: " + files.size() + " files, " + parts + " parts, " + skinned
            + " skinned nodes, " + failures.size() + " failures");
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
    /** Every meshopt-compressed view of the Khronos meshopt sample decodes to its uncompressed fallback's bytes. */
    @Test
    @SuppressWarnings("unchecked")
    void meshoptDecodesToTheFallback() throws Exception {
        String root = System.getProperty("truesim.gltf.samples");
        assumeThat(root).as("no samples given").isNotNull();
        Path file = Path.of(root, "MeshoptCubeTest", "glTF", "MeshoptCubeTest.gltf");
        Map<String, byte[]> side = new java.util.HashMap<>();
        try (Stream<Path> files = Files.list(file.getParent())) {
            for (Path p : files.toList()) {
                side.put(p.getFileName().toString(), Files.readAllBytes(p));
            }
        }
        GltfDocument document = GltfDocument.read(GltfDocumentTest.input(Files.readAllBytes(file), side));
        Map<String, Object> json = (Map<String, Object>) Json.parse(Files.readString(file));
        List<Map<String, Object>> buffers = (List<Map<String, Object>>) json.get("buffers");
        int compared = 0;
        List<Map<String, Object>> views = document.list("bufferViews");
        for (int v = 0; v < views.size(); v++) {
            Map<String, Object> view = views.get(v);
            if (Meshopt.compression(view).isEmpty()) {
                continue;
            }
            byte[] fallback = side.get((String) buffers.get(GltfDocument.integer(view, "buffer", 0)).get("uri"));
            int offset = GltfDocument.integer(view, "byteOffset", 0);
            byte[] expected = java.util.Arrays.copyOfRange(fallback, offset,
                offset + GltfDocument.integer(view, "byteLength", 0));
            java.nio.ByteBuffer decoded = document.view(v);
            byte[] actual = new byte[decoded.remaining()];
            decoded.get(actual);
            if ("TRIANGLES".equals(Meshopt.compression(view).get("mode"))) {
                // the triangle codec keeps each triangle and its winding, not which vertex comes first
                int stride = GltfDocument.integer(Meshopt.compression(view), "byteStride", 2);
                assertThat(rotated(actual, stride)).as("view " + v).isEqualTo(rotated(expected, stride));
            } else {
                assertThat(actual).as("view " + v + " " + Meshopt.compression(view)).isEqualTo(expected);
            }
            compared++;
        }
        System.out.println("meshopt: " + compared + " views decoded to their fallback");
        assertThat(compared).isPositive();
    }

    /** {@code bytes}' triangles (indices of {@code size} bytes), each turned to start at its smallest index. */
    private static List<List<Integer>> rotated(byte[] bytes, int size) {
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        List<List<Integer>> triangles = new ArrayList<>();
        for (int at = 0; at + 3 * size <= bytes.length; at += 3 * size) {
            int[] t = new int[3];
            for (int k = 0; k < 3; k++) {
                t[k] = size == 2 ? in.getShort(at + k * size) & 0xFFFF : in.getInt(at + k * size);
            }
            int first = t[0] <= t[1] && t[0] <= t[2] ? 0 : t[1] <= t[2] ? 1 : 2;
            triangles.add(List.of(t[first], t[(first + 1) % 3], t[(first + 2) % 3]));
        }
        return triangles;
    }
}
