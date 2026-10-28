package com.truesim.scene.assets;

/**
 * Where stored assets of one type come from: files, an archive, a network service. Registered with the cache per type
 * ({@link AssetCache#register}); runs in the background, on the type's lane.
 *
 * <pre>{@code
 * cache.register(MESH, (id, part) -> MeshData.read(archive.open(id), part));
 * }</pre>
 *
 * @param <T> what it loads
 */
@FunctionalInterface
public interface AssetSource<T> {

    /**
     * Loads asset {@code id}, or its part {@code part} for assets that stream in parts (LOD levels, mip ranges; 0 is
     * the whole asset or its first part). Exceptions mark the asset failed.
     */
    T load(AssetId id, int part) throws Exception;

    /** Releases what a loaded asset holds when the cache evicts it, e.g. native memory. Does nothing by default. */
    default void unload(T data) {
    }
}
