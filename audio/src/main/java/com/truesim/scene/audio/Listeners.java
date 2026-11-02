package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
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
 * <p>The listener graph has an edge M → L when rendering listener L hears a sound playing M's mix. After each
 * {@link Sounds#advance}, {@link #order} lists the rendering listeners so that each comes after those it hears
 * (rendered in that order, a chain adds no delay); edges closing a cycle are {@link #delayed} (they read the previous
 * block: one block of delay, which also makes feedback well defined); edges past a chain of {@value #MAX_CHAIN}
 * rendering listeners are {@link #cut}. A listener never hears its own re-emission (no self edge). Ids carry a
 * generation, as sound ids do. Used from the scene's thread; reading and the graph allocate nothing once grown.
 */
public final class Listeners {

    /** Rendering listeners in a chain, at most; deeper edges are cut and counted. */
    public static final int MAX_CHAIN = 4;

    /** Receives listeners. */
    @FunctionalInterface
    public interface Visitor {
        void visit(long listener);
    }

    private static final byte FREE = 0;
    private static final byte DEVICE = 1;
    private static final byte RENDER = 2;
    private static final byte EDGE = 0;
    private static final byte DELAYED = 1;
    private static final byte CUT = 2;

    private final SceneGraph scene;
    private int slots;
    private int size;
    private int version;
    private byte[] kinds = new byte[4];
    private int[] generations = new int[4];
    private long[] nodes = new long[4];
    private ListenerMix[] mixes = new ListenerMix[4];
    private double[] positions = new double[12];
    private double[] rotations = new double[16];

    // The graph, rebuilt each advance.
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

    Listeners(SceneGraph scene) {
        this.scene = scene;
    }

    /** A device listener on {@code node}. */
    @AllowAllocation("a handle per listener")
    public Listener device(long node) {
        return new Listener(this, add(DEVICE, node, null));
    }

    /** A rendering listener on {@code node}, mixing as {@code mix} says. */
    @AllowAllocation("a handle per listener")
    public Listener render(long node, ListenerMix mix) {
        return new Listener(this, add(RENDER, node, Objects.requireNonNull(mix, "mix")));
    }

    private long add(byte kind, long node, ListenerMix mix) {
        if (scene == null) {
            throw new IllegalStateException("The UI store has no listeners: its sounds are not placed");
        }
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        int slot = 0;
        while (slot < slots && kinds[slot] != FREE) {
            slot++;
        }
        if (slot == slots) {
            if (slots == kinds.length) {
                grow();
            }
            slots++;
        }
        kinds[slot] = kind;
        generations[slot]++;
        nodes[slot] = node;
        mixes[slot] = mix;
        Placement.place(scene, node, positions, rotations, slot);
        size++;
        version++;
        return id(slot);
    }

    @AllowAllocation("growing with the listeners")
    private void grow() {
        int n = 2 * kinds.length;
        kinds = Arrays.copyOf(kinds, n);
        generations = Arrays.copyOf(generations, n);
        nodes = Arrays.copyOf(nodes, n);
        mixes = Arrays.copyOf(mixes, n);
        positions = Arrays.copyOf(positions, 3 * n);
        rotations = Arrays.copyOf(rotations, 4 * n);
        order = Arrays.copyOf(order, n);
        marks = Arrays.copyOf(marks, n);
        depths = Arrays.copyOf(depths, n);
        stack = Arrays.copyOf(stack, n);
        stackEdge = Arrays.copyOf(stackEdge, n);
    }

    private long id(int slot) {
        return (long) generations[slot] << 32 | slot;
    }

    /** The slot of a listener's id, or -1 once removed. */
    int slot(long listener) {
        int slot = (int) listener;
        return slot >= 0 && slot < slots && kinds[slot] != FREE && generations[slot] == (int) (listener >>> 32) ? slot
            : -1;
    }

    /** Removes {@code listener}; nothing for one removed already. */
    public void remove(long listener) {
        int slot = slot(listener);
        if (slot >= 0) {
            kinds[slot] = FREE;
            mixes[slot] = null;
            size--;
            version++;
        }
    }

    public boolean alive(long listener) {
        return slot(listener) >= 0;
    }

    /** Whether {@code listener} renders a mix of its own (else it is a device listener). */
    public boolean rendering(long listener) {
        return kinds[held(listener)] == RENDER;
    }

    public long node(long listener) {
        return nodes[held(listener)];
    }

    /** A rendering listener's mix; {@code null} for a device listener. */
    public ListenerMix mix(long listener) {
        return mixes[held(listener)];
    }

    /** Component {@code axis} of where {@code listener} is, as of the last advance. */
    public double position(long listener, int axis) {
        return positions[3 * held(listener) + Objects.checkIndex(axis, 3)];
    }

    /** Component {@code axis} (x, y, z, w) of {@code listener}'s rotation, as of the last advance. */
    public double rotation(long listener, int axis) {
        return rotations[4 * held(listener) + Objects.checkIndex(axis, 4)];
    }

    /** How many listeners there are. */
    public int size() {
        return size;
    }

    /** A count that changes whenever a listener is added or removed. */
    public int version() {
        return version;
    }

    /** Visits every listener, in slot order. */
    public void forEach(Visitor visitor) {
        for (int slot = 0; slot < slots; slot++) {
            if (kinds[slot] != FREE) {
                visitor.visit(id(slot));
            }
        }
    }

    private int held(long listener) {
        int slot = slot(listener);
        if (slot < 0) {
            throw new IllegalArgumentException(gone(listener));
        }
        return slot;
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
        return kinds[slot] == RENDER;
    }

    ListenerMix mixAt(int slot) {
        return mixes[slot];
    }

    boolean deviceAt(int slot) {
        return kinds[slot] == DEVICE;
    }

    double[] positions() {
        return positions;
    }

    /** Takes the listeners' places from their nodes; a listener whose node was removed is removed. */
    void update() {
        for (int slot = 0; slot < slots; slot++) {
            if (kinds[slot] != FREE) {
                if (scene.contains(nodes[slot])) {
                    Placement.place(scene, nodes[slot], positions, rotations, slot);
                } else {
                    remove(id(slot));
                }
            }
        }
    }

    // The graph.

    /** Starts the graph over: no edges. */
    void clearGraph() {
        edgeCount = 0;
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
        Arrays.fill(marks, 0, slots, (byte) 0);
        orderCount = 0;
        cycles = 0;
        cuts = 0;
        int finished = 0;
        for (int root = 0; root < slots; root++) {
            if (kinds[root] != RENDER || marks[root] != 0) {
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
                    order[slots - 1 - finished++] = at; // reverse finishing order, filled from the end
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
        System.arraycopy(order, slots - finished, order, 0, finished);
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
        return id(order[Objects.checkIndex(i, orderCount)]);
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
        int f = slot(from);
        int t = slot(to);
        for (int e = 0; e < edgeCount; e++) {
            if (edgeFrom[e] == f && edgeTo[e] == t) {
                return e;
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
}
