package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.geometry.Frustum;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Picks a scene's {@link Pickable} nodes with world rays, engine-independent (the engine's scene interaction and the
 * UI's scene bridge both use it): {@link SpatialIndex#raycast(double, double, double, double, double, double, double,
 * int, SpatialIndex.RayFilter, SpatialIndex.Hit) SpatialIndex.raycast} for the boxes, then each reached pickable's
 * narrow phase (its box, its mesh's triangle BVH in its own space, its renderable's mesh posed as it is now, or a test
 * of its own). Up to the {@link PickHits}' capacity of nodes along the ray, nearest first, for pass-through. Nodes
 * without a pickable, switched off or on other pick layers are never hit.
 *
 * <pre>{@code
 * ScenePicker picker = new ScenePicker(scene, index).resolver(new RenderablePicking(renderables, meshes));
 * PickHits hits = new PickHits(4);
 * // each frame, after scene.update() and index.update():
 * picker.beginFrame();
 * if (picker.pick(ox, oy, oz, dx, dy, dz, 1000, Pickable.DEFAULT_LAYERS, hits) > 0) select(hits.node(0));
 * }</pre>
 *
 * <p><b>Once per ray and frame.</b> The picks of a frame are kept: a second pick of the same ray (bit for bit, the same
 * reach and layers) in the frame copies them, so the engine's interaction and the UI picking the same pointer cost one
 * raycast. {@link #beginFrame} forgets them; call it after the scene and the index are updated.
 *
 * <p>The scene and its index must be up to date. One thread at a time; nothing is allocated per pick.
 */
public final class ScenePicker {

    /** The most rays a frame's picks are kept for. */
    public static final int CACHED_RAYS = 16;
    /** The deepest a pick goes along its ray. */
    public static final int MAX_HITS = 16;

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    /** Finds the geometry of a {@link Pickable#mesh()} given none: its node's renderable's mesh, or null (its box). */
    @FunctionalInterface
    public interface Resolver {

        /** The geometry {@code node} is hit by, in its own space, or null to hit it by its box. */
        PickGeometry geometry(long node);
    }

    private final SceneGraph scene;
    private final SpatialIndex index;
    private final ComponentStore<Pickable> pickables;
    private Resolver resolver;
    private int sceneLayers = -1;

    // the frame's picks
    private final PickHits[] cache = new PickHits[CACHED_RAYS];
    private final int[] cacheLayers = new int[CACHED_RAYS];
    private final int[] cacheDepth = new int[CACHED_RAYS];
    private int cached;
    private long hits;
    private long raycasts;

    // one pick's state
    private final SpatialIndex.Hit hit = new SpatialIndex.Hit();
    private final SpatialIndex.RayFilter filter = this::narrow;
    private final long[] excluded = new long[MAX_HITS];
    private int excludedCount;
    private int queryLayers;
    private double bestDistance;
    private int bestPart;
    private long narrowTests;

    // frustum queries
    private SpatialIndex.Visitor frustumVisitor;
    private final SpatialIndex.Visitor frustumFilter = this::frustumNode;

    /** A picker of {@code scene}'s pickable nodes through {@code index}. */
    @AllowAllocation("one per scene")
    public ScenePicker(SceneGraph scene, SpatialIndex index) {
        this.scene = scene;
        this.index = index;
        this.pickables = scene.components().store(Pickable.TYPE);
        for (int i = 0; i < CACHED_RAYS; i++) {
            cache[i] = new PickHits(MAX_HITS);
        }
    }

    /** Resolves {@link Pickable#mesh()} pickables given no geometry (null: they are hit by their boxes); returns it. */
    public ScenePicker resolver(Resolver value) {
        resolver = value;
        return this;
    }

    /** The resolver, or null. */
    public Resolver resolver() {
        return resolver;
    }

    /** Only nodes on the scene layers of {@code mask} are picked (default: every layer; a hidden node is on none). */
    public ScenePicker sceneLayers(int mask) {
        sceneLayers = mask;
        cached = 0;
        return this;
    }

    /** The scene layers picked. */
    public int sceneLayers() {
        return sceneLayers;
    }

    /** The scene. */
    public SceneGraph scene() {
        return scene;
    }

    /** The spatial index. */
    public SpatialIndex index() {
        return index;
    }

    /** Forgets the frame's picks: call once a frame, after the scene and its index are updated. */
    public void beginFrame() {
        cached = 0;
    }

    /**
     * Picks along the ray from {@code ox, oy, oz} in direction {@code dx, dy, dz} up to {@code maxDistance} (in units
     * of the direction's length), the pickables on the pick layers of {@code layers}: writes up to {@code out}'s
     * capacity of them, nearest first, and returns how many. A ray picked already this frame is answered from what it
     * found then.
     */
    public int pick(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance, int layers,
        PickHits out) {
        for (int i = 0; i < cached; i++) {
            if (cacheLayers[i] == layers && cache[i].sameRay(ox, oy, oz, dx, dy, dz, maxDistance)
                && (cache[i].count() >= out.capacity() || cache[i].count() < cacheDepth[i])) {
                hits++;
                out.copyFrom(cache[i]);
                return out.count();
            }
        }
        out.reset(ox, oy, oz, dx, dy, dz, maxDistance);
        queryLayers = layers;
        excludedCount = 0;
        int depth = Math.min(out.capacity(), MAX_HITS);
        for (int k = 0; k < depth; k++) {
            bestDistance = Double.POSITIVE_INFINITY;
            bestPart = -1;
            raycasts++;
            if (!index.raycast(ox, oy, oz, dx, dy, dz, maxDistance, sceneLayers, filter, hit)) {
                break;
            }
            excluded[excludedCount++] = hit.node();
            out.add(hit.node(), hit.distance(), bestPart); // the index keeps the first nearest, as narrow() does
        }
        if (cached < CACHED_RAYS) {
            cache[cached].copyFrom(out);
            cacheDepth[cached] = depth;
            cacheLayers[cached++] = layers;
        }
        return out.count();
    }

    /**
     * Visits the enabled pickables on the pick layers of {@code layers} whose world boxes {@code frustum} touches: a
     * marquee's frustum from a screen rectangle. Stops when the visitor returns false; returns how many were visited.
     */
    public int pickFrustum(Frustum frustum, int layers, SpatialIndex.Visitor visitor) {
        frustumVisitor = visitor;
        queryLayers = layers;
        try {
            return index.queryFrustum(frustum, sceneLayers, frustumFilter);
        } finally {
            frustumVisitor = null;
        }
    }

    private boolean frustumNode(long node) {
        Pickable p = pickables.get(node);
        return p == null || !p.enabled() || (p.layers() & queryLayers) == 0 || frustumVisitor.visit(node);
    }

    /** How many picks this picker answered from the frame's earlier ones. */
    public long cacheHits() {
        return hits;
    }

    /** How many index raycasts it ran (one per hit, and one more for a ray ending before its capacity). */
    public long raycasts() {
        return raycasts;
    }

    /** How many narrow-phase tests ran: nodes without a pickable are never tested. */
    public long narrowTests() {
        return narrowTests;
    }

    /** The narrow phase of a node the ray's box test reached. */
    private double narrow(long node, double box, double ox, double oy, double oz, double dx, double dy, double dz,
        double max) {
        Pickable p = pickables.get(node);
        if (p == null || !p.enabled() || (p.layers() & queryLayers) == 0) {
            return Double.POSITIVE_INFINITY;
        }
        for (int i = 0; i < excludedCount; i++) {
            if (excluded[i] == node) {
                return Double.POSITIVE_INFINITY; // found by an earlier ray of this pick
            }
        }
        narrowTests++;
        double t;
        int part = -1;
        switch (p.shape()) {
            case MESH -> {
                PickGeometry g = p.geometry() != null ? p.geometry() : resolver != null ? resolver.geometry(node) : null;
                if (g == null) {
                    t = box;
                } else {
                    t = local(node, g, ox, oy, oz, dx, dy, dz, max);
                    part = g.part();
                }
            }
            case CUSTOM -> t = p.test().test(node, box, ox, oy, oz, dx, dy, dz, max);
            default -> t = box;
        }
        if (t < bestDistance && t <= max) { // the index keeps the first nearest within reach too
            bestDistance = t;
            bestPart = part;
        }
        return t;
    }

    /** The world ray against {@code geometry} in {@code node}'s space: its translation, rotation and scale undone. */
    private double local(long node, PickGeometry geometry, double ox, double oy, double oz, double dx, double dy,
        double dz, double max) {
        MemorySegment s = scene.worldTransforms().segment();
        long o = NodeHandle.index(node) * scene.worldTransforms().stride();
        double px = ox - s.get(DOUBLE, o);
        double py = oy - s.get(DOUBLE, o + 8);
        double pz = oz - s.get(DOUBLE, o + 16);
        double qx = s.get(DOUBLE, o + 24);
        double qy = s.get(DOUBLE, o + 32);
        double qz = s.get(DOUBLE, o + 40);
        double qw = s.get(DOUBLE, o + 48);
        double sx = s.get(DOUBLE, o + 56);
        double sy = s.get(DOUBLE, o + 64);
        double sz = s.get(DOUBLE, o + 72);
        // q^-1 v: the rotation by the conjugate, then the scale undone; the ray's parameter is kept
        double ux = 2 * (-qy * pz + qz * py);
        double uy = 2 * (-qz * px + qx * pz);
        double uz = 2 * (-qx * py + qy * px);
        double lox = (px + qw * ux + (-qy * uz + qz * uy)) / sx;
        double loy = (py + qw * uy + (-qz * ux + qx * uz)) / sy;
        double loz = (pz + qw * uz + (-qx * uy + qy * ux)) / sz;
        ux = 2 * (-qy * dz + qz * dy);
        uy = 2 * (-qz * dx + qx * dz);
        uz = 2 * (-qx * dy + qy * dx);
        double ldx = (dx + qw * ux + (-qy * uz + qz * uy)) / sx;
        double ldy = (dy + qw * uy + (-qz * ux + qx * uz)) / sy;
        double ldz = (dz + qw * uz + (-qx * uy + qy * ux)) / sz;
        return geometry.raycast(node, lox, loy, loz, ldx, ldy, ldz, max);
    }
}
