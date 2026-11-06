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
import java.util.Objects;

/**
 * A detached subtree of nodes, built away from the scene: their hierarchy, transforms, bounds, mobility, layers and
 * components, as arrays. A worker thread builds it (a streamed cell's content, a prefab or model spawned, a level
 * chunk) without touching the live scene; {@link SceneGraph#attach(SceneBatch, long)} then puts it into the scene on
 * the scene's thread in one pass, logged as one entry of the scene's change log and one per component store, so every
 * system following them (the renderer's instances, lights, audio, the spatial index) takes the whole batch at once.
 * {@link SceneGraph#detach(long, SceneBatch)} takes a subtree out into a batch again.
 *
 * <pre>{@code
 * // On a worker:
 * SceneBatch batch = new SceneBatch(64);
 * int cell = batch.add();                                  // a root: under attach's parent
 * batch.setTranslation(cell, x, 0, z);
 * for (Prop prop : props) {
 *     int node = batch.add(cell);
 *     batch.setTranslation(node, prop.x(), prop.y(), prop.z());
 *     batch.set(node, Renderable.TYPE, Renderable.of(prop.mesh(), prop.materials()));
 * }
 * batch.prepare();                                         // world transforms and bounds, off the scene's thread
 *
 * // On the scene's thread, once the worker handed it over:
 * long root = scene.attach(batch, NodeHandle.NONE);        // the handle of node 0
 * scene.remove(root);                                      // later: the whole subtree, as one entry
 * batch.clear();                                           // and build the next one in it
 * }</pre>
 *
 * <p>Nodes are numbered from 0 in the order added; a parent must be added before its children. Roots have no parent in
 * the batch: they go under the parent given to {@code attach} (or into the world). Components are any
 * {@link ComponentType}'s, at most one of a type per node, as in the scene.
 *
 * <p><b>Threads:</b> a batch belongs to one thread at a time and has no locks. A worker builds it; handing it to the
 * scene's thread (a queue, a future) passes it over, and the worker does not touch it again until it is handed back.
 * Attaching copies everything out of it, so after {@code attach} returns the batch is its holder's again, to
 * {@link #clear()} and fill anew: a few batches passed around keep streaming free of allocation once they have grown.
 * Values put in it are shared with the scene after attaching (as with {@code ComponentStore.set}); immutable ones are
 * safest.
 */
public final class SceneBatch {

    private static final int[] NO_NODES = new int[0];

    private int size;
    private int[] parent;
    private TransformArray local;
    private TransformArray world;
    private AabbArray localBounds;
    private AabbArray worldBounds;
    private boolean[] dynamic;
    private int[] layers;
    private int group;
    private boolean prepared;
    private Bvh staticTree;
    private int[] staticNodes = NO_NODES;
    private final double[] staticBox = new double[6];

    // Components, by type in the order first set: the batch nodes of each and their values.
    private ComponentType<?>[] types = new ComponentType<?>[4];
    private int[][] componentNodes = new int[4][];
    private Object[][] componentValues = new Object[4][];
    private int[] componentCounts = new int[4];
    private int typeCount;

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

    /**
     * Empties the batch to build another in it, keeping its room: nodes, components (the values let go of) and the
     * group. Allocates nothing.
     */
    public void clear() {
        size = 0;
        group = 0;
        prepared = false;
        staticTree = null;
        staticNodes = NO_NODES;
        for (int t = 0; t < typeCount; t++) {
            Arrays.fill(componentValues[t], 0, componentCounts[t], null);
            componentCounts[t] = 0;
        }
    }

    /** Adds a root node, placed under the parent the batch is attached to, and returns its number. */
    public int add() {
        return add(-1);
    }

    /** Adds a node under node {@code parentNode} of this batch (-1 for a root) and returns its number. */
    public int add(int parentNode) {
        if (parentNode < -1 || parentNode >= size) {
            throw new IllegalArgumentException("No node " + parentNode + " in this batch of " + size);
        }
        if (size == parent.length) {
            growNodes(size * 2);
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
    private void growNodes(int capacity) {
        parent = Arrays.copyOf(parent, capacity);
        dynamic = Arrays.copyOf(dynamic, capacity);
        layers = Arrays.copyOf(layers, capacity);
        local = grow(local, capacity);
        world = grow(world, capacity);
        localBounds = grow(localBounds, capacity);
        worldBounds = grow(worldBounds, capacity);
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

    /** Node {@code node}'s parent in this batch, or -1 for a root. */
    public int parent(int node) {
        check(node);
        return parent[node];
    }

    /**
     * Puts the batch's nodes in group {@code group} (0 or more, 0 by default) when attached: see
     * {@link SceneGraph#setGroup}. Streaming puts each cell's nodes in a group of their own.
     */
    public void setGroup(int group) {
        if (group < 0) {
            throw new IllegalArgumentException("Groups are 0 or more: " + group);
        }
        this.group = group;
    }

    /** The group the nodes go in. */
    public int group() {
        return group;
    }

    /** Sets the node's position relative to its parent (in the batch, or attach's parent for a root). */
    public void setTranslation(int node, double x, double y, double z) {
        if (!Double.isFinite(x + y + z)) {
            throw new IllegalArgumentException("Not a finite translation: " + x + ", " + y + ", " + z);
        }
        long offset = offset(node);
        SceneGraph.put(local.segment(), offset, 0, x);
        SceneGraph.put(local.segment(), offset, 1, y);
        SceneGraph.put(local.segment(), offset, 2, z);
    }

    /** Sets the node's rotation relative to its parent, kept normalized as {@link SceneGraph#setRotation} keeps it. */
    public void setRotation(int node, double x, double y, double z, double w) {
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(length > 1e-12) || length == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a rotation: " + x + ", " + y + ", " + z + ", " + w);
        }
        long offset = offset(node);
        SceneGraph.put(local.segment(), offset, 3, x / length);
        SceneGraph.put(local.segment(), offset, 4, y / length);
        SceneGraph.put(local.segment(), offset, 5, z / length);
        SceneGraph.put(local.segment(), offset, 6, w / length);
    }

    /** Sets the node's scale relative to its parent: finite and not zero on any axis. */
    public void setScale(int node, double x, double y, double z) {
        if (!Double.isFinite(x + y + z) || x == 0 || y == 0 || z == 0) {
            throw new IllegalArgumentException("Not a scale on every axis: " + x + ", " + y + ", " + z);
        }
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
        if (!Double.isFinite(minX + minY + minZ + maxX + maxY + maxZ)) {
            throw new IllegalArgumentException("Bounds that are not finite");
        }
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

    // ---------------------------------------------------------------- components

    /**
     * Gives node {@code node} {@code value} as its {@code type} component once attached. Setting a type twice on a node
     * keeps the last value. Allocates only to grow; cheapest set node by node in order (each node after the last one
     * given this type).
     */
    public <T> void set(int node, ComponentType<T> type, T value) {
        check(node);
        type.cast(Objects.requireNonNull(value, "value"));
        int t = typeIndex(type);
        int[] nodes = componentNodes[t];
        int count = componentCounts[t];
        if (count > 0 && node <= nodes[count - 1]) { // not after the last node set: maybe set already
            for (int i = count - 1; i >= 0; i--) {
                if (nodes[i] == node) {
                    componentValues[t][i] = value;
                    return;
                }
            }
        }
        if (count == nodes.length) {
            growComponents(t);
        }
        componentNodes[t][count] = node;
        componentValues[t][count] = value;
        componentCounts[t] = count + 1;
    }

    /** Node {@code node}'s {@code type} component in this batch, or null. */
    @SuppressWarnings("unchecked")
    public <T> T get(int node, ComponentType<T> type) {
        check(node);
        for (int t = 0; t < typeCount; t++) {
            if (types[t] == type) {
                for (int i = 0; i < componentCounts[t]; i++) {
                    if (componentNodes[t][i] == node) {
                        return (T) componentValues[t][i];
                    }
                }
            }
        }
        return null;
    }

    private int typeIndex(ComponentType<?> type) {
        for (int t = 0; t < typeCount; t++) {
            if (types[t] == type) {
                return t;
            }
        }
        return addType(type);
    }

    @AllowAllocation("a component type first used in this batch")
    private int addType(ComponentType<?> type) {
        if (typeCount == types.length) {
            types = Arrays.copyOf(types, 2 * typeCount);
            componentNodes = Arrays.copyOf(componentNodes, 2 * typeCount);
            componentValues = Arrays.copyOf(componentValues, 2 * typeCount);
            componentCounts = Arrays.copyOf(componentCounts, 2 * typeCount);
        }
        types[typeCount] = type;
        componentNodes[typeCount] = new int[Math.max(8, parent.length)];
        componentValues[typeCount] = new Object[componentNodes[typeCount].length];
        componentCounts[typeCount] = 0;
        return typeCount++;
    }

    @AllowAllocation("staging, off the main thread")
    private void growComponents(int t) {
        int length = 2 * componentNodes[t].length;
        componentNodes[t] = Arrays.copyOf(componentNodes[t], length);
        componentValues[t] = Arrays.copyOf(componentValues[t], length);
    }

    // ---------------------------------------------------------------- preparing

    /**
     * Computes world transforms and bounds (as if attached to the world: roots at their local transforms) and builds
     * the tree of the static nodes with bounds, so attaching is mostly copying. Call it where the batch is built, e.g.
     * on the worker; attaching prepares an unprepared batch itself, on the scene's thread. Allocates only for static
     * nodes with bounds (their tree).
     */
    @AllowAllocation("preparing, off the main thread: the static nodes' tree")
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
        Arrays.fill(staticBox, 0, 3, Double.POSITIVE_INFINITY);
        Arrays.fill(staticBox, 3, 6, Double.NEGATIVE_INFINITY);
        if (statics == 0) {
            staticNodes = NO_NODES;
            staticTree = null;
            prepared = true;
            return;
        }
        staticNodes = new int[statics];
        AabbArray boxes = AabbArray.onHeap(statics);
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
        staticTree = Bvh.build(Arena.ofAuto(), boxes, 4);
        prepared = true;
    }

    /** Whether {@link #prepare()} ran since the last change. */
    public boolean isPrepared() {
        return prepared;
    }

    // ---------------------------------------------------------------- for SceneGraph and SpatialIndex

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

    int parentOf(int node) {
        return parent[node];
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

    int typeCount() {
        return typeCount;
    }

    ComponentType<?> type(int t) {
        return types[t];
    }

    int componentCount(int t) {
        return componentCounts[t];
    }

    int[] componentNodes(int t) {
        return componentNodes[t];
    }

    Object[] componentValues(int t) {
        return componentValues[t];
    }

    /** Copies node {@code node}'s local transform from {@code from}'s slot {@code slot} (detaching). */
    void copyLocal(int node, TransformArray from, int slot) {
        MemorySegment.copy(from.segment(), slot * from.stride(), local.segment(), node * local.stride(),
            local.stride());
        prepared = false;
    }

    /** Copies node {@code node}'s local bounds from {@code from}'s slot {@code slot} (detaching). */
    void copyLocalBounds(int node, AabbArray from, int slot) {
        MemorySegment.copy(from.segment(), slot * from.stride(), localBounds.segment(), node * localBounds.stride(),
            localBounds.stride());
        prepared = false;
    }

    /** Puts {@code value} as node {@code node}'s {@code type} component without checks (detaching). */
    void setUnchecked(int node, ComponentType<?> type, Object value) {
        int t = typeIndex(type);
        if (componentCounts[t] == componentNodes[t].length) {
            growComponents(t);
        }
        componentNodes[t][componentCounts[t]] = node;
        componentValues[t][componentCounts[t]++] = value;
    }
}
