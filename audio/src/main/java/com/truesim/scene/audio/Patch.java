package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;

/**
 * A procedural source: the DSP patch registered under {@code name} with the engine (a car's engine by RPM, wind).
 * The scene only names it and the parameters it reads (its inputs and triggers), so instances can set them; the
 * engine resolves the name.
 *
 * <pre>{@code
 * Sound.builder().root(Patch.named("v8", RPM, LOAD, SHIFT)).loop().build();
 * }</pre>
 *
 * @param name    the patch's registered name
 * @param inputs  the parameters it reads; a trigger ({@link SoundParameter#trigger}) among them fires on each
 *                {@link SoundInstance#trigger}
 * @param shaping volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Patch(String name, List<SoundParameter> inputs, Shaping shaping) implements SoundNode {

    public Patch {
        Objects.requireNonNull(name, "name");
        inputs = List.copyOf(inputs);
        Objects.requireNonNull(shaping, "shaping");
    }

    /** The patch registered as {@code name}, reading {@code inputs}. */
    public static Patch named(String name, SoundParameter... inputs) {
        return new Patch(name, List.of(inputs), Shaping.NONE);
    }

    @Override
    public Patch with(Shaping shaping) {
        return new Patch(name, inputs, shaping);
    }
}
