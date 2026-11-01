package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One location of a mount ({@link Mounts}): opens files by their normalised path in the mount, {@code models/rock.gltf}.
 * A folder, an archive, a network service; used from the background, so it must be thread-safe.
 */
@FunctionalInterface
public interface PathSource {

    /** Opens the file at {@code path}, or returns {@code null} if this location does not have it. */
    InputStream open(String path) throws IOException;

    /** The files under {@code root}; never one outside it. */
    @AllowAllocation("setting up sources")
    static PathSource folder(Path root) {
        Path base = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        return path -> {
            Path file = base.resolve(path).normalize();
            return file.startsWith(base) && Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        };
    }
}
