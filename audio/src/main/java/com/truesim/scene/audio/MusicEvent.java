package com.truesim.scene.audio;

/** What music reports to its listeners, at the time it is heard. */
public enum MusicEvent {
    /** A bar line. */
    BAR,
    /** A beat (bar lines too). */
    BEAT,
    /** A segment's marker ({@link Music#lastMarker()} names it). */
    MARKER,
    /** A transition reached its sync point: the new state is heard from now. */
    TRANSITION
}
