package com.truesim.scene.world;

import com.truesim.scene.core.CellGrid;

/**
 * One cell of a layer at one level: where a {@link CellLoader} reads from. Its world-space box starts at
 * ({@link #minX()}, {@link #minY()}, {@link #minZ()}) and is {@link #size()} wide.
 */
public record Cell(Layer<?> layer, int level, int x, int y, int z) {

    /** The grid of this cell's level. */
    public CellGrid grid() {
        return layer.levels().grid(level);
    }

    public double size() {
        return grid().size();
    }

    public double minX() {
        return grid().cellMin(x);
    }

    public double minY() {
        return grid().cellMin(y);
    }

    public double minZ() {
        return grid().cellMin(z);
    }
}
