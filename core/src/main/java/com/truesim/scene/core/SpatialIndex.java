package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.geometry.AabbArray;
import com.truesim.common.math.geometry.Frustum;
import com.truesim.common.math.geometry.Intersections;
import com.truesim.common.math.spatial.Bvh;
import com.truesim.common.math.spatial.RayHit;
import com.truesim.common.state.ChangeCursor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * Finds a {@link SceneGraph}'s nodes by their world bounds: boxes, spheres, frustums and rays, filtered by
 * {@link SceneLayer} masks. Nodes without bounds are not indexed.
 *
 * <p>Static and dynamic nodes ({@link Mobility}) are indexed apart, so the cost per frame follows what moves:
 * <ul>
 *   <li>Static nodes are in a hierarchy that is rebuilt only when static nodes are added, removed or moved, e.g.
 *       when the world streams cells in or out.</li>
 *   <li>Dynamic nodes are in a hierarchy with spare room: each frame it refits the boxes of the nodes that moved;
 *       spawning and despawning reuse slots. It is rebuilt only when its quality has degraded too far
 *       ({@link #setRebuildThreshold}) or it has to grow.</li>
 * </ul>
 *
 * <pre>{@code
 * SpatialIndex index = new SpatialIndex(scene);
 * scene.update();
 * index.update();                                       // follows the scene's change log
 * index.queryFrustum(frustum, VISIBLE.mask(), node -> { draw(node); return true; });
 * if (index.raycast(origin, direction, 100, PICKABLE.mask(), hit)) select(hit.node());
 * }</pre>
 *
 * <p>Updating and querying allocate nothing, except when a hierarchy is built or rebuilt. Queries are not reentrant:
 * do not query from inside a query's visitor. One thread at a time.
 */
public final class SpatialIndex {

    private static final byte NOWHERE = 0;
    private static final byte STATIC = 1;
    private static final byte DYNAMIC = 2;
    private static final int LEAF_SIZE = 4;

    private final SceneGraph scene;
    private final ChangeCursor cursor;
    private final NodeChange.Visitor onChange = this::changed;
    private final SceneGraph.NodeVisitor onNode = this::place;

    // Membership, by scene slot.
    private byte[] where = new byte[0];
    private int[] position = new int[0];

    // Static nodes: dense, rebuilt on change.
    private long[] staticNodes = new long[16];
    private int[] staticLayers = new int[16];
    private int staticCount;
    private boolean staticChanged;
    private Bvh staticBvh;

    // Dynamic nodes: slots with a free list, refit on change.
    private long[] dynamicNodes = new long[0];
    private int[] dynamicLayers = new int[0];
    private int[] freeDynamic = new int[0];
    private int freeCount;
    private int dynamicCount;
    private AabbArray dynamicBounds;
    private Bvh dynamicBvh;
    private boolean dynamicMoved;
    private double rebuildThreshold = 2;

    // The running query.
    private Visitor visitor;
    private int mask;
    private boolean stopped;
    private int found;
    private final Bvh.Visitor visitStatic = primitive -> report(staticNodes[primitive], staticLayers[primitive]);
    private final Bvh.Visitor visitDynamic = primitive -> report(dynamicNodes[primitive], dynamicLayers[primitive]);
    private final Bvh.RayTest rayStatic = (primitive, ox, oy, oz, dx, dy, dz, max) ->
        rayTest(staticNodes[primitive], staticLayers[primitive], staticBvh.primitiveBounds(), primitive, ox, oy, oz, dx, dy, dz, max);
    private final Bvh.RayTest rayDynamic = (primitive, ox, oy, oz, dx, dy, dz, max) ->
        rayTest(dynamicNodes[primitive], dynamicLayers[primitive], dynamicBounds, primitive, ox, oy, oz, dx, dy, dz, max);
    private final RayHit rayHit = new RayHit();

    /** An index of {@code scene}'s nodes; empty until the first {@link #update()}. */
    @AllowAllocation("set up once")
    public SpatialIndex(SceneGraph scene) {
        this.scene = scene;
        this.cursor = scene.clock().cursor();
    }

    /** Receives nodes found by a query; return false to stop the query. */
    @FunctionalInterface
    public interface Visitor {

        boolean visit(long node);
    }

    /** The result of {@link #raycast}: the nearest node hit and its distance along the ray. Reuse one per thread. */
    public static final class Hit {

        private long node = NodeHandle.NONE;
        private double distance = Double.POSITIVE_INFINITY;

        /** The node hit, or {@link NodeHandle#NONE}. */
        public long node() {
            return node;
        }

        /** How far along the ray, in units of its direction's length; infinite without a hit. */
        public double distance() {
            return distance;
        }
    }

    // ---------------------------------------------------------------- update

    /**
     * Brings the index up to the scene's last {@link SceneGraph#update()}: adds, moves and removes nodes from the
     * change log, then refits or rebuilds the hierarchies that changed. If the scene's log no longer reaches back to
     * the previous update, the index rebuilds from all nodes.
     */
    public void update() {
        long since = cursor.mark();
        if (!scene.changesSince(since, onChange)) {
            clear();
            scene.forEach(onNode);
        }
        if (staticChanged) {
            rebuildStatic();
        }
        if (dynamicMoved) { // adding a dynamic node built its hierarchy
            dynamicBvh.refit();
            if (dynamicBvh.degradation() > rebuildThreshold) {
                dynamicBvh.rebuild();
            }
            dynamicMoved = false;
        }
    }

    /**
     * How much worse than freshly built the dynamic hierarchy may get (estimated query cost, 2 = twice) before it is
     * rebuilt instead of refit. Lower is faster queries, higher is fewer rebuilds.
     */
    public void setRebuildThreshold(double threshold) {
        if (!(threshold >= 1)) {
            throw new IllegalArgumentException("The threshold must be at least 1: " + threshold);
        }
        this.rebuildThreshold = threshold;
    }

    /** How many static nodes are indexed. */
    public int staticCount() {
        return staticCount;
    }

    /** How many dynamic nodes are indexed. */
    public int dynamicCount() {
        return dynamicCount;
    }

    private void changed(long node, int changes) {
        if (NodeChange.has(changes, NodeChange.REMOVED)) {
            int slot = NodeHandle.index(slotSafe(node));
            if (where[slot] != NOWHERE) { // the log is ordered: a slot is reused only after its removal
                remove(slot);
            }
        } else if (scene.contains(node)) {
            place(node);
        }
    }

    /** {@code node} itself, after making sure the membership arrays reach its slot. */
    private long slotSafe(long node) {
        int needed = NodeHandle.index(node) + 1;
        if (needed > where.length) {
            growMembership(Math.max(needed, scene.slotCount()));
        }
        return node;
    }

    @AllowAllocation("growing")
    private void growMembership(int length) {
        where = Arrays.copyOf(where, Math.max(length, where.length * 2));
        position = Arrays.copyOf(position, where.length);
    }

    /** Puts a live node where its bounds and mobility say, or updates its box there. */
    private void place(long node) {
        int slot = NodeHandle.index(slotSafe(node));
        byte wanted = !scene.hasBounds(node) ? NOWHERE : scene.mobility(node) == Mobility.STATIC ? STATIC : DYNAMIC;
        if (where[slot] != NOWHERE && where[slot] != wanted) {
            remove(slot);
        }
        if (wanted == STATIC) {
            if (where[slot] == NOWHERE) {
                addStatic(slot, node);
            }
            staticLayers[position[slot]] = scene.layers(node);
            staticChanged = true;
        } else if (wanted == DYNAMIC) {
            if (where[slot] == NOWHERE) {
                addDynamic(slot, node);
            }
            dynamicLayers[position[slot]] = scene.layers(node);
            copyBox(scene.worldBounds(), slot, dynamicBounds, position[slot]);
            dynamicMoved = true;
        }
    }

    private void remove(int slot) {
        int at = position[slot];
        if (where[slot] == STATIC) {
            int last = --staticCount;
            long moved = staticNodes[last];
            staticNodes[at] = moved;
            staticLayers[at] = staticLayers[last];
            position[NodeHandle.index(moved)] = at;
            staticNodes[last] = NodeHandle.NONE;
            staticChanged = true;
        } else {
            dynamicNodes[at] = NodeHandle.NONE; // its box stays until the slot is reused; queries skip it
            freeDynamic[freeCount++] = at;
            dynamicCount--;
        }
        where[slot] = NOWHERE;
    }

    @AllowAllocation("growing")
    private void addStatic(int slot, long node) {
        if (staticCount == staticNodes.length) {
            staticNodes = Arrays.copyOf(staticNodes, staticCount * 2);
            staticLayers = Arrays.copyOf(staticLayers, staticCount * 2);
        }
        position[slot] = staticCount;
        staticNodes[staticCount++] = node;
        where[slot] = STATIC;
    }

    private void addDynamic(int slot, long node) {
        if (freeCount == 0) {
            growDynamic(slot);
        }
        int at = freeDynamic[--freeCount];
        dynamicNodes[at] = node;
        position[slot] = at;
        where[slot] = DYNAMIC;
        dynamicCount++;
    }

    /** Doubles the dynamic slots; new slots start with the box of the node being added, so the tree stays tight. */
    @AllowAllocation("growing")
    private void growDynamic(int slot) {
        int old = dynamicNodes.length;
        int capacity = Math.max(16, old * 2);
        AabbArray bounds = AabbArray.onHeap(capacity);
        if (dynamicBounds != null) {
            MemorySegment.copy(dynamicBounds.segment(), 0, bounds.segment(), 0, dynamicBounds.segment().byteSize());
        }
        for (int i = old; i < capacity; i++) {
            copyBox(scene.worldBounds(), slot, bounds, i);
        }
        dynamicNodes = Arrays.copyOf(dynamicNodes, capacity);
        dynamicLayers = Arrays.copyOf(dynamicLayers, capacity);
        freeDynamic = Arrays.copyOf(freeDynamic, capacity);
        for (int i = capacity - 1; i >= old; i--) {
            freeDynamic[freeCount++] = i;
        }
        dynamicBounds = bounds;
        dynamicBvh = Bvh.build(Arena.ofAuto(), bounds, LEAF_SIZE);
    }

    @AllowAllocation("rebuilding the static hierarchy, when static nodes changed")
    private void rebuildStatic() {
        staticChanged = false;
        if (staticCount == 0) {
            staticBvh = null;
            return;
        }
        AabbArray bounds = AabbArray.onHeap(staticCount);
        AabbArray world = scene.worldBounds();
        for (int i = 0; i < staticCount; i++) {
            copyBox(world, NodeHandle.index(staticNodes[i]), bounds, i);
        }
        staticBvh = Bvh.build(Arena.ofAuto(), bounds, LEAF_SIZE);
    }

    private void clear() {
        Arrays.fill(where, NOWHERE);
        Arrays.fill(staticNodes, NodeHandle.NONE);
        staticCount = 0;
        staticChanged = true;
        for (int i = 0; i < dynamicNodes.length; i++) {
            if (dynamicNodes[i] != NodeHandle.NONE) {
                dynamicNodes[i] = NodeHandle.NONE;
                freeDynamic[freeCount++] = i;
            }
        }
        dynamicCount = 0;
    }

    private static void copyBox(AabbArray from, int fromIndex, AabbArray to, int toIndex) {
        to.set(toIndex, from.get(fromIndex, 0), from.get(fromIndex, 1), from.get(fromIndex, 2), from.get(fromIndex, 3),
            from.get(fromIndex, 4), from.get(fromIndex, 5));
    }

    // ---------------------------------------------------------------- queries

    /** Visits every node on {@code layerMask} whose box overlaps the box; returns how many were visited. */
    public int queryAabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int layerMask,
        Visitor visitor) {
        start(layerMask, visitor);
        if (staticBvh != null) {
            staticBvh.queryAabb(minX, minY, minZ, maxX, maxY, maxZ, visitStatic);
        }
        if (dynamicBvh != null && !stopped) {
            dynamicBvh.queryAabb(minX, minY, minZ, maxX, maxY, maxZ, visitDynamic);
        }
        return finish();
    }

    /** Visits every node on {@code layerMask} whose box overlaps the sphere; returns how many were visited. */
    public int querySphere(double cx, double cy, double cz, double radius, int layerMask, Visitor visitor) {
        start(layerMask, visitor);
        if (staticBvh != null) {
            staticBvh.querySphere(cx, cy, cz, radius, visitStatic);
        }
        if (dynamicBvh != null && !stopped) {
            dynamicBvh.querySphere(cx, cy, cz, radius, visitDynamic);
        }
        return finish();
    }

    /** Visits every node on {@code layerMask} whose box is inside or crosses the frustum, e.g. to draw. */
    public int queryFrustum(Frustum frustum, int layerMask, Visitor visitor) {
        start(layerMask, visitor);
        if (staticBvh != null) {
            staticBvh.queryFrustum(frustum, visitStatic);
        }
        if (dynamicBvh != null && !stopped) {
            dynamicBvh.queryFrustum(frustum, visitDynamic);
        }
        return finish();
    }

    /**
     * Finds the nearest node on {@code layerMask} whose box the ray from ({@code ox}, {@code oy}, {@code oz}) along
     * ({@code dx}, {@code dy}, {@code dz}) enters within {@code maxDistance}; writes it to {@code hit}.
     */
    public boolean raycast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance,
        int layerMask, Hit hit) {
        mask = layerMask;
        hit.node = NodeHandle.NONE;
        hit.distance = Double.POSITIVE_INFINITY;
        double best = maxDistance;
        if (staticBvh != null && staticBvh.raycast(ox, oy, oz, dx, dy, dz, best, rayStatic, rayHit)) {
            best = rayHit.distance();
            hit.node = staticNodes[rayHit.primitive()];
            hit.distance = best;
        }
        if (dynamicBvh != null && dynamicBvh.raycast(ox, oy, oz, dx, dy, dz, best, rayDynamic, rayHit)) {
            hit.node = dynamicNodes[rayHit.primitive()];
            hit.distance = rayHit.distance();
        }
        return hit.node != NodeHandle.NONE;
    }

    private void start(int layerMask, Visitor queryVisitor) {
        this.mask = layerMask;
        this.visitor = queryVisitor;
        this.stopped = false;
        this.found = 0;
    }

    private int finish() {
        int count = found;
        found = 0;
        visitor = null;
        return count;
    }

    private boolean report(long node, int layers) {
        if (node == NodeHandle.NONE || (layers & mask) == 0) {
            return true;
        }
        found++;
        if (!visitor.visit(node)) {
            stopped = true;
            return false;
        }
        return true;
    }

    private double rayTest(long node, int layers, AabbArray bounds, int primitive, double ox, double oy, double oz, double dx,
        double dy, double dz, double max) {
        if (node == NodeHandle.NONE || (layers & mask) == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double t = Intersections.rayAabb(ox, oy, oz, dx, dy, dz, bounds.get(primitive, 0), bounds.get(primitive, 1),
            bounds.get(primitive, 2), bounds.get(primitive, 3), bounds.get(primitive, 4), bounds.get(primitive, 5));
        return t <= max ? t : Double.POSITIVE_INFINITY;
    }
}
