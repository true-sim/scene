package com.truesim.scene.assets;

/**
 * Makes an asset from parameters and a seed instead of loading it, e.g. a rock, a tree or a terrain tile. The same
 * generator, equal parameters and the same seed must give the same asset: they are its key in the cache, so equal
 * recipes are one asset. Declare generators as constants (they are compared by identity), and make parameters values
 * with {@code equals} and {@code hashCode}, e.g. records. Runs in the background, on the asset type's lane.
 *
 * <pre>{@code
 * record RockParams(double size, int detail) {}
 * static final Generator<RockParams, MeshData> ROCKS = (params, seed) -> Rocks.generate(params, seed);
 *
 * AssetRef<MeshData> rock = AssetRef.generated(MESH, ROCKS, new RockParams(2.5, 3), 42);
 * }</pre>
 *
 * @param <P> the parameters
 * @param <T> what it makes
 */
@FunctionalInterface
public interface Generator<P, T> {

    T generate(P params, long seed) throws Exception;
}
