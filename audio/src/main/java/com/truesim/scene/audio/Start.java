package com.truesim.scene.audio;

/** Where a transition's destination starts playing. */
public enum Start {
    /** At its entry cue (its pre-entry before the sync point). */
    ENTRY,
    /** At the same bar and beat from its entry as the source was at the sync point. */
    SAME_POSITION,
    /** At its marker named as the source's last marker passed; at its entry if it has none such. */
    MATCHING_MARKER,
    /** At a bar picked from the music's seed. */
    RANDOM
}
