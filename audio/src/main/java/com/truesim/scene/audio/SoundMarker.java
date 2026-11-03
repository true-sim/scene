package com.truesim.scene.audio;

import java.util.Objects;

/**
 * A named point in a clip (a WAV {@code cue} point with its {@code labl}), where a loop may release or the game hears
 * an event.
 *
 * @param name  its label; {@code cue<id>} when the file gave none
 * @param frame the frame it marks, from the clip's start
 */
public record SoundMarker(String name, long frame) {
    public SoundMarker {
        Objects.requireNonNull(name, "name");
        if (frame < 0) {
            throw new IllegalArgumentException("A marker before the clip: " + frame);
        }
    }
}
