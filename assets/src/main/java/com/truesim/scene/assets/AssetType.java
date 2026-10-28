package com.truesim.scene.assets;

import com.truesim.common.jobs.LaneSpec;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * A kind of asset, e.g. meshes, textures or materials: a typed key with how much memory a loaded one takes (for the
 * cache's budget) and the lane its loads run on. Declare types as constants; like layers, they are compared by
 * identity.
 *
 * <pre>{@code
 * static final AssetType<MeshData> MESH = AssetType.of("mesh", MeshData.class, MeshData::byteSize);
 * static final AssetType<Texture> TEXTURE = AssetType.of("texture", Texture.class, Texture::byteSize, DECODING);
 * }</pre>
 *
 * @param <T> what a loaded asset of this type holds
 */
public final class AssetType<T> {

    /** The lane assets load on unless their type names its own: virtual threads, 16 loads at once. */
    public static final LaneSpec LOADING = LaneSpec.virtual("asset-loading", 16);

    private final String name;
    private final Class<T> type;
    private final ToLongFunction<? super T> bytes;
    private final LaneSpec lane;

    private AssetType(String name, Class<T> type, ToLongFunction<? super T> bytes, LaneSpec lane) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.lane = Objects.requireNonNull(lane, "lane");
    }

    /** A type loading on {@link #LOADING}; {@code bytes} tells how much memory a loaded asset takes. */
    public static <T> AssetType<T> of(String name, Class<T> type, ToLongFunction<? super T> bytes) {
        return new AssetType<>(name, type, bytes, LOADING);
    }

    /** A type loading on its own lane, e.g. platform threads for CPU-heavy decoding or generation. */
    public static <T> AssetType<T> of(String name, Class<T> type, ToLongFunction<? super T> bytes, LaneSpec lane) {
        return new AssetType<>(name, type, bytes, lane);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    public LaneSpec lane() {
        return lane;
    }

    /** How much memory {@code data} takes, as the type says. */
    long bytes(Object data) {
        return bytes.applyAsLong(type.cast(data));
    }

    @Override
    public String toString() {
        return "asset type " + name;
    }
}
