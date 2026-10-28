package com.truesim.scene.collision;

import com.truesim.common.collision.HeightfieldShape;
import com.truesim.common.math.Vec3;
import com.truesim.scene.core.CellGrid;
import com.truesim.scene.world.CellState;
import com.truesim.scene.world.Layer;
import com.truesim.scene.world.Levels;
import com.truesim.scene.world.World;

/**
 * The ground at a point, from the terrain of a collision layer's loaded cells: its height and material, e.g. to place
 * spawns, items or AI on the ground, on a server too, or to pick footstep sounds. Uses the finest loaded level covering
 * the point, and the same two triangles per heightfield cell as collision, so the height is where a ray straight down
 * would hit the terrain. Physics does not need this: contacts and hits report materials themselves. Allocation-free;
 * use it on the main thread, like the world.
 *
 * <pre>{@code
 * TerrainHeights ground = new TerrainHeights(world, COLLISION);
 * double y = ground.heightAt(x, z);       // NaN where no terrain has loaded
 * int material = ground.materialAt(x, z); // -1 there
 * }</pre>
 */
public final class TerrainHeights {

    /** {@link #materialAt} where no terrain has loaded. */
    public static final int NONE = -1;

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
        CellColliders cell = terrainAt(x, z);
        return cell == null ? Double.NaN : sample(cell.terrain(), cell.terrainOrigin(), x, z);
    }

    /**
     * The material of the terrain's heightfield cell at ({@code x}, {@code z}), as contacts and hits there report it,
     * or {@link #NONE} where no loaded cell's terrain covers it.
     */
    public int materialAt(double x, double z) {
        CellColliders cell = terrainAt(x, z);
        if (cell == null) {
            return NONE;
        }
        HeightfieldShape terrain = cell.terrain();
        double gx = (x - cell.terrainOrigin().x()) / terrain.cellSizeX();
        double gz = (z - cell.terrainOrigin().z()) / terrain.cellSizeZ();
        return terrain.cellMaterial(Math.min((int) gx, terrain.cellsX() - 1), Math.min((int) gz, terrain.cellsZ() - 1));
    }

    /**
     * The finest loaded cell whose terrain covers ({@code x}, {@code z}), or {@code null}. A point on a cell's lower
     * edge also lies on the edge of the cell before it, whose terrain reaches it too: that cell is looked at as well.
     */
    private CellColliders terrainAt(double x, double z) {
        Levels levels = layer.levels();
        for (int level = 0; level < levels.count(); level++) {
            CellGrid grid = levels.grid(level);
            int cx = grid.cell(x);
            int cz = grid.cell(z);
            int fromX = grid.cellMin(cx) == x ? cx - 1 : cx;
            int fromZ = grid.cellMin(cz) == z ? cz - 1 : cz;
            for (int gz = cz; gz >= fromZ; gz--) {
                for (int gx = cx; gx >= fromX; gx--) {
                    CellColliders cell = terrainCell(level, gx, gz, x, z);
                    if (cell != null) {
                        return cell;
                    }
                }
            }
        }
        return null;
    }

    /** Cell ({@code gx}, {@code gz}) of {@code level} if it is loaded and its terrain covers the point. */
    private CellColliders terrainCell(int level, int gx, int gz, double x, double z) {
        if (world.state(layer, level, gx, 0, gz) != CellState.LOADED) {
            return null;
        }
        CellColliders cell = world.data(layer, level, gx, 0, gz);
        return cell.terrain() != null && covers(cell.terrain(), cell.terrainOrigin(), x, z) ? cell : null;
    }

    private static boolean covers(HeightfieldShape terrain, Vec3 origin, double x, double z) {
        double gx = (x - origin.x()) / terrain.cellSizeX();
        double gz = (z - origin.z()) / terrain.cellSizeZ();
        return gx >= 0 && gx <= terrain.cellsX() && gz >= 0 && gz <= terrain.cellsZ();
    }

    /** The height of {@code terrain} at a world point, or {@code NaN} outside it. */
    static double sample(HeightfieldShape terrain, Vec3 origin, double x, double z) {
        if (!covers(terrain, origin, x, z)) {
            return Double.NaN;
        }
        double gx = (x - origin.x()) / terrain.cellSizeX();
        double gz = (z - origin.z()) / terrain.cellSizeZ();
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
