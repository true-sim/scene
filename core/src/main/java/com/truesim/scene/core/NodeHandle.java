package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;

/**
 * Nodes are named by {@code long} handles: a slot index and the slot's generation. When a node is removed its slot is
 * reused with the next generation, so an old handle to it is recognizably stale instead of naming whatever moved in.
 * {@link #NONE} (0) names no node. Handles are plain values: storing, comparing and passing them allocates nothing.
 *
 * <pre>{@code
 * long node = scene.create();
 * NodeHandle.index(node);       // the slot, for arrays indexed like the scene's own
 * NodeHandle.toString(node);    // "node 5#1", for logs
 * }</pre>
 */
public final class NodeHandle {

    /** No node. */
    public static final long NONE = 0;

    private NodeHandle() {
    }

    /**
     * The handle of slot {@code index} (from 0) in generation {@code generation} (from 1; generation 0 is reserved, so
     * no handle equals {@link #NONE}).
     */
    public static long of(int index, int generation) {
        if (index < 0 || generation < 1) {
            throw new IllegalArgumentException("Not a node: index " + index + ", generation " + generation);
        }
        return (long) generation << Integer.SIZE | index;
    }

    /** The slot of {@code handle}. */
    public static int index(long handle) {
        return (int) handle;
    }

    /** The generation of {@code handle}: how often its slot was reused, from 1. */
    public static int generation(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    /** {@code "node 5#1"} (slot 5, generation 1), or {@code "no node"}. */
    @AllowAllocation("diagnostics")
    public static String toString(long handle) {
        return handle == NONE ? "no node" : "node " + index(handle) + "#" + generation(handle);
    }
}
