package com.truesim.scene.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class MaterialDataTest {

    static final AssetRef<TextureData> ALBEDO =
        AssetRef.of(RenderAssets.TEXTURE, AssetId.parse("55555555555555555555555555555555"));
    static final AssetRef<TextureData> NORMALS =
        AssetRef.of(RenderAssets.TEXTURE, AssetId.parse("66666666666666666666666666666666"));
    static final AssetRef<TextureData> ORM =
        AssetRef.of(RenderAssets.TEXTURE, AssetId.parse("77777777777777777777777777777777"));
    static final AssetRef<TextureData> GLOW =
        AssetRef.of(RenderAssets.TEXTURE, AssetId.parse("88888888888888888888888888888888"));

    @Test
    void defaultsAreLightGrayOpaqueAndRough() {
        MaterialData d = MaterialData.DEFAULT;
        assertThat(d.baseColor(0)).isEqualTo(0.8f);
        assertThat(d.baseColor(3)).isEqualTo(1);
        assertThat(d.emissive(0)).isZero();
        assertThat(d.roughness()).isEqualTo(0.7f);
        assertThat(d.metallic()).isZero();
        assertThat(d.normalScale()).isEqualTo(1);
        assertThat(d.occlusionStrength()).isEqualTo(1);
        assertThat(d.textures()).isEmpty();
        assertThat(d.baseColorTexture()).isNull();
        assertThat(d.alphaMode()).isEqualTo(MaterialData.AlphaMode.OPAQUE);
        assertThat(d.alphaCutoff()).isEqualTo(0.5f);
        assertThat(d.doubleSided()).isFalse();
        assertThat(d.clearcoat()).isZero();
        assertThat(d.clearcoatTexture()).isNull();
        assertThat(d.clearcoatNormalScale()).isEqualTo(1);
        assertThat(d.bytes()).isPositive();
        assertThat(d).hasToString("material OPAQUE");
    }

    @Test
    void everyPropertyIsSet() {
        MaterialData.Builder builder = MaterialData.builder().baseColor(0.1f, 0.2f, 0.3f, 0.4f).emissive(1, 2, 3)
            .roughness(0.25f).metallic(1).baseColorTexture(ALBEDO).normalTexture(NORMALS, 0.5f).ormTexture(ORM, 0.75f)
            .emissiveTexture(GLOW).alpha(MaterialData.AlphaMode.MASK, 0.3f).doubleSided(true);
        MaterialData m = builder.build();
        assertThat(m.baseColor(2)).isEqualTo(0.3f);
        assertThat(m.baseColor(3)).isEqualTo(0.4f);
        assertThat(m.emissive(1)).isEqualTo(2);
        assertThat(m.roughness()).isEqualTo(0.25f);
        assertThat(m.metallic()).isEqualTo(1);
        assertThat(m.normalTexture()).isEqualTo(NORMALS);
        assertThat(m.normalScale()).isEqualTo(0.5f);
        assertThat(m.ormTexture()).isEqualTo(ORM);
        assertThat(m.occlusionStrength()).isEqualTo(0.75f);
        assertThat(m.emissiveTexture()).isEqualTo(GLOW);
        assertThat(m.textures()).containsExactly(ALBEDO, NORMALS, ORM, GLOW);
        assertThat(m.alphaMode()).isEqualTo(MaterialData.AlphaMode.MASK);
        assertThat(m.alphaCutoff()).isEqualTo(0.3f);
        assertThat(m).hasToString("material MASK, double-sided");
        // Built materials do not change with their builder.
        builder.baseColor(1, 1, 1, 1);
        assertThat(m.baseColor(0)).isEqualTo(0.1f);
        assertThatNullPointerException().isThrownBy(() -> MaterialData.builder().alpha(null, 0));
        assertThat(MaterialData.builder().emissiveTexture(GLOW).build().textures()).containsExactly(GLOW);
    }

    @Test
    void aClearCoatIsALayerOfItsOwn() {
        AssetRef<TextureData> coat =
            AssetRef.of(RenderAssets.TEXTURE, AssetId.parse("99999999999999999999999999999999"));
        MaterialData m = MaterialData.builder().baseColorTexture(ALBEDO).clearcoat(1, 0.05f).clearcoatTexture(coat)
            .clearcoatRoughnessTexture(coat).clearcoatNormalTexture(NORMALS, 0.5f).build();
        assertThat(m.clearcoat()).isEqualTo(1);
        assertThat(m.clearcoatRoughness()).isEqualTo(0.05f);
        assertThat(m.clearcoatTexture()).isEqualTo(coat);
        assertThat(m.clearcoatRoughnessTexture()).isEqualTo(coat);
        assertThat(m.clearcoatNormalTexture()).isEqualTo(NORMALS);
        assertThat(m.clearcoatNormalScale()).isEqualTo(0.5f);
        assertThat(m.textures()).as("each once").containsExactly(ALBEDO, coat, NORMALS);
        assertThat(m).hasToString("material OPAQUE, clear coat");
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
