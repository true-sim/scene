package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
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
 *   <li>Static nodes are indexed per {@linkplain SceneGraph#setGroup group}: each group has a tree of its own, and a
 *       top-level tree holds the groups' boxes in slots that groups joining and leaving reuse. Changing a group's
 *       static nodes rebuilds only its tree, then refits the top level. Streaming puts each cell in a group, so a cell
 *       coming or going costs about its own size.</li>
 *   <li>Dynamic nodes are in a tree with spare room: each frame it refits the boxes of the nodes that moved;
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
 * <p>Updating and querying allocate nothing, except when a tree is built or rebuilt. Queries are not reentrant: do not
 * query from inside a query's visitor. One thread at a time.
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

    // Membership, by scene slot: where, at which position, and for static nodes in which group.
    private byte[] where = new byte[0];
    private int[] position = new int[0];
    private int[] groupOf = new int[0];

    // Static nodes, by group: dense arrays, a tree each, and a box each.
    private long[][] groupNodes = new long[0][];
    private int[][] groupLayers = new int[0][];
    private int[] groupCount = new int[0];
    private Bvh[] groupTree = new Bvh[0];
    private double[] groupBox = new double[0];
    private boolean[] groupDirty = new boolean[0];
    private int[] dirtyGroups = new int[0];
    private int dirtyGroupCount;
    private int staticCount;

    // The top level: slots holding the boxes of groups with static nodes, with a free list, refit on change.
    private Bvh top;
    private int[] topGroups = new int[0];
    private int[] topSlot = new int[0];
    private int[] freeTop = new int[0];
    private int freeTopCount;
    private int topCount;

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

    // Rebuilding groups in parallel, when a job system is set.
    private JobSystem jobs;
    private final JobSystem.RangeTask buildGroups = (from, to, worker) -> {
        for (int i = from; i < to; i++) {
            buildGroup(dirtyGroups[i]);
        }
    };

    // Queries: one context for the public queries, one per job-system worker for several views at once.
    private final Query query = new Query();
    private Query[] workers = new Query[0];
    private Frustum[] viewFrustums;
    private int viewMask;
    private ViewResults viewResults;
    private final JobSystem.RangeTask queryViews = (from, to, worker) -> {
        for (int view = from; view < to; view++) {
            workers[worker].collect(view);
        }
    };
    private SceneGraph.Prepared[] adoptions = new SceneGraph.Prepared[0];
    private boolean[] adopting = new boolean[0];

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
     * change log, then rebuilds the trees of the static groups that changed, refits or rebuilds the top level, and
     * refits the dynamic tree. If the scene's log no longer reaches back to the previous update, the index rebuilds
     * from all nodes.
     */
    public void update() {
        long since = cursor.mark();
        int noted = collectPrepared();
        if (!scene.changesSince(since, onChange)) {
            clear();
            scene.forEach(onNode);
        }
        boolean adopted = finishAdoptions(noted);
        if (dirtyGroupCount > 0 || adopted) {
            rebuildGroups();
            updateTop();
        }
        if (dynamicMoved) { // adding a dynamic node built its tree
            dynamicBvh.refit();
            if (dynamicBvh.degradation() > rebuildThreshold) {
                dynamicBvh.rebuild();
            }
            dynamicMoved = false;
        }
    }

    /**
     * How much worse than freshly built the dynamic tree may get (estimated query cost, 2 = twice) before it is
     * rebuilt instead of refit. Lower is faster queries, higher is fewer rebuilds.
     */
    public void setRebuildThreshold(double threshold) {
        if (!(threshold >= 1)) {
            throw new IllegalArgumentException("The threshold must be at least 1: " + threshold);
        }
        this.rebuildThreshold = threshold;
    }

    /**
     * Rebuilds the trees of changed static groups on {@code jobs}, e.g. when a burst of cells attaches; {@code null}
     * (the default) rebuilds on the calling thread. Trees are the same either way: each group is built from its own
     * nodes, and groups take their top-level slots in the same order. {@link #queryFrustums} also queries its views in
     * parallel. The index stays single-threaded otherwise: call {@link #update()} and {@link #queryFrustums} from the
     * thread that submits to {@code jobs}.
     */
    public void setJobs(JobSystem jobs) {
        this.jobs = jobs;
    }

    /** How many static nodes are indexed. */
    public int staticCount() {
        return staticCount;
    }

    /** How many dynamic nodes are indexed. */
    public int dynamicCount() {
        return dynamicCount;
    }

    /** Group {@code group}'s static tree, or {@code null}: for tests. */
    Bvh groupTree(int group) {
        return group < groupTree.length ? groupTree[group] : null;
    }

    /** How many groups have static nodes. */
    public int groupCount() {
        return topCount;
    }

    private void changed(long node, int changes) {
        if (NodeChange.has(changes, NodeChange.REMOVED)) {
            int slot = NodeHandle.index(slotSafe(node));
            if (where[slot] != NOWHERE) { // the log is ordered: a slot is reused only after its removal
                remove(slot);
            }
        } else if (scene.contains(node)) {
            int slot = NodeHandle.index(slotSafe(node));
            int group = scene.group(node);
            if (NodeChange.has(changes, NodeChange.PREPARED) && group < adopting.length && adopting[group]) {
                return; // unchanged since its batch was applied: joins with the batch's tree after the log
            }
            place(node);
        }
    }

    /**
     * Notes the static trees of batches applied to the scene since the last update: their groups are adopted after the
     * change log is read (see {@link #finishAdoptions}), so that removals of nodes whose slots they reuse come first.
     */
    @AllowAllocation("growing")
    private int collectPrepared() {
        int count = 0;
        for (int i = 0; i < scene.preparedCount(); i++) {
            SceneGraph.Prepared prepared = scene.prepared(i);
            if (!prepared.adopted) {
                prepared.adopted = true;
                if (count == adoptions.length) {
                    adoptions = Arrays.copyOf(adoptions, Math.max(8, count * 2));
                }
                adoptions[count++] = prepared;
                if (prepared.group >= adopting.length) {
                    adopting = Arrays.copyOf(adopting, Math.max(prepared.group + 1, adopting.length * 2));
                }
                adopting[prepared.group] = true;
            }
        }
        return count;
    }

    /**
     * Takes over the noted trees, for groups this index has nothing in: their nodes join the group as the batch
     * numbered them and the batch's tree becomes the group's, so nothing is built here. Where that is not possible (a
     * node is gone, or the group is in use), the batch's nodes are placed one by one, as without a tree.
     */
    private boolean finishAdoptions(int count) {
        boolean any = false;
        for (int i = 0; i < count; i++) {
            SceneGraph.Prepared prepared = adoptions[i];
            adoptions[i] = null;
            adopting[prepared.group] = false;
            if (adopt(prepared)) {
                any = true;
            } else {
                for (long node : prepared.nodes) {
                    if (scene.contains(node)) {
                        place(node);
                    }
                }
            }
        }
        return any;
    }

    @AllowAllocation("adopting a prepared group")
    private boolean adopt(SceneGraph.Prepared prepared) {
        int group = prepared.group;
        ensureGroup(group);
        if (groupCount[group] > 0) {
            return false;
        }
        for (long node : prepared.nodes) {
            if (!scene.contains(node) || where[NodeHandle.index(slotSafe(node))] != NOWHERE) {
                return false; // gone, or changed and placed already
            }
        }
        int count = prepared.nodes.length;
        groupNodes[group] = prepared.nodes.clone();
        groupLayers[group] = new int[count];
        for (int i = 0; i < count; i++) {
            long node = prepared.nodes[i];
            int slot = NodeHandle.index(slotSafe(node));
            where[slot] = STATIC;
            position[slot] = i;
            groupOf[slot] = group;
            groupLayers[group][i] = scene.layers(node);
        }
        groupCount[group] = count;
        staticCount += count;
        groupTree[group] = prepared.tree;
        System.arraycopy(prepared.box, 0, groupBox, group * 6, 6);
        joinTop(group);
        return true;
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
        groupOf = Arrays.copyOf(groupOf, where.length);
    }

    /** Puts a live node where its bounds, mobility and group say, or updates its box there. */
    private void place(long node) {
        int slot = NodeHandle.index(slotSafe(node));
        byte wanted = !scene.hasBounds(node) ? NOWHERE : scene.mobility(node) == Mobility.STATIC ? STATIC : DYNAMIC;
        int group = scene.group(node);
        if (where[slot] != NOWHERE && (where[slot] != wanted || wanted == STATIC && groupOf[slot] != group)) {
            remove(slot);
        }
        if (wanted == STATIC) {
            if (where[slot] == NOWHERE) {
                addStatic(slot, node, group);
            }
            groupLayers[group][position[slot]] = scene.layers(node);
            markGroup(group);
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
            int group = groupOf[slot];
            int last = --groupCount[group];
            long moved = groupNodes[group][last];
            groupNodes[group][at] = moved;
            groupLayers[group][at] = groupLayers[group][last];
            position[NodeHandle.index(moved)] = at;
            staticCount--;
            markGroup(group);
        } else {
            dynamicNodes[at] = NodeHandle.NONE; // its box stays until the slot is reused; queries skip it
            freeDynamic[freeCount++] = at;
            dynamicCount--;
        }
        where[slot] = NOWHERE;
    }

    // ---------------------------------------------------------------- static groups

    @AllowAllocation("growing")
    private void ensureGroup(int group) {
        if (group >= groupCount.length) {
            int length = Math.max(group + 1, groupCount.length * 2);
            groupNodes = Arrays.copyOf(groupNodes, length);
            groupLayers = Arrays.copyOf(groupLayers, length);
            groupCount = Arrays.copyOf(groupCount, length);
            groupTree = Arrays.copyOf(groupTree, length);
            groupBox = Arrays.copyOf(groupBox, length * 6);
            groupDirty = Arrays.copyOf(groupDirty, length);
            dirtyGroups = Arrays.copyOf(dirtyGroups, length);
            int was = topSlot.length;
            topSlot = Arrays.copyOf(topSlot, length);
            Arrays.fill(topSlot, was, length, -1);
        }
    }

    @AllowAllocation("growing")
    private void addStatic(int slot, long node, int group) {
        ensureGroup(group);
        if (groupNodes[group] == null) {
            groupNodes[group] = new long[8];
            groupLayers[group] = new int[8];
        } else if (groupCount[group] == groupNodes[group].length) {
            groupNodes[group] = Arrays.copyOf(groupNodes[group], groupCount[group] * 2);
            groupLayers[group] = Arrays.copyOf(groupLayers[group], groupCount[group] * 2);
        }
        position[slot] = groupCount[group];
        groupNodes[group][groupCount[group]++] = node;
        groupOf[slot] = group;
        where[slot] = STATIC;
        staticCount++;
    }

    private void markGroup(int group) {
        if (!groupDirty[group]) {
            groupDirty[group] = true;
            dirtyGroups[dirtyGroupCount++] = group;
        }
    }

    /**
     * Rebuilds the dirty groups: their trees and boxes first, in parallel on the job system if there is one (each
     * writes only its own group's), then their slots in the top level, in order.
     */
    private void rebuildGroups() {
        if (jobs != null && dirtyGroupCount > 1) {
            jobs.parallelFor(dirtyGroupCount, 1, buildGroups);
        } else {
            buildGroups.run(0, dirtyGroupCount, 0);
        }
        for (int i = 0; i < dirtyGroupCount; i++) {
            placeGroup(dirtyGroups[i]);
        }
        dirtyGroupCount = 0;
    }

    /** Builds a group's tree and box from its nodes' current bounds; a group left without nodes keeps its old box. */
    @AllowAllocation("rebuilding a group's tree, when its static nodes changed")
    private void buildGroup(int group) {
        int count = groupCount[group];
        if (count == 0) {
            return;
        }
        AabbArray bounds = AabbArray.onHeap(count);
        AabbArray world = scene.worldBounds();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            int slot = NodeHandle.index(groupNodes[group][i]);
            copyBox(world, slot, bounds, i);
            minX = Math.min(minX, world.get(slot, 0));
            minY = Math.min(minY, world.get(slot, 1));
            minZ = Math.min(minZ, world.get(slot, 2));
            maxX = Math.max(maxX, world.get(slot, 3));
            maxY = Math.max(maxY, world.get(slot, 4));
            maxZ = Math.max(maxZ, world.get(slot, 5));
        }
        int box = group * 6;
        groupBox[box] = minX;
        groupBox[box + 1] = minY;
        groupBox[box + 2] = minZ;
        groupBox[box + 3] = maxX;
        groupBox[box + 4] = maxY;
        groupBox[box + 5] = maxZ;
        groupTree[group] = Bvh.build(Arena.ofAuto(), bounds, LEAF_SIZE);
    }

    /** After its tree was built: a group with nodes joins the top level or updates its box there, an empty one leaves. */
    private void placeGroup(int group) {
        groupDirty[group] = false;
        if (groupCount[group] > 0) {
            joinTop(group);
            return;
        }
        groupTree[group] = null;
        // It had a slot: a group gains nodes only in an update that then rebuilds it. Its slot is free now; its box
        // shrinks to its center, so the tree does not keep spanning areas left behind.
        int box = group * 6;
        double cx = (groupBox[box] + groupBox[box + 3]) * 0.5;
        double cy = (groupBox[box + 1] + groupBox[box + 4]) * 0.5;
        double cz = (groupBox[box + 2] + groupBox[box + 5]) * 0.5;
        top.primitiveBounds().set(topSlot[group], cx, cy, cz, cx, cy, cz);
        topGroups[topSlot[group]] = -1;
        freeTop[freeTopCount++] = topSlot[group];
        topSlot[group] = -1;
        topCount--;
    }

    /** Gives a group with a tree a slot in the top level, if it has none, and sets its box there. */
    private void joinTop(int group) {
        if (topSlot[group] < 0) {
            if (freeTopCount == 0) {
                growTop(group);
            }
            int slot = freeTop[--freeTopCount];
            topSlot[group] = slot;
            topGroups[slot] = group;
            topCount++;
        }
        setBox(top.primitiveBounds(), topSlot[group], group);
    }

    /**
     * Doubles the top level's slots, like the dynamic tree: groups joining and leaving reuse slots and only refit, so
     * a streaming world rebuilds the top level only when it grows or has degraded.
     */
    @AllowAllocation("growing the top level")
    private void growTop(int group) {
        int old = topGroups.length;
        int capacity = Math.max(16, old * 2);
        AabbArray boxes = AabbArray.onHeap(capacity);
        if (top != null) {
            MemorySegment.copy(top.primitiveBounds().segment(), 0, boxes.segment(), 0,
                top.primitiveBounds().segment().byteSize());
        }
        for (int i = old; i < capacity; i++) {
            setBox(boxes, i, group);
        }
        topGroups = Arrays.copyOf(topGroups, capacity);
        freeTop = Arrays.copyOf(freeTop, capacity);
        for (int i = capacity - 1; i >= old; i--) {
            topGroups[i] = -1;
            freeTop[freeTopCount++] = i;
        }
        top = Bvh.build(Arena.ofAuto(), boxes, LEAF_SIZE);
    }

    /** Refits the top level to its groups' boxes (it changed: groups were rebuilt); rebuilds it when degraded. */
    private void updateTop() {
        top.refit();
        if (top.degradation() > rebuildThreshold) {
            top.rebuild();
        }
    }

    private void setBox(AabbArray boxes, int index, int group) {
        int box = group * 6;
        boxes.set(index, groupBox[box], groupBox[box + 1], groupBox[box + 2], groupBox[box + 3], groupBox[box + 4],
            groupBox[box + 5]);
    }

    // ---------------------------------------------------------------- dynamic nodes

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

    private void clear() {
        Arrays.fill(where, NOWHERE);
        for (int group = 0; group < groupCount.length; group++) {
            if (groupCount[group] > 0) {
                groupCount[group] = 0;
                markGroup(group);
            }
        }
        staticCount = 0;
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
        return query.aabb(minX, minY, minZ, maxX, maxY, maxZ, layerMask, visitor);
    }

    /** Visits every node on {@code layerMask} whose box overlaps the sphere; returns how many were visited. */
    public int querySphere(double cx, double cy, double cz, double radius, int layerMask, Visitor visitor) {
        return query.sphere(cx, cy, cz, radius, layerMask, visitor);
    }

    /** Visits every node on {@code layerMask} whose box is inside or crosses the frustum, e.g. to draw. */
    public int queryFrustum(Frustum frustum, int layerMask, Visitor visitor) {
        return query.frustum(frustum, layerMask, visitor);
    }

    /**
     * Finds the nodes on {@code layerMask} in each of the first {@code views} frustums, e.g. a camera and its shadow
     * cascades, into {@code results}: view {@code v}'s nodes in the order {@link #queryFrustum} would visit them. The
     * views are queried in parallel on the job system if there is one ({@link #setJobs}), with the same results.
     */
    public void queryFrustums(Frustum[] frustums, int views, int layerMask, ViewResults results) {
        if (views < 0 || views > frustums.length) {
            throw new IllegalArgumentException("No " + views + " views in " + frustums.length + " frustums");
        }
        results.reset(views);
        viewFrustums = frustums;
        viewMask = layerMask;
        viewResults = results;
        try {
            if (jobs != null && views > 1) {
                workerQueries(jobs.threads());
                jobs.parallelFor(views, 1, queryViews);
            } else {
                for (int view = 0; view < views; view++) {
                    query.collect(view);
                }
            }
        } finally {
            viewFrustums = null;
            viewResults = null;
        }
    }

    /** One query context per worker of the job system, made when first needed. */
    @AllowAllocation("making the workers' query contexts, once")
    private void workerQueries(int threads) {
        if (workers.length < threads) {
            Query[] grown = Arrays.copyOf(workers, threads);
            for (int i = workers.length; i < threads; i++) {
                grown[i] = new Query();
            }
            workers = grown;
        }
    }

    /**
     * Finds the nearest node on {@code layerMask} whose box the ray from ({@code ox}, {@code oy}, {@code oz}) along
     * ({@code dx}, {@code dy}, {@code dz}) enters within {@code maxDistance}; writes it to {@code hit}.
     */
    public boolean raycast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance,
        int layerMask, Hit hit) {
        return query.raycast(ox, oy, oz, dx, dy, dz, maxDistance, layerMask, hit);
    }

    /** The nodes found per view by {@link #queryFrustums}. Reuse one; it grows as needed. */
    public static final class ViewResults {

        private long[][] nodes = new long[0][];
        private int[] counts = new int[0];
        private int views;

        /** How many views the last query had. */
        public int views() {
            return views;
        }

        /** How many nodes view {@code view} found. */
        public int count(int view) {
            check(view);
            return counts[view];
        }

        /** Node {@code index} of view {@code view}, in the order found. */
        public long node(int view, int index) {
            check(view);
            if (index < 0 || index >= counts[view]) {
                throw new IndexOutOfBoundsException(index);
            }
            return nodes[view][index];
        }

        private void check(int view) {
            if (view < 0 || view >= views) {
                throw new IndexOutOfBoundsException(view);
            }
        }

        @AllowAllocation("growing")
        private void reset(int viewCount) {
            if (nodes.length < viewCount) {
                int was = nodes.length;
                nodes = Arrays.copyOf(nodes, viewCount);
                counts = Arrays.copyOf(counts, viewCount);
                for (int i = was; i < viewCount; i++) {
                    nodes[i] = new long[64];
                }
            }
            Arrays.fill(counts, 0, viewCount, 0);
            views = viewCount;
        }

        /** Takes a view's nodes, collected apart, so threads do not write next to each other while collecting. */
        @AllowAllocation("growing")
        private void set(int view, long[] found, int count) {
            if (nodes[view].length < count) {
                nodes[view] = new long[Math.max(count, nodes[view].length * 2)];
            }
            System.arraycopy(found, 0, nodes[view], 0, count);
            counts[view] = count;
        }
    }

    /**
     * The state of a running query and its visitors, made once: one for the queries above, one per job-system worker
     * for {@link #queryFrustums}. The trees are only read, so contexts run side by side.
     */
    private final class Query {

        private Visitor visitor;
        private int mask;
        private boolean stopped;
        private int found;
        private int currentGroup;
        private double qa;
        private double qb;
        private double qc;
        private double qd;
        private double qe;
        private double qf;
        private Frustum qFrustum;
        private long rayNode;
        private long[] collected = new long[64];
        private int collectedCount;
        private final RayHit groupHit = new RayHit();
        private final RayHit rayHit = new RayHit();
        private final Visitor collect = node -> {
            if (collectedCount == collected.length) {
                grow();
            }
            collected[collectedCount++] = node;
            return true;
        };
        private final Bvh.Visitor visitGroup = primitive ->
            report(groupNodes[currentGroup][primitive], groupLayers[currentGroup][primitive]);
        private final Bvh.Visitor visitDynamic = primitive -> report(dynamicNodes[primitive], dynamicLayers[primitive]);
        private final Bvh.Visitor topAabb = primitive -> {
            int group = currentGroup = topGroups[primitive];
            if (group < 0) {
                return true; // a free slot
            }
            groupTree[group].queryAabb(qa, qb, qc, qd, qe, qf, visitGroup);
            return !stopped;
        };
        private final Bvh.Visitor topSphere = primitive -> {
            int group = currentGroup = topGroups[primitive];
            if (group < 0) {
                return true;
            }
            groupTree[group].querySphere(qa, qb, qc, qd, visitGroup);
            return !stopped;
        };
        private final Bvh.Visitor topFrustum = primitive -> {
            int group = currentGroup = topGroups[primitive];
            if (group < 0) {
                return true;
            }
            groupTree[group].queryFrustum(qFrustum, visitGroup);
            return !stopped;
        };
        private final Bvh.RayTest rayGroup = (primitive, ox, oy, oz, dx, dy, dz, max) -> rayTest(
            groupNodes[currentGroup][primitive], groupLayers[currentGroup][primitive], groupTree[currentGroup]
                .primitiveBounds(), primitive, ox, oy, oz, dx, dy, dz, max);
        private final Bvh.RayTest rayTop = (primitive, ox, oy, oz, dx, dy, dz, max) -> {
            int group = currentGroup = topGroups[primitive];
            if (group >= 0 && groupTree[group].raycast(ox, oy, oz, dx, dy, dz, max, rayGroup, groupHit)) {
                rayNode = groupNodes[group][groupHit.primitive()];
                return groupHit.distance();
            }
            return Double.POSITIVE_INFINITY;
        };
        private final Bvh.RayTest rayDynamic = (primitive, ox, oy, oz, dx, dy, dz, max) ->
            rayTest(dynamicNodes[primitive], dynamicLayers[primitive], dynamicBounds, primitive, ox, oy, oz, dx, dy,
                dz, max);

        @AllowAllocation("set up once")
        Query() {
        }

        int aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int layerMask,
            Visitor queryVisitor) {
            start(layerMask, queryVisitor);
            if (top != null) {
                qa = minX;
                qb = minY;
                qc = minZ;
                qd = maxX;
                qe = maxY;
                qf = maxZ;
                top.queryAabb(minX, minY, minZ, maxX, maxY, maxZ, topAabb);
            }
            if (dynamicBvh != null && !stopped) {
                dynamicBvh.queryAabb(minX, minY, minZ, maxX, maxY, maxZ, visitDynamic);
            }
            return finish();
        }

        int sphere(double cx, double cy, double cz, double radius, int layerMask, Visitor queryVisitor) {
            start(layerMask, queryVisitor);
            if (top != null) {
                qa = cx;
                qb = cy;
                qc = cz;
                qd = radius;
                top.querySphere(cx, cy, cz, radius, topSphere);
            }
            if (dynamicBvh != null && !stopped) {
                dynamicBvh.querySphere(cx, cy, cz, radius, visitDynamic);
            }
            return finish();
        }

        int frustum(Frustum frustum, int layerMask, Visitor queryVisitor) {
            start(layerMask, queryVisitor);
            if (top != null) {
                qFrustum = frustum;
                top.queryFrustum(frustum, topFrustum);
                qFrustum = null;
            }
            if (dynamicBvh != null && !stopped) {
                dynamicBvh.queryFrustum(frustum, visitDynamic);
            }
            return finish();
        }

        /** Queries view {@code viewIndex} of the running {@link #queryFrustums} into its results. */
        void collect(int viewIndex) {
            collectedCount = 0;
            frustum(viewFrustums[viewIndex], viewMask, collect);
            viewResults.set(viewIndex, collected, collectedCount);
        }

        @AllowAllocation("growing")
        private void grow() {
            collected = Arrays.copyOf(collected, collected.length * 2);
        }

        boolean raycast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance,
            int layerMask, Hit hit) {
            mask = layerMask;
            hit.node = NodeHandle.NONE;
            hit.distance = Double.POSITIVE_INFINITY;
            double best = maxDistance;
            if (top != null && top.raycast(ox, oy, oz, dx, dy, dz, best, rayTop, rayHit)) {
                // The top level reports the group of the nearest hit; that group's test left its node in rayNode only
                // if it was the last group tested, so ask the nearest group again.
                rayTop.intersect(rayHit.primitive(), ox, oy, oz, dx, dy, dz, rayHit.distance());
                best = rayHit.distance();
                hit.node = rayNode;
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
            if (node == NodeHandle.NONE || (layers & mask) == 0) { // free dynamic slots hold no node
                return true;
            }
            found++;
            if (!visitor.visit(node)) {
                stopped = true;
                return false;
            }
            return true;
        }

        private double rayTest(long node, int layers, AabbArray bounds, int primitive, double ox, double oy,
            double oz, double dx, double dy, double dz, double max) {
            if (node == NodeHandle.NONE || (layers & mask) == 0) {
                return Double.POSITIVE_INFINITY;
            }
            double t = Intersections.rayAabb(ox, oy, oz, dx, dy, dz, bounds.get(primitive, 0),
                bounds.get(primitive, 1), bounds.get(primitive, 2), bounds.get(primitive, 3), bounds.get(primitive, 4),
                bounds.get(primitive, 5));
            return t <= max ? t : Double.POSITIVE_INFINITY;
        }
    }
}
