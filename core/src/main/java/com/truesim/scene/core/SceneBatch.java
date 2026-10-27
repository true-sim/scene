package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.TransformArray;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.AabbArray;
import com.truesim.common.math.spatial.Bvh;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

/**
 * Nodes staged away from the scene, e.g. a streamed cell's content built by a loader in the background: their
 * hierarchy, transforms, bounds, mobility and layers, as arrays. {@link #prepare()} (in the background too) computes
 * their world transforms and bounds and builds the tree of their static nodes; {@link SceneGraph#apply} then copies
 * them into the scene and hands the tree to the spatial index, so the main thread does little more than copy memory.
 *
 * <pre>{@code
 * // In CellLoader.load(), on a lane:
 * SceneBatch batch = new SceneBatch();
 * for (Prop prop : props) {
 *     int node = batch.add();
 *     batch.setTranslation(node, prop.x(), prop.y(), prop.z());
 *     batch.setLocalBounds(node, -1, 0, -1, 1, 4, 1);
 *     batch.setMobility(node, Mobility.STATIC);
 * }
 * batch.prepare();
 *
 * // In CellLoader.attach(), on the main thread:
 * content.apply(batch);
 * }</pre>
 *
 * <p>Nodes are numbered from 0 in the order added; a parent must be added before its children. Roots have no parent:
 * they are placed in the world. A batch is used by one thread at a time, and applied once.
 */
public final class SceneBatch {

    private int size;
    private int[] parent;
    private TransformArray local;
    private TransformArray world;
    private AabbArray localBounds;
    private AabbArray worldBounds;
    private boolean[] dynamic;
    private int[] layers;
    private boolean prepared;
    private Bvh staticTree;
    private int[] staticNodes = new int[0];
    private final double[] staticBox = new double[6];

    /** An empty batch. */
    public SceneBatch() {
        this(16);
    }

    /** An empty batch with room for {@code capacity} nodes before it grows. */
    @AllowAllocation("staging, off the main thread")
    public SceneBatch(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A batch needs room for at least one node: " + capacity);
        }
        parent = new int[capacity];
        local = TransformArray.onHeap(capacity);
        world = TransformArray.onHeap(capacity);
        localBounds = AabbArray.onHeap(capacity);
        worldBounds = AabbArray.onHeap(capacity);
        dynamic = new boolean[capacity];
        layers = new int[capacity];
    }

    /** Adds a root node, placed in the world, and returns its number. */
    public int add() {
        return add(-1);
    }

    /** Adds a node under node {@code parentNode} of this batch (-1 for a root) and returns its number. */
    @AllowAllocation("staging, off the main thread")
    public int add(int parentNode) {
        if (parentNode < -1 || parentNode >= size) {
            throw new IllegalArgumentException("No node " + parentNode + " in this batch of " + size);
        }
        if (size == parent.length) {
            int capacity = size * 2;
            parent = Arrays.copyOf(parent, capacity);
            dynamic = Arrays.copyOf(dynamic, capacity);
            layers = Arrays.copyOf(layers, capacity);
            local = grow(local, capacity);
            world = grow(world, capacity);
            localBounds = grow(localBounds, capacity);
            worldBounds = grow(worldBounds, capacity);
        }
        int node = size++;
        parent[node] = parentNode;
        SceneGraph.identity(local, node);
        localBounds.set(node, SceneGraph.EMPTY, SceneGraph.EMPTY, SceneGraph.EMPTY, -SceneGraph.EMPTY,
            -SceneGraph.EMPTY, -SceneGraph.EMPTY);
        dynamic[node] = true;
        layers[node] = SceneLayer.DEFAULT.mask();
        prepared = false;
        return node;
    }

    @AllowAllocation("staging, off the main thread")
    private static TransformArray grow(TransformArray old, int capacity) {
        TransformArray grown = TransformArray.onHeap(capacity);
        MemorySegment.copy(old.segment(), 0, grown.segment(), 0, old.segment().byteSize());
        return grown;
    }

    @AllowAllocation("staging, off the main thread")
    private static AabbArray grow(AabbArray old, int capacity) {
        AabbArray grown = AabbArray.onHeap(capacity);
        MemorySegment.copy(old.segment(), 0, grown.segment(), 0, old.segment().byteSize());
        return grown;
    }

    /** How many nodes are staged. */
    public int size() {
        return size;
    }

    public void setTranslation(int node, double x, double y, double z) {
        long offset = offset(node);
        SceneGraph.put(local.segment(), offset, 0, x);
        SceneGraph.put(local.segment(), offset, 1, y);
        SceneGraph.put(local.segment(), offset, 2, z);
    }

    public void setRotation(int node, double x, double y, double z, double w) {
        long offset = offset(node);
        SceneGraph.put(local.segment(), offset, 3, x);
        SceneGraph.put(local.segment(), offset, 4, y);
        SceneGraph.put(local.segment(), offset, 5, z);
        SceneGraph.put(local.segment(), offset, 6, w);
    }

    public void setScale(int node, double x, double y, double z) {
        long offset = offset(node);
        SceneGraph.put(local.segment(), offset, 7, x);
        SceneGraph.put(local.segment(), offset, 8, y);
        SceneGraph.put(local.segment(), offset, 9, z);
    }

    public void setLocal(int node, Transform transform) {
        Vec3 t = transform.translation();
        Quat r = transform.rotation();
        Vec3 s = transform.scale();
        setTranslation(node, t.x(), t.y(), t.z());
        setRotation(node, r.x(), r.y(), r.z(), r.w());
        setScale(node, s.x(), s.y(), s.z());
    }

    /** Sets the node's bounds in its own space, as {@link SceneGraph#setLocalBounds}. */
    public void setLocalBounds(int node, double minX, double minY, double minZ, double maxX, double maxY,
        double maxZ) {
        check(node);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Bounds with a minimum above the maximum");
        }
        localBounds.set(node, minX, minY, minZ, maxX, maxY, maxZ);
        prepared = false;
    }

    public void setMobility(int node, Mobility mobility) {
        check(node);
        dynamic[node] = mobility == Mobility.DYNAMIC;
        prepared = false;
    }

    public void setLayers(int node, int mask) {
        check(node);
        layers[node] = mask;
    }

    private long offset(int node) {
        check(node);
        prepared = false;
        return node * local.stride();
    }

    private void check(int node) {
        if (node < 0 || node >= size) {
            throw new IllegalArgumentException("No node " + node + " in this batch of " + size);
        }
    }

    // ---------------------------------------------------------------- preparing

    /**
     * Computes world transforms and bounds and builds the tree of the static nodes with bounds, so applying is mostly
     * copying. Call it where the batch is built, e.g. in the background; {@link SceneGraph#apply} prepares unprepared
     * batches itself, on its thread.
     */
    @AllowAllocation("preparing, off the main thread")
    public void prepare() {
        int statics = 0;
        for (int node = 0; node < size; node++) {
            if (parent[node] < 0) {
                MemorySegment.copy(local.segment(), node * local.stride(), world.segment(), node * world.stride(),
                    world.stride());
            } else {
                world.setProduct(node, world, parent[node], local, node);
            }
            SceneGraph.worldBox(world, localBounds, worldBounds, node);
            if (!dynamic[node] && SceneGraph.hasBounds(node, localBounds)) {
                statics++;
            }
        }
        staticNodes = new int[statics];
        Arrays.fill(staticBox, 0, 3, Double.POSITIVE_INFINITY);
        Arrays.fill(staticBox, 3, 6, Double.NEGATIVE_INFINITY);
        AabbArray boxes = AabbArray.onHeap(Math.max(statics, 1));
        int next = 0;
        for (int node = 0; node < size; node++) {
            if (!dynamic[node] && SceneGraph.hasBounds(node, localBounds)) {
                staticNodes[next] = node;
                boxes.set(next++, worldBounds.get(node, 0), worldBounds.get(node, 1), worldBounds.get(node, 2),
                    worldBounds.get(node, 3), worldBounds.get(node, 4), worldBounds.get(node, 5));
                for (int c = 0; c < 3; c++) {
                    staticBox[c] = Math.min(staticBox[c], worldBounds.get(node, c));
                    staticBox[c + 3] = Math.max(staticBox[c + 3], worldBounds.get(node, c + 3));
                }
            }
        }
        staticTree = statics == 0 ? null : Bvh.build(Arena.ofAuto(), boxes, 4);
        prepared = true;
    }

    /** Whether {@link #prepare()} ran since the last change. */
    public boolean isPrepared() {
        return prepared;
    }

    // ---------------------------------------------------------------- for SceneGraph and SpatialIndex

    int parent(int node) {
        return parent[node];
    }

    TransformArray local() {
        return local;
    }

    TransformArray world() {
        return world;
    }

    AabbArray localBounds() {
        return localBounds;
    }

    AabbArray worldBounds() {
        return worldBounds;
    }

    boolean isDynamic(int node) {
        return dynamic[node];
    }

    int layers(int node) {
        return layers[node];
    }

    /** The tree of the static nodes with bounds, or {@code null} without any. */
    Bvh staticTree() {
        return staticTree;
    }

    /** The batch number of each primitive of {@link #staticTree()}. */
    int[] staticNodes() {
        return staticNodes;
    }

    double[] staticBox() {
        return staticBox;
    }
}
