package com.truesim.scene.collision;

import com.truesim.common.collision.HeightfieldShape;
import com.truesim.common.math.Vec3;
import com.truesim.scene.world.CellState;
import com.truesim.scene.world.Layer;
import com.truesim.scene.world.Levels;
import com.truesim.scene.world.World;

/**
 * The ground height at a point, from the terrain of a collision layer's loaded cells: e.g. to place spawns, items or
 * AI on the ground, on a server too. Uses the finest loaded level covering the point, and the same two triangles per
 * heightfield cell as collision, so the height is where a ray straight down would hit the terrain. Allocation-free;
 * use it on the main thread, like the world.
 *
 * <pre>{@code
 * TerrainHeights ground = new TerrainHeights(world, COLLISION);
 * double y = ground.heightAt(x, z);   // NaN where no terrain has loaded
 * }</pre>
 */
public final class TerrainHeights {

    private final World world;
    private final Layer<CellColliders> layer;

    /** Heights from {@code layer}'s cells in {@code world}; the layer's levels must be flat (not volumetric). */
    public TerrainHeights(World world, Layer<CellColliders> layer) {
        if (layer.levels().isVolumetric()) {
            throw new IllegalArgumentException("Terrain heights need flat levels: " + layer);
        }
        this.world = world;
        this.layer = layer;
    }

    /** The terrain height at ({@code x}, {@code z}), or {@code NaN} where no loaded cell's terrain covers it. */
    public double heightAt(double x, double z) {
        Levels levels = layer.levels();
        for (int level = 0; level < levels.count(); level++) {
            int cx = levels.grid(level).cell(x);
            int cz = levels.grid(level).cell(z);
            if (world.state(layer, level, cx, 0, cz) == CellState.LOADED) {
                CellColliders cell = world.data(layer, level, cx, 0, cz);
                if (cell.terrain() != null) {
                    double height = sample(cell.terrain(), cell.terrainOrigin(), x, z);
                    if (!Double.isNaN(height)) {
                        return height;
                    }
                }
            }
        }
        return Double.NaN;
    }

    /** The height of {@code terrain} at a world point, or {@code NaN} outside it. */
    static double sample(HeightfieldShape terrain, Vec3 origin, double x, double z) {
        double gx = (x - origin.x()) / terrain.cellSizeX();
        double gz = (z - origin.z()) / terrain.cellSizeZ();
        if (!(gx >= 0 && gx <= terrain.cellsX() && gz >= 0 && gz <= terrain.cellsZ())) {
            return Double.NaN;
        }
        int ix = Math.min((int) gx, terrain.cellsX() - 1);
        int iz = Math.min((int) gz, terrain.cellsZ() - 1);
        double fx = gx - ix;
        double fz = gz - iz;
        double h00 = terrain.height(ix, iz);
        double h10 = terrain.height(ix + 1, iz);
        double h01 = terrain.height(ix, iz + 1);
        double height;
        if (fx + fz <= 1) { // the cell's first triangle: (x, z), (x, z + 1), (x + 1, z)
            height = h00 + fx * (h10 - h00) + fz * (h01 - h00);
        } else { // the second: (x + 1, z), (x, z + 1), (x + 1, z + 1)
            double h11 = terrain.height(ix + 1, iz + 1);
            height = h11 + (1 - fx) * (h01 - h11) + (1 - fz) * (h10 - h11);
        }
        return origin.y() + height;
    }
}
