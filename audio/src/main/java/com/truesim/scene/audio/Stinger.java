package com.truesim.scene.audio;

import java.util.Objects;

/**
 * A short musical accent played over the music on a boundary (a level-up flourish): a typed constant; the score
 * gives its clip, quantize and duck. Equal by name.
 *
 * @param name its name
 */
public record Stinger(String name) {

    public Stinger {
        Objects.requireNonNull(name, "name");
    }

    /** The stinger called {@code name}. */
    public static Stinger of(String name) {
        return new Stinger(name);
    }
}
