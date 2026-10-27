package com.truesim.scene.core;

/**
 * What changed about a node, as bits of the {@code changes} that {@link SceneGraph#changesSince} reports. One report
 * can carry several bits, e.g. {@code CREATED | TRANSFORM} for a node created and placed in the same frame.
 */
public final class NodeChange {

    /** The node was created. */
    public static final int CREATED = 1;
    /** The node was removed; its handle is stale now. Reported alone. */
    public static final int REMOVED = 1 << 1;
    /** The node's world transform changed, by its own or an ancestor's transform or by a new parent. */
    public static final int TRANSFORM = 1 << 2;
    /** The node got a new parent. */
    public static final int PARENT = 1 << 3;
    /** The node's world bounds changed: it moved, or its local bounds changed. */
    public static final int BOUNDS = 1 << 4;
    /** The node's layers changed. */
    public static final int LAYERS = 1 << 5;
    /** The node's {@link Mobility} changed. */
    public static final int MOBILITY = 1 << 6;
    /** The node's group changed; see {@link SceneGraph#setGroup}. */
    public static final int GROUP = 1 << 7;
    /**
     * The node came from a prepared {@link SceneBatch} and has not changed since: the spatial index adopted the
     * batch's tree for it. Reported together with {@link #CREATED}.
     */
    public static final int PREPARED = 1 << 8;

    private NodeChange() {
    }

    /** Whether {@code changes} has any of the bits in {@code mask}. */
    public static boolean has(int changes, int mask) {
        return (changes & mask) != 0;
    }

    /** Receives changed nodes. */
    @FunctionalInterface
    public interface Visitor {

        /** {@code node} changed in the ways {@code changes} lists ({@link NodeChange} bits). */
        void changed(long node, int changes);
    }
}
