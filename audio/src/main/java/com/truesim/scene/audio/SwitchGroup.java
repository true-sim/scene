package com.truesim.scene.audio;

import java.util.Objects;

/**
 * A choice the game sets per playing sound from an enum ({@code instance.set(SURFACE, Surface.GRAVEL)}), which
 * {@link Switch} nodes pick their child by. Declared once as a typed constant with its default; equal by value.
 *
 * <pre>{@code
 * enum Surface { DIRT, GRAVEL, WOOD }
 * static final SwitchGroup<Surface> SURFACE = SwitchGroup.of("surface", Surface.class, Surface.DIRT);
 * }</pre>
 *
 * @param name     its name, unique within a sound
 * @param type     the enum of its values
 * @param fallback its value until set
 * @param <E>      the enum of its values
 */
public record SwitchGroup<E extends Enum<E>>(String name, Class<E> type, E fallback) {

    public SwitchGroup {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(fallback, "fallback");
    }

    /** A switch called {@code name} over {@code type}'s values, {@code fallback} until set. */
    public static <E extends Enum<E>> SwitchGroup<E> of(String name, Class<E> type, E fallback) {
        return new SwitchGroup<>(name, type, fallback);
    }
}
