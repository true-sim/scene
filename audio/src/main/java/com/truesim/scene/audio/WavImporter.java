package com.truesim.scene.audio;

import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import java.io.IOException;
import java.io.InputStream;

/**
 * Imports {@code .wav} files as clips through {@link WavReader}
 * ({@code paths.importers().add(AudioAssets.CLIP, "wav", WavImporter.INSTANCE)}); the cache closes the clips it
 * evicts.
 */
public final class WavImporter implements AssetLoader<AudioClip> {

    /** The importer: it keeps no state. */
    public static final WavImporter INSTANCE = new WavImporter();

    private WavImporter() {
    }

    @Override
    public AudioClip load(AssetInput input) throws IOException {
        try (InputStream in = input.open()) {
            return WavReader.read(in);
        }
    }
}
