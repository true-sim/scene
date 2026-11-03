package com.truesim.scene.audio;

/** Whether walls between a sound and its listeners lower and muffle it (AU13): chosen per sound. */
public enum Occlusion {
    /** Not occluded: UI, music, the player's own sounds, most small sounds. */
    NONE,
    /** Rays through the world's {@code AudioWorld}, budgeted and smoothed. */
    RAYS
}
