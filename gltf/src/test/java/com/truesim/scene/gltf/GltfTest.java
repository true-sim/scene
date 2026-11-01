package com.truesim.scene.gltf;

import static com.truesim.scene.gltf.GltfDocumentTest.base64;
import static com.truesim.scene.gltf.GltfDocumentTest.glb;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** glTF files imported through path assets, as a game loads them: models, meshes, materials and images. */
class GltfTest {

    @TempDir
    Path dir;

    private TaskScheduler tasks;
    private AssetCache cache;
    private PathAssets paths;

    @BeforeEach
    void start() {
        tasks = new TaskScheduler();
        cache = new AssetCache(tasks);
        paths = new PathAssets(cache);
        paths.mounts().add("game", PathSource.folder(dir));
        new Gltf(paths).register();
        paths.importers().add(RenderAssets.TEXTURE, "png", ImageImporter.INSTANCE);
    }

    @AfterEach
    void stop() {
        cache.close();
        tasks.close();
    }

    private <T> T load(AssetRef<T> ref) throws InterruptedException {
        cache.acquire(ref);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (cache.loadingCount() > 0) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
        T loaded = cache.get(ref);
        if (loaded == null && cache.failure(ref) != null && !expectFailure) {
            throw new AssertionError(ref + " failed", cache.failure(ref));
        }
        return loaded;
    }

    private boolean expectFailure;

    private Throwable failure(String path, com.truesim.scene.assets.AssetType<?> type) throws InterruptedException {
        AssetRef<?> ref = paths.ref(type, path);
        expectFailure = true;
        try {
            load(ref);
        } finally {
            expectFailure = false;
        }
        return cache.failure(ref);
    }

    private static byte[] png(int argb) throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A quad (4 vertices: positions, normals, uvs; 6 ushort indices) and a fan/strip mesh's 4 positions + colors. */
    private static byte[] geometry() {
        ByteBuffer b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        float[] quad = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
        for (float f : quad) {
            b.putFloat(f); // 0..48: positions
        }
        for (int i = 0; i < 4; i++) {
            b.putFloat(0).putFloat(0).putFloat(1); // 48..96: normals
        }
        for (int i = 0; i < 4; i++) {
            b.putFloat(quad[3 * i]).putFloat(quad[3 * i + 1]); // 96..128: uvs
        }
        for (short s : new short[] {0, 1, 2, 0, 2, 3}) {
            b.putShort(s); // 128..140: indices
        }
        b.position(140);
        for (int i = 0; i < 4; i++) {
            b.putFloat(1).putFloat(0.5f).putFloat(0.25f); // 140..188: RGB colors
        }
        return b.array();
    }

    private static final String VIEWS = """
        "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 48},
          {"buffer": 0, "byteOffset": 48, "byteLength": 48}, {"buffer": 0, "byteOffset": 96, "byteLength": 32},
          {"buffer": 0, "byteOffset": 128, "byteLength": 12}, {"buffer": 0, "byteOffset": 140, "byteLength": 48}],
        "accessors": [{"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
          {"bufferView": 1, "componentType": 5126, "count": 4, "type": "VEC3"},
          {"bufferView": 2, "componentType": 5126, "count": 4, "type": "VEC2"},
          {"bufferView": 3, "componentType": 5123, "count": 6, "type": "SCALAR"},
          {"bufferView": 4, "componentType": 5126, "count": 4, "type": "VEC3"}],
        """;

    @Test
    void aTextFileWithItsBufferAndImages() throws Exception {
        Files.createDirectories(dir.resolve("models"));
        Files.write(dir.resolve("models/car.bin"), geometry());
        Files.write(dir.resolve("models/paint.png"), png(0xFFFF0000));
        Files.write(dir.resolve("models/orm.png"), png(0xFF00FF00));
        String json = """
            {"asset": {"version": "2.0"}, "scene": 0, "scenes": [{"nodes": [0]}],
             "nodes": [{"name": "car", "matrix": [-1,0,0,0, 0,1,0,0, 0,0,-1,0, 5,6,7,1], "children": [1, 2]},
               {"name": "body", "mesh": 0}, {"name": "trim", "mesh": 1, "translation": [0, 1, 0],
               "rotation": [0, 0, 0, 1], "scale": [2, 2, 2]}],
             "buffers": [{"uri": "car.bin", "byteLength": 256}],
            """ + VIEWS + """
             "meshes": [{"primitives": [
                 {"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "indices": 3, "material": 0},
                 {"attributes": {"POSITION": 0, "NORMAL": 1, "TEXCOORD_0": 2}, "mode": 6}]},
               {"primitives": [{"attributes": {"POSITION": 0, "COLOR_0": 4}, "mode": 5, "material": 1},
                 {"attributes": {"POSITION": 0, "COLOR_0": 4}, "mode": 6, "material": 2}]}],
             "materials": [
               {"pbrMetallicRoughness": {"baseColorFactor": [1, 0.5, 0.5, 1], "baseColorTexture": {"index": 0},
                  "metallicFactor": 0.25, "roughnessFactor": 0.75, "metallicRoughnessTexture": {"index": 1}},
                "occlusionTexture": {"index": 1, "strength": 0.5}, "normalTexture": {"index": 2, "scale": 0.8},
                "emissiveFactor": [1, 1, 0], "emissiveTexture": {"index": 0}},
               {"alphaMode": "MASK", "alphaCutoff": 0.3, "doubleSided": true,
                "pbrMetallicRoughness": {"metallicRoughnessTexture": {"index": 1}}, "occlusionTexture": {"index": 0}},
               {"pbrMetallicRoughness": {"baseColorTexture": {"index": 3}}}],
             "textures": [{"source": 0}, {"source": 1}, {"source": 2}, {"extensions": {}}],
             "images": [{"uri": "paint.png"}, {"uri": "orm.png"}, {"uri": "%s"}]}
            """.formatted(base64(png(0xFF8080FF)).replace("application/octet-stream", "image/png"));
        Files.writeString(dir.resolve("models/car.gltf"), json);

        ModelData car = load(paths.ref(RenderAssets.MODEL, "game://models/car.gltf"));
        assertThat(car.nodes()).extracting(ModelData.Node::name).containsExactly("car", "body", "trim");
        ModelData.Node root = car.nodes().get(0);
        assertThat(root.tx()).isEqualTo(5);
        assertThat(root.ry()).as("turned half around y").isCloseTo(1, within(1e-9));
        ModelData.Node body = car.nodes().get(1);
        assertThat(body.parent()).isZero();
        assertThat(body.mesh()).isEqualTo(paths.ref(RenderAssets.MESH, "game://models/car.gltf#mesh/0"));
        assertThat(body.materials()).containsExactly(paths.ref(RenderAssets.MATERIAL,
            "game://models/car.gltf#material/0"), MaterialData.DEFAULT.ref());
        assertThat(car.nodes().get(2).sx()).isEqualTo(2);

        MeshData mesh = load(body.mesh());
        assertThat(mesh.materialSlots()).isEqualTo(2);
        assertThat(mesh.format().has(VertexAttribute.UV0)).isTrue();
        MeshData trim = load(car.nodes().get(2).mesh());
        assertThat(trim.format().has(VertexAttribute.COLOR)).as("RGB colors, alpha 1").isTrue();
        assertThat(trim.format().has(VertexAttribute.NORMAL)).as("smooth normals made").isTrue();
        assertThat(trim.materialSlots()).isEqualTo(2);

        MaterialData paint = load(body.materials().get(0));
        assertThat(paint.type()).isEqualTo(MaterialData.METALLIC_ROUGHNESS);
        assertThat(paint.values().floats("baseColor")).containsExactly(1, 0.5f, 0.5f, 1);
        assertThat(paint.values().f32("metallic", -1)).isEqualTo(0.25f);
        assertThat(paint.values().f32("roughness", -1)).isEqualTo(0.75f);
        assertThat(paint.values().f32("occlusion", -1)).as("packed in the ORM").isEqualTo(0.5f);
        assertThat(paint.values().f32("normalScale", -1)).isEqualTo(0.8f);
        assertThat(paint.values().texture("baseColorTexture"))
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://models/paint.png"));
        assertThat(paint.values().texture("ormTexture"))
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://models/orm.png#linear"));
        AssetRef<TextureData> normals = paint.values().texture("normalTexture");
        assertThat(normals).isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://models/car.gltf#image/2/linear"));
        assertThat(load(normals).colorSpace()).isEqualTo(TextureData.ColorSpace.LINEAR);
        assertThat(load(paint.values().texture("ormTexture")).colorSpace()).isEqualTo(TextureData.ColorSpace.LINEAR);
        MaterialData masked = load(paths.ref(RenderAssets.MATERIAL, "game://models/car.gltf#material/1"));
        assertThat(masked.alphaMode()).isEqualTo(MaterialData.AlphaMode.MASK);
        assertThat(masked.alphaCutoff()).isEqualTo(0.3f);
        assertThat(masked.doubleSided()).isTrue();
        assertThat(masked.values().f32("occlusion", -1)).as("another texture's: not read").isZero();
        MaterialData extension = load(paths.ref(RenderAssets.MATERIAL, "game://models/car.gltf#material/2"));
        assertThat(extension.values().has("baseColorTexture")).as("an extension's image only").isFalse();
        TextureData embedded = load(paths.ref(RenderAssets.TEXTURE, "game://models/car.gltf#image/2"));
        assertThat(embedded.colorSpace()).isEqualTo(TextureData.ColorSpace.SRGB);

        // What cannot load fails, with a reason.
        assertThat(failure("game://models/car.gltf#mesh/0", RenderAssets.MODEL))
            .hasMessageContaining("without a fragment");
        assertThat(failure("game://models/car.gltf#mesh/x", RenderAssets.MESH)).hasMessageContaining("#mesh/N");
        assertThat(failure("game://models/car.gltf#mesh", RenderAssets.MESH)).hasMessageContaining("#mesh/N");
        assertThat(failure("game://models/car.gltf#material/0", RenderAssets.MESH)).hasMessageContaining("#mesh/N");
        assertThat(failure("game://models/car.gltf#mesh/9", RenderAssets.MESH)).hasMessageContaining("meshes 9");
        assertThat(failure("game://models/car.gltf#image/0", RenderAssets.TEXTURE))
            .hasMessageContaining("a file of its own");
        AssetRef<MeshData> byId = AssetRef.of(RenderAssets.MESH, new AssetId(5, 5));
        expectFailure = true;
        load(byId);
        assertThat(cache.failure(byId)).hasMessageContaining("has no path");
    }

    @Test
    void aBinaryFileWithoutScenes() throws Exception {
        byte[] image = png(0xFF00FF00);
        byte[] geometry = geometry();
        byte[] bin = new byte[256 + ((image.length + 3) & ~3)];
        System.arraycopy(geometry, 0, bin, 0, geometry.length);
        System.arraycopy(image, 0, bin, 256, image.length);
        String json = """
            {"asset": {"version": "2.0"}, "buffers": [{"byteLength": %d}],
             "nodes": [{"name": "a", "children": [1]}, {"name": "b", "mesh": 0}, {"name": "c"}],
             "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 48},
               {"buffer": 0, "byteOffset": 256, "byteLength": %d}],
             "accessors": [{"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"}],
             "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "material": 0, "mode": 5}]}],
             "materials": [{"pbrMetallicRoughness": {"baseColorTexture": {"index": 0}}}],
             "textures": [{"source": 0}], "images": [{"bufferView": 1, "mimeType": "image/png"}, {}]}
            """.formatted(bin.length, image.length);
        Files.write(dir.resolve("crate.glb"), glb(json, bin, 2));
        ModelData crate = load(paths.ref(RenderAssets.MODEL, "game://crate.glb"));
        assertThat(crate.nodes()).extracting(ModelData.Node::name).containsExactly("a", "b", "c");
        assertThat(crate.nodes().get(1).parent()).isZero();
        assertThat(crate.nodes().get(2).parent()).isEqualTo(-1);
        MeshData mesh = load(crate.nodes().get(1).mesh());
        assertThat(mesh.format().has(VertexAttribute.UV0)).isFalse();
        MaterialData material = load(crate.nodes().get(1).materials().get(0));
        AssetRef<TextureData> texture = material.values().texture("baseColorTexture");
        assertThat(texture).isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://crate.glb#image/0"));
        assertThat(load(texture).width()).isEqualTo(1);
        assertThat(failure("game://crate.glb#image/1", RenderAssets.TEXTURE)).hasMessageContaining("neither");
    }

    @Test
    void brokenMeshesAndTrees() throws Exception {
        Files.write(dir.resolve("geometry.bin"), geometry());
        String json = """
            {"asset": {"version": "2.0"}, "buffers": [{"uri": "geometry.bin", "byteLength": 256}],
             "scenes": [{"nodes": [0]}], "nodes": [{"children": [1]}, {"children": [0]}],
            """ + VIEWS + """
             "meshes": [{"primitives": [{"attributes": {"NORMAL": 1}}]}, {"primitives": []},
               {"primitives": [{"attributes": {"POSITION": 0}, "mode": 1}]}]}
            """;
        Files.writeString(dir.resolve("broken.gltf"), json);
        assertThat(failure("game://broken.gltf", RenderAssets.MODEL)).hasMessageContaining("cycle");
        assertThat(failure("game://broken.gltf#mesh/0", RenderAssets.MESH)).hasMessageContaining("without positions");
        assertThat(failure("game://broken.gltf#mesh/1", RenderAssets.MESH)).hasMessageContaining("no primitives");
        assertThat(failure("game://broken.gltf#mesh/2", RenderAssets.MESH)).hasMessageContaining("mode 1");
    }

    @Test
    void matricesDecomposeIntoTranslationRotationAndScale() {
        double[][] identity = Gltf.decompose(new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 2, 3, 1});
        assertThat(identity[0]).containsExactly(1, 2, 3);
        assertThat(identity[1]).containsExactly(0, 0, 0, 1);
        assertThat(Gltf.decompose(new double[] {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1})[1][0])
            .as("half around x").isCloseTo(1, within(1e-9));
        assertThat(Gltf.decompose(new double[] {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1})[1][2])
            .as("half around z").isCloseTo(1, within(1e-9));
        double[] diagonal = Gltf.decompose(new double[] {0, 0, 1, 0, 0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1})[1];
        assertThat(diagonal[0]).as("half around x + z").isCloseTo(diagonal[2], within(1e-9));
        double[][] mirrored = Gltf.decompose(new double[] {-2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1});
        assertThat(mirrored[2]).containsExactly(-2, 2, 2);
        assertThat(mirrored[1][3]).isEqualTo(1);
        float[] normals = Gltf.smoothNormals(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 5, 5, 5}, new int[] {0, 1, 2});
        assertThat(normals).containsExactly(0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 1, 0);
    }
    @Test
    void extensionsBecomeEngineData() throws Exception {
        Files.write(dir.resolve("ext.bin"), geometry());
        Files.write(dir.resolve("albedo.png"), png(0xFFFFFFFF));
        String json = """
            {"asset": {"version": "2.0"},
             "extensionsUsed": ["KHR_lights_punctual", "KHR_node_visibility", "KHR_materials_sheen", "EXT_texture_webp"],
             "extensionsRequired": ["KHR_texture_transform", "KHR_materials_sheen", "KHR_mesh_quantization"],
             "extensions": {"KHR_lights_punctual": {"lights": [
               {"type": "spot", "name": "lamp", "color": [1, 0.5, 0], "intensity": 20, "range": 10,
                "spot": {"innerConeAngle": 0.1, "outerConeAngle": 0.5}},
               {"type": "directional"}]}},
             "scenes": [{"nodes": [0]}],
             "nodes": [{"name": "root", "children": [1, 2], "extensions": {"KHR_node_visibility": {"visible": false}}},
               {"name": "lamp", "extensions": {"KHR_lights_punctual": {"light": 0}}},
               {"name": "seen", "extensions": {"KHR_node_visibility": {"visible": true}}}],
             "buffers": [{"uri": "ext.bin", "byteLength": 256}],
            """ + VIEWS + """
             "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 3}]}],
             "materials": [
               {"emissiveFactor": [0.5, 0.25, 1],
                "pbrMetallicRoughness": {"baseColorTexture": {"index": 0, "texCoord": 1}},
                "extensions": {
                  "KHR_materials_emissive_strength": {"emissiveStrength": 4},
                  "KHR_materials_clearcoat": {"clearcoatFactor": 0.8, "clearcoatRoughnessFactor": 0.2,
                    "clearcoatTexture": {"index": 0}, "clearcoatRoughnessTexture": {"index": 0},
                    "clearcoatNormalTexture": {"index": 0, "scale": 0.5}},
                  "KHR_materials_sheen": {"sheenColorFactor": [1, 0, 0], "sheenRoughnessFactor": 0.3,
                    "sheenColorTexture": {"index": 0, "extensions": {"KHR_texture_transform":
                      {"offset": [0.5, 0], "rotation": 1.5, "scale": [2, 2], "texCoord": 1}}},
                    "sheenRoughnessTexture": {"index": 1}},
                  "KHR_materials_transmission": {"transmissionFactor": 1, "thin": true, "thick": false, "uv": [1, 2],
                    "tint": [1, 2, 3, 4], "one": [1], "five": [1, 2, 3, 4, 5], "label": "x", "other": {"a": 1}},
                  "KHR_materials_unlit": {},
                  "EXT_other_vendor": {"x": 1}}},
               {"extensions": {"KHR_materials_pbrSpecularGlossiness": {"diffuseFactor": [0.5, 0.5, 0.5, 1],
                  "glossinessFactor": 0.75, "diffuseTexture": {"index": 0},
                  "specularGlossinessTexture": {"index": 0}}}},
               {"extensions": {"KHR_materials_pbrSpecularGlossiness": {"glossinessFactor": 0.5}}},
               {"pbrMetallicRoughness": {"roughnessFactor": 0.4},
                "extensions": {"KHR_materials_pbrSpecularGlossiness": {"glossinessFactor": 0},
                  "KHR_materials_clearcoat": {}}},
               {"pbrMetallicRoughness": {"baseColorTexture": {"index": 2}}},
               {"pbrMetallicRoughness": {"baseColorTexture": {"index": 3}}}],
             "textures": [{"source": 0}, {"source": 1, "extensions": {"EXT_texture_webp": {"source": 2}}},
               {"extensions": {"EXT_texture_webp": {"source": 2}}}, {"extensions": {"VENDOR": {}}}],
             "images": [{"uri": "albedo.png"}, {"uri": "albedo.png"}, {"uri": "albedo.webp"}]}
            """;
        Files.writeString(dir.resolve("ext.gltf"), json);
        ModelData model = load(paths.ref(RenderAssets.MODEL, "game://ext.gltf"));
        assertThat(model.lights()).containsExactly(
            new ModelData.Light("lamp", ModelData.LightKind.SPOT, 1, 0.5, 0, 20, 10, 0.1, 0.5),
            new ModelData.Light("", ModelData.LightKind.DIRECTIONAL, 1, 1, 1, 1, 0, 0, Math.PI / 4));
        assertThat(model.nodes()).extracting(ModelData.Node::visible).containsExactly(false, true, true);
        assertThat(model.nodes()).extracting(ModelData.Node::light).containsExactly(-1, 0, -1);

        MaterialData rich = load(paths.ref(RenderAssets.MATERIAL, "game://ext.gltf#material/0"));
        assertThat(rich.values().floats("emissive")).as("times the strength").containsExactly(2, 1, 4);
        assertThat(rich.values().i32("baseColorTextureTexCoord", -1)).isEqualTo(1);
        assertThat(rich.features()).containsExactly("clearcoat", "sheen", "transmission", "unlit");
        MaterialData.Values coat = rich.feature("clearcoat");
        assertThat(coat.f32("strength", -1)).isEqualTo(0.8f);
        assertThat(coat.f32("roughness", -1)).isEqualTo(0.2f);
        assertThat(coat.f32("normalScale", -1)).isEqualTo(0.5f);
        assertThat(coat.texture("strengthTexture")).isEqualTo(paths.ref(RenderAssets.TEXTURE,
            "game://albedo.png#linear"));
        MaterialData.Values sheen = rich.feature("sheen");
        assertThat(sheen.floats("sheenColorFactor")).containsExactly(1, 0, 0);
        assertThat(sheen.texture("sheenColorTexture")).as("a color: sRGB")
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://albedo.png"));
        assertThat(sheen.floats("sheenColorTextureOffset")).containsExactly(0.5f, 0);
        assertThat(sheen.f32("sheenColorTextureRotation", -1)).isEqualTo(1.5f);
        assertThat(sheen.floats("sheenColorTextureScale")).containsExactly(2, 2);
        assertThat(sheen.i32("sheenColorTextureTexCoord", -1)).isEqualTo(1);
        assertThat(sheen.texture("sheenRoughnessTexture")).as("the core source over the extension's")
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://albedo.png#linear"));
        MaterialData.Values transmission = rich.feature("transmission");
        assertThat(transmission.f32("transmissionFactor", -1)).isEqualTo(1);
        assertThat(transmission.i32("thin", -1)).isEqualTo(1);
        assertThat(transmission.i32("thick", -1)).isZero();
        assertThat(transmission.floats("uv")).containsExactly(1, 2);
        assertThat(transmission.floats("tint")).containsExactly(1, 2, 3, 4);
        assertThat(transmission.names()).doesNotContain("one", "five", "label", "other");

        MaterialData specGloss = load(paths.ref(RenderAssets.MATERIAL, "game://ext.gltf#material/1"));
        assertThat(specGloss.values().floats("baseColor")).containsExactly(0.5f, 0.5f, 0.5f, 1);
        assertThat(specGloss.values().f32("roughness", -1)).isEqualTo(0.25f);
        assertThat(specGloss.values().f32("metallic", -1)).isZero();
        assertThat(specGloss.values().texture("baseColorTexture")).isNotNull();
        assertThat(specGloss.feature("specularGlossiness").texture("specularGlossinessTexture"))
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://albedo.png"));
        MaterialData plainGloss = load(paths.ref(RenderAssets.MATERIAL, "game://ext.gltf#material/2"));
        assertThat(plainGloss.values().floats("baseColor")).containsExactly(1, 1, 1, 1);
        assertThat(plainGloss.values().f32("roughness", -1)).isEqualTo(0.5f);
        MaterialData both = load(paths.ref(RenderAssets.MATERIAL, "game://ext.gltf#material/3"));
        assertThat(both.values().f32("roughness", -1)).as("metallic-roughness given: kept").isEqualTo(0.4f);
        assertThat(both.feature("clearcoat").has("normalScale")).isFalse();
        MaterialData webp = load(paths.ref(RenderAssets.MATERIAL, "game://ext.gltf#material/4"));
        assertThat(webp.values().texture("baseColorTexture")).as("an extension's image")
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://albedo.webp"));
        MaterialData vendor = load(paths.ref(RenderAssets.MATERIAL, "game://ext.gltf#material/5"));
        assertThat(vendor.values().has("baseColorTexture")).as("a vendor's image, not required").isFalse();
    }
}
