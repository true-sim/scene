package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.geometry.AabbArray;
import com.truesim.common.math.geometry.Intersections;
import com.truesim.common.math.spatial.Bvh;
import com.truesim.common.math.spatial.RayHit;

/**
 * A mesh's triangles for precise picking: positions and indices in the mesh's own space, and a triangle BVH built
 * lazily, on the first ray that reaches the mesh's bounds, so meshes nobody points at cost no memory beyond their data.
 * Keep one per mesh asset (scene-render's {@code PickMeshes}), shared by every node that shows the mesh:
 * {@link Pickable#mesh(PickGeometry)}. A skinned copy changes its positions in place and {@link #refit}s.
 *
 * <p>Raycasts allocate nothing once the BVH is built; one thread at a time.
 */
public final class PickMesh implements PickGeometry {

    private final float[] positions;
    private final int[] indices;
    private final int triangles;
    private Bvh bvh;
    private AabbArray boxes;
    private boolean stale;
    private int part = -1;
    private final RayHit hit = new RayHit();
    private final Bvh.RayTest triangle = this::triangle;

    /**
     * A mesh of the triangles {@code indices} (three per triangle) over {@code positions} (x, y, z per vertex), in the
     * mesh's space. The arrays are kept, not copied: change positions only to {@link #refit}.
     */
    @AllowAllocation("meshes are made when loaded")
    public PickMesh(float[] positions, int[] indices) {
        if (positions.length % 3 != 0 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("positions and indices come in threes");
        }
        int vertices = positions.length / 3;
        for (int index : indices) {
            if (index < 0 || index >= vertices) {
                throw new IllegalArgumentException("index " + index + " of " + vertices + " vertices");
            }
        }
        this.positions = positions;
        this.indices = indices;
        this.triangles = indices.length / 3;
    }

    /** How many triangles. */
    public int triangleCount() {
        return triangles;
    }

    /** The positions (x, y, z per vertex), kept: a skinned copy writes its pose here, then {@link #refit}s. */
    public float[] positions() {
        return positions;
    }

    /** Whether the BVH was built (a ray reached the mesh). */
    public boolean built() {
        return bvh != null;
    }

    /**
     * Takes the positions as changed: the BVH's boxes are recomputed and refit (its tree kept) when the next ray
     * reaches the mesh. Allocates nothing.
     */
    public void refit() {
        stale = true;
    }

    /**
     * Where the ray from {@code ox, oy, oz} along {@code dx, dy, dz} (mesh space) first hits a triangle from either
     * side, in units of the direction's length; {@link Double#POSITIVE_INFINITY} for a miss or beyond {@code max}.
     * Builds the BVH on first use.
     */
    public double raycast(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        part = -1;
        if (triangles == 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (bvh == null) {
            build();
        } else if (stale) {
            boxes();
            bvh.refit();
        }
        stale = false;
        if (!bvh.raycast(ox, oy, oz, dx, dy, dz, max, triangle, hit)) {
            return Double.POSITIVE_INFINITY;
        }
        part = hit.primitive();
        return hit.distance();
    }

    @Override
    public double raycast(long node, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        return raycast(ox, oy, oz, dx, dy, dz, max);
    }

    /** The triangle the last hit struck, or -1. */
    @Override
    public int part() {
        return part;
    }

    @AllowAllocation("the BVH, once per mesh")
    private void build() {
        boxes = AabbArray.onHeap(triangles);
        boxes();
        bvh = Bvh.build(boxes);
    }

    private void boxes() {
        for (int t = 0; t < triangles; t++) {
            int a = indices[t * 3] * 3;
            int b = indices[t * 3 + 1] * 3;
            int c = indices[t * 3 + 2] * 3;
            boxes.set(t,
                Math.min(positions[a], Math.min(positions[b], positions[c])),
                Math.min(positions[a + 1], Math.min(positions[b + 1], positions[c + 1])),
                Math.min(positions[a + 2], Math.min(positions[b + 2], positions[c + 2])),
                Math.max(positions[a], Math.max(positions[b], positions[c])),
                Math.max(positions[a + 1], Math.max(positions[b + 1], positions[c + 1])),
                Math.max(positions[a + 2], Math.max(positions[b + 2], positions[c + 2])));
        }
    }

    private double triangle(int t, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        int a = indices[t * 3] * 3;
        int b = indices[t * 3 + 1] * 3;
        int c = indices[t * 3 + 2] * 3;
        return Intersections.rayTriangle(ox, oy, oz, dx, dy, dz,
            positions[a], positions[a + 1], positions[a + 2],
            positions[b], positions[b + 1], positions[b + 2],
            positions[c], positions[c + 1], positions[c + 2]);
    }
}
