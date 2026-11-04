package com.truesim.scene.core;

/**
 * Marks a node as selected (an editor's selection, a strategy game's units): data, saved with the scene as
 * {@code truesim.selected}. The engine's {@code SceneSelection} sets and clears it from clicks and marquees, and the
 * selection outline draws it; game code may set it too.
 *
 * <pre>{@code
 * scene.components().set(unit, Selected.TYPE, Selected.YES);
 * boolean picked = scene.components().has(unit, Selected.TYPE);
 * }</pre>
 */
public final class Selected {

    /** The selected component type. */
    public static final ComponentType<Selected> TYPE = ComponentType.of("truesim.selected", Selected.class);

    /** The one value: selected. */
    public static final Selected YES = new Selected();

    private Selected() {
    }

    @Override
    public String toString() {
        return "selected";
    }
}
