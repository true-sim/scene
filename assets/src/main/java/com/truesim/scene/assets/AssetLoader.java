package com.truesim.scene.assets;

/**
 * Reads one format into asset data, e.g. glTF into meshes, KTX2 into textures: from an {@link AssetInput}, whatever
 * source the bytes come from and however they were compressed or encrypted on the way. Registered with the cache per
 * asset type ({@link AssetCache#register}); runs in the background, on the type's lane, so it must be thread-safe.
 *
 * <pre>{@code
 * cache.register(MESH, input -> {
 *     try (InputStream in = input.open()) {
 *         Gltf gltf = Gltf.parse(in);
 *         return MeshData.from(gltf, name -> input.open(name), input.part()); // buffers and images by name
 *     }
 * });
 * }</pre>
 *
 * @param <T> what it reads
 */
@FunctionalInterface
public interface AssetLoader<T> {

    /** Reads the asset {@code input} stands for. Exceptions mark the asset failed. */
    T load(AssetInput input) throws Exception;

    /** Releases what a loaded asset holds when the cache evicts it, e.g. native memory. Does nothing by default. */
    default void unload(T data) {
    }
}
