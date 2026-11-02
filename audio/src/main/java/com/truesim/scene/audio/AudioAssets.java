package com.truesim.scene.audio;

import com.truesim.scene.assets.AssetType;

/**
 * The asset types of audio. Use them, so references from any loader or tool are equal; register importers for clips
 * ({@code paths.importers().add(AudioAssets.CLIP, "wav", WavImporter.INSTANCE)}); {@link Sound#ref()} makes an asset
 * of a sound built in code.
 */
public final class AudioAssets {

    /** Samples: decoded PCM in native memory, with loop points and markers. */
    public static final AssetType<AudioClip> CLIP = AssetType.of("audio clip", AudioClip.class, AudioClip::bytes);

    /** Sound definitions: a tree of containers over clips, with bus, attenuation and the rest. */
    public static final AssetType<Sound> SOUND = AssetType.of("sound", Sound.class, Sound::bytes);

    private AudioAssets() {
    }
}
