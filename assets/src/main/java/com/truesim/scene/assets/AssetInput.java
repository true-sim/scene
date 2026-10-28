package com.truesim.scene.assets;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * What an {@link AssetLoader} reads one asset from: the asset's id and part, and its resources, opened from the type's
 * {@link AssetSource} with its transforms applied.
 */
public interface AssetInput {

    AssetId id();

    /** The part to load: an LOD level or mip range of an asset that streams in parts; 0 for the whole or the first. */
    int part();

    /** Opens the asset's main resource; the caller closes it. */
    default InputStream open() throws IOException {
        return open(AssetSource.MAIN);
    }

    /**
     * Opens a side resource the format names, e.g. a glTF buffer or image; the caller closes it.
     *
     * @throws FileNotFoundException if the source does not have it
     */
    InputStream open(String resource) throws IOException;
}
