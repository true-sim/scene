package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import java.util.Arrays;
import java.util.Objects;

/**
 * The components of one {@link ComponentType} on a scene's nodes (application-api.md D46): a sparse set, so finding a
 * node's component, adding and removing one cost the same however many there are, and the components are packed for
 * iteration:
 *
 * <pre>{@code
 * ComponentStore<LightSource> lights = scene.components().store(LightSource.TYPE);
 * for (int i = 0; i < lights.size(); i++) {               // packed: allocates nothing
 *     long node = lights.node(i);
 *     LightSource light = lights.value(i);
 * }
 * lights.changesSince(since, (node, changes) -> ...);     // ADDED, CHANGED, REMOVED, on the scene's clock
 * }</pre>
 *
 * <p>Changes are logged on the scene's {@link ChangeClock}, as the scene graph's own: a consumer keeps a
 * {@code ChangeCursor} mark and reads what changed since; when it fell too far behind ({@link #changesSince} returns
 * false) it rebuilds by iterating. A value changed in place is reported by {@link #changed}. A type that declares its
 * values' fields ({@link ComponentType#of(String, Class, ComponentType.Fields)}) logs, with {@link #CHANGED}, the bits of
 * the fields a replacing value changed (from {@link #FIELD} up), and nothing when it changed none. A node removed from the
 * scene loses its components at once, logged {@link #REMOVED}. Iteration order is the packed order: deterministic for
 * the same calls, but changed by removals (the last component moves into the gap).
 *
 * <p>Changing is checked against the clock's owner thread like the scene's; reading allocates nothing; adding allocates
 * only to grow.
 *
 * @param <T> the class of the values
 */
public final class ComponentStore<T> {

    /** The node got a component of this type. */
    public static final int ADDED = 1;
    /** The node's component was replaced or changed in place. */
    public static final int CHANGED = 1 << 1;
    /** The node's component was removed, or the node itself; its handle may be stale. Reported alone. */
    public static final int REMOVED = 1 << 2;
    /** The first of the bits a type's declared fields use, each with {@link #CHANGED}: {@code FIELD << i}. */
    public static final int FIELD = 1 << 3;
    /** Every bit a type's fields may use. */
    public static final int FIELDS = ~(FIELD - 1) & Integer.MAX_VALUE;

    /**
     * The most changes a store keeps for consumers: past it, the oldest go and a consumer that late rebuilds. As many as
     * a scene graph keeps by default ({@link SceneGraph#DEFAULT_LOG_CAPACITY}); the log grows to it only as needed.
     */
    public static final int MAX_LOG = SceneGraph.DEFAULT_LOG_CAPACITY;

    /** Receives changes. */
    @FunctionalInterface
    public interface Visitor {

        /** {@code node}'s component changed in the ways {@code changes} lists. */
        void changed(long node, int changes);
    }

    /** Receives components. */
    @FunctionalInterface
    public interface Each<T> {

        void visit(long node, T value);
    }

    private final SceneGraph scene;
    private final ChangeClock clock;
    private final ComponentType<T> type;
    private int[] sparse = new int[0]; // by node index: the dense index + 1, 0 for none
    private long[] nodes = new long[8];
    private T[] values;
    private int size;
    private long version;

    // The change log: a ring of (stamp, node, changes), growing up to MAX_LOG.
    private long[] logStamps = new long[16];
    private long[] logNodes = new long[16];
    private int[] logChanges = new int[16];
    private int logStart;
    private int logSize;
    private long logIncompleteThrough = -1;

    @SuppressWarnings("unchecked")
    @AllowAllocation("a store per type and scene")
    ComponentStore(SceneGraph scene, ComponentType<T> type) {
        this.scene = scene;
        this.clock = scene.clock();
        this.type = type;
        this.values = (T[]) new Object[8];
    }

    /** Its type. */
    public ComponentType<T> type() {
        return type;
    }

    /** The scene it belongs to. */
    public SceneGraph scene() {
        return scene;
    }

    /**
     * Gives {@code node} {@code value} as its component of this type, replacing the one it had: logged
     * {@link #CHANGED}, with the bits of the fields that differ for a type that declares them (nothing when none does).
     */
    public void set(long node, T value) {
        checkOwner();
        type.cast(Objects.requireNonNull(value, "value"));
        int d = dense(node);
        if (d >= 0) {
            int fields = type.changedFields(values[d], value);
            values[d] = value;
            if (fields != 0) {
                log(node, CHANGED | fields);
            }
            return;
        }
        if (!scene.contains(node)) {
            throw new IllegalArgumentException(notInScene(node));
        }
        int slot = NodeHandle.index(node);
        if (slot >= sparse.length) {
            growSparse(slot + 1);
        }
        if (size == nodes.length) {
            growDense();
        }
        nodes[size] = node;
        values[size] = value;
        sparse[slot] = ++size;
        log(node, ADDED);
    }

    /**
     * Reports that {@code node}'s component was changed in place (a mutable value), so consumers following the log see
     * it; throws if the node has none.
     */
    public void changed(long node) {
        changed(node, 0);
    }

    /**
     * Reports that {@code node}'s component was changed in place in {@code fields} (bits from {@link #FIELD} up), so
     * consumers following the log see which; throws if the node has none.
     */
    public void changed(long node, int fields) {
        checkOwner();
        if ((fields & ~FIELDS) != 0) {
            throw new IllegalArgumentException(notFields(fields));
        }
        held(node);
        log(node, CHANGED | fields);
    }

    @AllowAllocation("diagnostics")
    private static String notFields(int fields) {
        return "Not field bits: " + Integer.toBinaryString(fields);
    }

    /** Takes {@code node}'s component away; false if it had none. */
    public boolean remove(long node) {
        checkOwner();
        int d = dense(node);
        if (d < 0) {
            return false;
        }
        drop(d);
        log(node, REMOVED);
        return true;
    }

    /** Called by the scene for each node it removes, before the slot can be reused. */
    void nodeRemoved(long node) {
        int d = dense(node);
        if (d >= 0) {
            drop(d);
            log(node, REMOVED);
        }
    }

    private void drop(int d) {
        int last = --size;
        sparse[NodeHandle.index(nodes[d])] = 0;
        if (d != last) {
            nodes[d] = nodes[last];
            values[d] = values[last];
            sparse[NodeHandle.index(nodes[d])] = d + 1;
        }
        nodes[last] = NodeHandle.NONE;
        values[last] = null;
    }

    /** Whether {@code node} has a component of this type. */
    public boolean has(long node) {
        return dense(node) >= 0;
    }

    /** {@code node}'s component, or null. */
    public T get(long node) {
        int d = dense(node);
        return d < 0 ? null : values[d];
    }

    /** {@code node}'s packed index (for {@link #value(int)}), or -1 without a component. */
    public int indexOf(long node) {
        return dense(node);
    }

    /** How many nodes have a component of this type. */
    public int size() {
        return size;
    }

    /** The node of packed entry {@code i} (0 to {@link #size()} - 1). */
    public long node(int i) {
        return nodes[Objects.checkIndex(i, size)];
    }

    /** The value of packed entry {@code i}. */
    public T value(int i) {
        return values[Objects.checkIndex(i, size)];
    }

    /** Visits every component, in packed order. Do not add or remove components from the visitor. */
    public void forEach(Each<? super T> visitor) {
        for (int i = 0; i < size; i++) {
            visitor.visit(nodes[i], values[i]);
        }
    }

    /** A count that grows with every change: a consumer comparing it skips frames without one. */
    public long version() {
        return version;
    }

    private int dense(long node) {
        int slot = NodeHandle.index(node);
        if (node == NodeHandle.NONE || slot < 0 || slot >= sparse.length) {
            return -1;
        }
        int d = sparse[slot] - 1;
        return d >= 0 && nodes[d] == node ? d : -1;
    }

    private int held(long node) {
        int d = dense(node);
        if (d < 0) {
            throw new IllegalArgumentException(none(node));
        }
        return d;
    }

    @AllowAllocation("diagnostics")
    private String none(long node) {
        return "No " + type.name() + " on " + NodeHandle.toString(node);
    }

    @AllowAllocation("diagnostics")
    private static String notInScene(long node) {
        return "Not a node of the scene: " + NodeHandle.toString(node);
    }

    @AllowAllocation("growing with the scene")
    private void growSparse(int length) {
        sparse = Arrays.copyOf(sparse, Math.max(length, sparse.length * 2));
    }

    @AllowAllocation("growing with the components")
    private void growDense() {
        nodes = Arrays.copyOf(nodes, nodes.length * 2);
        values = Arrays.copyOf(values, values.length * 2);
    }

    // ---------------------------------------------------------------- changes

    /**
     * Reports every component that changed after {@code since} (a {@code ChangeCursor} mark on the scene's clock), in
     * the order of the changes; a node may be reported more than once. Returns false, reporting nothing, when changes
     * that old are no longer kept: rebuild by iterating then. Allocates nothing.
     */
    public boolean changesSince(long since, Visitor visitor) {
        if (since < logIncompleteThrough) {
            return false;
        }
        for (int i = firstAfter(since); i < logSize; i++) {
            int entry = (logStart + i) % logStamps.length;
            visitor.changed(logNodes[entry], logChanges[entry]);
        }
        return true;
    }

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

    private void log(long node, int changes) {
        version++;
        if (logSize == logStamps.length) {
            if (logStamps.length < MAX_LOG) {
                growLog();
            } else {
                logIncompleteThrough = logStamps[logStart];
                logStart = (logStart + 1) % logStamps.length;
                logSize--;
            }
        }
        int entry = (logStart + logSize) % logStamps.length;
        logStamps[entry] = clock.stamp();
        logNodes[entry] = node;
        logChanges[entry] = changes;
        logSize++;
    }

    @AllowAllocation("growing the change log, up to MAX_LOG")
    private void growLog() {
        int length = logStamps.length * 2;
        long[] stamps = new long[length];
        long[] logged = new long[length];
        int[] changes = new int[length];
        for (int i = 0; i < logSize; i++) {
            int entry = (logStart + i) % logStamps.length;
            stamps[i] = logStamps[entry];
            logged[i] = logNodes[entry];
            changes[i] = logChanges[entry];
        }
        logStamps = stamps;
        logNodes = logged;
        logChanges = changes;
        logStart = 0;
    }

    private void checkOwner() {
        Thread owner = clock.owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(SceneGraph.notOwner(owner));
        }
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return type.name() + " store, " + size + " components";
    }
}
