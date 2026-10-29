package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A surface as data: the name of its material type, its parameters by field name, the features it shows with theirs,
 * how alpha is used, and whether both sides show. No shader code, and nothing the engine's types are made of: the
 * renderer finds the type by its name and reads the values by the names of its parameters' fields, nested ones by path
 * ({@code base.roughness}). Immutable, compared by value; made by {@link #builder}.
 *
 * <pre>{@code
 * MaterialData rock = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
 *     .set("baseColor", 0.5f, 0.45f, 0.4f, 1).set("roughness", 0.9f).texture("normalTexture", ROCK_NORMALS)
 *     .build();
 * MaterialData carPaint = MaterialData.builder("carPaint")
 *     .set("base.baseColor", 0.6f, 0.05f, 0.05f, 1).set("flakes", 0.4f)
 *     .feature("clearcoat", coat -> coat.set("roughness", 0.05f))
 *     .build();
 * renderables.set(node, carMesh, List.of(carPaint.ref()));   // equal data, one material
 * }</pre>
 *
 * <p>Values are floats, integers, vectors of 2 to 4 floats (colors among them) and textures. A field the data does not
 * name keeps its type's default.
 */
@AllowAllocation("material data is made and read when assets load")
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

    /** The engine's metallic-roughness type, as glTF's materials are. */
    public static final String METALLIC_ROUGHNESS = "metallicRoughness";

    /** The default surface: metallic-roughness's defaults, opaque, one-sided. */
    public static final MaterialData DEFAULT = builder(METALLIC_ROUGHNESS).build();

    /** Makes an {@link #ref inline} material: the data itself. */
    private static final Generator<MaterialData, MaterialData> INLINE = (data, seed) -> data;

    private final String type;
    private final Values values;
    private final Map<String, Values> features;
    private final AlphaMode alphaMode;
    private final float alphaCutoff;
    private final boolean doubleSided;
    /** What equality compares. */
    private final List<Object> key;

    private MaterialData(Builder b) {
        type = b.type;
        values = ((ValuesBuilder<?>) b).values.copy();
        Map<String, Values> copied = new LinkedHashMap<>();
        b.features.forEach((name, v) -> copied.put(name, v.copy()));
        features = Collections.unmodifiableMap(copied);
        alphaMode = b.alphaMode;
        alphaCutoff = b.alphaCutoff;
        doubleSided = b.doubleSided;
        key = List.of(type, values, features, alphaMode, alphaCutoff, doubleSided);
    }

    /** Sets up material data of the material type called {@code type}. */
    public static Builder builder(String type) {
        return new Builder(type);
    }

    /** The name of its material type. */
    public String type() {
        return type;
    }

    /** Its parameters by field name. */
    public Values values() {
        return values;
    }

    /** The names of the features it shows, e.g. {@code clearcoat}. */
    public Set<String> features() {
        return features.keySet();
    }

    /** The parameters of feature {@code name}, or null if it does not show it. */
    public Values feature(String name) {
        return features.get(name);
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

    /** The textures it names, its parameters' then its features', each once. */
    public List<AssetRef<TextureData>> textures() {
        List<AssetRef<TextureData>> textures = new ArrayList<>(4);
        values.addTextures(textures);
        features.values().forEach(v -> v.addTextures(textures));
        return Collections.unmodifiableList(textures);
    }

    /**
     * A reference to this data as a material asset of its own, for renderables: equal data gives equal references,
     * so renderables given equal data share one material.
     */
    public AssetRef<MaterialData> ref() {
        return AssetRef.generated(RenderAssets.MATERIAL, INLINE, this, 0);
    }

    /** About the memory the material takes, for the asset cache's budget: it is small, its textures are apart. */
    public long bytes() {
        return 128 + 32L * (values.size() + features.values().stream().mapToInt(Values::size).sum());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MaterialData d && key.equals(d.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "material " + type + " " + alphaMode + (doubleSided ? ", double-sided" : "")
            + (features.isEmpty() ? "" : ", " + String.join(", ", features.keySet()));
    }

    /**
     * Parameters by field name: floats, integers, vectors of 2 to 4 floats, textures. Immutable once in material
     * data; compared by value.
     */
    public static final class Values {
        private final Map<String, Object> entries;

        private Values(Map<String, Object> entries) {
            this.entries = entries;
        }

        /** The names it has a value for. */
        public Set<String> names() {
            return Collections.unmodifiableSet(entries.keySet());
        }

        public int size() {
            return entries.size();
        }

        public boolean has(String name) {
            return entries.containsKey(name);
        }

        /** Field {@code name}'s float, or {@code fallback} without one. */
        public float f32(String name, float fallback) {
            Object value = entries.get(name);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Float f) {
                return f;
            }
            if (value instanceof Integer i) {
                return i;
            }
            throw wrong(name, "a float", value);
        }

        /** Field {@code name}'s integer, or {@code fallback} without one. */
        public int i32(String name, int fallback) {
            Object value = entries.get(name);
            if (value == null) {
                return fallback;
            }
            if (value instanceof Integer i) {
                return i;
            }
            throw wrong(name, "an integer", value);
        }

        /** Field {@code name}'s vector (a copy: 2 to 4 floats), or null without one. */
        public float[] floats(String name) {
            Object value = entries.get(name);
            if (value == null) {
                return null;
            }
            if (value instanceof Vector v) {
                return v.floats.clone();
            }
            throw wrong(name, "a vector", value);
        }

        /** Field {@code name}'s texture, or null without one. */
        @SuppressWarnings("unchecked")
        public AssetRef<TextureData> texture(String name) {
            Object value = entries.get(name);
            if (value == null) {
                return null;
            }
            if (value instanceof AssetRef<?> ref) {
                return (AssetRef<TextureData>) ref; // texture() takes only textures
            }
            throw wrong(name, "a texture", value);
        }

        private static IllegalArgumentException wrong(String name, String expected, Object value) {
            return new IllegalArgumentException(name + " is " + expected + ", not " + value);
        }

        private void addTextures(List<AssetRef<TextureData>> textures) {
            for (Object value : entries.values()) {
                if (value instanceof AssetRef<?> ref && !textures.contains(ref)) {
                    @SuppressWarnings("unchecked")
                    AssetRef<TextureData> texture = (AssetRef<TextureData>) ref; // texture() takes only textures
                    textures.add(texture);
                }
            }
        }

        private Values copy() {
            return new Values(new LinkedHashMap<>(entries)); // the values are immutable
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Values v && entries.equals(v.entries);
        }

        @Override
        public int hashCode() {
            return entries.hashCode();
        }

        @Override
        public String toString() {
            return entries.toString();
        }
    }

    /** A vector value, compared by its floats. */
    private record Vector(float[] floats) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Vector v && Arrays.equals(floats, v.floats);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(floats);
        }

        @Override
        public String toString() {
            return Arrays.toString(floats);
        }
    }

    /** Sets up values by field name, of a material's parameters or of one of its features'. */
    public static class ValuesBuilder<B extends ValuesBuilder<B>> {
        private final Values values = new Values(new LinkedHashMap<>());

        @SuppressWarnings("unchecked")
        private B self() {
            return (B) this;
        }

        public B set(String name, float value) {
            values.entries.put(Objects.requireNonNull(name, "name"), value);
            return self();
        }

        public B set(String name, int value) {
            values.entries.put(Objects.requireNonNull(name, "name"), value);
            return self();
        }

        /** A vector of 2 to 4 floats, e.g. a color: {@code set("baseColor", r, g, b, a)}. */
        public B set(String name, float x, float y, float... more) {
            if (more.length > 2) {
                throw new IllegalArgumentException(name + ": vectors are 2 to 4 floats");
            }
            float[] vector = new float[2 + more.length];
            vector[0] = x;
            vector[1] = y;
            System.arraycopy(more, 0, vector, 2, more.length);
            values.entries.put(Objects.requireNonNull(name, "name"), new Vector(vector));
            return self();
        }

        public B texture(String name, AssetRef<TextureData> texture) {
            values.entries.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(texture, "texture"));
            return self();
        }
    }

    /** A feature's values. */
    public static final class FeatureBuilder extends ValuesBuilder<FeatureBuilder> {
        private FeatureBuilder() {
        }
    }

    /** Sets up a {@link MaterialData}. */
    public static final class Builder extends ValuesBuilder<Builder> {
        private final String type;
        private final Map<String, Values> features = new LinkedHashMap<>();
        private AlphaMode alphaMode = AlphaMode.OPAQUE;
        private float alphaCutoff = 0.5f;
        private boolean doubleSided;

        private Builder(String type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        /** Shows feature {@code name} (e.g. {@code clearcoat}) with the values {@code values} sets. */
        public Builder feature(String name, Consumer<FeatureBuilder> values) {
            FeatureBuilder feature = new FeatureBuilder();
            values.accept(feature);
            features.put(Objects.requireNonNull(name, "name"), ((ValuesBuilder<?>) feature).values);
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
