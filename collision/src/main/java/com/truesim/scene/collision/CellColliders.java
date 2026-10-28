package com.truesim.scene.collision;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.collision.HeightfieldShape;
import com.truesim.common.collision.Shape;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One cell's static collision: colliders (boxes, hulls, meshes, ... in world space) and optionally a terrain
 * heightfield. Built by a loader in the background, immutable once built; a {@link ColliderLoader} adds it to a
 * collision world when the cell attaches and removes it when the cell goes.
 *
 * <pre>{@code
 * CellColliders cell = CellColliders.builder()
 *     .terrain(HeightfieldShape.of(65, 65, 0.5, 0.5, heights), cell.minX(), 0, cell.minZ())
 *     .add(new BoxShape(Vec3.of(1, 2, 1)), Transform.ofTranslation(barrier))
 *     .add(trackMesh, Transform.IDENTITY, ASPHALT, TRACK_GROUP, -1, true) // baked into the static tree
 *     .build();
 * }</pre>
 */
public final class CellColliders {

    /** One collider: a shape, its world pose, its material and collision filter, and whether it is baked. */
    public record Collider(Shape shape, Transform pose, int material, int group, int mask, boolean baked) {

        public Collider {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(pose, "pose");
        }
    }

    private final List<Collider> colliders;
    private final HeightfieldShape terrain;
    private final Vec3 terrainOrigin;
    private int[] ids; // in the collision world while attached; set on the main thread

    private CellColliders(List<Collider> colliders, HeightfieldShape terrain, Vec3 terrainOrigin) {
        this.colliders = colliders;
        this.terrain = terrain;
        this.terrainOrigin = terrainOrigin;
    }

    @AllowAllocation("building a cell, in the background")
    public static Builder builder() {
        return new Builder();
    }

    /** The colliders, in the order added. */
    public List<Collider> colliders() {
        return colliders;
    }

    /** The cell's terrain, or {@code null} without one. */
    public HeightfieldShape terrain() {
        return terrain;
    }

    /** Where the terrain's first sample lies in the world; {@code null} without terrain. */
    public Vec3 terrainOrigin() {
        return terrainOrigin;
    }

    /** Whether the cell is in a collision world now. */
    public boolean isAttached() {
        return ids != null;
    }

    int[] ids() {
        return ids;
    }

    void attached(int[] colliderIds) {
        this.ids = colliderIds;
    }

    /** Collects a cell's colliders. */
    public static final class Builder {
        private final List<Collider> colliders = new ArrayList<>();
        private HeightfieldShape terrain;
        private Vec3 terrainOrigin;

        private Builder() {
        }

        /** Adds a collider with material 0, colliding with everything, not baked. */
        public Builder add(Shape shape, Transform pose) {
            return add(shape, pose, 0, 1, -1, false);
        }

        /**
         * Adds a collider with a material, a collision group and mask (see {@code CollisionWorld.setFilter}), and
         * whether a mesh or heightfield goes into the static tree triangle by triangle ({@code CollisionWorld.addStatic}).
         */
        @AllowAllocation("building a cell, in the background")
        public Builder add(Shape shape, Transform pose, int material, int group, int mask, boolean bake) {
            colliders.add(new Collider(shape, pose, material, group, mask, bake));
            return this;
        }

        /**
         * Sets the cell's terrain: a heightfield whose first sample lies at ({@code x}, {@code y}, {@code z}), axis
         * aligned. Its heights are offsets from {@code y}.
         */
        @AllowAllocation("building a cell, in the background")
        public Builder terrain(HeightfieldShape heightfield, double x, double y, double z) {
            this.terrain = Objects.requireNonNull(heightfield, "heightfield");
            this.terrainOrigin = Vec3.of(x, y, z);
            return this;
        }

        @AllowAllocation("building a cell, in the background")
        public CellColliders build() {
            return new CellColliders(List.copyOf(colliders), terrain, terrainOrigin);
        }
    }
}
