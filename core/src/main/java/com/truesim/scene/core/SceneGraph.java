package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.TransformArray;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Aabb;
import com.truesim.common.math.geometry.AabbArray;
import com.truesim.common.math.spatial.Bvh;
import com.truesim.common.state.ChangeClock;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 * <p><b>Many nodes at once.</b> A subtree built away from the scene, on a worker, is a {@link SceneBatch}:
 * {@link #attach(SceneBatch, long)} puts it in on the scene's thread in one pass, {@link #detach(long, SceneBatch)}
 * takes a subtree out into one, and {@link #remove(long)} removes a whole subtree. On the scene's thread,
 * {@link #create(long, long[], int, int)} makes many nodes and {@link #remove(long[], int, int)} removes many subtrees.
 * Each of these is logged as one entry ({@link ChangeBatch}), in the scene's log and in each component store's, so
 * systems following the logs take the nodes in one pass ({@link NodeChange.Visitor#changed(ChangeBatch)}).
 *
 * <p>A scene graph is used by one thread at a time. Name that thread with {@link ChangeClock#ownedBy} on the scene's
 * clock and every change from another one throws, instead of corrupting the scene unnoticed. A {@link SceneBatch} is
 * the way to build nodes on other threads.
 */
public final class SceneGraph {

    /** The default number of changes kept for consumers that read them later. */
    public static final int DEFAULT_LOG_CAPACITY = 1 << 16;

    private static final int NONE = -1;
    /** Fewer moved nodes than this propagate on the calling thread even with a job system: not worth the hand-off. */
    private static final int PARALLEL_MIN = 256;
    /** Moved subtrees per parallel chunk: most are single moving nodes. */
    private static final int ROOTS_PER_CHUNK = 64;
    /** Bounds from +infinity to -infinity: none. */
    static final double EMPTY = Double.POSITIVE_INFINITY;

    private final ChangeClock clock;
    private final Components components = new Components(this);
    private final List<Prepared> prepared = new ArrayList<>();
    private long updates;

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
    private int[] groups;
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

    // Propagating in parallel, when a job system is set: the roots of the moved subtrees, computed one per index.
    private JobSystem jobs;
    private int[] roots;
    private int rootCount;
    private final JobSystem.RangeTask computeRoots = (from, to, worker) -> {
        for (int i = from; i < to; i++) {
            compute(roots[i]);
        }
    };
    private boolean[] boundsDirty;
    private int[] boundsDirtySlots;
    private int boundsDirtyCount;

    // The change log: entries of (stamp, nodes changed). It grows rather than drop a change made since the last update,
    // so a consumer that keeps up with updates never has to rebuild, however many nodes change at once.
    private final ChangeLog log;
    // Nodes made together (attach, create many) waiting for the next update's log: their slots, by batch; a batch takes
    // one place in pendingSlots, as -(batch + 1), so the log keeps the order of the calls.
    private int[] batchSlots = new int[64];
    private int batchSlotCount;
    private int[] batchFrom = new int[8];
    private int[] batchSize = new int[8];
    private int batchCount;
    // Scratch, by slot capacity: nodes logged or removed together, their changes, handles of an attach, batch numbers.
    private long[] together;
    private int[] togetherChanges;
    private long[] attachHandles;
    private int[] detachIndex;
    private long updatedAt = -1; // the clock's time at the end of the last update

    /** An empty scene stamping its changes on {@code clock}. */
    public SceneGraph(ChangeClock clock) {
        this(clock, 64, DEFAULT_LOG_CAPACITY);
    }

    /**
     * An empty scene with room for {@code capacity} nodes before it grows, keeping the last {@code logCapacity}
     * changes for consumers, and every change since the last update: the log grows rather than drop one.
     */
    @AllowAllocation("set up once")
    public SceneGraph(ChangeClock clock, int capacity, int logCapacity) {
        if (capacity < 1 || logCapacity < 1) {
            throw new IllegalArgumentException("Capacities must be positive: " + capacity + ", " + logCapacity);
        }
        this.clock = clock;
        allocate(capacity);
        log = new ChangeLog(logCapacity, logCapacity);
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
        pendingSlots = Arrays.copyOf(pendingSlots == null ? new int[0] : pendingSlots,
            Math.max(capacity, pendingSlots == null ? 0 : pendingSlots.length));
        stack = Arrays.copyOf(stack == null ? new int[0] : stack, capacity);
        roots = Arrays.copyOf(roots == null ? new int[0] : roots, capacity);
        dynamic = Arrays.copyOf(dynamic == null ? new boolean[0] : dynamic, capacity);
        layers = Arrays.copyOf(layers == null ? new int[0] : layers, capacity);
        groups = Arrays.copyOf(groups == null ? new int[0] : groups, capacity);
        boundsDirty = Arrays.copyOf(boundsDirty == null ? new boolean[0] : boundsDirty, capacity);
        boundsDirtySlots = Arrays.copyOf(boundsDirtySlots == null ? new int[0] : boundsDirtySlots, capacity);
        together = new long[capacity];
        togetherChanges = new int[capacity];
        attachHandles = new long[capacity];
        detachIndex = new int[capacity];
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

    /**
     * The components on this scene's nodes (application-api.md D46): cameras, lights, pick shapes, a game's own. A
     * removed node loses its components with it.
     */
    public Components components() {
        return components;
    }

    // ---------------------------------------------------------------- nodes

    /** A new node at the world's origin, without a parent. */
    public long create() {
        return create(NodeHandle.NONE);
    }

    /** A new node with identity local transform under {@code parent} ({@link NodeHandle#NONE} for none). */
    public long create(long parent) {
        checkOwner();
        int parentSlot = parent == NodeHandle.NONE ? NONE : slot(parent);
        int slot = newSlot();
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
        groups[slot] = 0;
        if (parentSlot != NONE) {
            link(slot, parentSlot);
        }
        size++;
        markDirty(slot);
        markPending(slot, NodeChange.CREATED);
        return NodeHandle.of(slot, generation[slot]);
    }

    /** Grows the storage, if needed, so that {@code nodes} more fit. */
    private void room(int nodes) {
        if (nodes > freeCount && slotCount + nodes - freeCount > generation.length) {
            allocate(Math.max(generation.length * 2, slotCount + nodes - freeCount));
        }
    }

    /** A free slot, growing the storage when there is none. */
    private int newSlot() {
        if (freeCount > 0) {
            return freeSlots[--freeCount];
        }
        if (slotCount == generation.length) {
            allocate(generation.length * 2);
        }
        return slotCount++;
    }

    /**
     * Makes {@code count} nodes under {@code parent} ({@link NodeHandle#NONE} for none), as {@link #create(long)} makes
     * each, their handles into {@code into} from {@code from}; logged at the next update as one entry
     * ({@link ChangeBatch}). Allocates nothing once the scene has grown.
     */
    public void create(long parent, long[] into, int from, int count) {
        checkOwner();
        java.util.Objects.checkFromIndexSize(from, count, into.length);
        int parentSlot = parent == NodeHandle.NONE ? NONE : slot(parent);
        if (count == 0) {
            return;
        }
        room(count);
        int batch = beginBatch(count);
        for (int i = 0; i < count; i++) {
            int slot = newSlot();
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
            groups[slot] = 0;
            if (parentSlot != NONE) {
                link(slot, parentSlot);
            }
            size++;
            markDirty(slot);
            markBatched(batch, slot, NodeChange.CREATED);
            into[from + i] = NodeHandle.of(slot, generation[slot]);
        }
    }

    /** Starts a batch of {@code count} nodes made together, logged as one entry at the next update. */
    private int beginBatch(int count) {
        if (batchSlotCount + count > batchSlots.length) {
            growBatchSlots(batchSlotCount + count);
        }
        if (batchCount == batchFrom.length) {
            growBatches();
        }
        if (pendingCount == pendingSlots.length) { // only when batches listed nothing new: slots reused, say
            growPending();
        }
        int batch = batchCount++;
        batchFrom[batch] = batchSlotCount;
        batchSize[batch] = 0;
        pendingSlots[pendingCount++] = -(batch + 1);
        return batch;
    }

    @AllowAllocation("growing with the most nodes made together between updates")
    private void growBatchSlots(int length) {
        batchSlots = Arrays.copyOf(batchSlots, Math.max(length, 2 * batchSlots.length));
    }

    @AllowAllocation("growing with the most changes between updates")
    private void growPending() {
        pendingSlots = Arrays.copyOf(pendingSlots, 2 * pendingSlots.length);
    }

    @AllowAllocation("growing with the most batches between updates")
    private void growBatches() {
        batchFrom = Arrays.copyOf(batchFrom, 2 * batchFrom.length);
        batchSize = Arrays.copyOf(batchSize, 2 * batchSize.length);
    }

    /** {@link #markPending} for a node of batch {@code batch}: listed in the batch, not alone. */
    private void markBatched(int batch, int slot, int change) {
        if (!listed[slot]) { // a slot listed already (freed and reused since) is logged where it was listed
            listed[slot] = true;
            batchSlots[batchSlotCount++] = slot;
            batchSize[batch]++;
        }
        pending[slot] |= change;
    }

    // ---------------------------------------------------------------- batches

    /**
     * Puts {@code batch}'s nodes into the scene, its roots under {@code parent} ({@link NodeHandle#NONE}: in the world),
     * with their components, in one pass; returns the handle of the batch's node 0. As
     * {@link #attach(SceneBatch, long, long[])}, keeping no handles but the first: a subtree under one root is removed
     * by it.
     */
    public long attach(SceneBatch batch, long parent) {
        checkOwner();
        if (batch.size() == 0) {
            throw new IllegalArgumentException("An empty batch has no node 0");
        }
        room(batch.size()); // first: growing replaces the scratch
        attach(batch, parent, attachHandles);
        return attachHandles[0];
    }

    /**
     * Puts {@code batch}'s nodes into the scene, its roots under {@code parent} ({@link NodeHandle#NONE}: in the world),
     * in the batch's group, writing their handles to {@code handles} by batch number. Their components go into the
     * stores. One pass: the nodes are logged as one entry at the next update ({@link NodeChange#CREATED}, with
     * {@link NodeChange#TRANSFORM} and {@link NodeChange#BOUNDS} as they have them), and each component type as one
     * entry now ({@link ComponentStore#ADDED}).
     *
     * <p>A prepared batch ({@link SceneBatch#prepare()}) attached into the world is copied with its world transforms
     * and bounds, so {@link #update()} has nothing to compute for it, and the tree of its static nodes is handed to the
     * scene's {@link SpatialIndex}, which adopts it instead of building one (those nodes are logged
     * {@link NodeChange#PREPARED} too). Under a parent, its roots' world transforms are computed at the next update. The
     * batch is copied: once this returns it is the caller's again, to clear and reuse. Allocates nothing once the
     * scene and the stores have grown, but to hand a static tree over.
     */
    public void attach(SceneBatch batch, long parent, long[] handles) {
        checkOwner();
        if (handles.length < batch.size()) {
            throw new IllegalArgumentException("Room for " + handles.length + " handles, the batch has " + batch.size());
        }
        int parentSlot = parent == NodeHandle.NONE ? NONE : slot(parent);
        if (batch.size() == 0) {
            return;
        }
        room(batch.size());
        boolean placed = parentSlot == NONE;
        if (placed && !batch.isPrepared()) {
            batch.prepare();
        }
        int group = batch.group();
        long transformStride = local.stride();
        long boundsStride = localBounds.stride();
        int logged = beginBatch(batch.size());
        for (int node = 0; node < batch.size(); node++) {
            int slot = newSlot();
            generation[slot]++;
            alive[slot] = true;
            this.parent[slot] = NONE;
            firstChild[slot] = NONE;
            nextSibling[slot] = NONE;
            previousSibling[slot] = NONE;
            int batchParent = batch.parentOf(node);
            if (batchParent >= 0) {
                link(slot, NodeHandle.index(handles[batchParent]));
            } else if (parentSlot != NONE) {
                link(slot, parentSlot);
                markDirty(slot); // its world follows the parent's: computed at the update, with its subtree
            }
            MemorySegment.copy(batch.local().segment(), node * transformStride, local.segment(),
                slot * transformStride, transformStride);
            MemorySegment.copy(batch.localBounds().segment(), node * boundsStride, localBounds.segment(),
                slot * boundsStride, boundsStride);
            if (placed) {
                MemorySegment.copy(batch.world().segment(), node * transformStride, world.segment(),
                    slot * transformStride, transformStride);
                MemorySegment.copy(batch.worldBounds().segment(), node * boundsStride, worldBounds.segment(),
                    slot * boundsStride, boundsStride);
            }
            dynamic[slot] = batch.isDynamic(node);
            layers[slot] = batch.layers(node);
            groups[slot] = group;
            size++;
            boolean bounded = hasBounds(slot, localBounds);
            markBatched(logged, slot, NodeChange.CREATED | NodeChange.TRANSFORM | (bounded ? NodeChange.BOUNDS : 0)
                | (placed && bounded && !dynamic[slot] ? NodeChange.PREPARED : 0));
            handles[node] = NodeHandle.of(slot, generation[slot]);
        }
        for (int t = 0; t < batch.typeCount(); t++) {
            int count = batch.componentCount(t);
            if (count == 0) {
                continue;
            }
            int[] nodes = batch.componentNodes(t);
            for (int i = 0; i < count; i++) {
                together[i] = handles[nodes[i]];
            }
            components.store(batch.type(t)).setChecked(together, batch.componentValues(t), 0, count);
        }
        if (placed && batch.staticTree() != null) {
            handOver(batch, group, handles);
        }
    }

    @AllowAllocation("applying a batch: handing its static tree over")
    private void handOver(SceneBatch batch, int group, long[] handles) {
        int[] statics = batch.staticNodes();
        long[] nodes = new long[statics.length];
        for (int i = 0; i < statics.length; i++) {
            nodes[i] = handles[statics[i]];
        }
        prepared.add(new Prepared(group, batch.staticTree(), nodes, batch.staticBox().clone(), updates));
    }

    /**
     * Adds a {@link SceneBatch}'s nodes to the world in {@code group}, writing their handles to {@code handles} in the
     * batch's numbering: {@link #attach(SceneBatch, long, long[])} into the world, the batch's group set to
     * {@code group} first.
     */
    public void apply(SceneBatch batch, int group, long[] handles) {
        checkOwner();
        batch.setGroup(group);
        attach(batch, NodeHandle.NONE, handles);
    }

    /**
     * Takes {@code root} and its subtree out of the scene into {@code into}: added there as a new root (after what it
     * holds), with its local transform, and below it its descendants with their transforms, bounds, mobility, layers
     * and components; then removed, as {@link #remove(long)} removes. Returns the batch number of the root. Attaching
     * the batch again (here, under another parent, or in another scene) puts the subtree back as it was, in new nodes.
     */
    public int detach(long root, SceneBatch into) {
        int rootSlot = changing(root);
        int top = 0;
        stack[top++] = rootSlot;
        int first = -1;
        while (top > 0) {
            int slot = stack[--top];
            int parentIndex = slot == rootSlot ? -1 : detachIndex[parent[slot]];
            int node = into.add(parentIndex);
            if (first < 0) {
                first = node;
            }
            detachIndex[slot] = node;
            into.copyLocal(node, local, slot);
            into.copyLocalBounds(node, localBounds, slot);
            into.setMobility(node, dynamic[slot] ? Mobility.DYNAMIC : Mobility.STATIC);
            into.setLayers(node, layers[slot]);
            long handle = NodeHandle.of(slot, generation[slot]);
            for (int s = 0; s < components.storeCount(); s++) {
                ComponentStore<?> store = components.storeAt(s);
                Object value = store.get(handle);
                if (value != null) {
                    into.setUnchecked(node, store.type(), value);
                }
            }
            for (int child = firstChild[slot]; child != NONE; child = nextSibling[child]) {
                stack[top++] = child;
            }
        }
        remove(root);
        return first;
    }

    /** A static tree from an applied batch, waiting for the spatial index to adopt it. */
    static final class Prepared {
        final int group;
        final Bvh tree;
        final long[] nodes;
        final double[] box;
        final long applied;
        boolean adopted;

        Prepared(int group, Bvh tree, long[] nodes, double[] box, long applied) {
            this.group = group;
            this.tree = tree;
            this.nodes = nodes;
            this.box = box;
            this.applied = applied;
        }
    }

    int preparedCount() {
        return prepared.size();
    }

    Prepared prepared(int index) {
        return prepared.get(index);
    }

    /** Forgets prepared trees older than the last update: an index that missed them builds its own. */
    private void prunePrepared() {
        for (int i = prepared.size() - 1; i >= 0; i--) {
            if (prepared.get(i).applied < updates - 1) {
                prepared.remove(i);
            }
        }
    }

    /**
     * Removes {@code node} and all its descendants, in one pass; their handles become stale, their components go. Logged
     * as one entry ({@link ChangeBatch}) of {@link NodeChange#REMOVED} for a subtree, and one entry per component
     * store. Allocates nothing.
     */
    public void remove(long node) {
        int slot = changing(node);
        unlink(slot);
        int count = takeOut(slot, 0);
        logRemoved(count);
    }

    /**
     * Removes nodes {@code from} to {@code from + count - 1} of {@code nodes} and their descendants, in one pass, logged
     * as one entry as {@link #remove(long)}. Nodes removed already by the time they are reached (below another of the
     * list, say) are skipped. Allocates nothing.
     */
    public void remove(long[] nodes, int from, int count) {
        checkOwner();
        java.util.Objects.checkFromIndexSize(from, count, nodes.length);
        int removed = 0;
        for (int i = from; i < from + count; i++) {
            if (contains(nodes[i])) {
                int slot = NodeHandle.index(nodes[i]);
                unlink(slot);
                removed = takeOut(slot, removed);
            }
        }
        logRemoved(removed);
    }

    /** Frees {@code slot} and its subtree, their handles into {@link #together} from {@code at}; returns the end. */
    private int takeOut(int slot, int at) {
        int top = 0;
        stack[top++] = slot;
        while (top > 0) {
            int current = stack[--top];
            for (int child = firstChild[current]; child != NONE; child = nextSibling[child]) {
                stack[top++] = child;
            }
            together[at++] = NodeHandle.of(current, generation[current]);
            alive[current] = false;
            pending[current] = 0;
            freeSlots[freeCount++] = current;
            size--;
        }
        return at;
    }

    /** Logs the first {@code count} nodes of {@link #together} removed, and removes their components. */
    private void logRemoved(int count) {
        if (count == 0) {
            return;
        }
        long stamp = clock.stamp();
        for (int i = 0; i < count; i++) {
            version[NodeHandle.index(together[i])] = stamp;
        }
        log.addAll(stamp, updatedAt, together, null, NodeChange.REMOVED, 0, count);
        components.nodesRemoved(together, count);
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
        int slot = changing(node);
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

    /**
     * Sets the node's position relative to its parent.
     *
     * @throws IllegalArgumentException if it is not finite
     */
    public void setTranslation(long node, double x, double y, double z) {
        int slot = changing(node);
        if (!Double.isFinite(x + y + z)) {
            throw new IllegalArgumentException("Not a finite translation: " + x + ", " + y + ", " + z);
        }
        MemorySegment s = local.segment();
        long offset = slot * local.stride();
        put(s, offset, 0, x);
        put(s, offset, 1, y);
        put(s, offset, 2, z);
        markDirty(slot);
    }

    /**
     * Sets the node's rotation relative to its parent, as a quaternion: kept normalized, so one a little off unit
     * length (accumulated rounding, say) does not scale the node.
     *
     * @throws IllegalArgumentException if it is not finite, or too short to give a direction
     */
    public void setRotation(long node, double x, double y, double z, double w) {
        int slot = changing(node);
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(length > 1e-12) || length == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a rotation: " + x + ", " + y + ", " + z + ", " + w);
        }
        MemorySegment s = local.segment();
        long offset = slot * local.stride();
        put(s, offset, 3, x / length);
        put(s, offset, 4, y / length);
        put(s, offset, 5, z / length);
        put(s, offset, 6, w / length);
        markDirty(slot);
    }

    /**
     * Sets the node's scale relative to its parent.
     *
     * @throws IllegalArgumentException if it is not finite, or zero on an axis (nothing would be left to draw or hit)
     */
    public void setScale(long node, double x, double y, double z) {
        int slot = changing(node);
        if (!Double.isFinite(x + y + z) || x == 0 || y == 0 || z == 0) {
            throw new IllegalArgumentException("Not a scale on every axis: " + x + ", " + y + ", " + z);
        }
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
        setLocal(node, t.x(), t.y(), t.z(), r.x(), r.y(), r.z(), r.w(), s.x(), s.y(), s.z());
    }

    /**
     * Sets the node's whole transform relative to its parent: translation, rotation (kept normalized) and scale, checked
     * as their own setters check them, at once. Animation poses nodes through this.
     *
     * @throws IllegalArgumentException as {@link #setTranslation}, {@link #setRotation} or {@link #setScale} would; the
     *     node is unchanged then
     */
    public void setLocal(long node, double tx, double ty, double tz, double rx, double ry, double rz, double rw,
        double sx, double sy, double sz) {
        int slot = changing(node);
        if (!Double.isFinite(tx + ty + tz)) {
            throw new IllegalArgumentException("Not a finite translation: " + tx + ", " + ty + ", " + tz);
        }
        double length = Math.sqrt(rx * rx + ry * ry + rz * rz + rw * rw);
        if (!(length > 1e-12) || length == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a rotation: " + rx + ", " + ry + ", " + rz + ", " + rw);
        }
        if (!Double.isFinite(sx + sy + sz) || sx == 0 || sy == 0 || sz == 0) {
            throw new IllegalArgumentException("Not a scale on every axis: " + sx + ", " + sy + ", " + sz);
        }
        MemorySegment s = local.segment();
        long offset = slot * local.stride();
        put(s, offset, 0, tx);
        put(s, offset, 1, ty);
        put(s, offset, 2, tz);
        put(s, offset, 3, rx / length);
        put(s, offset, 4, ry / length);
        put(s, offset, 5, rz / length);
        put(s, offset, 6, rw / length);
        put(s, offset, 7, sx);
        put(s, offset, 8, sy);
        put(s, offset, 9, sz);
        markDirty(slot);
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

    static void identity(TransformArray transforms, int slot) {
        MemorySegment s = transforms.segment();
        long offset = slot * transforms.stride();
        for (int component = 0; component < 10; component++) {
            put(s, offset, component, component >= 6 ? 1 : 0); // rotation w and scale
        }
    }

    static void put(MemorySegment segment, long offset, int component, double value) {
        segment.set(ValueLayout.JAVA_DOUBLE, offset + (long) component * Double.BYTES, value);
    }

    // ---------------------------------------------------------------- bounds, mobility and layers

    /**
     * Sets the node's bounds in its own space, e.g. its mesh's box; its world bounds follow its transform from the
     * next {@link #update()}. Nodes start without bounds (groups, pivots), which queries skip.
     */
    public void setLocalBounds(long node, double minX, double minY, double minZ, double maxX, double maxY,
        double maxZ) {
        int slot = changing(node);
        if (!Double.isFinite(minX + minY + minZ + maxX + maxY + maxZ)) {
            throw new IllegalArgumentException("Bounds that are not finite");
        }
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
        int slot = changing(node);
        localBounds.set(slot, EMPTY, EMPTY, EMPTY, -EMPTY, -EMPTY, -EMPTY);
        markBoundsDirty(slot);
    }

    /** Whether the node has bounds. */
    public boolean hasBounds(long node) {
        return hasBounds(slot(node), localBounds);
    }

    static boolean hasBounds(int slot, AabbArray bounds) {
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
        int slot = changing(node);
        boolean moving = mobility == Mobility.DYNAMIC;
        if (dynamic[slot] != moving) {
            dynamic[slot] = moving;
            markPending(slot, NodeChange.MOBILITY);
        }
    }

    /** The node's group: 0 unless set. */
    public int group(long node) {
        return groups[slot(node)];
    }

    /**
     * Puts the node in group {@code group} (0 or more): a partition for the spatial index, which keeps a static tree
     * per group, so changing one group's static nodes rebuilds only that group's tree. Streaming puts each cell's nodes
     * in a group of their own; everything else stays in group 0.
     */
    public void setGroup(long node, int group) {
        int slot = changing(node);
        if (group < 0) {
            throw new IllegalArgumentException("Groups are 0 or more: " + group);
        }
        if (groups[slot] != group) {
            groups[slot] = group;
            markPending(slot, NodeChange.GROUP);
        }
    }

    /** The node's layer mask; see {@link SceneLayer}. */
    public int layers(long node) {
        return layers[slot(node)];
    }

    /** Puts the node on the layers in {@code mask} ({@link SceneLayer#mask()} bits). */
    public void setLayers(long node, int mask) {
        int slot = changing(node);
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
        worldBox(world, localBounds, worldBounds, slot);
    }

    /** Sets {@code out[slot]} to {@code local[slot]} under {@code transforms[slot]}, or empty without local bounds. */
    static void worldBox(TransformArray transforms, AabbArray localBounds, AabbArray out, int slot) {
        TransformArray world = transforms;
        AabbArray worldBounds = out;
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
        checkOwner();
        if (jobs != null && dirtyCount >= PARALLEL_MIN) {
            propagateInParallel();
        } else {
            for (int i = 0; i < dirtyCount; i++) {
                int slot = dirtySlots[i];
                if (alive[slot] && !hasDirtyAncestor(slot)) {
                    propagate(slot);
                }
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
                if (slot < 0) {
                    logBatch(stamp, -slot - 1);
                    continue;
                }
                int changes = pending[slot];
                if (changes != 0) { // removing clears a node's pending changes
                    log(stamp, NodeHandle.of(slot, generation[slot]), changes);
                    version[slot] = stamp;
                }
                pending[slot] = 0;
                listed[slot] = false;
            }
            pendingCount = 0;
            batchCount = 0;
            batchSlotCount = 0;
        }
        updates++;
        updatedAt = clock.now();
        prunePrepared();
    }

    /** Logs the nodes of batch {@code batch} with changes as one entry. */
    private void logBatch(long stamp, int batch) {
        int count = 0;
        for (int i = batchFrom[batch]; i < batchFrom[batch] + batchSize[batch]; i++) {
            int slot = batchSlots[i];
            int changes = pending[slot];
            if (changes != 0) {
                together[count] = NodeHandle.of(slot, generation[slot]);
                togetherChanges[count++] = changes;
                version[slot] = stamp;
            }
            pending[slot] = 0;
            listed[slot] = false;
        }
        log.addAll(stamp, updatedAt, together, togetherChanges, 0, 0, count);
    }

    private boolean hasDirtyAncestor(int slot) {
        for (int ancestor = parent[slot]; ancestor != NONE; ancestor = parent[ancestor]) {
            if (dirty[ancestor]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes the world transforms of the nodes moved since the last update on {@code jobs}, e.g. when many nodes
     * move or a large scene first updates; {@code null} (the default) computes on the calling thread. Moved subtrees
     * are computed in parallel, each writing only its own nodes; their changes are then recorded in the same order as
     * on one thread, so the change log does not depend on the number of threads. Call {@link #update()} from the thread
     * that submits to {@code jobs}.
     */
    public void setJobs(JobSystem jobs) {
        checkOwner();
        this.jobs = jobs;
    }

    /**
     * Like the serial loop in {@link #update()}: the moved subtrees' transforms and bounds in parallel, then their
     * changes recorded in order.
     */
    private void propagateInParallel() {
        rootCount = 0;
        for (int i = 0; i < dirtyCount; i++) {
            int slot = dirtySlots[i];
            if (alive[slot] && !hasDirtyAncestor(slot)) {
                roots[rootCount++] = slot;
            }
        }
        jobs.parallelFor(rootCount, ROOTS_PER_CHUNK, computeRoots);
        for (int i = 0; i < rootCount; i++) {
            record(roots[i]);
        }
    }

    /**
     * Computes the world transforms and bounds of {@code root} and everything below it, walking the child and sibling
     * links without a stack, so threads need nothing of their own.
     */
    private void compute(int root) {
        int slot = root;
        while (true) {
            computeNode(slot);
            if (firstChild[slot] != NONE) {
                slot = firstChild[slot];
                continue;
            }
            while (slot != root && nextSibling[slot] == NONE) {
                slot = parent[slot];
            }
            if (slot == root) {
                return;
            }
            slot = nextSibling[slot];
        }
    }

    private void computeNode(int slot) {
        int parentSlot = parent[slot];
        if (parentSlot == NONE) {
            MemorySegment.copy(local.segment(), slot * local.stride(), world.segment(), slot * world.stride(),
                world.stride());
        } else {
            world.setProduct(slot, world, parentSlot, local, slot);
        }
        if (hasBounds(slot, localBounds)) {
            computeWorldBounds(slot);
        }
    }

    /** Records the changes of {@code root} and everything below it, in the order {@link #propagate} does. */
    private void record(int root) {
        int top = 0;
        stack[top++] = root;
        while (top > 0) {
            int slot = stack[--top];
            markPending(slot, hasBounds(slot, localBounds) ? NodeChange.TRANSFORM | NodeChange.BOUNDS
                : NodeChange.TRANSFORM);
            for (int child = firstChild[slot]; child != NONE; child = nextSibling[child]) {
                stack[top++] = child;
            }
        }
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
     * the order of the changes, one report per node and update; nodes changed together (attached, made or removed at
     * once) as one {@link ChangeBatch}. Returns false, reporting nothing, when changes that
     * old are no longer kept: rebuild from {@link #forEach} then.
     */
    public boolean changesSince(long since, NodeChange.Visitor visitor) {
        return log.since(since, visitor);
    }

    /**
     * How many node changes {@link #changesSince} would report for {@code since} (a batch counts its nodes), or -1 when
     * changes that old are no longer kept: e.g. to rewrite everything rather than follow the changes when most nodes
     * changed. Allocates nothing.
     */
    public int changeCountSince(long since) {
        return log.countSince(since);
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
        // changed after attaching: its prepared tree is out of date (only attaching marks PREPARED)
        pending[slot] = pending[slot] & ~NodeChange.PREPARED | change;
    }

    private void log(long stamp, long node, int changes) {
        log.add(stamp, updatedAt, node, changes);
    }

    // ---------------------------------------------------------------- handles

    /** Throws unless the calling thread may change the scene: any, or the clock's {@linkplain ChangeClock#ownedBy owner}. */
    private void checkOwner() {
        Thread owner = clock.owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(notOwner(owner));
        }
    }

    /** The slot of {@code node}, about to change: checks the thread first. */
    private int changing(long node) {
        checkOwner();
        return slot(node);
    }

    @AllowAllocation("diagnostics")
    static String notOwner(Thread owner) {
        return "The scene changes on " + owner.getName() + " only, not on " + Thread.currentThread().getName();
    }

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
