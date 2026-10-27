package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.TransformArray;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Aabb;
import com.truesim.common.math.geometry.AabbArray;
import com.truesim.common.state.ChangeClock;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * The scene's nodes: their transform hierarchy, bounds, {@link Mobility} and {@link SceneLayer layers}. Nodes are {@link NodeHandle}s; each has a local transform relative
 * to its parent (or to the world, without one) and a world transform that {@link #update()} computes, parents before
 * children. Positions are {@code double}, so the world needs no origin shifting.
 *
 * <pre>{@code
 * SceneGraph scene = new SceneGraph(clock);
 * long car = scene.create();
 * long wheel = scene.create(car);
 * scene.setTranslation(car, 1200.5, 0, -40);
 * scene.setTranslation(wheel, 0.8, 0.3, 1.4);
 * scene.update();                                     // world transforms, then the change log
 *
 * long since = cursor.mark();                         // e.g. once per frame, in the renderer
 * if (!scene.changesSince(since, (node, changes) -> upload(node, changes))) {
 *     scene.forEach(node -> upload(node, NodeChange.CREATED | NodeChange.TRANSFORM)); // fell behind: rebuild
 * }
 * }</pre>
 *
 * <p>Storage is structure-of-arrays: transforms live in {@link TransformArray}s indexed by
 * {@link NodeHandle#index(long)}, which renderers and physics can read directly ({@link #worldTransforms()}). Creating,
 * moving, reparenting, removing, updating and reading changes allocate nothing once the arrays have grown to the
 * scene's size. Everything is deterministic: the same calls give the same handles, transforms and change order.
 *
 * <p>A scene graph is used by one thread at a time.
 */
public final class SceneGraph {

    /** The default number of changes kept for consumers that read them later. */
    public static final int DEFAULT_LOG_CAPACITY = 1 << 16;

    private static final int NONE = -1;
    /** Bounds from +infinity to -infinity: none. */
    private static final double EMPTY = Double.POSITIVE_INFINITY;

    private final ChangeClock clock;

    // Slots, by index.
    private int[] generation;
    private boolean[] alive;
    private int[] parent;
    private int[] firstChild;
    private int[] nextSibling;
    private int[] previousSibling;
    private TransformArray local;
    private TransformArray world;
    private AabbArray localBounds;
    private AabbArray worldBounds;
    private boolean[] dynamic;
    private int[] layers;
    private long[] version;
    private int[] freeSlots;
    private int freeCount;
    private int slotCount;
    private int size;

    // Work between updates.
    private boolean[] dirty;
    private int[] dirtySlots;
    private int dirtyCount;
    private int[] pending;
    private boolean[] listed;
    private int[] pendingSlots;
    private int pendingCount;
    private int[] stack;
    private boolean[] boundsDirty;
    private int[] boundsDirtySlots;
    private int boundsDirtyCount;

    // The change log: a ring of (stamp, handle, changes).
    private final long[] logStamps;
    private final long[] logNodes;
    private final int[] logChanges;
    private int logStart;
    private int logSize;
    private long logIncompleteThrough = -1;

    /** An empty scene stamping its changes on {@code clock}. */
    public SceneGraph(ChangeClock clock) {
        this(clock, 64, DEFAULT_LOG_CAPACITY);
    }

    /**
     * An empty scene with room for {@code capacity} nodes before it grows, keeping the last {@code logCapacity}
     * changes for consumers.
     */
    @AllowAllocation("set up once")
    public SceneGraph(ChangeClock clock, int capacity, int logCapacity) {
        if (capacity < 1 || logCapacity < 1) {
            throw new IllegalArgumentException("Capacities must be positive: " + capacity + ", " + logCapacity);
        }
        this.clock = clock;
        allocate(capacity);
        logStamps = new long[logCapacity];
        logNodes = new long[logCapacity];
        logChanges = new int[logCapacity];
    }

    @AllowAllocation("growing")
    private void allocate(int capacity) {
        generation = Arrays.copyOf(generation == null ? new int[0] : generation, capacity);
        alive = Arrays.copyOf(alive == null ? new boolean[0] : alive, capacity);
        parent = Arrays.copyOf(parent == null ? new int[0] : parent, capacity);
        firstChild = Arrays.copyOf(firstChild == null ? new int[0] : firstChild, capacity);
        nextSibling = Arrays.copyOf(nextSibling == null ? new int[0] : nextSibling, capacity);
        previousSibling = Arrays.copyOf(previousSibling == null ? new int[0] : previousSibling, capacity);
        version = Arrays.copyOf(version == null ? new long[0] : version, capacity);
        freeSlots = Arrays.copyOf(freeSlots == null ? new int[0] : freeSlots, capacity);
        dirty = Arrays.copyOf(dirty == null ? new boolean[0] : dirty, capacity);
        dirtySlots = Arrays.copyOf(dirtySlots == null ? new int[0] : dirtySlots, capacity);
        pending = Arrays.copyOf(pending == null ? new int[0] : pending, capacity);
        listed = Arrays.copyOf(listed == null ? new boolean[0] : listed, capacity);
        pendingSlots = Arrays.copyOf(pendingSlots == null ? new int[0] : pendingSlots, capacity);
        stack = Arrays.copyOf(stack == null ? new int[0] : stack, capacity);
        dynamic = Arrays.copyOf(dynamic == null ? new boolean[0] : dynamic, capacity);
        layers = Arrays.copyOf(layers == null ? new int[0] : layers, capacity);
        boundsDirty = Arrays.copyOf(boundsDirty == null ? new boolean[0] : boundsDirty, capacity);
        boundsDirtySlots = Arrays.copyOf(boundsDirtySlots == null ? new int[0] : boundsDirtySlots, capacity);
        local = grow(local, capacity);
        world = grow(world, capacity);
        localBounds = grow(localBounds, capacity);
        worldBounds = grow(worldBounds, capacity);
    }

    @AllowAllocation("growing")
    private static TransformArray grow(TransformArray old, int capacity) {
        TransformArray grown = TransformArray.onHeap(capacity);
        if (old != null) {
            MemorySegment.copy(old.segment(), 0, grown.segment(), 0, old.segment().byteSize());
        }
        return grown;
    }

    @AllowAllocation("growing")
    private static AabbArray grow(AabbArray old, int capacity) {
        AabbArray grown = AabbArray.onHeap(capacity);
        if (old != null) {
            MemorySegment.copy(old.segment(), 0, grown.segment(), 0, old.segment().byteSize());
        }
        return grown;
    }

    /** The clock this scene stamps its changes on, e.g. for {@link ChangeClock#cursor()}. */
    public ChangeClock clock() {
        return clock;
    }

    // ---------------------------------------------------------------- nodes

    /** A new node at the world's origin, without a parent. */
    public long create() {
        return create(NodeHandle.NONE);
    }

    /** A new node with identity local transform under {@code parent} ({@link NodeHandle#NONE} for none). */
    public long create(long parent) {
        int parentSlot = parent == NodeHandle.NONE ? NONE : slot(parent);
        int slot;
        if (freeCount > 0) {
            slot = freeSlots[--freeCount];
        } else {
            if (slotCount == generation.length) {
                allocate(generation.length * 2);
            }
            slot = slotCount++;
        }
        generation[slot]++;
        alive[slot] = true;
        this.parent[slot] = NONE;
        firstChild[slot] = NONE;
        nextSibling[slot] = NONE;
        previousSibling[slot] = NONE;
        identity(local, slot);
        identity(world, slot);
        localBounds.set(slot, EMPTY, EMPTY, EMPTY, -EMPTY, -EMPTY, -EMPTY);
        worldBounds.set(slot, EMPTY, EMPTY, EMPTY, -EMPTY, -EMPTY, -EMPTY);
        dynamic[slot] = true;
        layers[slot] = SceneLayer.DEFAULT.mask();
        if (parentSlot != NONE) {
            link(slot, parentSlot);
        }
        size++;
        markDirty(slot);
        markPending(slot, NodeChange.CREATED);
        return NodeHandle.of(slot, generation[slot]);
    }

    /** Removes {@code node} and all its descendants; their handles become stale. */
    public void remove(long node) {
        int slot = slot(node);
        unlink(slot);
        long stamp = clock.stamp();
        int top = 0;
        stack[top++] = slot;
        while (top > 0) {
            int current = stack[--top];
            for (int child = firstChild[current]; child != NONE; child = nextSibling[child]) {
                stack[top++] = child;
            }
            log(stamp, NodeHandle.of(current, generation[current]), NodeChange.REMOVED);
            alive[current] = false;
            pending[current] = 0;
            version[current] = stamp;
            freeSlots[freeCount++] = current;
            size--;
        }
    }

    /** Whether {@code node} names a node of this scene that was not removed. */
    public boolean contains(long node) {
        int slot = NodeHandle.index(node);
        return node != NodeHandle.NONE && slot >= 0 && slot < slotCount && alive[slot]
            && generation[slot] == NodeHandle.generation(node);
    }

    /** How many nodes there are. */
    public int size() {
        return size;
    }

    /**
     * One more than the highest slot in use: arrays indexed by {@link NodeHandle#index(long)} need this length.
     */
    public int slotCount() {
        return slotCount;
    }

    /** Visits every node, parents before children, e.g. to rebuild after falling behind the change log. */
    public void forEach(NodeVisitor visitor) {
        for (int slot = 0; slot < slotCount; slot++) {
            if (alive[slot] && parent[slot] == NONE) {
                int top = 0;
                stack[top++] = slot;
                while (top > 0) {
                    int current = stack[--top];
                    visitor.visit(NodeHandle.of(current, generation[current]));
                    for (int child = firstChild[current]; child != NONE; child = nextSibling[child]) {
                        stack[top++] = child;
                    }
                }
            }
        }
    }

    /** Receives nodes. */
    @FunctionalInterface
    public interface NodeVisitor {

        void visit(long node);
    }

    // ---------------------------------------------------------------- hierarchy

    /** The parent of {@code node}, or {@link NodeHandle#NONE}. */
    public long parent(long node) {
        return handle(parent[slot(node)]);
    }

    /** The first child of {@code node}, or {@link NodeHandle#NONE}; the others follow with {@link #nextSibling}. */
    public long firstChild(long node) {
        return handle(firstChild[slot(node)]);
    }

    /** The next child of {@code node}'s parent, or {@link NodeHandle#NONE}. */
    public long nextSibling(long node) {
        return handle(nextSibling[slot(node)]);
    }

    /**
     * Moves {@code node} under {@code newParent} ({@link NodeHandle#NONE} for none), keeping its local transform, so
     * its world transform follows the new parent.
     */
    public void setParent(long node, long newParent) {
        int slot = slot(node);
        int parentSlot = newParent == NodeHandle.NONE ? NONE : slot(newParent);
        for (int ancestor = parentSlot; ancestor != NONE; ancestor = parent[ancestor]) {
            if (ancestor == slot) {
                throw new IllegalArgumentException(NodeHandle.toString(newParent) + " is "
                    + NodeHandle.toString(node) + " or below it");
            }
        }
        if (parent[slot] == parentSlot) {
            return;
        }
        unlink(slot);
        if (parentSlot != NONE) {
            link(slot, parentSlot);
        }
        markDirty(slot);
        markPending(slot, NodeChange.PARENT);
    }

    private void link(int slot, int parentSlot) {
        parent[slot] = parentSlot;
        int first = firstChild[parentSlot];
        nextSibling[slot] = first;
        previousSibling[slot] = NONE;
        if (first != NONE) {
            previousSibling[first] = slot;
        }
        firstChild[parentSlot] = slot;
    }

    private void unlink(int slot) {
        int parentSlot = parent[slot];
        if (parentSlot == NONE) {
            return;
        }
        int previous = previousSibling[slot];
        int next = nextSibling[slot];
        if (previous == NONE) {
            firstChild[parentSlot] = next;
        } else {
            nextSibling[previous] = next;
        }
        if (next != NONE) {
            previousSibling[next] = previous;
        }
        parent[slot] = NONE;
        nextSibling[slot] = NONE;
        previousSibling[slot] = NONE;
    }

    // ---------------------------------------------------------------- transforms

    /** Sets the node's position relative to its parent. */
    public void setTranslation(long node, double x, double y, double z) {
        int slot = slot(node);
        MemorySegment s = local.segment();
        long offset = slot * local.stride();
        put(s, offset, 0, x);
        put(s, offset, 1, y);
        put(s, offset, 2, z);
        markDirty(slot);
    }

    /** Sets the node's rotation relative to its parent, as a unit quaternion. */
    public void setRotation(long node, double x, double y, double z, double w) {
        int slot = slot(node);
        MemorySegment s = local.segment();
        long offset = slot * local.stride();
        put(s, offset, 3, x);
        put(s, offset, 4, y);
        put(s, offset, 5, z);
        put(s, offset, 6, w);
        markDirty(slot);
    }

    /** Sets the node's scale relative to its parent. */
    public void setScale(long node, double x, double y, double z) {
        int slot = slot(node);
        MemorySegment s = local.segment();
        long offset = slot * local.stride();
        put(s, offset, 7, x);
        put(s, offset, 8, y);
        put(s, offset, 9, z);
        markDirty(slot);
    }

    /** Sets the node's whole transform relative to its parent. */
    public void setLocal(long node, Transform transform) {
        Vec3 t = transform.translation();
        Quat r = transform.rotation();
        Vec3 s = transform.scale();
        setTranslation(node, t.x(), t.y(), t.z());
        setRotation(node, r.x(), r.y(), r.z(), r.w());
        setScale(node, s.x(), s.y(), s.z());
    }

    /** The node's transform relative to its parent. */
    @AllowAllocation("a value for convenience; read localTransforms() on hot paths")
    public Transform local(long node) {
        return local.get(slot(node));
    }

    /** The node's world transform as of the last {@link #update()}. */
    @AllowAllocation("a value for convenience; read worldTransforms() on hot paths")
    public Transform world(long node) {
        return world.get(slot(node));
    }

    /** Local transforms by {@link NodeHandle#index(long)}; read them, change them through the setters. */
    public TransformArray localTransforms() {
        return local;
    }

    /**
     * World transforms by {@link NodeHandle#index(long)}, as of the last {@link #update()}: for renderers and physics
     * to read without copying. Do not write them. The array is replaced when the scene grows, so fetch it after
     * {@link #update()} instead of keeping it.
     */
    public TransformArray worldTransforms() {
        return world;
    }

    private static void identity(TransformArray transforms, int slot) {
        MemorySegment s = transforms.segment();
        long offset = slot * transforms.stride();
        for (int component = 0; component < 10; component++) {
            put(s, offset, component, component >= 6 ? 1 : 0); // rotation w and scale
        }
    }

    private static void put(MemorySegment segment, long offset, int component, double value) {
        segment.set(ValueLayout.JAVA_DOUBLE, offset + (long) component * Double.BYTES, value);
    }

    // ---------------------------------------------------------------- bounds, mobility and layers

    /**
     * Sets the node's bounds in its own space, e.g. its mesh's box; its world bounds follow its transform from the
     * next {@link #update()}. Nodes start without bounds (groups, pivots), which queries skip.
     */
    public void setLocalBounds(long node, double minX, double minY, double minZ, double maxX, double maxY,
        double maxZ) {
        int slot = slot(node);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Bounds with a minimum above the maximum");
        }
        localBounds.set(slot, minX, minY, minZ, maxX, maxY, maxZ);
        markBoundsDirty(slot);
    }

    /** Sets the node's bounds in its own space. */
    public void setLocalBounds(long node, Aabb bounds) {
        Vec3 min = bounds.min();
        Vec3 max = bounds.max();
        setLocalBounds(node, min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }

    /** Removes the node's bounds: queries skip it from the next {@link #update()}. */
    public void clearBounds(long node) {
        int slot = slot(node);
        localBounds.set(slot, EMPTY, EMPTY, EMPTY, -EMPTY, -EMPTY, -EMPTY);
        markBoundsDirty(slot);
    }

    /** Whether the node has bounds. */
    public boolean hasBounds(long node) {
        return hasBounds(slot(node), localBounds);
    }

    private static boolean hasBounds(int slot, AabbArray bounds) {
        return bounds.get(slot, 0) <= bounds.get(slot, 3);
    }

    /** The node's bounds in its own space, or {@link Aabb#EMPTY}. */
    @AllowAllocation("a value for convenience; read worldBounds() on hot paths")
    public Aabb localBounds(long node) {
        return localBounds.get(slot(node));
    }

    /** The node's world-space box as of the last {@link #update()}, or {@link Aabb#EMPTY}. */
    @AllowAllocation("a value for convenience; read worldBounds() on hot paths")
    public Aabb worldBounds(long node) {
        return worldBounds.get(slot(node));
    }

    /**
     * World-space boxes by {@link NodeHandle#index(long)}, as of the last {@link #update()}; empty (minimum above
     * maximum) for nodes without bounds. Read them, do not write them; fetch the array after {@link #update()}.
     */
    public AabbArray worldBounds() {
        return worldBounds;
    }

    public Mobility mobility(long node) {
        return dynamic[slot(node)] ? Mobility.DYNAMIC : Mobility.STATIC;
    }

    /** Whether the node is expected to move; see {@link Mobility}. */
    public void setMobility(long node, Mobility mobility) {
        int slot = slot(node);
        boolean moving = mobility == Mobility.DYNAMIC;
        if (dynamic[slot] != moving) {
            dynamic[slot] = moving;
            markPending(slot, NodeChange.MOBILITY);
        }
    }

    /** The node's layer mask; see {@link SceneLayer}. */
    public int layers(long node) {
        return layers[slot(node)];
    }

    /** Puts the node on the layers in {@code mask} ({@link SceneLayer#mask()} bits). */
    public void setLayers(long node, int mask) {
        int slot = slot(node);
        if (layers[slot] != mask) {
            layers[slot] = mask;
            markPending(slot, NodeChange.LAYERS);
        }
    }

    private void markBoundsDirty(int slot) {
        if (!boundsDirty[slot]) {
            boundsDirty[slot] = true;
            boundsDirtySlots[boundsDirtyCount++] = slot;
        }
    }

    /**
     * The world box of the node's local box under its world transform: the transformed center, with half extents
     * from the absolute rotation-scale matrix, so it encloses the rotated box exactly.
     */
    private void computeWorldBounds(int slot) {
        if (!hasBounds(slot, localBounds)) {
            worldBounds.set(slot, EMPTY, EMPTY, EMPTY, -EMPTY, -EMPTY, -EMPTY);
            return;
        }
        double cx = (localBounds.get(slot, 0) + localBounds.get(slot, 3)) * 0.5;
        double cy = (localBounds.get(slot, 1) + localBounds.get(slot, 4)) * 0.5;
        double cz = (localBounds.get(slot, 2) + localBounds.get(slot, 5)) * 0.5;
        double ex = (localBounds.get(slot, 3) - localBounds.get(slot, 0)) * 0.5;
        double ey = (localBounds.get(slot, 4) - localBounds.get(slot, 1)) * 0.5;
        double ez = (localBounds.get(slot, 5) - localBounds.get(slot, 2)) * 0.5;
        MemorySegment w = world.segment();
        long o = slot * world.stride();
        double qx = get(w, o, 3);
        double qy = get(w, o, 4);
        double qz = get(w, o, 5);
        double qw = get(w, o, 6);
        double sx = get(w, o, 7);
        double sy = get(w, o, 8);
        double sz = get(w, o, 9);
        double m00 = 1 - 2 * (qy * qy + qz * qz);
        double m01 = 2 * (qx * qy - qz * qw);
        double m02 = 2 * (qx * qz + qy * qw);
        double m10 = 2 * (qx * qy + qz * qw);
        double m11 = 1 - 2 * (qx * qx + qz * qz);
        double m12 = 2 * (qy * qz - qx * qw);
        double m20 = 2 * (qx * qz - qy * qw);
        double m21 = 2 * (qy * qz + qx * qw);
        double m22 = 1 - 2 * (qx * qx + qy * qy);
        double vx = sx * cx;
        double vy = sy * cy;
        double vz = sz * cz;
        double wx = get(w, o, 0) + m00 * vx + m01 * vy + m02 * vz;
        double wy = get(w, o, 1) + m10 * vx + m11 * vy + m12 * vz;
        double wz = get(w, o, 2) + m20 * vx + m21 * vy + m22 * vz;
        double ax = Math.abs(sx) * ex;
        double ay = Math.abs(sy) * ey;
        double az = Math.abs(sz) * ez;
        double hx = Math.abs(m00) * ax + Math.abs(m01) * ay + Math.abs(m02) * az;
        double hy = Math.abs(m10) * ax + Math.abs(m11) * ay + Math.abs(m12) * az;
        double hz = Math.abs(m20) * ax + Math.abs(m21) * ay + Math.abs(m22) * az;
        worldBounds.set(slot, wx - hx, wy - hy, wz - hz, wx + hx, wy + hy, wz + hz);
    }

    private static double get(MemorySegment segment, long offset, int component) {
        return segment.get(ValueLayout.JAVA_DOUBLE, offset + (long) component * Double.BYTES);
    }

    // ---------------------------------------------------------------- update and changes

    /**
     * Computes the world transforms of every node moved, created or reparented since the last update, and of their
     * descendants, parents first; then logs the changes with one stamp.
     */
    public void update() {
        for (int i = 0; i < dirtyCount; i++) {
            int slot = dirtySlots[i];
            if (alive[slot] && !hasDirtyAncestor(slot)) {
                propagate(slot);
            }
        }
        for (int i = 0; i < dirtyCount; i++) {
            dirty[dirtySlots[i]] = false;
        }
        dirtyCount = 0;
        for (int i = 0; i < boundsDirtyCount; i++) {
            int slot = boundsDirtySlots[i];
            boundsDirty[slot] = false;
            if (alive[slot]) {
                computeWorldBounds(slot);
                markPending(slot, NodeChange.BOUNDS);
            }
        }
        boundsDirtyCount = 0;
        if (pendingCount > 0) {
            long stamp = clock.stamp();
            for (int i = 0; i < pendingCount; i++) {
                int slot = pendingSlots[i];
                int changes = pending[slot];
                if (changes != 0) { // removing clears a node's pending changes
                    log(stamp, NodeHandle.of(slot, generation[slot]), changes);
                    version[slot] = stamp;
                }
                pending[slot] = 0;
                listed[slot] = false;
            }
            pendingCount = 0;
        }
    }

    private boolean hasDirtyAncestor(int slot) {
        for (int ancestor = parent[slot]; ancestor != NONE; ancestor = parent[ancestor]) {
            if (dirty[ancestor]) {
                return true;
            }
        }
        return false;
    }

    /** Recomputes the world transforms of {@code root} and everything below it. */
    private void propagate(int root) {
        int top = 0;
        stack[top++] = root;
        while (top > 0) {
            int slot = stack[--top];
            int parentSlot = parent[slot];
            if (parentSlot == NONE) {
                MemorySegment.copy(local.segment(), slot * local.stride(), world.segment(), slot * world.stride(),
                    world.stride());
            } else {
                world.setProduct(slot, world, parentSlot, local, slot);
            }
            if (hasBounds(slot, localBounds)) {
                computeWorldBounds(slot);
                markPending(slot, NodeChange.TRANSFORM | NodeChange.BOUNDS);
            } else {
                markPending(slot, NodeChange.TRANSFORM);
            }
            for (int child = firstChild[slot]; child != NONE; child = nextSibling[child]) {
                stack[top++] = child;
            }
        }
    }

    /**
     * Reports every node that changed after {@code since} (a {@link com.truesim.common.state.ChangeCursor} mark), in
     * the order of the changes, one report per node and update. Returns false, reporting nothing, when changes that
     * old are no longer kept: rebuild from {@link #forEach} then.
     */
    public boolean changesSince(long since, NodeChange.Visitor visitor) {
        if (since < logIncompleteThrough) {
            return false;
        }
        for (int i = firstAfter(since); i < logSize; i++) {
            int entry = (logStart + i) % logStamps.length;
            visitor.changed(logNodes[entry], logChanges[entry]);
        }
        return true;
    }

    /** The position in the log of the first entry stamped after {@code since}: stamps only grow, so a binary search. */
    private int firstAfter(long since) {
        int low = 0;
        int high = logSize;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (logStamps[(logStart + middle) % logStamps.length] > since) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    /** The stamp of the node's last logged change. */
    public long version(long node) {
        return version[slot(node)];
    }

    private void markDirty(int slot) {
        if (!dirty[slot]) {
            dirty[slot] = true;
            dirtySlots[dirtyCount++] = slot;
        }
    }

    private void markPending(int slot, int change) {
        if (!listed[slot]) {
            listed[slot] = true;
            pendingSlots[pendingCount++] = slot;
        }
        pending[slot] |= change;
    }

    private void log(long stamp, long node, int changes) {
        if (logSize == logStamps.length) {
            logIncompleteThrough = logStamps[logStart];
            logStart = (logStart + 1) % logStamps.length;
            logSize--;
        }
        int entry = (logStart + logSize) % logStamps.length;
        logStamps[entry] = stamp;
        logNodes[entry] = node;
        logChanges[entry] = changes;
        logSize++;
    }

    // ---------------------------------------------------------------- handles

    private int slot(long node) {
        if (!contains(node)) {
            throw new IllegalArgumentException(staleMessage(node));
        }
        return NodeHandle.index(node);
    }

    @AllowAllocation("diagnostics")
    private static String staleMessage(long node) {
        return "Not a node of this scene: " + NodeHandle.toString(node);
    }

    private long handle(int slot) {
        return slot == NONE ? NodeHandle.NONE : NodeHandle.of(slot, generation[slot]);
    }
}
