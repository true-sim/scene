package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Where asset bytes come from: a directory, an archive, a network service. A source only opens streams, by asset id
 * and resource name; what the bytes mean is up to the type's {@link AssetLoader}, so one loader (e.g. glTF) reads from
 * any source. Sources chain ({@link #firstOf}) and transform their streams ({@link #through}), e.g. to decompress or
 * decrypt:
 *
 * <pre>{@code
 * AssetSource source = AssetSource.firstOf(
 *         AssetSource.directory(patches),                         // loose files first
 *         AssetSource.directory(baseGame).through(AssetSource.GZIP),
 *         cdn.through((id, resource, in) -> decrypt(in, keyFor(id))));
 * }</pre>
 *
 * <p>Each asset has a main resource ({@link #MAIN}) and may have side resources its format names, e.g. a glTF file's
 * buffers and images. Sources are used from the background, on the asset types' lanes: they must be thread-safe.
 */
@FunctionalInterface
public interface AssetSource {

    /** The name of an asset's main resource. */
    String MAIN = "";

    /** Inflates gzip streams. */
    Transform GZIP = (id, resource, in) -> new GZIPInputStream(in);

    /**
     * Opens resource {@code resource} of asset {@code id} ({@link #MAIN} for its main resource), or returns
     * {@code null} if this source does not have it. The caller closes the stream.
     */
    InputStream open(AssetId id, String resource) throws IOException;

    /** Turns one stream into another: decompression, decryption, a checksum; for one asset's resource. */
    @FunctionalInterface
    interface Transform {
        InputStream apply(AssetId id, String resource, InputStream in) throws IOException;
    }

    /** This source with {@code transform} applied to every stream it opens, after transforms applied before. */
    @AllowAllocation("setting up sources")
    default AssetSource through(Transform transform) {
        Objects.requireNonNull(transform, "transform");
        return (id, resource) -> {
            InputStream in = open(id, resource);
            return in == null ? null : transform.apply(id, resource, in);
        };
    }

    /** Opens from the first of {@code sources} that has the resource, e.g. patches before the base game. */
    @AllowAllocation("setting up sources")
    static AssetSource firstOf(AssetSource... sources) {
        List<AssetSource> list = List.of(sources);
        return (id, resource) -> {
            for (AssetSource source : list) {
                InputStream in = source.open(id, resource);
                if (in != null) {
                    return in;
                }
            }
            return null;
        };
    }

    /**
     * Files under {@code root}: an asset's main resource is the file named by its id ({@code root/<id>}), its other
     * resources are {@code root/<id>.<resource>}.
     */
    static AssetSource directory(Path root) {
        return directory(root, (id, resource) -> MAIN.equals(resource) ? id.toString() : id + "." + resource);
    }

    /** Files under {@code root}, at the relative paths {@code layout} gives each asset's resources. */
    @AllowAllocation("setting up sources")
    static AssetSource directory(Path root, Layout layout) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(layout, "layout");
        return (id, resource) -> {
            Path file = root.resolve(layout.path(id, resource));
            return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        };
    }

    /** Where a directory keeps an asset's resource, relative to its root. */
    @FunctionalInterface
    interface Layout {
        String path(AssetId id, String resource);
    }
}
