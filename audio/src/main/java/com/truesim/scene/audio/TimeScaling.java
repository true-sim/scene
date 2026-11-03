package com.truesim.scene.audio;

/** How a sound follows its world's time scale. */
public enum TimeScaling {
    /** Playback rate and Doppler times the scale, like tape in slow motion: world sounds by default. */
    PITCH,
    /** Not at all: dialogue, UI and 2D sounds by default. */
    NONE
}
