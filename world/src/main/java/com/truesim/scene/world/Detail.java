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
 *   <li>{@link #screenError(double)}: as fine as needed for a cell never to look more than that many pixels too coarse
 *       on the source's view ({@link InterestSource#setView}), out to the {@link Range}: the bands follow the field of
 *       view and resolution, so zooming in loads finer detail.</li>
 * </ul>
 *
 * <p>Bands pick cells from the coarsest level down: a cell closer than the next finer band is replaced by its finer
 * cells, so the chosen cells cover the area exactly once, without overlapping levels.
 */
public final class Detail {

    private static final Detail FULL = new Detail(new double[0], 0, 0);

    private final double[] bands;
    private final double pixels;
    private final double errorPerMeter;

    private Detail(double[] bands, double pixels, double errorPerMeter) {
        this.bands = bands;
        this.pixels = pixels;
        this.errorPerMeter = errorPerMeter;
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
        return new Detail(distances.clone(), 0, 0);
    }

    /**
     * As fine as needed for no cell to look more than {@code pixels} too coarse: a cell is replaced by its finer cells
     * while its size, projected at its distance on the source's view, exceeds {@code pixels}.
     */
    public static Detail screenError(double pixels) {
        return screenError(pixels, 1);
    }

    /**
     * As fine as needed for no cell to look more than {@code pixels} too coarse, where a cell's geometric error is its
     * size times {@code errorPerMeter}: e.g. 0.01 for terrain whose coarser tiles deviate by about 1 % of their size.
     * {@link #screenError(double)} takes the whole size as the error, which is conservative.
     */
    public static Detail screenError(double pixels, double errorPerMeter) {
        if (!(pixels > 0) || Double.isInfinite(pixels)) {
            throw new IllegalArgumentException("The screen-space error must be positive and finite: " + pixels);
        }
        if (!(errorPerMeter > 0) || Double.isInfinite(errorPerMeter)) {
            throw new IllegalArgumentException("The error per meter must be positive and finite: " + errorPerMeter);
        }
        return new Detail(new double[0], pixels, errorPerMeter);
    }

    /** A cell's geometric error per meter of its size, for {@link #screenError(double, double)}. */
    public double errorPerMeter() {
        return errorPerMeter;
    }

    /** Whether this is {@link #screenError(double)}. */
    public boolean isScreenError() {
        return pixels > 0;
    }

    /** The allowed screen-space error in pixels; 0 unless {@link #isScreenError()}. */
    public double pixels() {
        return pixels;
    }

    /** Whether this is {@link #full()}. */
    public boolean isFull() {
        return bands.length == 0 && pixels == 0;
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
        if (isFull()) {
            return "full";
        }
        return isScreenError() ? "screen error " + pixels + " px" : "bands " + Arrays.toString(bands);
    }
}
