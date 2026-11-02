package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A procedural source: the DSP patch registered under {@code name} with the engine (a car's engine by RPM, wind).
 * The scene only names it; the engine resolves the name.
 *
 * @param name    the patch's registered name
 * @param shaping volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Patch(String name, Shaping shaping) implements SoundNode {

    public Patch {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(shaping, "shaping");
    }

    /** The patch registered as {@code name}. */
    public static Patch named(String name) {
        return new Patch(name, Shaping.NONE);
    }

    @Override
    public Patch with(Shaping shaping) {
        return new Patch(name, shaping);
    }
}
