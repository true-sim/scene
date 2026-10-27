package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.CellGrid;
import java.util.Arrays;

/**
 * The detail levels of a {@link Layer}: level 0 is the finest, each further level has larger cells, a whole multiple
 * of the level below, so finer cells tile a coarser one exactly. A coarse level covers what its finer levels cover with
 * less data, e.g. a terrain tile pyramid, HLOD proxies standing in for groups of cells, or a very low-poly horizon.
 *
 * <pre>{@code
 * Levels.grid(64, 6);                        // terrain: 64 m tiles, then 128 m, ... up to 2048 m
 * Levels.sizes(64, 256, 4096, 16384);        // the same, with steps of your choice (4x, 16x, 4x)
 * Levels.hlod(32, 4);                        // props: 32 m cells, then merged proxies for 64, 128 and 256 m
 * Levels.single(128);                        // collision: one level of 128 m cells
 * Levels.grid(256, 4).volumetric();          // space: cells in all three dimensions
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

    /** At most this many levels. */
    public static final int MAX_LEVELS = 16;

    private final Kind kind;
    private final CellGrid[] grids;
    private final int[] ratios;
    private final long[] scales;
    private final boolean volumetric;

    @AllowAllocation("set up once")
    private Levels(Kind kind, double[] sizes, boolean volumetric) {
        if (sizes.length < 1 || sizes.length > MAX_LEVELS) {
            throw new IllegalArgumentException("A layer has 1 to " + MAX_LEVELS + " levels: " + sizes.length);
        }
        this.kind = kind;
        this.volumetric = volumetric;
        this.grids = new CellGrid[sizes.length];
        this.ratios = new int[sizes.length];
        this.scales = new long[sizes.length];
        ratios[0] = 1;
        scales[0] = 1;
        for (int level = 0; level < sizes.length; level++) {
            grids[level] = new CellGrid(sizes[level]);
            if (level > 0) {
                double ratio = sizes[level] / sizes[level - 1];
                if (!(ratio >= 2) || ratio != Math.rint(ratio) || ratio > 1 << 10) {
                    throw new IllegalArgumentException("Each level must be 2 to 1024 times as large as the one below: "
                        + Arrays.toString(sizes));
                }
                ratios[level] = (int) ratio;
                scales[level] = scales[level - 1] * ratios[level];
            }
        }
    }

    @AllowAllocation("set up once")
    private static double[] doubling(double size, int count) {
        if (count < 1 || count > MAX_LEVELS) {
            throw new IllegalArgumentException("A layer has 1 to " + MAX_LEVELS + " levels: " + count);
        }
        double[] sizes = new double[count];
        for (int level = 0; level < count; level++) {
            sizes[level] = size * (1L << level);
        }
        return sizes;
    }

    /** {@code count} levels of the same data, the finest with cells {@code size} wide, each twice the one below. */
    public static Levels grid(double size, int count) {
        return new Levels(Kind.GRID, doubling(size, count), false);
    }

    /**
     * Levels of the same data with the given cell sizes, finest first; each must be a whole multiple (2 to 1024) of the
     * one below, e.g. {@code sizes(64, 256, 4096, 16384)} for detailed tiles, then coarser ones, then a low-poly
     * horizon.
     */
    @AllowAllocation("set up once")
    public static Levels sizes(double... sizes) {
        return new Levels(Kind.GRID, sizes.clone(), false);
    }

    /** Cells {@code size} wide and {@code count - 1} levels of merged proxies above them, each twice the one below. */
    public static Levels hlod(double size, int count) {
        return new Levels(Kind.HLOD, doubling(size, count), false);
    }

    /** Cells of the first size and merged proxies at the given larger sizes, as for {@link #sizes(double...)}. */
    @AllowAllocation("set up once")
    public static Levels hlodSizes(double... sizes) {
        return new Levels(Kind.HLOD, sizes.clone(), false);
    }

    /** One level of cells {@code size} wide. */
    @AllowAllocation("set up once")
    public static Levels single(double size) {
        return new Levels(Kind.SINGLE, new double[] {size}, false);
    }

    /**
     * The same levels with cells stacked in height too, for worlds that stream in three dimensions (space, caves,
     * tall cities). Levels are flat by default: cells cover the x-z plane, all at y = 0, and distances ignore height.
     */
    @AllowAllocation("set up once")
    public Levels volumetric() {
        double[] sizes = new double[grids.length];
        for (int level = 0; level < sizes.length; level++) {
            sizes[level] = grids[level].size();
        }
        return new Levels(kind, sizes, true);
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
        check(level);
        return grids[level];
    }

    /** How many cells of the level below fit along each axis of a cell of {@code level} (1 for level 0). */
    public int ratio(int level) {
        check(level);
        return ratios[level];
    }

    /** How many level-0 cells fit along each axis of a cell of {@code level}. */
    public long scale(int level) {
        check(level);
        return scales[level];
    }

    private void check(int level) {
        if (level < 0 || level >= grids.length) {
            throw new IllegalArgumentException("No level " + level + " in " + grids.length + " levels");
        }
    }
}
