package com.truesim.scene.collision;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.collision.CollisionWorld;
import com.truesim.common.math.Transform;
import com.truesim.scene.world.Cell;
import com.truesim.scene.world.CellContent;
import com.truesim.scene.world.CellLoader;
import com.truesim.scene.world.FeatureView;
import java.util.List;
import java.util.Objects;

/**
 * Streams a collision layer into a {@link CollisionWorld}: cells load their {@link CellColliders} in the background,
 * and their colliders are added to the collision world when the cell attaches and removed when it goes. Works without
 * rendering, e.g. on a physics-only server whose interest sources are the players and want only this layer.
 *
 * <pre>{@code
 * static final Layer<CellColliders> COLLISION = Layer.of("collision", CellColliders.class, Levels.single(128));
 *
 * CollisionWorld collisions = new CollisionWorld();
 * world.register(COLLISION, new ColliderLoader(collisions, (cell, features) -> readColliders(archive, cell)));
 * InterestSource player = world.addInterest(InterestProfile.builder()
 *     .layer(COLLISION, Detail.full(), Range.radius(300)).build(), Priority.HIGH);
 *
 * // Every step, after the world's frame boundary (tasks.drainCompleted()):
 * collisions.update();
 * }</pre>
 *
 * <p>Colliders get the materials and filters the cell gives them, the terrain too; their user data is 0. Attaching
 * and detaching run on the main thread, as the collision world is used from one thread at a time.
 */
public final class ColliderLoader implements CellLoader<CellColliders> {

    /** Loads a cell's colliders, in the background; features read here make the cell depend on them. */
    @FunctionalInterface
    public interface Source {
        CellColliders load(Cell cell, FeatureView features) throws Exception;
    }

    private final CollisionWorld collisions;
    private final Source source;

    public ColliderLoader(CollisionWorld collisions, Source source) {
        this.collisions = Objects.requireNonNull(collisions, "collisions");
        this.source = Objects.requireNonNull(source, "source");
    }

    /** The collision world cells go into. */
    public CollisionWorld collisions() {
        return collisions;
    }

    @Override
    public CellColliders load(Cell cell, FeatureView features) throws Exception {
        return source.load(cell, features);
    }

    @Override
    @AllowAllocation("attaching a cell")
    public void attach(CellColliders data, CellContent content) {
        if (data.isAttached()) {
            throw new IllegalStateException("These colliders are attached already: load a cell's colliders anew");
        }
        List<CellColliders.Collider> colliders = data.colliders();
        int[] ids = new int[colliders.size() + (data.terrain() == null ? 0 : 1)];
        for (int i = 0; i < colliders.size(); i++) {
            CellColliders.Collider collider = colliders.get(i);
            int id = collisions.addStatic(collider.shape(), collider.pose(), collider.baked());
            collisions.setMaterial(id, collider.material());
            collisions.setFilter(id, collider.group(), collider.mask());
            ids[i] = id;
        }
        if (data.terrain() != null) {
            int id = collisions.addStatic(data.terrain(), Transform.ofTranslation(data.terrainOrigin()));
            collisions.setMaterial(id, data.terrainMaterial());
            collisions.setFilter(id, data.terrainGroup(), data.terrainMask());
            ids[colliders.size()] = id;
        }
        data.attached(ids);
    }

    @Override
    public void detach(CellColliders data, CellContent content) {
        for (int id : data.ids()) {
            collisions.remove(id);
        }
        data.attached(null);
        content.removeAll();
    }
}
