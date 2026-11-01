package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assets addressed by path, {@code mount://folder/file.ext}, on top of an {@link AssetCache}: mounts say where files
 * are ({@link #mounts()}), importers turn their formats into asset data by extension ({@link #importers()}), and a
 * path's {@link AssetId} is derived from it ({@link AssetPath#id()}), so references are ordinary {@link AssetRef}s the
 * cache loads, holds and evicts as any other.
 *
 * <pre>{@code
 * PathAssets assets = new PathAssets(cache);                     // the cache's source, from now on
 * assets.mounts().add("game", PathSource.folder(Path.of("assets")));
 * assets.importers().add(MODEL, "gltf", GLTF);
 * AssetRef<Model> rock = assets.ref(MODEL, "game://models/rock.gltf");
 * cache.acquire(rock);
 * }</pre>
 *
 * <p>Side resources a format names (a glTF file's buffers and images) are paths relative to the asset's folder. A
 * thread-safe index maps the ids of paths made here back to their paths, for the cache's background loads; ids from
 * elsewhere (a saved file) are known once a path made them, or through {@link #remember}.
 */
@AllowAllocation("set up once; references made when loading")
public final class PathAssets {

    private final Map<AssetId, AssetPath> paths = new ConcurrentHashMap<>();
    private final Mounts mounts = new Mounts();
    private final Importers importers;

    /** Path-addressed assets for {@code cache}, whose source they become ({@link #source()}). */
    public PathAssets(AssetCache cache) {
        Objects.requireNonNull(cache, "cache");
        importers = new Importers(cache, this);
        cache.setSource(source());
    }

    /** Where files are: per mount, its locations and redirects. */
    public Mounts mounts() {
        return mounts;
    }

    /** How formats become asset data: per type and extension. */
    public Importers importers() {
        return importers;
    }

    /** The reference to the {@code type} asset at {@code path} ({@code mount://folder/file.ext}). */
    public <T> AssetRef<T> ref(AssetType<T> type, String path) {
        return ref(type, AssetPath.parse(path));
    }

    /** The reference to the {@code type} asset at {@code path}. */
    public <T> AssetRef<T> ref(AssetType<T> type, AssetPath path) {
        return AssetRef.of(type, remember(path));
    }

    /** Notes {@code path} in the index, so its id loads; returns that id. */
    public AssetId remember(AssetPath path) {
        AssetId id = path.id();
        paths.putIfAbsent(id, path);
        return id;
    }

    /** The path whose id {@code id} is, or {@code null} if no path made here has it. */
    public AssetPath pathOf(AssetId id) {
        return paths.get(id);
    }

    /**
     * The id {@code id}'s asset has after the mounts' redirects: the new path's id for a renamed asset, {@code id}
     * itself otherwise. Saved references read through it load and cache as the new path does.
     */
    public AssetId canonical(AssetId id) {
        AssetPath path = paths.get(id);
        if (path == null) {
            return id;
        }
        AssetPath target = mounts.redirected(path);
        return target.equals(path) ? id : remember(target);
    }

    /**
     * The source reading path-addressed assets through the mounts: an asset's main resource is its path (after
     * redirects), a side resource a path relative to its folder. Opens nothing for ids no path made here has. The
     * cache gets it in the constructor; chain it with others ({@link AssetSource#firstOf}) for assets addressed by id
     * as well.
     */
    public AssetSource source() {
        return (id, resource) -> {
            AssetPath path = paths.get(id);
            if (path == null) {
                return null;
            }
            AssetPath at = mounts.redirected(path); // side resources sit next to where the asset is now
            return mounts.open(AssetSource.MAIN.equals(resource) ? at : at.resolve(resource));
        };
    }
}
