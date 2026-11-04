package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.Objects;

/**
 * The listeners of a world (AU12), on nodes: device listeners, whose positions the device mix is spatialized for (each
 * voice for its nearest one: split screen), and rendering listeners, which mix what they hear into a mix of their own
 * ({@link ListenerMix}) that sounds re-emit in the world (a camera heard from a television), a device bus or a tap.
 *
 * <pre>{@code
 * Listener p1 = sounds.listeners().device(player1Camera);
 * Listener cctv = sounds.listeners().render(cameraNode, ListenerMix.mono().buses(Bus.SFX).maxDistance(40));
 * sounds.play(TV_SPEAKER, tvNode).input(cctv);
 * }</pre>
 *
 * <p>Listeners are {@link AudioListener} components on their nodes (application-api.md D46): saved with the scene,
 * gone with their nodes, and set through the scene's components as well as here. A listener's id is its node's handle;
 * a node has one listener at most (adding another replaces it).
 *
 * <p>The listener graph has an edge M → L when rendering listener L hears a sound playing M's mix. After each
 * {@link Sounds#advance}, {@link #order} lists the rendering listeners so that each comes after those it hears
 * (rendered in that order, a chain adds no delay); edges closing a cycle are {@link #delayed} (they read the previous
 * block: one block of delay, which also makes feedback well defined); edges past a chain of {@value #MAX_CHAIN}
 * rendering listeners are {@link #cut}. A listener never hears its own re-emission (no self edge). Used from the
 * scene's thread; reading and the graph allocate nothing once grown.
 */
public final class Listeners {

    /** Rendering listeners in a chain, at most; deeper edges are cut and counted. */
    public static final int MAX_CHAIN = 4;

    /** Receives listeners. */
    @FunctionalInterface
    public interface Visitor {
        void visit(long listener);
    }

    private static final byte EDGE = 0;
    private static final byte DELAYED = 1;
    private static final byte CUT = 2;

    private final SceneGraph scene;
    private final ChangeClock clock;
    private final ComponentStore<AudioListener> store; // null for the UI store, which places nothing

    // The listeners as of the last advance (or add or remove): their slots for the store's own work.
    private int slots;
    private long[] nodes = new long[4];
    private AudioListener[] values = new AudioListener[4];
    private double[] positions = new double[12];

    // The graph, rebuilt each advance, over the slots it was solved for.
    private long[] solved = new long[4];
    private int solvedCount;
    private int edgeCount;
    private int[] edgeFrom = new int[8];
    private int[] edgeTo = new int[8];
    private byte[] edgeKind = new byte[8];
    private int orderCount;
    private int[] order = new int[4];
    private byte[] marks = new byte[4];
    private int[] depths = new int[4];
    private int[] stack = new int[4];
    private int[] stackEdge = new int[4];
    private int cycles;
    private int cuts;

    Listeners(SceneGraph scene, ChangeClock clock) {
        this.scene = scene;
        this.clock = clock;
        this.store = scene == null ? null : scene.components().store(AudioListener.TYPE);
    }

    /** A device listener on {@code node}. */
    @AllowAllocation("a handle per listener")
    public Listener device(long node) {
        checkOwner();
        return new Listener(this, add(node, AudioListener.device()));
    }

    /** A rendering listener on {@code node}, mixing as {@code mix} says. */
    @AllowAllocation("a handle per listener")
    public Listener render(long node, ListenerMix mix) {
        checkOwner();
        return new Listener(this, add(node, AudioListener.render(Objects.requireNonNull(mix, "mix"))));
    }

    private long add(long node, AudioListener listener) {
        if (scene == null) {
            throw new IllegalStateException("The UI store has no listeners: its sounds are not placed");
        }
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        Placement.place(scene, node, listener.place);
        store.set(node, listener);
        snapshot();
        return node;
    }

    /** The slot of a listener's id as of the last snapshot, or -1. */
    int slot(long listener) {
        for (int slot = 0; slot < slots; slot++) {
            if (nodes[slot] == listener) {
                return slot;
            }
        }
        return -1;
    }

    /** Removes {@code listener}; nothing for one removed already. */
    public void remove(long listener) {
        checkOwner();
        if (store != null && store.remove(listener)) {
            snapshot();
        }
    }

    public boolean alive(long listener) {
        return store != null && store.has(listener);
    }

    /** Whether {@code listener} renders a mix of its own (else it is a device listener). */
    public boolean rendering(long listener) {
        return held(listener).rendering();
    }

    public long node(long listener) {
        held(listener);
        return listener;
    }

    /** A rendering listener's mix; {@code null} for a device listener. */
    public ListenerMix mix(long listener) {
        return held(listener).mix();
    }

    /** Component {@code axis} of where {@code listener} is, as of the last advance. */
    public double position(long listener, int axis) {
        return held(listener).position(axis);
    }

    /** Component {@code axis} (x, y, z, w) of {@code listener}'s rotation, as of the last advance. */
    public double rotation(long listener, int axis) {
        return held(listener).rotation(axis);
    }

    /** How many listeners there are. */
    public int size() {
        return store == null ? 0 : store.size();
    }

    /** A count that changes whenever a listener is added, replaced or removed. */
    public int version() {
        return store == null ? 0 : (int) store.version();
    }

    /** Visits every listener, in the store's packed order. */
    public void forEach(Visitor visitor) {
        for (int i = 0; i < size(); i++) {
            visitor.visit(store.node(i));
        }
    }

    private AudioListener held(long listener) {
        AudioListener value = store == null ? null : store.get(listener);
        if (value == null) {
            throw new IllegalArgumentException(gone(listener));
        }
        return value;
    }

    @AllowAllocation("diagnostics")
    private static String gone(long listener) {
        return "No listener is " + Long.toHexString(listener);
    }

    // By slot, for the store.

    int slots() {
        return slots;
    }

    boolean renderingAt(int slot) {
        return values[slot].rendering();
    }

    ListenerMix mixAt(int slot) {
        return values[slot].mix();
    }

    boolean deviceAt(int slot) {
        return !values[slot].rendering();
    }

    double[] positions() {
        return positions;
    }

    /** Takes the listeners' places from their nodes (a removed node took its listener with it). */
    void update() {
        for (int i = 0; i < size(); i++) {
            Placement.place(scene, store.node(i), store.value(i).place);
        }
        snapshot();
    }

    /** Takes the store's listeners as the slots. */
    private void snapshot() {
        int n = size();
        if (n > nodes.length) {
            grow(n);
        }
        for (int slot = 0; slot < n; slot++) {
            nodes[slot] = store.node(slot);
            AudioListener value = store.value(slot);
            values[slot] = value;
            System.arraycopy(value.place, 0, positions, 3 * slot, 3);
        }
        for (int slot = n; slot < slots; slot++) {
            values[slot] = null;
        }
        slots = n;
    }

    @AllowAllocation("growing with the listeners")
    private void grow(int at) {
        int n = Math.max(at, 2 * nodes.length);
        nodes = Arrays.copyOf(nodes, n);
        values = Arrays.copyOf(values, n);
        positions = Arrays.copyOf(positions, 3 * n);
        solved = Arrays.copyOf(solved, n);
        order = Arrays.copyOf(order, n);
        marks = Arrays.copyOf(marks, n);
        depths = Arrays.copyOf(depths, n);
        stack = Arrays.copyOf(stack, n);
        stackEdge = Arrays.copyOf(stackEdge, n);
    }

    // The graph.

    /** Starts the graph over: no edges, over the slots as they are now. */
    void clearGraph() {
        edgeCount = 0;
        System.arraycopy(nodes, 0, solved, 0, slots);
        solvedCount = slots;
    }

    /** Adds the edge M → L for rendering listeners in slots {@code from} and {@code to}, once. */
    void edge(int from, int to) {
        for (int e = 0; e < edgeCount; e++) {
            if (edgeFrom[e] == from && edgeTo[e] == to) {
                return;
            }
        }
        if (edgeCount == edgeFrom.length) {
            growEdges();
        }
        edgeFrom[edgeCount] = from;
        edgeTo[edgeCount] = to;
        edgeKind[edgeCount++] = EDGE;
    }

    @AllowAllocation("growing with the listener graph")
    private void growEdges() {
        int n = 2 * edgeFrom.length;
        edgeFrom = Arrays.copyOf(edgeFrom, n);
        edgeTo = Arrays.copyOf(edgeTo, n);
        edgeKind = Arrays.copyOf(edgeKind, n);
    }

    /**
     * Orders the rendering listeners: a depth-first search from each in slot order marks edges into the path as
     * delayed (they close a cycle); the reverse of the finishing order puts each listener after those it hears; then
     * edges reaching past a chain of {@link #MAX_CHAIN} are cut.
     */
    void solveGraph() {
        int n = solvedCount;
        Arrays.fill(marks, 0, n, (byte) 0);
        orderCount = 0;
        cycles = 0;
        cuts = 0;
        int finished = 0;
        for (int root = 0; root < n; root++) {
            if (!values[root].rendering() || marks[root] != 0) {
                continue;
            }
            int top = 0;
            stack[0] = root;
            stackEdge[0] = 0;
            marks[root] = 1;
            while (top >= 0) {
                int at = stack[top];
                int e = stackEdge[top];
                while (e < edgeCount && edgeFrom[e] != at) {
                    e++;
                }
                if (e == edgeCount) {
                    marks[at] = 2;
                    order[n - 1 - finished++] = at; // reverse finishing order, filled from the end
                    top--;
                    continue;
                }
                stackEdge[top] = e + 1;
                int to = edgeTo[e];
                if (marks[to] == 1) {
                    edgeKind[e] = DELAYED;
                    cycles++;
                } else if (marks[to] == 0) {
                    marks[to] = 1;
                    stack[++top] = to;
                    stackEdge[top] = 0;
                }
            }
        }
        System.arraycopy(order, n - finished, order, 0, finished);
        orderCount = finished;
        for (int i = 0; i < orderCount; i++) {
            depths[order[i]] = 0;
        }
        for (int i = 0; i < orderCount; i++) {
            int at = order[i];
            for (int e = 0; e < edgeCount; e++) {
                if (edgeFrom[e] == at && edgeKind[e] == EDGE) {
                    if (depths[at] + 1 >= MAX_CHAIN) {
                        edgeKind[e] = CUT;
                        cuts++;
                    } else {
                        depths[edgeTo[e]] = Math.max(depths[edgeTo[e]], depths[at] + 1);
                    }
                }
            }
        }
    }

    /** How many rendering listeners {@link #order} lists. */
    public int orderCount() {
        return orderCount;
    }

    /** Rendering listener {@code i} in rendering order: each after those it hears through a chain. */
    public long order(int i) {
        return solved[order[Objects.checkIndex(i, orderCount)]];
    }

    /** Whether rendering listener {@code to} hears {@code from} at all, through a sound playing its mix. */
    public boolean hears(long to, long from) {
        return edgeIndex(from, to) >= 0;
    }

    /** Whether the edge {@code from} → {@code to} closes a cycle: {@code to} hears {@code from}'s previous block. */
    public boolean delayed(long from, long to) {
        int e = edgeIndex(from, to);
        return e >= 0 && edgeKind[e] == DELAYED;
    }

    /** Whether the edge {@code from} → {@code to} was cut, reaching past a chain of {@link #MAX_CHAIN}. */
    public boolean cut(long from, long to) {
        int e = edgeIndex(from, to);
        return e >= 0 && edgeKind[e] == CUT;
    }

    private int edgeIndex(long from, long to) {
        int f = solvedSlot(from);
        int t = solvedSlot(to);
        for (int e = 0; e < edgeCount; e++) {
            if (edgeFrom[e] == f && edgeTo[e] == t) {
                return e;
            }
        }
        return -1;
    }

    private int solvedSlot(long listener) {
        for (int slot = 0; slot < solvedCount; slot++) {
            if (solved[slot] == listener) {
                return slot;
            }
        }
        return -1;
    }

    /** How many edges closed a cycle in the last graph. */
    public int cycles() {
        return cycles;
    }

    /** How many edges the last graph cut. */
    public int cuts() {
        return cuts;
    }

    /** Throws unless the calling thread may change the listeners: any, or the clock's owner (ChangeClock.ownedBy). */
    private void checkOwner() {
        Thread owner = clock.owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(notOwner(owner));
        }
    }

    @AllowAllocation("diagnostics")
    private static String notOwner(Thread owner) {
        return "The listeners change on " + owner.getName() + " only, not on " + Thread.currentThread().getName();
    }
}
