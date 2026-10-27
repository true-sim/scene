package com.truesim.scene.core;

/**
 * Divides the world into cubic cells of one size, for streaming: which cell a position is in, a cell's bounds, the
 * cells a box overlaps, and cells packed into {@code long} keys for maps and change logs. Positions stay {@code double};
 * cells only say where data lives. A world uses one grid per streaming level, each level typically twice as coarse.
 *
 * <pre>{@code
 * CellGrid grid = new CellGrid(128);
 * int cx = grid.cell(position.x());
 * long key = CellGrid.key(cx, grid.cell(position.y()), grid.cell(position.z()));
 * grid.forEachCell(minX, minY, minZ, maxX, maxY, maxZ, (x, y, z) -> request(CellGrid.key(x, y, z)));
 * }</pre>
 *
 * <p>Keys hold cell coordinates from {@link #MIN_CELL} to {@link #MAX_CELL} on each axis: with 128 m cells that is
 * over 100,000 km in every direction.
 */
public final class CellGrid {

    /** The lowest cell coordinate a key can hold. */
    public static final int MIN_CELL = -(1 << 20);
    /** The highest cell coordinate a key can hold. */
    public static final int MAX_CELL = (1 << 20) - 1;

    private static final int BITS = 21;
    private static final long MASK = (1L << BITS) - 1;

    private final double size;
    private final double inverse;

    /** A grid of cells {@code size} wide on every axis. */
    public CellGrid(double size) {
        if (!(size > 0) || Double.isInfinite(size)) {
            throw new IllegalArgumentException("A cell size must be positive and finite: " + size);
        }
        this.size = size;
        this.inverse = 1 / size;
    }

    public double size() {
        return size;
    }

    /** The cell coordinate of {@code position} on one axis: cell {@code c} spans {@code [c * size, (c + 1) * size)}. */
    public int cell(double position) {
        return (int) Math.floor(position * inverse);
    }

    /** Where cell {@code cell} starts on one axis. */
    public double cellMin(int cell) {
        return cell * size;
    }

    /** Receives cells. */
    @FunctionalInterface
    public interface CellVisitor {

        void visit(int x, int y, int z);
    }

    /**
     * Visits every cell the box overlaps, x fastest, then y, then z, and returns how many. A box ending exactly on a
     * cell boundary does not reach into the next cell.
     */
    public int forEachCell(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
        CellVisitor visitor) {
        int x0 = cell(minX);
        int y0 = cell(minY);
        int z0 = cell(minZ);
        int x1 = last(minX, maxX);
        int y1 = last(minY, maxY);
        int z1 = last(minZ, maxZ);
        int count = 0;
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    visitor.visit(x, y, z);
                    count++;
                }
            }
        }
        return count;
    }

    /** The last cell a span reaches: one before {@code max}'s cell when {@code max} lies exactly on its start. */
    private int last(double min, double max) {
        int cell = cell(max);
        return max > min && cellMin(cell) == max ? cell - 1 : cell;
    }

    // ---------------------------------------------------------------- keys

    /** Packs cell ({@code x}, {@code y}, {@code z}) into one {@code long}: 21 bits per axis. */
    public static long key(int x, int y, int z) {
        check(x);
        check(y);
        check(z);
        return (x & MASK) << (2 * BITS) | (y & MASK) << BITS | z & MASK;
    }

    private static void check(int cell) {
        if (cell < MIN_CELL || cell > MAX_CELL) {
            throw new IllegalArgumentException("Cell coordinate out of range: " + cell);
        }
    }

    public static int keyX(long key) {
        return signed(key >>> (2 * BITS));
    }

    public static int keyY(long key) {
        return signed(key >>> BITS);
    }

    public static int keyZ(long key) {
        return signed(key);
    }

    /** The low 21 bits of {@code bits} as a signed number. */
    private static int signed(long bits) {
        return (int) ((bits & MASK) << (Long.SIZE - BITS) >> (Long.SIZE - BITS));
    }
}
