package com.truesim.scene.render;

import com.truesim.scene.assets.AssetRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A surface as data, metallic-roughness as in glTF: a base color, metalness, roughness, a tangent-space normal map,
 * occlusion and emission, each a factor times an optional texture; how alpha is used, and whether both sides show. No
 * shader code: the engine decides how to draw it. Immutable; made by {@link #builder()}.
 *
 * <pre>{@code
 * MaterialData rock = MaterialData.builder().baseColor(0.5f, 0.45f, 0.4f, 1).roughness(0.9f)
 *     .baseColorTexture(ROCK_ALBEDO).normalTexture(ROCK_NORMALS, 1)
 *     .build();
 * }</pre>
 */
public final class MaterialData {

    /** How a surface's alpha is used, as in glTF. */
    public enum AlphaMode {
        /** Alpha is ignored: fully opaque. */
        OPAQUE,
        /** Alpha below {@link #alphaCutoff()} is not drawn, the rest is opaque: foliage, fences. */
        MASK,
        /** Blended over what is behind: glass, smoke. */
        BLEND
    }

    /** The default surface: light gray, dielectric, fairly rough, opaque, one-sided. */
    public static final MaterialData DEFAULT = builder().build();

    private final float[] baseColor;
    private final float[] emissive;
    private final float roughness;
    private final float metallic;
    private final float normalScale;
    private final float occlusionStrength;
    private final AssetRef<TextureData> baseColorTexture;
    private final AssetRef<TextureData> normalTexture;
    private final AssetRef<TextureData> ormTexture;
    private final AssetRef<TextureData> emissiveTexture;
    private final AlphaMode alphaMode;
    private final float alphaCutoff;
    private final boolean doubleSided;

    private MaterialData(Builder b) {
        baseColor = b.baseColor.clone();
        emissive = b.emissive.clone();
        roughness = b.roughness;
        metallic = b.metallic;
        normalScale = b.normalScale;
        occlusionStrength = b.occlusionStrength;
        baseColorTexture = b.baseColorTexture;
        normalTexture = b.normalTexture;
        ormTexture = b.ormTexture;
        emissiveTexture = b.emissiveTexture;
        alphaMode = b.alphaMode;
        alphaCutoff = b.alphaCutoff;
        doubleSided = b.doubleSided;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The base color's channel {@code channel} (0 red to 3 alpha), linear. */
    public float baseColor(int channel) {
        return baseColor[channel];
    }

    /** The emission's channel {@code channel} (0 red to 2 blue), linear. */
    public float emissive(int channel) {
        return emissive[channel];
    }

    public float roughness() {
        return roughness;
    }

    public float metallic() {
        return metallic;
    }

    public float normalScale() {
        return normalScale;
    }

    public float occlusionStrength() {
        return occlusionStrength;
    }

    /** The base color texture (sRGB), times the base color; {@code null} for none. */
    public AssetRef<TextureData> baseColorTexture() {
        return baseColorTexture;
    }

    /** The tangent-space normal map (linear); {@code null} for none. */
    public AssetRef<TextureData> normalTexture() {
        return normalTexture;
    }

    /** Occlusion, roughness and metalness in red, green and blue (linear), as glTF packs them; {@code null} for none. */
    public AssetRef<TextureData> ormTexture() {
        return ormTexture;
    }

    /** The emission texture (sRGB), times the emission; {@code null} for none. */
    public AssetRef<TextureData> emissiveTexture() {
        return emissiveTexture;
    }

    /** The textures this material names, in a fixed order (base color, normal, ORM, emission), without the absent. */
    public List<AssetRef<TextureData>> textures() {
        List<AssetRef<TextureData>> textures = new ArrayList<>(4);
        addPresent(textures, baseColorTexture);
        addPresent(textures, normalTexture);
        addPresent(textures, ormTexture);
        addPresent(textures, emissiveTexture);
        return Collections.unmodifiableList(textures);
    }

    private static void addPresent(List<AssetRef<TextureData>> textures, AssetRef<TextureData> texture) {
        if (texture != null) {
            textures.add(texture);
        }
    }

    public AlphaMode alphaMode() {
        return alphaMode;
    }

    /** With {@link AlphaMode#MASK}, the alpha below which nothing is drawn. */
    public float alphaCutoff() {
        return alphaCutoff;
    }

    /** Whether the back side shows too (no back-face culling, the normal flipped for it). */
    public boolean doubleSided() {
        return doubleSided;
    }

    /** About the memory the material takes, for the asset cache's budget: it is small, its textures are apart. */
    public long bytes() {
        return 128;
    }

    @Override
    public String toString() {
        return "material " + alphaMode + (doubleSided ? ", double-sided" : "");
    }

    /** Sets up a {@link MaterialData}. */
    public static final class Builder {
        private final float[] baseColor = {0.8f, 0.8f, 0.8f, 1};
        private final float[] emissive = new float[3];
        private float roughness = 0.7f;
        private float metallic;
        private float normalScale = 1;
        private float occlusionStrength = 1;
        private AssetRef<TextureData> baseColorTexture;
        private AssetRef<TextureData> normalTexture;
        private AssetRef<TextureData> ormTexture;
        private AssetRef<TextureData> emissiveTexture;
        private AlphaMode alphaMode = AlphaMode.OPAQUE;
        private float alphaCutoff = 0.5f;
        private boolean doubleSided;

        private Builder() {
        }

        /** The base color, linear, with alpha: diffuse color, or a metal's reflectance. Light gray by default. */
        public Builder baseColor(float r, float g, float b, float a) {
            baseColor[0] = r;
            baseColor[1] = g;
            baseColor[2] = b;
            baseColor[3] = a;
            return this;
        }

        /** Light the surface gives off, linear; none by default. */
        public Builder emissive(float r, float g, float b) {
            emissive[0] = r;
            emissive[1] = g;
            emissive[2] = b;
            return this;
        }

        /** Perceptual roughness, 0 (mirror) to 1; 0.7 by default. */
        public Builder roughness(float value) {
            roughness = value;
            return this;
        }

        /** 0 (dielectric, the default) to 1 (metal). */
        public Builder metallic(float value) {
            metallic = value;
            return this;
        }

        public Builder baseColorTexture(AssetRef<TextureData> texture) {
            baseColorTexture = texture;
            return this;
        }

        /** A tangent-space normal map, its effect scaled by {@code scale}. */
        public Builder normalTexture(AssetRef<TextureData> texture, float scale) {
            normalTexture = texture;
            normalScale = scale;
            return this;
        }

        /** Occlusion, roughness and metalness packed as glTF does; occlusion counts {@code occlusionStrength}. */
        public Builder ormTexture(AssetRef<TextureData> texture, float occlusionStrength) {
            ormTexture = texture;
            this.occlusionStrength = occlusionStrength;
            return this;
        }

        public Builder emissiveTexture(AssetRef<TextureData> texture) {
            emissiveTexture = texture;
            return this;
        }

        /** How alpha is used; {@code cutoff} counts for {@link AlphaMode#MASK}. Opaque by default. */
        public Builder alpha(AlphaMode mode, float cutoff) {
            alphaMode = Objects.requireNonNull(mode, "mode");
            alphaCutoff = cutoff;
            return this;
        }

        public Builder doubleSided(boolean value) {
            doubleSided = value;
            return this;
        }

        public MaterialData build() {
            return new MaterialData(this);
        }
    }
}
