package com.truesim.scene.core;

/**
 * Nodes that changed together, as one entry of a change log: a {@link SceneBatch} attached, a subtree removed, nodes
 * made or components set in one call. {@link NodeChange.Visitor#changed(ChangeBatch)} receives it, so a consumer can
 * take the whole batch in one pass (register many instances at once, say) instead of node by node; the default visits
 * each node in turn, as if logged one by one.
 *
 * <pre>{@code
 * scene.changesSince(since, new NodeChange.Visitor() {
 *     public void changed(long node, int changes) { ... }        // nodes changed alone
 *     public void changed(ChangeBatch batch) {                  // nodes changed together
 *         for (int i = 0; i < batch.size(); i++) {
 *             long node = batch.node(i);
 *             int changes = batch.changes(i);
 *         }
 *     }
 * });
 * }</pre>
 *
 * <p>A view into the log, valid only during the call that hands it over: read it there, do not keep it. Reading
 * allocates nothing. Each node is in a batch once; its changes are its own (one node of an attached batch may have
 * bounds and another not).
 */
public final class ChangeBatch {

    private long[] nodes;
    private int[] changes;
    private int start;
    private int size;
    private int mask;

    ChangeBatch() {
    }

    /** Points this view at {@code size} records from {@code start} of a ring of {@code mask + 1} (a power of two). */
    void set(long[] nodes, int[] changes, int start, int size) {
        this.nodes = nodes;
        this.changes = changes;
        this.start = start;
        this.size = size;
        this.mask = nodes.length - 1;
    }

    /** How many nodes changed together. */
    public int size() {
        return size;
    }

    /** Node {@code i} (0 to {@link #size()} - 1). */
    public long node(int i) {
        return nodes[(start + check(i)) & mask];
    }

    /** How node {@code i} changed: {@link NodeChange} bits for the scene's log, a store's bits for a component log. */
    public int changes(int i) {
        return changes[(start + check(i)) & mask];
    }

    private int check(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i);
        }
        return i;
    }
}
