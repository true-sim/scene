package com.truesim.scene.audio;

import java.util.Objects;

/**
 * A state of the music (explore, combat): a typed constant the game asks for; the score says what plays in it and
 * how to get there from the others. Equal by name.
 *
 * @param name its name
 */
public record MusicState(String name) {

    /** Any state, for transitions that apply from everywhere. */
    public static final MusicState ANY = new MusicState("*");

    public MusicState {
        Objects.requireNonNull(name, "name");
    }

    /** The state called {@code name}. */
    public static MusicState of(String name) {
        return new MusicState(name);
    }
}
