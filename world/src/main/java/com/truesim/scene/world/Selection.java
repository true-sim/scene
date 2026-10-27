package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.CellGrid;

/**
 * Works out which cells one interest source wants, into its {@code next} sets, reading only the source: so sources
 * select independently, in parallel if the world has a job system, before the world applies the differences in order.
 * Made once per source; its visitors are made once with it.
 */
final class Selection {

    private final InterestSource source;
    private double hysteresis;

    // The entry being selected: fields, so the visitors below are made once.
    private Levels levels;
    private Detail detail;
    private LongIntMap[] into;
    private LongIntMap[] previous;
    private int level;
    private double reach;
    private final CellGrid.CellVisitor addFull = (x, y, z) -> {
        double distance = distanceSquared(level, x, y, z);
        long key = CellGrid.key(x, y, z);
        if (distance <= square(reach) || distance <= square(reach * (1 + hysteresis)) && previous[0].get(key) >= 0) {
            into[0].put(key, 1);
        }
    };
    private final CellGrid.CellVisitor addBanded = (x, y, z) -> {
        double distance = distanceSquared(level, x, y, z);
        if (distance <= square(reach) || distance <= square(reach * (1 + hysteresis)) && held(level, x, y, z)) {
            refine(level, x, y, z);
        }
    };

    @AllowAllocation("adding an interest source")
    Selection(InterestSource source) {
        this.source = source;
    }

    /** Fills the source's {@code next} sets with the cells it wants for each layer of its profile. */
    void select(double fraction) {
        this.hysteresis = fraction;
        for (int i = 0; i < source.wanted.length; i++) {
            InterestProfile.Entry entry = source.profile().entries().get(i);
            LongIntMap[] next = source.next[i];
            for (LongIntMap cells : next) {
                cells.clear();
            }
            levels = entry.layer().levels();
            detail = entry.detail();
            into = next;
            previous = source.wanted[i];
            if (detail.isFull()) {
                level = 0;
                reach = entry.range().radius();
                forEachCellInReach(0, reach * (1 + hysteresis), addFull);
            } else if (detail.isScreenError()) {
                level = levels.count() - 1;
                reach = entry.range().radius();
                forEachCellInReach(level, reach * (1 + hysteresis), addBanded);
            } else {
                level = detail.bandCount() - 1;
                reach = detail.band(level);
                forEachCellInReach(level, reach * (1 + hysteresis), addBanded);
            }
        }
        into = null;
        previous = null;
    }

    /** Visits the cells of {@code cellLevel} whose box may lie within {@code distance} of the source. */
    private void forEachCellInReach(int cellLevel, double distance, CellGrid.CellVisitor visitor) {
        InterestSource s = source;
        boolean volumetric = levels.isVolumetric();
        levels.grid(cellLevel).forEachCell(s.x - distance, volumetric ? s.y - distance : 0, s.z - distance,
            s.x + distance, volumetric ? s.y + distance : 0, s.z + distance, visitor);
    }

    /**
     * Takes a cell, or, if the source is closer to it than the next finer band, its finer cells instead, each refined
     * the same way: the result covers the cell's area once, finest where nearest. A cell already split stays split up
     * to the hysteresis beyond the band.
     */
    private void refine(int cellLevel, int x, int y, int z) {
        long key = CellGrid.key(x, y, z);
        if (cellLevel > 0) {
            // Split nearer than the band; within the hysteresis beyond it only if it was split before (the history is
            // looked up only there, as it is the costly part).
            double distance = distanceSquared(cellLevel, x, y, z);
            double band = band(cellLevel - 1);
            if (distance < square(band) || distance < square(band * (1 + hysteresis))
                && previous[cellLevel].get(key) < 0 && heldBelow(cellLevel, x, y, z)) {
                boolean volumetric = levels.isVolumetric();
                int r = levels.ratio(cellLevel);
                int y0 = volumetric ? r * y : 0;
                int y1 = volumetric ? r * y + r - 1 : 0;
                for (int cy = y0; cy <= y1; cy++) {
                    for (int cz = r * z; cz < r * z + r; cz++) {
                        for (int cx = r * x; cx < r * x + r; cx++) {
                            refine(cellLevel - 1, cx, cy, cz);
                        }
                    }
                }
                return;
            }
        }
        into[cellLevel].put(key, 1);
    }

    /**
     * The distance within which cells of level {@code finer} replace the next coarser ones: the profile's band, or for
     * screen-space error the distance at which a coarser cell would look as big as the allowed error.
     */
    private double band(int finer) {
        if (detail.isScreenError()) {
            return levels.grid(finer + 1).size() * detail.errorPerMeter() * source.projection / detail.pixels();
        }
        return detail.band(finer);
    }

    /** Whether the source held this cell, or finer cells within it, before. */
    private boolean held(int cellLevel, int x, int y, int z) {
        return previous[cellLevel].get(CellGrid.key(x, y, z)) >= 0 || heldBelow(cellLevel, x, y, z);
    }

    /** Whether the source held finer cells within this cell before. */
    private boolean heldBelow(int cellLevel, int x, int y, int z) {
        if (cellLevel == 0) {
            return false;
        }
        boolean volumetric = levels.isVolumetric();
        int r = levels.ratio(cellLevel);
        int y0 = volumetric ? r * y : 0;
        int y1 = volumetric ? r * y + r - 1 : 0;
        for (int cy = y0; cy <= y1; cy++) {
            for (int cz = r * z; cz < r * z + r; cz++) {
                for (int cx = r * x; cx < r * x + r; cx++) {
                    if (held(cellLevel - 1, cx, cy, cz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The squared distance from the source to a cell's box; flat levels ignore height. */
    private double distanceSquared(int cellLevel, int x, int y, int z) {
        CellGrid grid = levels.grid(cellLevel);
        double size = grid.size();
        InterestSource s = source;
        double dx = axisDistance(s.x, grid.cellMin(x), size);
        double dy = levels.isVolumetric() ? axisDistance(s.y, grid.cellMin(y), size) : 0;
        double dz = axisDistance(s.z, grid.cellMin(z), size);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }

    private static double axisDistance(double position, double min, double size) {
        return position < min ? min - position : Math.max(0, position - (min + size));
    }
}
