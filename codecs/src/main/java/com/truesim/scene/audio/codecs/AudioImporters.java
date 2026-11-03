package com.truesim.scene.audio.codecs;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.WavImporter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Registers the audio importers by extension (D47): {@code .wav} and {@code .ogg} (Ogg Vorbis). An Ogg file streams
 * when it is long or large (over {@value Vorbis#STREAM_SECONDS} s or 1 MB decoded) or when its path says
 * {@code #stream} ({@code game://music/theme.ogg#stream}); {@code #decode} keeps it whole. WAV files always decode.
 *
 * <pre>{@code
 * new AudioImporters(paths).register();
 * }</pre>
 */
@AllowAllocation("set up once")
public final class AudioImporters {

    private final PathAssets paths;

    /** The importers for {@code paths}. */
    public AudioImporters(PathAssets paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    /** Registers {@code .wav} and {@code .ogg} for clips. */
    public AudioImporters register() {
        paths.importers().add(AudioAssets.CLIP, "wav", WavImporter.INSTANCE)
            .add(AudioAssets.CLIP, "ogg", AudioImporters::ogg);
        return this;
    }

    static AudioClip ogg(AssetInput input) throws IOException {
        String fragment = input.path() == null ? "" : input.path().fragment();
        Boolean stream = switch (fragment) {
            case "stream" -> Boolean.TRUE;
            case "decode" -> Boolean.FALSE;
            default -> null;
        };
        try (InputStream in = input.open()) {
            return Vorbis.read(in.readAllBytes(), stream);
        }
    }
}
