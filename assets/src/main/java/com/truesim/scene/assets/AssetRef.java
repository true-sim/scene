package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A typed reference to an asset, the way nodes, cells and other assets point at one: a stored asset by its stable
 * {@link AssetId}, or a generated one by its recipe (generator, parameters and seed), optionally one part of it (an LOD
 * level or mip range). Immutable and cheap; two references are equal when they name the same asset, which is the
 * cache's key. Holding a reference loads nothing: {@link AssetCache#acquire} does.
 *
 * <pre>{@code
 * AssetRef<MeshData> barrier = AssetRef.of(MESH, AssetId.parse("4f1c..."));
 * AssetRef<MeshData> farLod = barrier.part(2);
 * AssetRef<MeshData> rock = AssetRef.generated(MESH, ROCKS, new RockParams(2.5, 3), 42);
 * }</pre>
 *
 * @param <T> what the asset holds when loaded
 */
public final class AssetRef<T> {

    private final AssetType<T> type;
    private final AssetId id;
    private final Generator<?, T> generator;
    private final Object params;
    private final long seed;
    private final int part;
    private final int hash;

    private AssetRef(AssetType<T> type, AssetId id, Generator<?, T> generator, Object params, long seed, int part) {
        this.type = type;
        this.id = id;
        this.generator = generator;
        this.params = params;
        this.seed = seed;
        this.part = part;
        int h = System.identityHashCode(type);
        h = 31 * h + Objects.hashCode(id);
        h = 31 * h + System.identityHashCode(generator);
        h = 31 * h + Objects.hashCode(params);
        h = 31 * h + Long.hashCode(seed);
        this.hash = 31 * h + part;
    }

    /** A stored asset. */
    @AllowAllocation("making a reference")
    public static <T> AssetRef<T> of(AssetType<T> type, AssetId id) {
        return new AssetRef<>(Objects.requireNonNull(type, "type"), Objects.requireNonNull(id, "id"), null, null, 0, 0);
    }

    /** A generated asset: {@code generator} makes it from {@code params} and {@code seed}. */
    @AllowAllocation("making a reference")
    public static <P, T> AssetRef<T> generated(AssetType<T> type, Generator<P, T> generator, P params, long seed) {
        return new AssetRef<>(Objects.requireNonNull(type, "type"), null, Objects.requireNonNull(generator,
            "generator"), Objects.requireNonNull(params, "params"), seed, 0);
    }

    /** Part {@code index} of this stored asset, e.g. an LOD level or mip range of an asset that streams in parts. */
    @AllowAllocation("making a reference")
    public AssetRef<T> part(int index) {
        if (generator != null) {
            throw new IllegalStateException("Generated assets have no parts: " + this);
        }
        if (index < 0) {
            throw new IllegalArgumentException("Parts are numbered from 0: " + index);
        }
        return new AssetRef<>(type, id, generator, params, seed, index);
    }

    public AssetType<T> type() {
        return type;
    }

    /** The stored asset's id; {@code null} for a generated one. */
    public AssetId id() {
        return id;
    }

    /** Whether a generator makes this asset instead of a source loading it. */
    public boolean isGenerated() {
        return generator != null;
    }

    /** The part of the asset; 0 for the whole asset or its first part. */
    public int part() {
        return part;
    }

    /** Makes a generated asset; runs in the background. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    T generate() throws Exception {
        return (T) ((Generator) generator).generate(params, seed);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AssetRef<?> ref && type == ref.type && part == ref.part
            && Objects.equals(id, ref.id) && generator == ref.generator && Objects.equals(params, ref.params)
            && seed == ref.seed;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    @AllowAllocation("formatting")
    public String toString() {
        String name = generator != null ? "generated " + params + " seed " + seed : id.toString();
        return type.name() + " " + name + (part == 0 ? "" : " part " + part);
    }
}
