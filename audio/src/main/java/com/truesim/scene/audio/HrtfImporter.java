package com.truesim.scene.audio;

import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import java.io.IOException;
import java.io.InputStream;

/**
 * Imports {@code .hrtf} files (§2.9: HRTF sets converted from SOFA at build time) as {@link HrtfSet}s
 * ({@code paths.importers().add(AudioAssets.HRTF, "hrtf", HrtfImporter.INSTANCE)}).
 */
public final class HrtfImporter implements AssetLoader<HrtfSet> {

    /** The importer: it keeps no state. */
    public static final HrtfImporter INSTANCE = new HrtfImporter();

    private HrtfImporter() {
    }

    @Override
    public HrtfSet load(AssetInput input) throws IOException {
        try (InputStream in = input.open()) {
            return HrtfSet.read(in);
        }
    }
}
