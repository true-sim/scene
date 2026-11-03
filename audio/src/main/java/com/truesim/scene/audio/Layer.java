package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;

/**
 * Plays all its children at once.
 *
 * @param children the children
 * @param shaping  volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Layer(List<SoundNode> children, Shaping shaping) implements SoundNode {

    public Layer {
        children = List.copyOf(children);
        Objects.requireNonNull(shaping, "shaping");
    }

    /** All of {@code children} together. */
    public static Layer of(SoundNode... children) {
        return new Layer(List.of(children), Shaping.NONE);
    }

    @Override
    public Layer with(Shaping shaping) {
        return new Layer(children, shaping);
    }
}
