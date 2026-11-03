package com.truesim.scene.audio;

import java.util.Objects;

/**
 * A subtitle cue of a line: from {@code start} to {@code end} seconds into its clip, showing {@code text} (a text key
 * the game localises, or the text itself).
 *
 * @param start seconds into the clip it starts
 * @param end   seconds into the clip it ends
 * @param text  its text key
 */
public record Cue(double start, double end, String text) {

    public Cue {
        Objects.requireNonNull(text, "text");
        if (!(start >= 0 && end >= start)) {
            throw new IllegalArgumentException("A cue runs forwards from 0: " + start + " to " + end);
        }
    }
}
