package com.truesim.scene.audio;

import java.util.Objects;

/**
 * A caption for a sound ("[explosion]"), for players who cannot hear it: a text key the game localises.
 *
 * @param key the text key
 */
public record Caption(String key) {
    public Caption {
        Objects.requireNonNull(key, "key");
    }

    /** The caption of text key {@code key}. */
    public static Caption of(String key) {
        return new Caption(key);
    }
}
