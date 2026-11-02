package com.truesim.scene.audio;

/** How a sound is placed around the listener. */
public enum Spatial {
    /** Not placed: panned as the clip is (UI, music, beds). */
    TWO_D,
    /** Attenuated by distance and panned (or VBAP on speakers). */
    THREE_D,
    /** Attenuated and spatialized through the HRTF when headphones are on; else as {@link #THREE_D}. */
    HRTF
}
