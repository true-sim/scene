package com.truesim.scene.effects;

/** The frame an emitter's particles live in. */
public enum Space {
    /** Particles stay where they were born: smoke behind a moving car. */
    WORLD,
    /** Particles move with the emitter's node or joint: a ring around a rotating orb. */
    LOCAL
}
