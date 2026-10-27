package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * How much detail of a layer an interest source wants:
 *
 * <ul>
 *   <li>{@link #full()}: the finest level everywhere within the {@link Range}, e.g. collision around a player.</li>
 *   <li>{@link #bands(double...)}: finer levels nearer, coarser further out: level 0 within the first distance, level
 *       1 within the second, and so on; nothing beyond the last.</li>
 * </ul>
 *
 * <p>Bands pick cells from the coarsest level down: a cell closer than the next finer band is replaced by its finer
 * cells, so the chosen cells cover the area exactly once, without overlapping levels.
 */
public final class Detail {

    private static final Detail FULL = new Detail(new double[0]);

    private final double[] bands;

    private Detail(double[] bands) {
        this.bands = bands;
    }

    /** The finest level everywhere within the range. */
    public static Detail full() {
        return FULL;
    }

    /** Level {@code i} within {@code distances[i]}; the distances grow, and there is nothing beyond the last. */
    @AllowAllocation("set up once")
    public static Detail bands(double... distances) {
        if (distances.length == 0) {
            throw new IllegalArgumentException("Bands need at least one distance");
        }
        for (int i = 0; i < distances.length; i++) {
            if (!(distances[i] > 0) || i > 0 && distances[i] <= distances[i - 1]) {
                throw new IllegalArgumentException("Band distances must be positive and grow: "
                    + Arrays.toString(distances));
            }
        }
        return new Detail(distances.clone());
    }

    /** Whether this is {@link #full()}. */
    public boolean isFull() {
        return bands.length == 0;
    }

    /** How many bands, and so levels, this uses; 0 for {@link #full()}. */
    public int bandCount() {
        return bands.length;
    }

    /** The outer distance of band {@code level}. */
    public double band(int level) {
        return bands[level];
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return isFull() ? "full" : "bands " + Arrays.toString(bands);
    }
}
