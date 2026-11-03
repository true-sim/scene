package com.truesim.scene.gltf;

import static com.truesim.scene.gltf.GltfDocumentTest.base64;
import static com.truesim.scene.gltf.GltfDocumentTest.glb;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.Track;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.LineData;
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

    /**
     * A skinned quad's buffer: positions 0..48, indices 48..60, JOINTS_0 (bytes) 64..80, WEIGHTS_0 80..144, JOINTS_1
     * 144..160, WEIGHTS_1 160..224, one inverse bind matrix 224..288.
     */
    private static byte[] skinned() {
        ByteBuffer b = ByteBuffer.allocate(288).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}) {
            b.putFloat(f);
        }
        for (short i : new short[] {0, 1, 2, 0, 2, 3}) {
            b.putShort(i);
        }
        b.position(64);
        // Vertex 0: eight influences, the four heaviest kept (a tie: the earlier); 1: joint 1; 2: joint 6; 3: no weight.
        b.put(new byte[] {0, 1, 2, 3, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0});
        for (float f : new float[] {0.5f, 0.1f, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0}) {
            b.putFloat(f);
        }
        b.put(new byte[] {4, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        for (float f : new float[] {0.3f, 0.1f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}) {
            b.putFloat(f);
        }
        for (float f : new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -2, 0, 0, 1}) {
            b.putFloat(f); // a translation by -2: the joint rests at x = 2
        }
        return b.array();
    }

    @Test
    void skinsTheirJointsAndTheVerticesWeights() throws Exception {
        Files.write(dir.resolve("skin.bin"), skinned());
        String joints = """
            {"name": "j0"}, {"name": "j1"}, {"name": "j2"}, {"name": "j3"}, {"name": "j4"}, {"name": "j5"},
            {"name": "j6"}""";
        String json = """
            {"asset": {"version": "2.0"}, "scenes": [{"nodes": [0, 9]}],
             "nodes": [{"name": "body", "mesh": 0, "skin": 0, "children": [1, 2, 3, 4, 5, 6, 7]}, %s,
               {"name": "unplaced"}, {"name": "arm", "mesh": 0, "skin": 1, "children": [10]}, {"name": "bone"},
               {"name": "rigid", "skin": 0}],
             "skins": [{"name": "body", "joints": [1, 2, 3, 4, 5, 6, 7], "skeleton": 0},
               {"joints": [10], "inverseBindMatrices": 6}],
             "buffers": [{"uri": "skin.bin", "byteLength": 288}],
             "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 48},
               {"buffer": 0, "byteOffset": 48, "byteLength": 12}, {"buffer": 0, "byteOffset": 64, "byteLength": 16},
               {"buffer": 0, "byteOffset": 80, "byteLength": 64}, {"buffer": 0, "byteOffset": 144, "byteLength": 16},
               {"buffer": 0, "byteOffset": 160, "byteLength": 64}, {"buffer": 0, "byteOffset": 224, "byteLength": 64}],
             "accessors": [{"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
               {"bufferView": 1, "componentType": 5123, "count": 6, "type": "SCALAR"},
               {"bufferView": 2, "componentType": 5121, "count": 4, "type": "VEC4"},
               {"bufferView": 3, "componentType": 5126, "count": 4, "type": "VEC4"},
               {"bufferView": 4, "componentType": 5121, "count": 4, "type": "VEC4"},
               {"bufferView": 5, "componentType": 5126, "count": 4, "type": "VEC4"},
               {"bufferView": 6, "componentType": 5126, "count": 1, "type": "MAT4"}],
             "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "JOINTS_0": 2, "WEIGHTS_0": 3, "JOINTS_1": 4,
               "WEIGHTS_1": 5}, "indices": 1}]}]}
            """.formatted(joints);
        Files.writeString(dir.resolve("skin.gltf"), json);
        ModelData model = load(paths.ref(RenderAssets.MODEL, "game://skin.gltf"));
        assertThat(model.nodes()).extracting(ModelData.Node::name)
            .containsExactly("body", "j0", "j1", "j2", "j3", "j4", "j5", "j6", "arm", "bone");
        assertThat(model.nodes()).extracting(ModelData.Node::skin).containsExactly(0, -1, -1, -1, -1, -1, -1, -1, 1,
            -1);
        assertThat(model.skins()).hasSize(2);
        ModelData.Skin body = model.skins().get(0);
        assertThat(body.name()).isEqualTo("body");
        assertThat(body.joints()).as("the model's node indices").containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(body.skeleton()).isZero();
        assertThat(body.inverseBind(3, 0)).as("identity, none given").isEqualTo(1);
        assertThat(body.inverseBind(3, 12)).isZero();
        ModelData.Skin arm = model.skins().get(1);
        assertThat(arm.joints()).containsExactly(9);
        assertThat(arm.skeleton()).isEqualTo(-1);
        assertThat(arm.inverseBind(0, 12)).isEqualTo(-2);
        MeshData mesh = load(paths.ref(RenderAssets.MESH, "game://skin.gltf#mesh/0"));
        assertThat(mesh.format().has(VertexAttribute.JOINTS)).isTrue();
        // The joints the vertices weigh: 0 (vertex 3 too, its whole weight the first's), 1, 4, 5, 6.
        assertThat(mesh.jointCount()).isEqualTo(5);
        int[] palette = new int[5];
        for (int j = 0; j < 5; j++) {
            palette[j] = mesh.paletteJoint(j);
        }
        assertThat(palette).containsExactly(0, 1, 4, 5, 6);
        // The builder reorders vertices: find each by its position.
        double[] position = new double[3];
        int[] named = new int[4];
        double[] weights = new double[4];
        for (int v = 0; v < mesh.vertexCount(); v++) {
            mesh.position(v, position);
            mesh.joints(v, named);
            mesh.unorm4(VertexAttribute.WEIGHTS, v, weights);
            int source = position[0] < 0.5 ? (position[1] < 0.5 ? 0 : 3) : position[1] < 0.5 ? 1 : 2;
            switch (source) {
                case 0 -> { // 0.5 of joint 0, 0.3 of joint 4, then 0.1 each of joints 1 and 5 (a tie: the earlier)
                    assertThat(new int[] {palette[named[0]], palette[named[1]], palette[named[2]], palette[named[3]]})
                        .containsExactly(0, 4, 1, 5);
                    assertThat(weights[0]).isCloseTo(0.5, within(2 / 255.0)); // the sum's rounding: the largest's
                    assertThat(weights[1]).isCloseTo(0.3, within(1 / 255.0));
                }
                case 1 -> assertThat(palette[named[0]]).isEqualTo(1);
                case 2 -> assertThat(palette[named[0]]).isEqualTo(6);
                default -> {
                    assertThat(palette[named[0]]).as("no weight: all of it the first").isZero();
                    assertThat(weights[0]).isEqualTo(1);
                }
            }
        }
        double[] box = new double[6];
        mesh.jointBox(4, box); // skin joint 6: vertex 2 alone, at (1, 1)
        assertThat(box[0]).isCloseTo(1, within(1e-4));
        assertThat(box[1]).isCloseTo(1, within(1e-4));
        // A skin naming a node outside the scene; inverse bind matrices of the wrong count.
        expectFailure = true;
        Files.writeString(dir.resolve("loose.gltf"), json.replace("\"joints\": [10]", "\"joints\": [8]"));
        AssetRef<ModelData> loose = paths.ref(RenderAssets.MODEL, "game://loose.gltf");
        load(loose);
        assertThat(cache.failure(loose)).hasMessageContaining("joint 8 is not a node of the scene");
        Files.writeString(dir.resolve("count.gltf"), json.replace("\"joints\": [10]", "\"joints\": [10, 1]"));
        AssetRef<ModelData> count = paths.ref(RenderAssets.MODEL, "game://count.gltf");
        load(count);
        assertThat(cache.failure(count)).hasMessageContaining("2 joints has 1 inverse bind matrices");
        String[] bad = {"[99]", "[-1]"};
        for (int i = 0; i < bad.length; i++) {
            Files.writeString(dir.resolve("bad" + i + ".gltf"), json.replace("\"joints\": [10]", "\"joints\": "
                + bad[i]));
            AssetRef<ModelData> broken = paths.ref(RenderAssets.MODEL, "game://bad" + i + ".gltf");
            load(broken);
            assertThat(cache.failure(broken)).hasMessageContaining("is not a node of the scene");
        }
        expectFailure = false;
        // A skeleton past the nodes: none.
        Files.writeString(dir.resolve("rootless.gltf"), json.replace("\"skeleton\": 0", "\"skeleton\": 99"));
        assertThat(load(paths.ref(RenderAssets.MODEL, "game://rootless.gltf")).skins().get(0).skeleton()).isEqualTo(-1);
        // Joints without weights: not skinned. A second set without its weights: the first alone.
        Files.writeString(dir.resolve("unweighted.gltf"), json.replace("\"WEIGHTS_0\": 3, ", ""));
        assertThat(load(paths.ref(RenderAssets.MESH, "game://unweighted.gltf#mesh/0")).format()
            .has(VertexAttribute.JOINTS)).isFalse();
        Files.writeString(dir.resolve("half.gltf"), json.replace("\"WEIGHTS_1\"", "\"_WEIGHTS_1\""));
        Files.writeString(dir.resolve("first.gltf"), json.replace("\"JOINTS_1\": 4,", ""));
        for (String name : new String[] {"half", "first"}) {
            MeshData first = load(paths.ref(RenderAssets.MESH, "game://" + name + ".gltf#mesh/0"));
            assertThat(first.jointCount()).as(name + ": joints 4 and 5 not read").isEqualTo(3);
        }
    }

    @Test
    void morphTargetsTheirNamesAndWeights() throws Exception {
        // A quad (0..48) and its indices (48..60); target 0: positions 64..112, normals 112..160; target 1: positions
        // 160..208, tangents 208..256. Target 0 lifts vertex 2 by 1; target 1 moves vertex 1 along x by 0.5.
        ByteBuffer b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0}) {
            b.putFloat(f);
        }
        for (short i : new short[] {0, 1, 2, 0, 2, 3}) {
            b.putShort(i);
        }
        b.position(64);
        float[][] blocks = {
            {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0},
            {0, 0, 0, 0.5f, 0, 0, 0, 0, 0, 0, 0, 0}, {0, 0, 0, 0, 0.25f, 0, 0, 0, 0, 0, 0, 0}};
        for (float[] block : blocks) {
            for (float f : block) {
                b.putFloat(f);
            }
        }
        Files.write(dir.resolve("face.bin"), b.array());
        String json = """
            {"asset": {"version": "2.0"}, "scenes": [{"nodes": [0, 1, 2]}],
             "nodes": [{"name": "rest", "mesh": 0}, {"name": "smiling", "mesh": 0, "weights": [1, 0]},
               {"name": "plain", "mesh": 1}],
             "buffers": [{"uri": "face.bin", "byteLength": 256}],
             "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 48},
               {"buffer": 0, "byteOffset": 48, "byteLength": 12}, {"buffer": 0, "byteOffset": 64, "byteLength": 48},
               {"buffer": 0, "byteOffset": 112, "byteLength": 48}, {"buffer": 0, "byteOffset": 160, "byteLength": 48},
               {"buffer": 0, "byteOffset": 208, "byteLength": 48}],
             "accessors": [{"bufferView": 0, "componentType": 5126, "count": 4, "type": "VEC3"},
               {"bufferView": 1, "componentType": 5123, "count": 6, "type": "SCALAR"},
               {"bufferView": 2, "componentType": 5126, "count": 4, "type": "VEC3"},
               {"bufferView": 3, "componentType": 5126, "count": 4, "type": "VEC3"},
               {"bufferView": 4, "componentType": 5126, "count": 4, "type": "VEC3"},
               {"bufferView": 5, "componentType": 5126, "count": 4, "type": "VEC3"}],
             "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1,
                 "targets": [{"POSITION": 2, "NORMAL": 3}, {"POSITION": 4, "TANGENT": 5}]}],
                 "weights": [0.25, 0.5], "extras": {"targetNames": ["smile", "wide"]}},
               {"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "targets": [{"POSITION": 4}]}]},
               {"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "targets": [{"POSITION": 4}]},
                 {"attributes": {"POSITION": 0}, "indices": 1}]}]}
            """;
        Files.writeString(dir.resolve("face.gltf"), json);
        ModelData model = load(paths.ref(RenderAssets.MODEL, "game://face.gltf"));
        assertThat(model.nodes().get(0).morphWeights()).as("the mesh's").containsExactly(0.25, 0.5);
        assertThat(model.nodes().get(1).morphWeights()).as("its own").containsExactly(1.0, 0.0);
        assertThat(model.nodes().get(2).morphWeights()).as("none given").isEmpty();
        MeshData face = load(paths.ref(RenderAssets.MESH, "game://face.gltf#mesh/0"));
        assertThat(face.morphTargetCount()).isEqualTo(2);
        assertThat(face.morphTargetName(0)).isEqualTo("smile");
        assertThat(face.morphTargetName(1)).isEqualTo("wide");
        assertThat(face.morphMaxDisplacement(0)).isEqualTo(1);
        double[] p = new double[3];
        double[] delta = new double[9];
        for (int v = 0; v < face.vertexCount(); v++) {
            face.position(v, p);
            int source = p[0] < 0.5 ? (p[1] < 0.5 ? 0 : 3) : p[1] < 0.5 ? 1 : 2;
            face.morphDelta(v, 0, delta);
            assertThat(delta[1]).isEqualTo(source == 2 ? 1 : 0);
            assertThat(delta[4]).as("its normal").isEqualTo(source == 2 ? 0.5 : 0);
            face.morphDelta(v, 1, delta);
            assertThat(delta[0]).isEqualTo(source == 1 ? 0.5 : 0);
            assertThat(delta[7]).as("its tangent").isEqualTo(source == 1 ? 0.25 : 0);
        }
        MeshData plain = load(paths.ref(RenderAssets.MESH, "game://face.gltf#mesh/1"));
        assertThat(plain.morphTargetName(0)).as("no names").isEmpty();
        expectFailure = true;
        AssetRef<MeshData> uneven = paths.ref(RenderAssets.MESH, "game://face.gltf#mesh/2");
        load(uneven);
        assertThat(cache.failure(uneven)).hasMessageContaining("different numbers of morph targets");
    }

    @Test
    void animationsAreClipsTheModelNames() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[] {0, 1, 0, 0, 0, 2, 0, 0}) {
            b.putFloat(f); // times, then translations
        }
        for (short v : new short[] {0, 0, 0, 32767, 0, 23170, 0, 23170}) {
            b.putShort(v); // rotations, normalized shorts
        }
        for (float f : new float[] {0, 0, 1, 0, 1, 0, 1, 1, 1, 3, 3, 3}) {
            b.putFloat(f); // a cubic spline weight (in, value, out per key), then scales
        }
        String json = """
            {"asset": {"version": "2.0"}, "scenes": [{"nodes": [0]}],
             "nodes": [{"name": "hip", "children": [1]}, {"name": "knee"}, {"name": "loose"}],
             "buffers": [{"uri": "%s", "byteLength": 96}],
             "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 8},
               {"buffer": 0, "byteOffset": 8, "byteLength": 24}, {"buffer": 0, "byteOffset": 32, "byteLength": 16},
               {"buffer": 0, "byteOffset": 48, "byteLength": 24}, {"buffer": 0, "byteOffset": 72, "byteLength": 24}],
             "accessors": [{"bufferView": 0, "componentType": 5126, "count": 2, "type": "SCALAR"},
               {"bufferView": 1, "componentType": 5126, "count": 2, "type": "VEC3"},
               {"bufferView": 2, "componentType": 5122, "normalized": true, "count": 2, "type": "VEC4"},
               {"bufferView": 3, "componentType": 5126, "count": 6, "type": "SCALAR"},
               {"bufferView": 4, "componentType": 5126, "count": 2, "type": "VEC3"}],
             "animations": [{"name": "bend",
                 "samplers": [{"input": 0, "output": 1}, {"input": 0, "output": 2, "interpolation": "STEP"},
                   {"input": 0, "output": 3, "interpolation": "CUBICSPLINE"}, {"input": 0, "output": 4}],
                 "channels": [{"sampler": 0, "target": {"node": 0, "path": "translation"}},
                   {"sampler": 1, "target": {"node": 1, "path": "rotation"}},
                   {"sampler": 2, "target": {"node": 1, "path": "weights"}},
                   {"sampler": 3, "target": {"node": 2, "path": "scale"}},
                   {"sampler": 0, "target": {"path": "pointer"}},
                   {"sampler": 0, "target": {"node": 0, "path": "pointer"}},
                   {"sampler": 0, "target": {"path": "translation"}}]},
               {"samplers": [], "channels": []}]}
            """.formatted(base64(b.array()));
        Files.writeString(dir.resolve("bend.gltf"), json);
        ModelData model = load(paths.ref(RenderAssets.MODEL, "game://bend.gltf"));
        assertThat(model.animations()).extracting(ModelData.Animation::name).containsExactly("bend", "");
        AnimationClip clip = load(model.animation("bend"));
        assertThat(clip.name()).isEqualTo("bend");
        assertThat(clip.duration()).isEqualTo(1);
        assertThat(clip.tracks()).extracting(Track::nodeName).containsExactly("hip", "knee", "knee", "loose");
        assertThat(clip.tracks()).extracting(Track::node).as("by the model's index; -1 not placed")
            .containsExactly(0, 1, 1, -1);
        assertThat(clip.tracks()).extracting(Track::property).containsExactly(Track.Property.TRANSLATION,
            Track.Property.ROTATION, Track.Property.WEIGHTS, Track.Property.SCALE);
        assertThat(clip.tracks()).extracting(Track::interpolation).containsExactly(Track.Interpolation.LINEAR,
            Track.Interpolation.STEP, Track.Interpolation.CUBIC_SPLINE, Track.Interpolation.LINEAR);
        assertThat(clip.tracks().get(2).components()).isOne();
        double[] out = new double[4];
        clip.tracks().get(1).sample(1, new int[1], 0, out);
        assertThat(out[1]).isCloseTo(Math.sqrt(0.5), within(1e-4));
        assertThat(load(model.animation("")).tracks()).isEmpty();
    }

    @Test
    void camerasPerspectiveAndOrthographic() throws Exception {
        String json = """
            {"asset": {"version": "2.0"}, "scenes": [{"nodes": [0, 1, 2]}],
             "nodes": [{"name": "eye", "camera": 0}, {"name": "map", "camera": 1, "translation": [0, 10, 0]},
               {"name": "none"}],
             "cameras": [{"name": "wide", "type": "perspective",
                 "perspective": {"yfov": 0.8, "aspectRatio": 1.5, "znear": 0.05, "zfar": 200}},
               {"type": "orthographic", "orthographic": {"xmag": 4, "ymag": 3, "znear": 0.5, "zfar": 20}}]}
            """;
        Files.writeString(dir.resolve("eyes.gltf"), json);
        ModelData model = load(paths.ref(RenderAssets.MODEL, "game://eyes.gltf"));
        assertThat(model.nodes()).extracting(ModelData.Node::camera).containsExactly(0, 1, -1);
        assertThat(model.cameras()).containsExactly(ModelData.Camera.perspective("wide", 0.8, 1.5, 0.05, 200),
            ModelData.Camera.orthographic("", 4, 3, 0.5, 20));
    }

    @Test
    void aMeshNamesAtMost256Joints() throws Exception {
        // 65 vertices of four joints each, all different: 260 joints.
        int vertices = 65;
        ByteBuffer b = ByteBuffer.allocate(12 * vertices + 8 * vertices + 16 * vertices + 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int v = 0; v < vertices; v++) {
            b.putFloat(v).putFloat(v % 2).putFloat(0);
        }
        for (int v = 0; v < 4 * vertices; v++) {
            b.putShort((short) v);
        }
        for (int v = 0; v < 4 * vertices; v++) {
            b.putFloat(0.25f);
        }
        b.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        Files.write(dir.resolve("many.bin"), b.array());
        int jointsAt = 12 * vertices;
        int weightsAt = jointsAt + 8 * vertices;
        int indicesAt = weightsAt + 16 * vertices;
        String json = """
            {"asset": {"version": "2.0"}, "nodes": [{"mesh": 0}],
             "buffers": [{"uri": "many.bin", "byteLength": %d}],
             "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": %d},
               {"buffer": 0, "byteOffset": %d, "byteLength": %d}, {"buffer": 0, "byteOffset": %d, "byteLength": %d},
               {"buffer": 0, "byteOffset": %d, "byteLength": 6}],
             "accessors": [{"bufferView": 0, "componentType": 5126, "count": %d, "type": "VEC3"},
               {"bufferView": 1, "componentType": 5123, "count": %d, "type": "VEC4"},
               {"bufferView": 2, "componentType": 5126, "count": %d, "type": "VEC4"},
               {"bufferView": 3, "componentType": 5123, "count": 3, "type": "SCALAR"}],
             "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2},
               "indices": 3}]}]}
            """.formatted(b.capacity(), jointsAt, jointsAt, 8 * vertices, weightsAt, 16 * vertices, indicesAt,
            vertices, vertices, vertices);
        Files.writeString(dir.resolve("many.gltf"), json);
        expectFailure = true;
        AssetRef<MeshData> many = paths.ref(RenderAssets.MESH, "game://many.gltf#mesh/0");
        load(many);
        assertThat(cache.failure(many)).hasMessageContaining("260 joints, at most 256");
    }

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
               {"pbrMetallicRoughness": {"baseColorTexture": {"index": 3}}},
               {"pbrMetallicRoughness": {"metallicRoughnessTexture": {"index": 1}}, "occlusionTexture": {"index": 1,
                "strength": 0.25, "extensions": {"KHR_texture_transform": {"offset": [0.5, 0]}}}},
               {"occlusionTexture": {"index": 1, "strength": 0.75}},
               {"pbrMetallicRoughness": {"metallicRoughnessTexture": {"index": 1}}, "occlusionTexture": {"index": 1,
                "texCoord": 1}},
               {"pbrMetallicRoughness": {"metallicRoughnessTexture": {"index": 1}}}],
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
        assertThat(masked.values().f32("occlusion", -1)).as("another texture's: its own").isEqualTo(1);
        assertThat(masked.values().texture("occlusionTexture"))
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://models/paint.png#linear"));
        assertThat(paint.values().has("occlusionTexture")).as("packed: read from the ORM").isFalse();
        MaterialData moved = load(paths.ref(RenderAssets.MATERIAL, "game://models/car.gltf#material/3"));
        assertThat(moved.values().f32("occlusion", -1)).as("the ORM's texture read otherwise: its own")
            .isEqualTo(0.25f);
        assertThat(moved.values().texture("occlusionTexture"))
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://models/orm.png#linear"));
        assertThat(moved.values().floats("occlusionTextureOffset")).containsExactly(0.5f, 0);
        MaterialData other = load(paths.ref(RenderAssets.MATERIAL, "game://models/car.gltf#material/5"));
        assertThat(other.values().i32("occlusionTextureTexCoord", 0)).as("another UV set: its own").isEqualTo(1);
        MaterialData none = load(paths.ref(RenderAssets.MATERIAL, "game://models/car.gltf#material/6"));
        assertThat(none.values().f32("occlusion", -1)).as("no occlusion texture: the ORM's red unread").isZero();
        assertThat(none.values().has("occlusionTexture")).isFalse();
        MaterialData alone = load(paths.ref(RenderAssets.MATERIAL, "game://models/car.gltf#material/4"));
        assertThat(alone.values().f32("occlusion", -1)).as("without an ORM").isEqualTo(0.75f);
        assertThat(alone.values().texture("occlusionTexture"))
            .isEqualTo(paths.ref(RenderAssets.TEXTURE, "game://models/orm.png#linear"));
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
               {"primitives": [{"attributes": {"POSITION": 0}, "mode": 1}]},
               {"primitives": [{"attributes": {"POSITION": 0}, "mode": 7}]}]}
            """;
        Files.writeString(dir.resolve("broken.gltf"), json);
        assertThat(failure("game://broken.gltf", RenderAssets.MODEL)).hasMessageContaining("cycle");
        assertThat(failure("game://broken.gltf#mesh/0", RenderAssets.MESH)).hasMessageContaining("without positions");
        assertThat(failure("game://broken.gltf#mesh/1", RenderAssets.MESH)).hasMessageContaining("no triangles");
        assertThat(failure("game://broken.gltf#mesh/2", RenderAssets.MESH)).as("lines only")
            .hasMessageContaining("no triangles");
        assertThat(failure("game://broken.gltf#mesh/3", RenderAssets.MESH)).hasMessageContaining("Not a mode");
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
               {"pbrMetallicRoughness": {"baseColorTexture": {"index": 3}}},
               {"pbrMetallicRoughness": {"metallicRoughnessTexture": {"index": 1}}, "occlusionTexture": {"index": 1,
                "strength": 0.25, "texCoord": 1, "extensions": {"KHR_texture_transform": {"offset": [0.5, 0]}}}},
               {"occlusionTexture": {"index": 1, "strength": 0.75}}],
             "textures": [{"source": 0, "sampler": 0},
               {"source": 1, "sampler": 1, "extensions": {"EXT_texture_webp": {"source": 2}}},
               {"extensions": {"EXT_texture_webp": {"source": 2}}}, {"extensions": {"VENDOR": {}}}],
             "samplers": [{"wrapS": 10497}, {"wrapS": 33071, "wrapT": 33648, "magFilter": 9728}],
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
        assertThat(rich.values().i32("baseColorTextureWrapS", -1)).as("repeating: glTF's default, left out")
            .isEqualTo(-1);
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
        assertThat(sheen.i32("sheenRoughnessTextureWrapS", -1)).as("clamped").isEqualTo(33071);
        assertThat(sheen.i32("sheenRoughnessTextureWrapT", -1)).as("mirrored").isEqualTo(33648);
        assertThat(sheen.i32("sheenRoughnessTextureMagFilter", -1)).as("nearest").isEqualTo(9728);
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
    @Test
    void pointsAndLinesAreAssetsOfTheirOwn() throws Exception {
        Files.write(dir.resolve("lines.bin"), geometry());
        String json = """
            {"asset": {"version": "2.0"}, "scenes": [{"nodes": [0, 1]}],
             "nodes": [{"name": "mixed", "mesh": 0}, {"name": "outline", "mesh": 1}],
             "buffers": [{"uri": "lines.bin", "byteLength": 256}],
            """ + VIEWS + """
             "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 3},
                 {"attributes": {"POSITION": 0, "COLOR_0": 4}, "mode": 1, "material": 0}]},
               {"primitives": [{"attributes": {"POSITION": 0}, "mode": 0},
                 {"attributes": {"POSITION": 0}, "mode": 2}, {"attributes": {"POSITION": 0}, "mode": 3},
                 {"attributes": {"POSITION": 0}, "indices": 3, "mode": 1}, {"attributes": {"NORMAL": 1}, "mode": 1}]}],
             "materials": [{}]}
            """;
        Files.writeString(dir.resolve("lines.gltf"), json);
        ModelData model = load(paths.ref(RenderAssets.MODEL, "game://lines.gltf"));
        ModelData.Node mixed = model.nodes().get(0);
        assertThat(mixed.mesh()).isNotNull();
        assertThat(mixed.materials()).as("the triangles' only").hasSize(1);
        assertThat(mixed.lines()).containsExactly(paths.ref(RenderAssets.LINES, "game://lines.gltf#lines/0/1"));
        ModelData.Node outline = model.nodes().get(1);
        assertThat(outline.mesh()).as("no triangles: no mesh").isNull();
        assertThat(outline.lines()).hasSize(5);
        assertThat(load(mixed.mesh()).materialSlots()).isEqualTo(1);

        LineData colored = load(mixed.lines().get(0));
        assertThat(colored.kind()).isEqualTo(LineData.Kind.LINES);
        assertThat(colored.indices()).containsExactly(0, 1, 2, 3);
        assertThat(colored.colors()).hasSize(16).startsWith(1, 0.5f, 0.25f, 1);
        assertThat(colored.material()).isEqualTo(paths.ref(RenderAssets.MATERIAL, "game://lines.gltf#material/0"));
        LineData points = load(outline.lines().get(0));
        assertThat(points.kind()).isEqualTo(LineData.Kind.POINTS);
        assertThat(points.indices()).containsExactly(0, 1, 2, 3);
        assertThat(points.colors()).isEmpty();
        assertThat(points.material()).isEqualTo(MaterialData.DEFAULT.ref());
        assertThat(load(outline.lines().get(1)).indices()).as("a loop").containsExactly(0, 1, 1, 2, 2, 3, 3, 0);
        assertThat(load(outline.lines().get(2)).indices()).as("a strip").containsExactly(0, 1, 1, 2, 2, 3);
        assertThat(load(outline.lines().get(3)).indices()).as("six indices: three segments")
            .containsExactly(0, 1, 2, 0, 2, 3);
        assertThat(failure("game://lines.gltf#lines/1/4", RenderAssets.LINES)).hasMessageContaining("without positions");
        assertThat(failure("game://lines.gltf#lines/0/0", RenderAssets.LINES)).hasMessageContaining("Not a primitive");
        assertThat(failure("game://lines.gltf#lines/0/9", RenderAssets.LINES)).hasMessageContaining("Not a primitive");
        assertThat(failure("game://lines.gltf#lines/0/-1", RenderAssets.LINES)).hasMessageContaining("Not a primitive");
        assertThat(failure("game://lines.gltf#lines/0", RenderAssets.LINES)).hasMessageContaining("#lines/M/P");
        assertThat(failure("game://lines.gltf#mesh/0/0", RenderAssets.LINES)).hasMessageContaining("#lines/M/P");
        assertThat(failure("game://lines.gltf#lines/x/0", RenderAssets.LINES)).hasMessageContaining("#lines/M/P");
    }
}
