package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.collision.CollisionQuery;
import com.truesim.common.collision.CollisionWorld;
import com.truesim.common.collision.RaycastHit;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * An {@link AudioWorld} over physics (§2.11): rays through common-collision, each surface hit a crossing whose
 * material (a mesh triangle's, else its collider's) the table maps to an {@link AudioMaterial}; up to 8 crossings. A
 * convex body counts once (going in; the ray then steps out of it in doubling steps, each probe cast back so a body
 * it steps into or over still counts), a triangle mesh once per side crossed. A query per thread, made on its first
 * trace. Thread-safe while the collision world does not change.
 */
public final class CollisionAudioWorld implements AudioWorld {

    /** Crossings counted along a ray, at most. */
    public static final int MAX_CROSSINGS = 8;
    private static final double STEP = 1e-3;
    private static final int MAX_CASTS = 96;

    private final CollisionWorld world;
    private final IntFunction<AudioMaterial> materials;
    private final int mask;
    private final ThreadLocal<CollisionQuery> queries;
    private final ThreadLocal<RaycastHit> hits = ThreadLocal.withInitial(CollisionAudioWorld::newHit);

    /** Rays through {@code world}'s colliders in groups {@code mask}, materials by {@code materials} (null: open). */
    @AllowAllocation("set up once")
    public CollisionAudioWorld(CollisionWorld world, IntFunction<AudioMaterial> materials, int mask) {
        this.world = Objects.requireNonNull(world, "world");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.mask = mask;
        queries = ThreadLocal.withInitial(world::newQuery);
    }

    @AllowAllocation("a hit per thread, once")
    private static RaycastHit newHit() {
        return new RaycastHit();
    }

    @Override
    public boolean trace(double x0, double y0, double z0, double x1, double y1, double z1, Transmission out) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < STEP) {
            return false;
        }
        dx /= length;
        dy /= length;
        dz /= length;
        CollisionQuery query = queries.get();
        RaycastHit hit = hits.get();
        double travelled = 0;
        double step = 10 * STEP;
        int inside = RaycastHit.NONE; // the convex body the ray is stepping out of
        int crossings = 0;
        for (int casts = 0; casts < MAX_CASTS && travelled < length; casts++) {
            if (inside == RaycastHit.NONE) {
                if (!query.raycast(x0 + dx * travelled, y0 + dy * travelled, z0 + dz * travelled, dx, dy, dz,
                    length - travelled, mask, hit)) {
                    break;
                }
                if (hit.distance() <= 0) { // starting inside a body: not counted, stepped out of
                    inside = hit.collider();
                    step = 10 * STEP;
                    continue;
                }
                crossings = cross(hit, crossings, out); // a mesh's side, or a body's face going in
                travelled += hit.distance() + STEP;
                inside = hit.triangle() == RaycastHit.NONE ? hit.collider() : RaycastHit.NONE;
                step = 10 * STEP;
                continue;
            }
            // In a body: probe a step on (doubling) and cast back. Hitting the body's far face first, nothing lies
            // between it and the probe; another body first, the probe went past its exit and into or over that one.
            double probe = Math.min(step, length - travelled);
            double at = travelled + probe;
            boolean back = query.raycast(x0 + dx * at, y0 + dy * at, z0 + dz * at, -dx, -dy, -dz, probe, mask, hit);
            if (!back || hit.collider() == inside) {
                travelled = at;
                if (!back || hit.distance() > 0) { // out, and on from the probe
                    inside = RaycastHit.NONE;
                } else { // still in it
                    step *= 2;
                }
            } else if (probe > STEP) { // probe closer, until it falls between the two
                step = probe / 2;
            } else { // the other starts within a step of this one's end: entered it, and out of it in turn
                crossings = cross(hit, crossings, out); // (behind the probe, a mesh's side: the next probe is out)
                travelled = at;
                inside = hit.collider();
                step = 10 * STEP;
            }
        }
        return crossings > 0;
    }

    /** Counts the surface hit, up to {@link #MAX_CROSSINGS}; the crossings so far, plus one. */
    private int cross(RaycastHit hit, int crossings, Transmission out) {
        if (crossings < MAX_CROSSINGS) {
            // a mesh's triangle has its own material; a convex shape takes its collider's
            int id = hit.material() != RaycastHit.NONE ? hit.material() : world.material(hit.collider());
            AudioMaterial material = materials.apply(id);
            out.cross(material == null ? AudioMaterial.OPEN : material);
        }
        return crossings + 1;
    }

    /** The collision world it traces. */
    public CollisionWorld world() {
        return world;
    }
}
