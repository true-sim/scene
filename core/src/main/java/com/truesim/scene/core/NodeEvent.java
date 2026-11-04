package com.truesim.scene.core;

/**
 * A pointer event on a scene node, as its router (the engine's scene interaction) hands it to the listeners of the
 * nodes on its route, its own and front ends' alike ({@link RoutedListener}: the UI's): one route, so
 * {@link #consume} by any listener stops it for all, and its pass-through along the ray. Engine-independent: the
 * contract a front end (the UI toolkit) needs without depending on the engine. Reused by the router: read it during the
 * call. Focus events have no pointer: their pointer fields read as none ({@link #hasPointer}).
 */
public interface NodeEvent {

    /** The phases of a bubbling event's route. */
    enum Phase {
        /** From the root down to the target's parent, for capture listeners. */
        CAPTURE,
        /** At the target. */
        TARGET,
        /** From the target's parent up to the root. */
        BUBBLE
    }

    SceneEventType type();

    Phase phase();

    /** The node it is for: the node hit (before bubbling). */
    long target();

    /** The node whose listeners run now. */
    long currentNode();

    /** Whether it has a pointer: every type but the focus events. */
    boolean hasPointer();

    /** Its pointer's id (the front end's own: the UI hands its pointers on), or -1. */
    int pointerId();

    /** The local player of its pointer, or -1. */
    int player();

    /** Where its pointer is across, in window pixels; NaN without a pointer. */
    float x();

    /** Where its pointer is down; NaN without a pointer. */
    float y();

    /** The button a press or release is of (1 primary, 2 secondary, 4 middle), else 0. */
    int button();

    /** The buttons held. */
    int buttons();

    /** The modifiers held (1 Shift, 2 Ctrl, 4 Alt, 8 Meta). */
    int modifiers();

    /** A click's count: 1, 2 for a double click; 0 for other types. */
    int clickCount();

    float scrollX();

    float scrollY();

    /** Where the pointer's ray hit the target this frame into {@code out[0..2]}; false if it did not. */
    boolean hitPoint(double[] out);

    /** How far along the ray the target was hit, or infinity. */
    double hitDistance();

    /** Stops it: no further listeners of any kind, and no pass-through to what is behind along the ray. */
    void consume();

    boolean consumed();

    /** Captures the pointer to the current node: its moves and releases go there until the primary release. */
    void capturePointer();

    /** Releases the pointer's capture. */
    void releasePointer();
}
