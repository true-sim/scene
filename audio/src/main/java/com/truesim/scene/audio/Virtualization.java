package com.truesim.scene.audio;

/** What happens to a sound's voice when it is made virtual (inaudible, or out of budget). */
public enum Virtualization {
    /** Keeps time without a voice and resumes in place when it becomes real again. */
    KEEP_TIME,
    /** Ends. */
    KILL,
    /** Ends if it would end by itself (a one-shot), else keeps time (a loop): the default. */
    KILL_IF_FINITE
}
