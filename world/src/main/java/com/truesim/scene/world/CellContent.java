package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;

/**
 * The scene content of one loaded cell: a {@link CellLoader} creates nodes through it, and it remembers them, so
 * unloading the cell removes exactly what it added. Used on the main thread only.
 */
public final class CellContent {

    private final SceneGraph scene;
    private Cell cell;
    private long[] nodes = new long[8];
    private int count;

    CellContent(SceneGraph scene) {
        this.scene = scene;
    }

    void reset(Cell newCell) {
        this.cell = newCell;
        this.count = 0;
    }

    /** The scene to fill. Nodes made directly in it are not removed with the cell unless {@link #adopt}ed. */
    public SceneGraph scene() {
        return scene;
    }

    /** The cell this content belongs to. */
    public Cell cell() {
        return cell;
    }

    /** A new node without a parent, removed with the cell. */
    public long create() {
        return adopt(scene.create());
    }

    /** A new node under {@code parent}, removed with the cell. */
    public long create(long parent) {
        return adopt(scene.create(parent));
    }

    /** Makes {@code node} part of this cell, so it is removed with it; returns it. */
    @AllowAllocation("growing")
    public long adopt(long node) {
        if (count == nodes.length) {
            nodes = Arrays.copyOf(nodes, count * 2);
        }
        nodes[count++] = node;
        return node;
    }

    /** How many nodes belong to this cell. */
    public int nodeCount() {
        return count;
    }

    /** Node {@code index} of this cell, in the order they were added. */
    public long node(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
        return nodes[index];
    }

    /** Removes every node of this cell still in the scene (children go with their parents). */
    public void removeAll() {
        for (int i = count - 1; i >= 0; i--) {
            if (scene.contains(nodes[i])) {
                scene.remove(nodes[i]);
            }
        }
        count = 0;
    }
}
