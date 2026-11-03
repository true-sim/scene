package com.truesim.scene.audio.codecs;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.audio.AudioAssets;
import com.truesim.scene.audio.AudioClip;
import com.truesim.scene.audio.HrtfImporter;
import com.truesim.scene.audio.WavImporter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Registers the audio importers by extension (D47): {@code .wav}, {@code .ogg} (Ogg Vorbis) and {@code .opus} for clips,
 * {@code .hrtf} for HRTF sets. An Ogg or Opus file streams
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

    /** Registers {@code .wav}, {@code .ogg} and {@code .opus} for clips and {@code .hrtf} for HRTF sets. */
    public AudioImporters register() {
        paths.importers().add(AudioAssets.CLIP, "wav", WavImporter.INSTANCE)
            .add(AudioAssets.CLIP, "ogg", AudioImporters::ogg)
            .add(AudioAssets.CLIP, "opus", AudioImporters::opus)
            .add(AudioAssets.HRTF, "hrtf", HrtfImporter.INSTANCE);
        return this;
    }

    static AudioClip ogg(AssetInput input) throws IOException {
        try (InputStream in = input.open()) {
            return Vorbis.read(in.readAllBytes(), stream(input));
        }
    }

    static AudioClip opus(AssetInput input) throws IOException {
        try (InputStream in = input.open()) {
            return Opus.read(in.readAllBytes(), stream(input));
        }
    }

    /** What the path's fragment asks: {@code #stream}, {@code #decode}, or the importer's choice (null). */
    private static Boolean stream(AssetInput input) {
        String fragment = input.path() == null ? "" : input.path().fragment();
        return switch (fragment) {
            case "stream" -> Boolean.TRUE;
            case "decode" -> Boolean.FALSE;
            default -> null;
        };
    }
}
