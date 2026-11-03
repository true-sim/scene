package com.truesim.scene.gltf;

import static com.truesim.scene.gltf.GltfDocumentTest.base64;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.animation.AnimationAssets;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.AnimationPointer;
import com.truesim.scene.animation.Track;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.PathSource;
import com.truesim.scene.render.RenderAssets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** KHR_animation_pointer's channels: what of materials, lights, cameras and nodes the importer animates. */
class GltfPointersTest {

    /** Each pointer's channel, and what it animates (target, index, property, rest), or skipped. */
    private static final Map<String, String> CASES = Map.ofEntries(
        Map.entry("/materials/0/pbrMetallicRoughness/baseColorFactor", "MATERIAL 0 baseColor [0.5, 0.5, 0.5, 1.0]"),
        Map.entry("/materials/1/pbrMetallicRoughness/baseColorFactor", "MATERIAL 1 baseColor [1.0, 1.0, 1.0, 1.0]"),
        Map.entry("/materials/0/pbrMetallicRoughness/metallicFactor", "MATERIAL 0 metallic [1.0]"),
        Map.entry("/materials/0/pbrMetallicRoughness/roughnessFactor", "MATERIAL 0 roughness [1.0]"),
        Map.entry("/materials/0/emissiveFactor", "MATERIAL 0 emissive [2.0, 0.0, 0.0]"),
        Map.entry("/materials/0/normalTexture/scale", "MATERIAL 0 normalScale [1.0]"),
        Map.entry("/materials/0/occlusionTexture/strength", "MATERIAL 0 occlusion [0.5]"),
        Map.entry("/materials/1/occlusionTexture/strength", "MATERIAL 1 occlusion [1.0]"),
        Map.entry("/materials/2/occlusionTexture/strength", "skipped"),
        Map.entry("/materials/0/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/offset", "MATERIAL 0 baseColorTextureOffset [0.0, 0.0]"),
        Map.entry("/materials/0/pbrMetallicRoughness/metallicRoughnessTexture/extensions/KHR_texture_transform/rotation", "MATERIAL 0 ormTextureRotation [0.0]"),
        Map.entry("/materials/0/emissiveTexture/extensions/KHR_texture_transform/scale", "MATERIAL 0 emissiveTextureScale [1.0, 1.0]"),
        Map.entry("/materials/0/normalTexture/extensions/KHR_texture_transform/offset", "MATERIAL 0 normalTextureOffset [0.0, 0.0]"),
        Map.entry("/materials/0/occlusionTexture/extensions/KHR_texture_transform/offset", "skipped"),
        Map.entry("/materials/1/occlusionTexture/extensions/KHR_texture_transform/offset", "MATERIAL 1 occlusionTextureOffset [0.0, 0.0]"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatNormalTexture/extensions/KHR_texture_transform/scale", "MATERIAL 0 clearcoat:normalTextureScale [1.0, 1.0]"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatOther/extensions/KHR_texture_transform/scale", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_sheen/sheenColorTexture/extensions/KHR_texture_transform/offset", "MATERIAL 0 sheen:sheenColorTextureOffset [0.0, 0.0]"),
        Map.entry("/materials/0/extensions/KHR_materials_pbrSpecularGlossiness/diffuseTexture/extensions/KHR_texture_transform/offset", "skipped"),
        Map.entry("/materials/0/extensions/other/diffuseTexture/extensions/KHR_texture_transform/offset", "skipped"),
        Map.entry("/materials/0/x/extensions/KHR_texture_transform/offset", "skipped"),
        Map.entry("/materials/0/a/b/c/extensions/KHR_texture_transform/offset", "skipped"),
        Map.entry("/materials/2/extensions/KHR_materials_sheen/sheenRoughnessFactor", "MATERIAL 2 sheen:sheenRoughnessFactor [0.0]"),
        Map.entry("/materials/1/pbrMetallicRoughness/roughnessFactor", "skipped"),
        Map.entry("/materials/0/emissiveTexture/extensions/KHR_texture_transform/texCoord", "skipped"),
        Map.entry("/materials/0/x/extensions/other/offset", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatFactor", "MATERIAL 0 clearcoat:strength [0.3]"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatRoughnessFactor", "MATERIAL 0 clearcoat:roughness [0.0]"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatNormalTexture/scale", "MATERIAL 0 clearcoat:normalScale [1.0]"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatTexture", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatOther", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatNormalTexture/texCoord", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_clearcoat/clearcoatFactor/x", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_sheen/sheenRoughnessFactor", "MATERIAL 0 sheen:sheenRoughnessFactor [0.0]"),
        Map.entry("/materials/0/extensions/KHR_materials_sheen/sheenColorTexture", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_sheen/sheenColorTexture/texCoord", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_sheen/a~1b~0c", "MATERIAL 0 sheen:a/b~c [0.0]"),
        Map.entry("/materials/0/extensions/KHR_materials_emissive_strength/emissiveStrength", "skipped"),
        Map.entry("/materials/0/extensions/KHR_materials_ior/ior", "MATERIAL 0 ior:ior [1.5]"),
        Map.entry("/materials/0/extensions/other/x", "skipped"),
        Map.entry("/materials/0/a/b/c", "skipped"),
        Map.entry("/materials/0/alphaCutoff", "skipped"),
        Map.entry("/materials/5/alphaCutoff", "skipped"),
        Map.entry("/materials/x/alphaCutoff", "skipped"),
        Map.entry("/materials/0", "skipped"),
        Map.entry("/materials/1/pbrMetallicRoughness/metallicFactor", "skipped"),
        Map.entry("/materials/1/emissiveFactor", "MATERIAL 1 emissive [0.0, 0.0, 0.0, 1.0]"),
        Map.entry("/cameras/0/perspective/yfov", "CAMERA 0 yfov [0.8]"),
        Map.entry("/cameras/0/orthographic/xmag", "CAMERA 0 xmag [0.0]"),
        Map.entry("/cameras/0/perspective/zfar", "CAMERA 0 zfar [3.0]"),
        Map.entry("/cameras/0/perspective/type", "skipped"),
        Map.entry("/cameras/x/perspective/yfov", "skipped"),
        Map.entry("/cameras/0/perspective", "skipped"),
        Map.entry("/extensions/KHR_lights_punctual/lights/0/color", "LIGHT 0 color [1.0, 0.0, 0.0]"),
        Map.entry("/extensions/KHR_lights_punctual/lights/0/intensity", "LIGHT 0 intensity [5.0]"),
        Map.entry("/extensions/KHR_lights_punctual/lights/0/spot/outerConeAngle", "LIGHT 0 outerConeAngle [0.7853981633974483]"),
        Map.entry("/extensions/KHR_lights_punctual/lights/0/spot/foo", "skipped"),
        Map.entry("/extensions/KHR_lights_punctual/lights/0/type", "skipped"),
        Map.entry("/extensions/KHR_lights_punctual/lights/0/range", "LIGHT 0 range [0.0]"),
        Map.entry("/extensions/KHR_lights_punctual/lights/a/color", "skipped"),
        Map.entry("/extensions/KHR_other/lights/0/color", "skipped"),
        Map.entry("/extensions/KHR_lights_punctual/other/0/color", "skipped"),
        Map.entry("/extensions/KHR_lights_punctual/lights/1/color", "LIGHT 1 color [0.0, 0.0, 0.0]"),
        Map.entry("/extensions/KHR_lights_punctual/lights/1/spot/innerConeAngle", "LIGHT 1 innerConeAngle [0.0]"),
        Map.entry("/extensions/KHR_lights_punctual/lights/7/intensity", "LIGHT 7 intensity [1.0]"),
        Map.entry("/extensions/KHR_lights_punctual/lights/0/spot/extra/innerConeAngle", "skipped"),
        Map.entry("/extensions/KHR_lights_punctual/lights/0/x/innerConeAngle", "skipped"),
        Map.entry("/extensions/KHR_lights_punctual", "skipped"),
        Map.entry("/nodes/1/extensions/KHR_node_visibility/visible", "VISIBILITY 1 visible [0.0]"),
        Map.entry("/nodes/0/extensions/KHR_node_visibility/visible", "VISIBILITY 0 visible [1.0]"),
        Map.entry("/nodes/3/extensions/KHR_node_visibility/visible", "VISIBILITY 2 visible [1.0]"),
        Map.entry("/nodes/2/extensions/KHR_node_visibility/visible", "skipped"),
        Map.entry("/nodes/9/extensions/KHR_node_visibility/visible", "skipped"),
        Map.entry("/nodes/x/extensions/KHR_node_visibility/visible", "skipped"),
        Map.entry("/nodes/0/extensions/KHR_node_visibility/hidden", "skipped"),
        Map.entry("/nodes/0/extensions/other/visible", "skipped"),
        Map.entry("/nodes/0/a/b/c/d", "skipped"),
        Map.entry("/nodes/x/translation", "skipped"),
        Map.entry("/nodes/0/matrix", "skipped"),
        Map.entry("/nodes/0/a/translation", "skipped"),
        Map.entry("/cameras/0/translation", "skipped"));

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
        assertThat(cache.failure(ref)).isNull();
        return cache.get(ref);
    }

    @Test
    void pointersAnimateMaterialsLightsCamerasAndNodes() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(156).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : new float[] {0, 1, 0, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0.5f, 0, 0, 0, 0, 1, 1, 0, 0, 1,
            0, 3, 0, 0, 5, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10}) {
            b.putFloat(f); // times; a scalar, 2, 3 and 4 wide; a cubic spline scalar; 5 wide
        }
        b.put((byte) 1).put((byte) 0); // booleans
        String json = """
            {"asset": {"version": "2.0"}, "extensionsRequired": ["KHR_animation_pointer"],
             "scenes": [{"nodes": [0, 3]}],
             "nodes": [{"name": "root", "children": [1]},
               {"name": "lamp", "extensions": {"KHR_node_visibility": {"visible": false}}}, {"name": "loose"},
               {"name": "shown", "extensions": {"KHR_node_visibility": {"visible": true}}}],
             "materials": [{"pbrMetallicRoughness": {"baseColorFactor": [0.5, 0.5, 0.5, 1],
                 "metallicRoughnessTexture": {"index": 0}}, "occlusionTexture": {"index": 0, "strength": 0.5},
                 "emissiveFactor": [1, 0, 0], "extensions": {"KHR_materials_emissive_strength":
                   {"emissiveStrength": 2}, "KHR_materials_clearcoat": {"clearcoatFactor": 0.3}}},
               {"occlusionTexture": {"index": 1}}, {"extensions": {"KHR_materials_sheen": [1, 2]}}],
             "cameras": [{"type": "perspective", "perspective": {"yfov": 0.8, "znear": 0.1}}],
             "extensions": {"KHR_lights_punctual": {"lights": [{"type": "spot", "color": [1, 0, 0], "intensity": 5},
               {"type": "point", "color": [1, "a", 0], "spot": 3}]}},
             "buffers": [{"uri": "%s", "byteLength": 156}],
             "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 8},
               {"buffer": 0, "byteOffset": 8, "byteLength": 8}, {"buffer": 0, "byteOffset": 16, "byteLength": 16},
               {"buffer": 0, "byteOffset": 32, "byteLength": 24}, {"buffer": 0, "byteOffset": 56, "byteLength": 32},
               {"buffer": 0, "byteOffset": 88, "byteLength": 24}, {"buffer": 0, "byteOffset": 112, "byteLength": 40},
               {"buffer": 0, "byteOffset": 152, "byteLength": 2}],
             "accessors": [{"bufferView": 0, "componentType": 5126, "count": 2, "type": "SCALAR"},
               {"bufferView": 1, "componentType": 5126, "count": 2, "type": "SCALAR"},
               {"bufferView": 2, "componentType": 5126, "count": 2, "type": "VEC2"},
               {"bufferView": 3, "componentType": 5126, "count": 2, "type": "VEC3"},
               {"bufferView": 4, "componentType": 5126, "count": 2, "type": "VEC4"},
               {"bufferView": 5, "componentType": 5126, "count": 6, "type": "SCALAR"},
               {"bufferView": 6, "componentType": 5126, "count": 10, "type": "SCALAR"},
               {"bufferView": 7, "componentType": 5121, "count": 2, "type": "SCALAR"},
               {"bufferView": 1, "componentType": 5126, "count": 1, "type": "SCALAR"}],
             "animations": [{"name": "everything",
                 "samplers": [{"input": 0, "output": 1}, {"input": 0, "output": 2}, {"input": 0, "output": 3},
                   {"input": 0, "output": 4}, {"input": 0, "output": 5, "interpolation": "CUBICSPLINE"},
                   {"input": 0, "output": 6}, {"input": 0, "output": 7, "interpolation": "STEP"},
                   {"input": 0, "output": 8}],
                 "channels": [{"sampler": 3, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/pbrMetallicRoughness/baseColorFactor"}}}},
                   {"sampler": 3, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/1/pbrMetallicRoughness/baseColorFactor"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/pbrMetallicRoughness/metallicFactor"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/pbrMetallicRoughness/roughnessFactor"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/emissiveFactor"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/normalTexture/scale"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/occlusionTexture/strength"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/1/occlusionTexture/strength"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/2/occlusionTexture/strength"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/pbrMetallicRoughness/baseColorTexture/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/pbrMetallicRoughness/metallicRoughnessTexture/extensions/KHR_texture_transform/rotation"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/emissiveTexture/extensions/KHR_texture_transform/scale"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/normalTexture/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/occlusionTexture/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/1/occlusionTexture/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatNormalTexture/extensions/KHR_texture_transform/scale"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatOther/extensions/KHR_texture_transform/scale"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_sheen/sheenColorTexture/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_pbrSpecularGlossiness/diffuseTexture/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/other/diffuseTexture/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/x/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 1, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/a/b/c/extensions/KHR_texture_transform/offset"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/2/extensions/KHR_materials_sheen/sheenRoughnessFactor"}}}},
                   {"sampler": 7, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/1/pbrMetallicRoughness/roughnessFactor"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/emissiveTexture/extensions/KHR_texture_transform/texCoord"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/x/extensions/other/offset"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatFactor"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatRoughnessFactor"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatNormalTexture/scale"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatTexture"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatOther"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatNormalTexture/texCoord"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_clearcoat/clearcoatFactor/x"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_sheen/sheenRoughnessFactor"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_sheen/sheenColorTexture"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_sheen/sheenColorTexture/texCoord"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_sheen/a~1b~0c"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_emissive_strength/emissiveStrength"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/KHR_materials_ior/ior"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/extensions/other/x"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/a/b/c"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0/alphaCutoff"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/5/alphaCutoff"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/x/alphaCutoff"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/0"}}}},
                   {"sampler": 5, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/1/pbrMetallicRoughness/metallicFactor"}}}},
                   {"sampler": 3, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/materials/1/emissiveFactor"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/cameras/0/perspective/yfov"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/cameras/0/orthographic/xmag"}}}},
                   {"sampler": 4, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/cameras/0/perspective/zfar"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/cameras/0/perspective/type"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/cameras/x/perspective/yfov"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/cameras/0/perspective"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/0/color"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/0/intensity"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/0/spot/outerConeAngle"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/0/spot/foo"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/0/type"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/0/range"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/a/color"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_other/lights/0/color"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/other/0/color"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/1/color"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/1/spot/innerConeAngle"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/7/intensity"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/0/spot/extra/innerConeAngle"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual/lights/0/x/innerConeAngle"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/extensions/KHR_lights_punctual"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/1/extensions/KHR_node_visibility/visible"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/0/extensions/KHR_node_visibility/visible"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/3/extensions/KHR_node_visibility/visible"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/2/extensions/KHR_node_visibility/visible"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/9/extensions/KHR_node_visibility/visible"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/x/extensions/KHR_node_visibility/visible"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/0/extensions/KHR_node_visibility/hidden"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/0/extensions/other/visible"}}}},
                   {"sampler": 6, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/0/a/b/c/d"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/x/translation"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/0/matrix"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/0/a/translation"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/cameras/0/translation"}}}},
                   {"sampler": 2, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/0/translation"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "/nodes/1/weights"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": "materials/0"}}}},
                   {"sampler": 0, "target": {"path": "pointer", "extensions": {"KHR_animation_pointer": {"pointer": 3}}}},
                   {"sampler": 0, "target": {"path": "pointer"}},
                   {"sampler": 0, "target": {"node": 0, "path": "foo"}}]}]}
            """.formatted(base64(b.array()));
        Files.writeString(dir.resolve("p.gltf"), json);
        AnimationClip clip = load(paths.ref(AnimationAssets.CLIP, "game://p.gltf#animation/0"));
        Map<String, String> found = new LinkedHashMap<>();
        Map<String, Track> tracks = new LinkedHashMap<>();
        for (Track track : clip.tracks()) {
            AnimationPointer p = track.pointer();
            if (p != null) {
                double[] rest = new double[p.components()];
                Arrays.setAll(rest, p::rest);
                found.put(p.path(), p.target() + " " + p.index() + " " + p.property() + " " + Arrays.toString(rest));
                tracks.put(p.path(), track);
            }
        }
        Map<String, String> expected = new LinkedHashMap<>();
        CASES.forEach((path, what) -> {
            if (!what.equals("skipped")) {
                expected.put(path, what);
            }
        });
        assertThat(found).containsExactlyInAnyOrderEntriesOf(expected);
        // A material's pointer names its asset; its values scaled as the importer scales them
        AnimationPointer emissive = tracks.get("/materials/0/emissiveFactor").pointer();
        assertThat(emissive.asset()).isEqualTo(paths.ref(RenderAssets.MATERIAL, "game://p.gltf#material/0"));
        double[] out = new double[4];
        tracks.get("/materials/0/emissiveFactor").sample(1, new int[1], 0, out);
        assertThat(out).startsWith(2, 1, 0);
        assertThat(tracks.get("/cameras/0/perspective/yfov").pointer().asset()).isNull();
        // A node's own paths are its tracks
        List<Track> nodeTracks = clip.tracks().stream().filter(t -> t.pointer() == null).toList();
        assertThat(nodeTracks).extracting(Track::property).containsExactly(Track.Property.TRANSLATION,
            Track.Property.WEIGHTS);
        assertThat(nodeTracks).extracting(Track::nodeName).containsExactly("root", "lamp");
        assertThat(nodeTracks.get(1).components()).isOne();
    }
}
