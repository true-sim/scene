package com.truesim.scene.audio;

import com.truesim.scene.assets.AssetRef;

/**
 * What music needs to know of its clips: whether one is ready to start at once (decoded, or its stream primed), asking
 * for it to be made ready, and how long it is. The engine's streaming answers; tests fake it. Called on the game
 * thread; must not block.
 */
public interface MusicClips {

    /** Every clip ready, lengths unknown: for tests and for music made of clips loaded in advance. */
    MusicClips READY = new MusicClips() {
        @Override
        public boolean ready(AssetRef<AudioClip> clip) {
            return true;
        }

        @Override
        public void prime(AssetRef<AudioClip> clip) {
        }

        @Override
        public double seconds(AssetRef<AudioClip> clip) {
            return Double.NaN;
        }
    };

    /** Whether {@code clip} can start at once. */
    boolean ready(AssetRef<AudioClip> clip);

    /** Asks for {@code clip} to be made ready (loaded, or its stream primed); returns at once. */
    void prime(AssetRef<AudioClip> clip);

    /** How long {@code clip} plays, in seconds; NaN while not known. */
    double seconds(AssetRef<AudioClip> clip);
}
