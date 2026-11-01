package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The importers of {@link PathAssets}: per asset type and file extension, the {@link AssetLoader} reading that format,
 * e.g. {@code gltf} and {@code glb} into models, {@code png} into textures. None is registered by default. The first
 * importer of a type registers one loader with the cache for it, which picks the importer by the asset path's
 * extension; data it evicts is closed if it is {@link AutoCloseable}.
 *
 * <pre>{@code
 * importers.add(TEXTURE, "png", PNG).add(TEXTURE, "jpg", JPEG);
 * importers.add(MODEL, "gltf", GLTF).add(MODEL, "glb", GLTF);
 * }</pre>
 */
@AllowAllocation("set up once")
public final class Importers {

    private final AssetCache cache;
    private final PathAssets paths;
    private final Map<AssetType<?>, Map<String, AssetLoader<?>>> byType = new IdentityHashMap<>();

    Importers(AssetCache cache, PathAssets paths) {
        this.cache = cache;
        this.paths = paths;
    }

    /**
     * Imports files ending in {@code .extension} (any case) as assets of {@code type} with {@code importer}. Throws if
     * the type has an importer for it already, or if the cache has a loader of its own for the type.
     */
    public <T> Importers add(AssetType<T> type, String extension, AssetLoader<T> importer) {
        Objects.requireNonNull(importer, "importer");
        String key = extension.toLowerCase(Locale.ROOT);
        if (key.isEmpty() || key.startsWith(".")) {
            throw new IllegalArgumentException("An extension without its dot: '" + extension + "'");
        }
        Map<String, AssetLoader<?>> importers = byType.get(type);
        if (importers == null) {
            Map<String, AssetLoader<?>> created = new ConcurrentHashMap<>();
            cache.register(type, input -> load(type, created, input));
            byType.put(type, created);
            importers = created;
        }
        if (importers.putIfAbsent(key, importer) != null) {
            throw new IllegalStateException(type + " has an importer for ." + key + " already");
        }
        return this;
    }

    /** The importer of {@code type} for {@code extension}, or {@code null}. */
    @SuppressWarnings("unchecked")
    public <T> AssetLoader<T> find(AssetType<T> type, String extension) {
        Map<String, AssetLoader<?>> importers = byType.get(type);
        return importers == null ? null : (AssetLoader<T>) importers.get(extension.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private <T> T load(AssetType<T> type, Map<String, AssetLoader<?>> importers, AssetInput input) throws Exception {
        AssetPath path = paths.pathOf(input.id());
        if (path == null) {
            throw new IllegalStateException("Asset " + input.id() + " has no path: " + type + " loads by path");
        }
        AssetLoader<T> importer = (AssetLoader<T>) importers.get(path.extension());
        if (importer == null) {
            throw new IllegalStateException("No importer for ." + path.extension() + " as " + type + ": " + path);
        }
        return importer.load(new AssetInput() {
            @Override
            public AssetId id() {
                return input.id();
            }

            @Override
            public int part() {
                return input.part();
            }

            @Override
            public AssetPath path() {
                return path;
            }

            @Override
            public InputStream open(String resource) throws IOException {
                return input.open(resource);
            }
        });
    }
}
