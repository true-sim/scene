package com.truesim.scene.core;

/**
 * What a {@link NodeEvent} reports: the pointer interaction set UI elements get, for scene nodes. Listeners register
 * for a mask of types ({@link #bit()} or'ed). Types that {@linkplain #bubbles() bubble} run a capture phase (root to
 * target), the target, and a bubble phase (target to root) along the scene graph; the others go to their target only,
 * as DOM's {@code mouseenter}: each node entered gets its own. The engine's scene interaction routes them; front ends
 * (the UI) take part through {@link RoutedListener}s.
 */
public enum SceneEventType {
    /** A pointer came over the node or one of its descendants (target only, one per node entered). */
    ENTER(false),
    /** A pointer left the node and its descendants (target only). */
    EXIT(false),
    /** A pointer moved over the node (or the node holds the pointer's capture). */
    MOVE(true),
    /** A pointer button went down: {@link NodeEvent#button()}. Unconsumed, it passes to the next hit along the ray. */
    PRESS(true),
    /** A pointer button went up: to the node that captured the pointer on its press. */
    RELEASE(true),
    /** A primary press and release on the same node: {@link NodeEvent#clickCount()} (2 for a double click). */
    CLICK(true),
    /** A press held still for the long press delay. Consumed, it suppresses the click. */
    LONG_PRESS(true),
    /** A context menu is asked for: the secondary button, or a long touch no listener consumed. */
    CONTEXT(true),
    /** The wheel or a scroll gesture: {@link NodeEvent#scrollX()}, {@link NodeEvent#scrollY()}. Passes through. */
    WHEEL(true),
    /** A pressed pointer moved past the drag threshold; a listener may start a drag. */
    DRAG_START(true),
    /** The dragging pointer moved. */
    DRAG(true),
    /** The dragging pointer was released, or the drag cancelled. */
    DRAG_END(true),
    /** A drag's payload came over a node that accepted it (target only). */
    DROP_ENTER(false),
    /** A drag's payload is over the node: a listener may accept it. Passes through. */
    DROP_OVER(true),
    /** The payload left the node that accepted it (target only). */
    DROP_LEAVE(false),
    /** The payload was dropped on the node that accepted it (target only). */
    DROP(false),
    /** The node got the scene's focus (a primary press on it); bubbles, like DOM's {@code focusin}. No pointer. */
    FOCUS_IN(true),
    /** The node lost the scene's focus; bubbles. No pointer. */
    FOCUS_OUT(true),
    /** A pointer rested on the node for the tooltip delay, no button held: show a tooltip. Bubbles to the nearest. */
    TOOLTIP(true);

    private static final SceneEventType[] ALL = values();

    /** Every type. */
    public static final int ALL_MASK = (1 << ALL.length) - 1;

    private final boolean bubbles;

    SceneEventType(boolean bubbles) {
        this.bubbles = bubbles;
    }

    /** This type's bit in listener masks. */
    public int bit() {
        return 1 << ordinal();
    }

    /** Whether events of this type run capture and bubble phases. */
    public boolean bubbles() {
        return bubbles;
    }

    /** The type of an ordinal, without copying {@code values()}. */
    public static SceneEventType of(int ordinal) {
        return ALL[ordinal];
    }

    /** How many types exist. */
    public static int count() {
        return ALL.length;
    }
}
