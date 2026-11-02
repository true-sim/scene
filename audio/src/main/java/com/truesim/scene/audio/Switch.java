package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plays the child of the instance's value of a switch group when it starts (footsteps by surface), or
 * {@code otherwise} for values without a child of their own.
 *
 * <pre>{@code
 * Switch.on(SURFACE).when(Surface.GRAVEL, gravel).when(Surface.WOOD, wood).otherwise(dirt)
 * }</pre>
 *
 * @param group     the switch group read
 * @param cases     a child per value
 * @param otherwise the child of other values; {@code null} for none (values without a case are then a problem)
 * @param shaping   volume, pitch, delay and curves
 * @param <E>       the enum of the group's values
 */
@AllowAllocation("set up once")
public record Switch<E extends Enum<E>>(SwitchGroup<E> group, List<Case<E>> cases, SoundNode otherwise,
        Shaping shaping) implements SoundNode {

    /**
     * The child of one value.
     *
     * @param value the value
     * @param node  its child
     * @param <E>   the enum of the values
     */
    public record Case<E extends Enum<E>>(E value, SoundNode node) {
        public Case {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(node, "node");
        }
    }

    public Switch {
        Objects.requireNonNull(group, "group");
        cases = List.copyOf(cases);
        Objects.requireNonNull(shaping, "shaping");
    }

    /** A switch on {@code group}, to give cases. */
    public static <E extends Enum<E>> Switch<E> on(SwitchGroup<E> group) {
        return new Switch<>(group, List.of(), null, Shaping.NONE);
    }

    /** A copy playing {@code node} for {@code value}. */
    public Switch<E> when(E value, SoundNode node) {
        List<Case<E>> list = new ArrayList<>(cases);
        list.add(new Case<>(value, node));
        return new Switch<>(group, list, otherwise, shaping);
    }

    /** A copy playing {@code clip} for {@code value}. */
    public Switch<E> when(E value, AssetRef<AudioClip> clip) {
        return when(value, Play.clip(clip));
    }

    /** A copy playing {@code node} for values without a case. */
    public Switch<E> otherwise(SoundNode node) {
        return new Switch<>(group, cases, Objects.requireNonNull(node, "node"), shaping);
    }

    /** The child for {@code value} (an ordinal of the group's enum): its case's, else {@code otherwise}, maybe null. */
    public SoundNode child(int value) {
        for (Case<E> c : cases) {
            if (c.value().ordinal() == value) {
                return c.node();
            }
        }
        return otherwise;
    }

    @Override
    public Switch<E> with(Shaping shaping) {
        return new Switch<>(group, cases, otherwise, shaping);
    }
}
