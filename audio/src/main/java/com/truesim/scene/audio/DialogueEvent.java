package com.truesim.scene.audio;

/** What happened to a line (§2.15), reported at the time it is heard. */
public enum DialogueEvent {
    /** It began to be heard. */
    LINE_STARTED,
    /** A subtitle cue began: show its text. */
    CUE_STARTED,
    /** A subtitle cue ended: hide its text. */
    CUE_ENDED,
    /** It played to its end. */
    LINE_ENDED,
    /** A higher priority line of its speaker cut it off (it fades over 100 ms). */
    INTERRUPTED,
    /** It was not played: a lower priority than its speaker's line, or its key cooling down. */
    DROPPED,
    /** It waited in its speaker's queue longer than the queue keeps lines. */
    EXPIRED
}
