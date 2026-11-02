package com.truesim.scene.audio;

/** Hears music's bars, beats, markers and transitions as they are heard; called on the game thread. */
@FunctionalInterface
public interface MusicListener {

    /** {@code event} is heard now, in bar {@code bar} at beat {@code beat} (both from 1) of the segment playing. */
    void heard(MusicEvent event, int bar, int beat);
}
