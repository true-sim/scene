package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.CellGrid;

/**
 * The detail levels of a {@link Layer}: level 0 is the finest, each further level has cells twice as wide. A coarse
 * level covers what its finer levels cover with less data, e.g. a terrain tile pyramid or HLOD proxies standing in for
 * groups of cells.
 *
 * <pre>{@code
 * Levels.grid(64, 6);    // terrain: 64 m tiles, then 128 m, ... up to 2048 m
 * Levels.hlod(32, 4);    // props: 32 m cells, then merged proxies for 64, 128 and 256 m
 * Levels.single(128);    // collision: one level of 128 m cells
 * Levels.grid(256, 4).volumetric(); // space: cells in all three dimensions
 * }</pre>
 */
public final class Levels {

    /** What the coarser levels hold. */
    public enum Kind {
        /** The same kind of data at lower resolution, e.g. terrain tiles. */
        GRID,
        /** Merged proxies standing in for everything in the finer cells below. */
        HLOD,
        /** One level only. */
        SINGLE
    }

    /** At most this many levels: level 15 cells are 32768 times as wide as level 0 ones. */
    public static final int MAX_LEVELS = 16;

    private final Kind kind;
    private final CellGrid[] grids;
    private final boolean volumetric;

    @AllowAllocation("set up once")
    private Levels(Kind kind, double size, int count, boolean volumetric) {
        if (count < 1 || count > MAX_LEVELS) {
            throw new IllegalArgumentException("A layer has 1 to " + MAX_LEVELS + " levels: " + count);
        }
        this.kind = kind;
        this.volumetric = volumetric;
        this.grids = new CellGrid[count];
        for (int level = 0; level < count; level++) {
            grids[level] = new CellGrid(size * (1 << level));
        }
    }

    /** {@code count} levels of the same data, the finest with cells {@code size} wide. */
    public static Levels grid(double size, int count) {
        return new Levels(Kind.GRID, size, count, false);
    }

    /** Cells {@code size} wide and {@code count - 1} levels of merged proxies above them. */
    public static Levels hlod(double size, int count) {
        return new Levels(Kind.HLOD, size, count, false);
    }

    /** One level of cells {@code size} wide. */
    public static Levels single(double size) {
        return new Levels(Kind.SINGLE, size, 1, false);
    }

    /**
     * The same levels with cells stacked in height too, for worlds that stream in three dimensions (space, caves,
     * tall cities). Levels are flat by default: cells cover the x-z plane, all at y = 0, and distances ignore height.
     */
    public Levels volumetric() {
        return new Levels(kind, grids[0].size(), grids.length, true);
    }

    /** Whether cells are stacked in height; see {@link #volumetric()}. */
    public boolean isVolumetric() {
        return volumetric;
    }

    public Kind kind() {
        return kind;
    }

    public int count() {
        return grids.length;
    }

    /** The cells of {@code level}. */
    public CellGrid grid(int level) {
        if (level < 0 || level >= grids.length) {
            throw new IllegalArgumentException("No level " + level + " in " + grids.length + " levels");
        }
        return grids[level];
    }
}
