package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;

/**
 * Plays its children one after the other, each starting where the one before ends (as Unreal's concatenator).
 *
 * @param children the children, in order
 * @param shaping  volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Sequence(List<SoundNode> children, Shaping shaping) implements SoundNode {

    public Sequence {
        children = List.copyOf(children);
        Objects.requireNonNull(shaping, "shaping");
    }

    /** {@code children} in order. */
    public static Sequence of(SoundNode... children) {
        return new Sequence(List.of(children), Shaping.NONE);
    }

    @Override
    public Sequence with(Shaping shaping) {
        return new Sequence(children, shaping);
    }
}
