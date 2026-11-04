package com.truesim.scene.core;

/**
 * Marks a node a pointer is over (runtime state, never saved: {@code truesim.hovered}), set and cleared by the
 * engine's scene interaction as pointers enter and leave nodes, so systems that only read the scene can follow hover:
 * the hover outline, a game's own highlight. The value is the one {@link #YES}.
 */
public final class Hovered {

    /** The hovered component type (transient). */
    public static final ComponentType<Hovered> TYPE = ComponentType.transientOf("truesim.hovered", Hovered.class);

    /** The one value: hovered. */
    public static final Hovered YES = new Hovered();

    private Hovered() {
    }

    @Override
    public String toString() {
        return "hovered";
    }
}
