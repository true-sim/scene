package com.truesim.scene.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MaterialDataTest {

    static final AssetRef<TextureData> ALBEDO =
        AssetRef.of(RenderAssets.TEXTURE, AssetId.parse("55555555555555555555555555555555"));
    static final AssetRef<TextureData> NORMALS =
        AssetRef.of(RenderAssets.TEXTURE, AssetId.parse("66666666666666666666666666666666"));

    @Test
    void theDefaultIsMetallicRoughnessOpaqueAndOneSided() {
        MaterialData d = MaterialData.DEFAULT;
        assertThat(d.type()).isEqualTo(MaterialData.METALLIC_ROUGHNESS).isEqualTo("metallicRoughness");
        assertThat(d.values().size()).isZero();
        assertThat(d.values().f32("roughness", 0.7f)).as("the type's default").isEqualTo(0.7f);
        assertThat(d.features()).isEmpty();
        assertThat(d.textures()).isEmpty();
        assertThat(d.alphaMode()).isEqualTo(MaterialData.AlphaMode.OPAQUE);
        assertThat(d.alphaCutoff()).isEqualTo(0.5f);
        assertThat(d.doubleSided()).isFalse();
        assertThat(d.bytes()).isPositive();
        assertThat(d).hasToString("material metallicRoughness OPAQUE");
    }

    @Test
    void valuesAreKeptByFieldName() {
        MaterialData.Builder builder = MaterialData.builder("carPaint").set("base.baseColor", 0.1f, 0.2f, 0.3f, 0.4f)
            .set("base.roughness", 0.25f).set("bands", 3).texture("base.baseColorTexture", ALBEDO)
            .texture("base.normalTexture", NORMALS).alpha(MaterialData.AlphaMode.MASK, 0.3f).doubleSided(true);
        MaterialData m = builder.build();
        MaterialData.Values v = m.values();
        assertThat(m.type()).isEqualTo("carPaint");
        assertThat(v.floats("base.baseColor")).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        assertThat(v.f32("base.roughness", 0)).isEqualTo(0.25f);
        assertThat(v.f32("bands", 0)).as("an integer read as a float").isEqualTo(3);
        assertThat(v.i32("bands", 0)).isEqualTo(3);
        assertThat(v.i32("missing", 7)).isEqualTo(7);
        assertThat(v.floats("missing")).isNull();
        assertThat(v.texture("base.baseColorTexture")).isEqualTo(ALBEDO);
        assertThat(v.texture("missing")).isNull();
        assertThat(v.has("bands")).isTrue();
        assertThat(v.names()).containsExactly("base.baseColor", "base.roughness", "bands", "base.baseColorTexture",
            "base.normalTexture");
        assertThat(m.textures()).containsExactly(ALBEDO, NORMALS);
        assertThat(m.alphaMode()).isEqualTo(MaterialData.AlphaMode.MASK);
        assertThat(m.alphaCutoff()).isEqualTo(0.3f);
        assertThat(m).hasToString("material carPaint MASK, double-sided");
        assertThat(v).hasToString("{base.baseColor=[0.1, 0.2, 0.3, 0.4], base.roughness=0.25, bands=3, "
            + "base.baseColorTexture=" + ALBEDO + ", base.normalTexture=" + NORMALS + "}");
        // Values of the wrong kind say so.
        assertThatIllegalArgumentException().isThrownBy(() -> v.i32("base.roughness", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> v.f32("base.baseColor", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> v.texture("bands"));
        assertThatIllegalArgumentException().isThrownBy(() -> MaterialData.builder("x").set("v", 1, 2, 3, 4, 5));
        // Built materials do not change with their builder.
        builder.set("base.roughness", 1f);
        assertThat(v.f32("base.roughness", 0)).isEqualTo(0.25f);
        assertThatNullPointerException().isThrownBy(() -> MaterialData.builder("x").alpha(null, 0));
        assertThatNullPointerException().isThrownBy(() -> MaterialData.builder(null));
    }

    @Test
    void featuresHoldTheirOwnValues() {
        AssetRef<TextureData> coat =
            AssetRef.of(RenderAssets.TEXTURE, AssetId.parse("99999999999999999999999999999999"));
        MaterialData m = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).texture("baseColorTexture", ALBEDO)
            .feature("clearcoat", c -> c.set("roughness", 0.05f).texture("strengthTexture", coat)
                .texture("roughnessTexture", coat).texture("normalTexture", NORMALS))
            .build();
        assertThat(m.features()).containsExactly("clearcoat");
        assertThat(m.feature("clearcoat").f32("roughness", 0)).isEqualTo(0.05f);
        assertThat(m.feature("sheen")).isNull();
        assertThat(m.textures()).as("each once").containsExactly(ALBEDO, coat, NORMALS);
        assertThat(m).hasToString("material metallicRoughness OPAQUE, clearcoat");
    }

    @Test
    void equalDataIsOneMaterial() {
        MaterialData a = MaterialData.builder("glow").set("color", 1f, 0f, 1f).set("strength", 2f)
            .feature("clearcoat", c -> c.set("roughness", 0.1f)).build();
        MaterialData b = MaterialData.builder("glow").set("color", 1f, 0f, 1f).set("strength", 2f)
            .feature("clearcoat", c -> c.set("roughness", 0.1f)).build();
        MaterialData other = MaterialData.builder("glow").set("color", 1f, 0f, 0f).set("strength", 2f).build();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(other).isNotEqualTo("glow");
        assertThat(a.ref()).isEqualTo(b.ref()).isNotEqualTo(other.ref());
        assertThat(a.ref().type()).isEqualTo(RenderAssets.MATERIAL);
        assertThat(MaterialData.builder("glow").doubleSided(true).build())
            .isNotEqualTo(MaterialData.builder("glow").build());
        assertThat(a.values()).isNotEqualTo("glow");
        assertThat(MaterialData.builder("glow").set("color", 1f, 0f, 1f).build())
            .as("a vector, not a float").isNotEqualTo(MaterialData.builder("glow").set("color", 1f).build());
        assertThat(a.values().floats("color")).containsExactly(1, 0, 1);
        assertThatIllegalArgumentException().isThrownBy(() -> a.values().floats("strength"));
    }

    @Test
    void anInlineMaterialLoadsAsItsData() throws InterruptedException {
        MaterialData data = MaterialData.builder("glow").set("strength", 2f).build();
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            AssetRef<MaterialData> ref = data.ref();
            cache.acquire(ref);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cache.loadingCount() > 0) {
                tasks.drainCompleted();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
            }
            assertThat(cache.get(ref)).isSameAs(data);
        }
    }

    @Test
    void texturesHoldRgbaTexels() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment texels = arena.allocate(4 * 2 * 3);
            TextureData texture = new TextureData(2, 3, TextureData.ColorSpace.SRGB, texels);
            assertThat(texture.bytes()).isEqualTo(24);
            assertThat(texture.colorSpace()).isEqualTo(TextureData.ColorSpace.SRGB);
            assertThat(TextureData.ColorSpace.valueOf("LINEAR")).isEqualTo(TextureData.ColorSpace.LINEAR);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextureData(3, 3, TextureData.ColorSpace.LINEAR, texels));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextureData(0, 3, TextureData.ColorSpace.LINEAR, texels));
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new TextureData(2, 0, TextureData.ColorSpace.LINEAR, texels));
            assertThatNullPointerException().isThrownBy(() -> new TextureData(2, 3, null, texels));
            assertThatNullPointerException()
                .isThrownBy(() -> new TextureData(2, 3, TextureData.ColorSpace.LINEAR, null));
        }
    }

    @Test
    void assetTypesNameTheirData() {
        assertThat(RenderAssets.MESH.name()).isEqualTo("mesh");
        assertThat(RenderAssets.MATERIAL.type()).isEqualTo(MaterialData.class);
        assertThat(RenderAssets.TEXTURE.type()).isEqualTo(TextureData.class);
        assertThat(MaterialData.AlphaMode.valueOf("BLEND")).isEqualTo(MaterialData.AlphaMode.BLEND);
    }
}
