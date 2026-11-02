package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.TransformArray;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * The effects playing in a scene: for each, its effect data, where it plays (a node, maybe a joint, or a world
 * position), its seed, user parameters, time scale and state, on a clock of whole ticks. Changes are logged on the
 * scene's clock, as renderables' are, so the engine and replication follow what plays, not particles.
 *
 * <pre>{@code
 * Effects effects = new Effects(scene);
 * Effect torch = effects.play(TORCH_FIRE, torchNode);           // attached: follows the node
 * torch.set(INTENSITY, 0.8f);
 * Effect shot = effects.play(MUZZLE_FLASH, x, y, z);            // at a world position
 * effects.burst(BULLET_IMPACTS, x, y, z, nx, ny, nz, surface);  // into the shared instance of its cell (E22)
 * // each frame, after scene.update():
 * effects.advance(seconds);                                     // the world clock's delta; ended effects go
 * effects.changesSince(since, (effect, changes) -> ...);        // ADDED, STOPPED, REMOVED, PARAMETERS, ...
 * effects.replace(TORCH_FIRE, editedFire);                      // hot reload: its instances restart, handles kept
 * }</pre>
 *
 * <p>Effects are slots: an id names a slot and its generation, so an {@link Effect} handle of an effect that ended
 * never touches the slot's next effect; it is inert, and {@link Effect#alive()} says so. An effect ends by itself when
 * its last particle has died at the latest (known from the data, without asking the GPU), or when killed. Time is
 * counted in ticks of {@value #TICKS_PER_SECOND} a second; each effect's own time moves by the delta times its time
 * scale and the global one, carried exactly in fixed point, so the same ticks give the same times however frames
 * split them, and nothing moves while paused. Advancing, reading and setting allocate nothing; playing allocates its
 * handle. Used from the scene's thread.
 */
public final class Effects {

    /** Ticks per second of effect time: microseconds. */
    public static final long TICKS_PER_SECOND = 1_000_000;

    /** The effect started playing. */
    public static final int ADDED = 1;
    /** The effect stopped spawning; its particles live on. */
    public static final int STOPPED = 1 << 1;
    /** The effect ended or was killed; its id is stale. Reported alone. */
    public static final int REMOVED = 1 << 2;
    /** A user parameter of the effect changed. */
    public static final int PARAMETERS = 1 << 3;
    /** The effect's time scale or pause changed. */
    public static final int TIME = 1 << 4;
    /** Where the effect plays changed: its offset, position, attachment or inherited velocity. */
    public static final int MOVED = 1 << 5;
    /** The effect's seed changed. */
    public static final int SEED = 1 << 6;
    /**
     * The effect restarted with new data ({@link #replace}, a hot reload), keeping its id: whoever follows it starts it
     * over, as {@link #restarts} tells.
     */
    public static final int REPLACED = 1 << 7;

    /** Receives changed effects. */
    @FunctionalInterface
    public interface Visitor {
        /** {@code effect} changed in the ways {@code changes} lists. */
        void changed(long effect, int changes);
    }

    /** Receives playing effects. */
    @FunctionalInterface
    public interface EffectVisitor {
        void visit(long effect);
    }

    /** Time scales are fixed point with this many fraction bits. */
    private static final int SCALE_BITS = 16;
    private static final int ONE = 1 << SCALE_BITS;
    private static final byte FREE = 0;
    private static final byte PLAYING = 1;
    private static final byte STOPPING = 2;
    // Kinds of slots (E23): an effect of its own, a placement of a shared effect, a shared simulation (a variant) and
    // a near placement's simulation of its own.
    private static final byte OWN = 0;
    private static final byte PLACEMENT = 1;
    private static final byte VARIANT = 2;
    private static final byte NEAR = 3;

    private final SceneGraph scene;
    private final ChangeClock clock;
    private int size;
    private int slots;
    private int[] free = new int[16];
    private int freeCount;
    private byte[] states = new byte[16];
    private int[] generations = new int[16];
    private EffectData[] data = new EffectData[16];
    private long[] nodes = new long[16];
    private double[] offsets = new double[3 * 16];
    /** World positions (3 per effect) and rotations (4 per effect), as of the last advance. */
    private double[] positions = new double[3 * 16];
    private double[] rotations = new double[4 * 16];
    private float[] inherit = new float[16];
    private int[] seeds = new int[16];
    private long[] starts = new long[16];
    private long[] times = new long[16];
    private long[] remainders = new long[16];
    private int[] scales = new int[16];
    private boolean[] paused = new boolean[16];
    /** Held by the runtime: culled and paused by its effect type. */
    private boolean[] held = new boolean[16];
    private long[] ends = new long[16];
    private long[] stops = new long[16];
    private boolean[] shared = new boolean[16];
    private long[] cells = new long[3 * 16];
    private float[][] parameters = new float[16][];
    private int[] restarts = new int[16];
    private byte[] kinds = new byte[16];
    /** A placement's variant and a near simulation's placement, by id. */
    private long[] links = new long[16];
    /** A variant's index among its effect's. */
    private int[] variantIndex = new int[16];
    /** A variant's placements. */
    private int[] placements = new int[16];
    /** A placement's near simulation, by id (0: none). */
    private long[] nears = new long[16];
    private float[] drawScales = new float[16];
    private int placed;
    private long now;
    private int played;
    private int scale = ONE;
    private boolean pausedAll;
    private final float[] wind = new float[3];
    private final EffectColliders colliders;

    // Burst calls into shared instances, in the order made.
    private long[] burstEffects = new long[16];
    private long[] burstTimes = new long[16];
    private double[] burstPositions = new double[3 * 16];
    private float[] burstNormals = new float[3 * 16];
    private int[] burstPayloads = new int[16];
    private int[] burstSerials = new int[16];
    private int burstSerial;
    private int burstCount;

    // The change log: a ring of (stamp, effect, changes).
    private final long[] logStamps;
    private final long[] logEffects;
    private final int[] logChanges;
    private int logStart;
    private int logSize;
    private long logIncompleteThrough = -1;

    /** The effects of {@code scene}, logging their changes on its clock. */
    public Effects(SceneGraph scene) {
        this(scene, SceneGraph.DEFAULT_LOG_CAPACITY);
    }

    /** The effects of {@code scene}, keeping the last {@code logCapacity} changes for consumers. */
    @AllowAllocation("created rarely")
    public Effects(SceneGraph scene, int logCapacity) {
        if (logCapacity < 1) {
            throw new IllegalArgumentException("The change log needs room: " + logCapacity);
        }
        this.scene = Objects.requireNonNull(scene, "scene");
        colliders = new EffectColliders(scene);
        clock = scene.clock();
        logStamps = new long[logCapacity];
        logEffects = new long[logCapacity];
        logChanges = new int[logCapacity];
    }

    public SceneGraph scene() {
        return scene;
    }

    /** The colliders on the scene's nodes that collision modules bounce particles off. */
    public EffectColliders colliders() {
        return colliders;
    }

    /** Plays {@code effect} attached to {@code node}: it follows the node (a joint's node for a socket). */
    @AllowAllocation("a handle per effect played")
    public Effect play(EffectData effect, long node) {
        return new Effect(this, start(effect, node));
    }

    /** Plays {@code effect} at the world position ({@code x}, {@code y}, {@code z}), unrotated. */
    @AllowAllocation("a handle per effect played")
    public Effect play(EffectData effect, double x, double y, double z) {
        return new Effect(this, start(effect, x, y, z));
    }

    /**
     * {@link #play(EffectData, long)} without a handle: returns the effect's id, which the methods taking ids change
     * ({@link #offset(long, double, double, double)}, {@link #set(long, EffectParameter.Float, float)}, ...).
     * Allocates nothing once the slots have grown.
     */
    public long start(EffectData effect, long node) {
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        int slot = claimPlayed(effect);
        nodes[slot] = node;
        place(slot);
        return added(slot);
    }

    /** {@link #play(EffectData, double, double, double)} without a handle: returns the effect's id. */
    public long start(EffectData effect, double x, double y, double z) {
        int slot = claimPlayed(effect);
        positions[3 * slot] = x;
        positions[3 * slot + 1] = y;
        positions[3 * slot + 2] = z;
        return added(slot);
    }

    /**
     * A slot for an effect played: of its own, or for a shared effect (E23) a placement of one of its variants, in
     * turn, which is started (at the origin, unrotated) if it does not play.
     */
    private int claimPlayed(EffectData effect) {
        int slot = claim(effect);
        if (effect.variants() > 0) {
            kinds[slot] = PLACEMENT;
            int variant = variantSlot(effect, placed++ % effect.variants());
            links[slot] = id(variant);
            placements[variant]++;
        }
        return slot;
    }

    /** The slot of variant {@code v} of shared effect {@code effect}, started if it does not play. */
    private int variantSlot(EffectData effect, int v) {
        for (int slot = 0; slot < slots; slot++) {
            if (states[slot] == PLAYING && kinds[slot] == VARIANT && variantIndex[slot] == v
                && data[slot].equals(effect)) {
                return slot;
            }
        }
        int slot = claim(effect);
        kinds[slot] = VARIANT;
        variantIndex[slot] = v;
        seeds[slot] = Hash.combine(effect.hashCode(), v);
        log(id(slot), ADDED);
        return slot;
    }

    /**
     * Bursts {@code effect} at ({@code x}, {@code y}, {@code z}) facing the normal ({@code nx}, {@code ny},
     * {@code nz}), with {@code payload} for its burst inputs (a surface type, say), into the one instance of the effect
     * in that cell of its type's {@link EffectType#burstCell() burst cells} (E22): made on the cell's first burst,
     * ended once its particles of the last burst have died. Its emitters' bursts fire, after their delays, as if the
     * effect started now; rates do not run in shared instances. Returns the shared instance's id.
     */
    public long burst(EffectData effect, double x, double y, double z, float nx, float ny, float nz, int payload) {
        checkOwner();
        double size = effect.type().burstCell();
        long cx = (long) Math.floor(x / size);
        long cy = (long) Math.floor(y / size);
        long cz = (long) Math.floor(z / size);
        int slot = sharedSlot(effect, cx, cy, cz);
        if (slot < 0) {
            slot = claim(effect);
            shared[slot] = true;
            ends[slot] = 0;
            cells[3 * slot] = cx;
            cells[3 * slot + 1] = cy;
            cells[3 * slot + 2] = cz;
            positions[3 * slot] = (cx + 0.5) * size;
            positions[3 * slot + 1] = (cy + 0.5) * size;
            positions[3 * slot + 2] = (cz + 0.5) * size;
            log(id(slot), ADDED);
        }
        ends[slot] = Math.max(ends[slot], ticks(seconds(times[slot]) + effect.burstEnd()));
        if (burstCount == burstEffects.length) {
            growBursts();
        }
        int b = burstCount++;
        burstEffects[b] = id(slot);
        burstTimes[b] = times[slot];
        burstPositions[3 * b] = x;
        burstPositions[3 * b + 1] = y;
        burstPositions[3 * b + 2] = z;
        burstNormals[3 * b] = nx;
        burstNormals[3 * b + 1] = ny;
        burstNormals[3 * b + 2] = nz;
        burstPayloads[b] = payload;
        burstSerials[b] = burstSerial++;
        return id(slot);
    }

    private int sharedSlot(EffectData effect, long cx, long cy, long cz) {
        for (int slot = 0; slot < slots; slot++) {
            if (shared[slot] && cells[3 * slot] == cx && cells[3 * slot + 1] == cy && cells[3 * slot + 2] == cz
                && data[slot].equals(effect)) {
                return slot;
            }
        }
        return -1;
    }

    /** How many burst calls are kept: those made since the oldest the runtime still needs. */
    public int burstCount() {
        return burstCount;
    }

    /** The shared instance burst call {@code i} went into. */
    public long burstEffect(int i) {
        return burstEffects[Objects.checkIndex(i, burstCount)];
    }

    /** Burst call {@code i}'s time on its instance's clock, in ticks. */
    public long burstTime(int i) {
        return burstTimes[Objects.checkIndex(i, burstCount)];
    }

    /** Component {@code axis} (0 to 2) of burst call {@code i}'s world position. */
    public double burstPosition(int i, int axis) {
        return burstPositions[3 * Objects.checkIndex(i, burstCount) + Objects.checkIndex(axis, 3)];
    }

    /** Component {@code axis} (0 to 2) of burst call {@code i}'s normal. */
    public float burstNormal(int i, int axis) {
        return burstNormals[3 * Objects.checkIndex(i, burstCount) + Objects.checkIndex(axis, 3)];
    }

    /** Burst call {@code i}'s payload. */
    public int burstPayload(int i) {
        return burstPayloads[Objects.checkIndex(i, burstCount)];
    }

    /** Burst call {@code i}'s serial number: burst calls are numbered as they are made, from 0. */
    public int burstSerial(int i) {
        return burstSerials[Objects.checkIndex(i, burstCount)];
    }

    /** The serial number the next burst call will get. */
    int nextBurstSerial() {
        return burstSerial;
    }

    /** Forgets the oldest {@code count} burst calls, once nothing needs them. */
    public void dropBursts(int count) {
        Objects.checkFromIndexSize(0, count, burstCount);
        int kept = burstCount - count;
        System.arraycopy(burstEffects, count, burstEffects, 0, kept);
        System.arraycopy(burstTimes, count, burstTimes, 0, kept);
        System.arraycopy(burstPositions, 3 * count, burstPositions, 0, 3 * kept);
        System.arraycopy(burstNormals, 3 * count, burstNormals, 0, 3 * kept);
        System.arraycopy(burstPayloads, count, burstPayloads, 0, kept);
        System.arraycopy(burstSerials, count, burstSerials, 0, kept);
        burstCount = kept;
    }

    @AllowAllocation("room for more burst calls")
    private void growBursts() {
        int length = 2 * burstEffects.length;
        burstEffects = Arrays.copyOf(burstEffects, length);
        burstTimes = Arrays.copyOf(burstTimes, length);
        burstPositions = Arrays.copyOf(burstPositions, 3 * length);
        burstNormals = Arrays.copyOf(burstNormals, 3 * length);
        burstPayloads = Arrays.copyOf(burstPayloads, length);
        burstSerials = Arrays.copyOf(burstSerials, length);
    }

    private long added(int slot) {
        long id = id(slot);
        log(id, ADDED);
        return id;
    }

    /** A slot for a new effect of {@code effect}, set up to play from now. */
    @AllowAllocation("growing with the effects playing")
    private int claim(EffectData effect) {
        checkOwner();
        Objects.requireNonNull(effect, "effect");
        int slot;
        if (freeCount > 0) {
            slot = free[--freeCount];
        } else {
            if (slots == states.length) {
                grow(2 * slots);
            }
            slot = slots++;
        }
        size++;
        generations[slot]++;
        states[slot] = PLAYING;
        data[slot] = effect;
        nodes[slot] = NodeHandle.NONE;
        Arrays.fill(offsets, 3 * slot, 3 * slot + 3, 0);
        Arrays.fill(positions, 3 * slot, 3 * slot + 3, 0);
        Arrays.fill(rotations, 4 * slot, 4 * slot + 4, 0);
        rotations[4 * slot + 3] = 1;
        inherit[slot] = 0;
        seeds[slot] = Hash.pcg(played++);
        starts[slot] = now;
        times[slot] = 0;
        remainders[slot] = 0;
        scales[slot] = ONE;
        paused[slot] = false;
        held[slot] = false;
        ends[slot] = ticks(effect.end());
        stops[slot] = -1;
        shared[slot] = false;
        restarts[slot] = 0;
        kinds[slot] = OWN;
        links[slot] = 0;
        placements[slot] = 0;
        nears[slot] = 0;
        drawScales[slot] = 1;
        if (parameters[slot] == null || parameters[slot].length != effect.parameterFloats()) {
            parameters[slot] = new float[effect.parameterFloats()];
        }
        effect.defaults(parameters[slot], 0);
        return slot;
    }

    /** Ticks of {@code seconds}, rounded up; the most there are for infinity. */
    static long ticks(double seconds) {
        return seconds >= Long.MAX_VALUE / TICKS_PER_SECOND ? Long.MAX_VALUE
            : (long) Math.ceil(seconds * TICKS_PER_SECOND);
    }

    private static double seconds(long ticks) {
        return (double) ticks / TICKS_PER_SECOND;
    }

    @AllowAllocation("growing with the effects playing")
    private void grow(int length) {
        free = Arrays.copyOf(free, length);
        states = Arrays.copyOf(states, length);
        generations = Arrays.copyOf(generations, length);
        data = Arrays.copyOf(data, length);
        nodes = Arrays.copyOf(nodes, length);
        offsets = Arrays.copyOf(offsets, 3 * length);
        positions = Arrays.copyOf(positions, 3 * length);
        rotations = Arrays.copyOf(rotations, 4 * length);
        inherit = Arrays.copyOf(inherit, length);
        seeds = Arrays.copyOf(seeds, length);
        starts = Arrays.copyOf(starts, length);
        times = Arrays.copyOf(times, length);
        remainders = Arrays.copyOf(remainders, length);
        scales = Arrays.copyOf(scales, length);
        paused = Arrays.copyOf(paused, length);
        held = Arrays.copyOf(held, length);
        ends = Arrays.copyOf(ends, length);
        stops = Arrays.copyOf(stops, length);
        shared = Arrays.copyOf(shared, length);
        cells = Arrays.copyOf(cells, 3 * length);
        parameters = Arrays.copyOf(parameters, length);
        restarts = Arrays.copyOf(restarts, length);
        kinds = Arrays.copyOf(kinds, length);
        links = Arrays.copyOf(links, length);
        variantIndex = Arrays.copyOf(variantIndex, length);
        placements = Arrays.copyOf(placements, length);
        nears = Arrays.copyOf(nears, length);
        drawScales = Arrays.copyOf(drawScales, length);
    }

    private static long id(int slot, int generation) {
        return ((long) generation << 32) | slot;
    }

    private long id(int slot) {
        return id(slot, generations[slot]);
    }

    /** The slot of a playing effect's id, or -1 for an ended (or never played) one. */
    int slot(long effect) {
        int slot = (int) effect;
        return slot >= 0 && slot < slots && states[slot] != FREE && generations[slot] == (int) (effect >>> 32) ? slot
            : -1;
    }

    /** Whether {@code effect} still plays (stopping ones too): false once it ended or was killed. */
    public boolean alive(long effect) {
        return slot(effect) >= 0;
    }

    /** How many effects play. */
    public int size() {
        return size;
    }

    /** Visits every playing effect, in slot order. */
    public void forEach(EffectVisitor visitor) {
        for (int slot = 0; slot < slots; slot++) {
            if (states[slot] != FREE) {
                visitor.visit(id(slot));
            }
        }
    }

    // By slot, for the runtime.

    /** One past the highest slot in use. */
    int slots() {
        return slots;
    }

    /** The playing effect in {@code slot}, or 0 for none. */
    long effectAt(int slot) {
        return states[slot] != FREE ? id(slot) : 0;
    }

    EffectData dataAt(int slot) {
        return data[slot];
    }

    long timeAt(int slot) {
        return times[slot];
    }

    /** When the effect in {@code slot} stopped, or {@link Long#MAX_VALUE} while it spawns. */
    long stopAt(int slot) {
        return states[slot] == STOPPING ? stops[slot] : Long.MAX_VALUE;
    }

    boolean sharedAt(int slot) {
        return shared[slot];
    }

    int seedAt(int slot) {
        return seeds[slot];
    }

    double positionAt(int slot, int axis) {
        return positions[3 * slot + axis];
    }

    float[] parametersAt(int slot) {
        return parameters[slot];
    }

    int restartsAt(int slot) {
        return restarts[slot];
    }

    /** Holds the effect in {@code slot} still in time, or lets it go on: culled effects of pausing types. */
    void hold(int slot, boolean hold) {
        held[slot] = hold;
    }

    void killAt(int slot) {
        end(slot);
    }

    /** The data a playing effect plays. */
    public EffectData data(long effect) {
        return data[held(effect)];
    }

    /** The node a playing effect follows, or {@link NodeHandle#NONE}. */
    public long node(long effect) {
        return nodes[held(effect)];
    }

    /** A playing effect's seed. */
    public int seed(long effect) {
        return seeds[held(effect)];
    }

    /** When a playing effect started, in ticks of this clock ({@link #now()}). */
    public long start(long effect) {
        return starts[held(effect)];
    }

    /** A playing effect's own time since it started, in ticks: scaled, and still while paused. */
    public long time(long effect) {
        return times[held(effect)];
    }

    /** When a playing effect ends at the latest on its own clock, in ticks; {@link Long#MAX_VALUE} for never. */
    public long end(long effect) {
        return ends[held(effect)];
    }

    /** When a playing effect stopped on its own clock, in ticks, or -1 while it spawns. */
    public long stopTime(long effect) {
        return stops[held(effect)];
    }

    /** Whether a playing effect stopped spawning. */
    public boolean stopped(long effect) {
        return states[held(effect)] == STOPPING;
    }

    /** Whether a playing effect is a shared burst instance. */
    public boolean shared(long effect) {
        return shared[held(effect)];
    }

    /**
     * Whether a playing effect is a placement of a shared effect (E23): it simulates nothing, and draws its variant's
     * particles ({@link #variantOf}) where it is, turned and scaled as it is.
     */
    public boolean placement(long effect) {
        return kinds[held(effect)] == PLACEMENT;
    }

    /** Whether a playing effect is a variant of a shared effect: simulated at the origin, drawn only at placements. */
    public boolean variant(long effect) {
        return kinds[held(effect)] == VARIANT;
    }

    /** The variant a placement draws, by id; 0 once it ended (a shared effect that ends on its own) or for others. */
    public long variantOf(long effect) {
        int slot = held(effect);
        return kinds[slot] == PLACEMENT && slot(links[slot]) >= 0 ? links[slot] : 0;
    }

    /** A placement's simulation of its own while it is near a viewer (or its particles linger), by id; else 0. */
    public long nearOf(long effect) {
        int slot = held(effect);
        return kinds[slot] == PLACEMENT && slot(nears[slot]) >= 0 ? nears[slot] : 0;
    }

    /** The scale a placement draws its variant at; 1 for others. */
    public float scale(long effect) {
        return drawScales[held(effect)];
    }

    /** Component {@code axis} (0 to 2) of where a playing effect is, as of the last advance (its node's position). */
    public double position(long effect, int axis) {
        return positions[3 * held(effect) + Objects.checkIndex(axis, 3)];
    }

    /** Component {@code axis} (0 x to 3 w) of a playing effect's rotation, as of the last advance. */
    public double rotation(long effect, int axis) {
        return rotations[4 * held(effect) + Objects.checkIndex(axis, 4)];
    }

    /** Component {@code axis} (0 to 2) of a playing effect's offset in its node's frame. */
    public double offset(long effect, int axis) {
        return offsets[3 * held(effect) + Objects.checkIndex(axis, 3)];
    }

    /** How much of its node's velocity a playing effect's particles inherit. */
    public float inheritVelocity(long effect) {
        return inherit[held(effect)];
    }

    /** Float {@code index} of a playing effect's parameter block (see {@link EffectData#parameterOffset}). */
    public float parameter(long effect, int index) {
        return parameters[held(effect)][index];
    }

    /** Copies a playing effect's parameter block into {@code block[offset..)}. */
    public void parameters(long effect, float[] block, int offset) {
        float[] values = parameters[held(effect)];
        System.arraycopy(values, 0, block, offset, values.length);
    }

    /** A playing effect's own time scale (the global one apart). */
    public float timeScaleOf(long effect) {
        return (float) scales[held(effect)] / ONE;
    }

    /**
     * How many times a playing effect restarted with new data ({@link #replace}): followers keeping state per effect
     * (the runtime, the simulators, the GPU side) start theirs over when it changes, as for a new id.
     */
    public int restarts(long effect) {
        return restarts[held(effect)];
    }

    /** Whether a playing effect is paused (the global pause apart). */
    public boolean paused(long effect) {
        return paused[held(effect)];
    }

    /** The slot of a playing effect; throws for an ended one. */
    private int held(long effect) {
        int slot = slot(effect);
        if (slot < 0) {
            throw new IllegalArgumentException(notPlaying(effect));
        }
        return slot;
    }

    @AllowAllocation("diagnostics")
    private static String notPlaying(long effect) {
        return "No effect plays as " + Long.toHexString(effect);
    }

    // Changes through handles: inert for ended effects.

    /**
     * Sets float user parameter {@code parameter} of playing effect {@code effect} (inert once it ended), as
     * {@link Effect#set(EffectParameter.Float, float)} does. Placements of shared effects take none: they draw a
     * simulation shared with others.
     */
    public void set(long effect, EffectParameter.Float parameter, float value) {
        set(effect, parameter, value, 0, 0, 0);
    }

    void set(long effect, EffectParameter parameter, float a, float b, float c, float d) {
        int slot = slot(effect);
        if (slot < 0) {
            return;
        }
        checkOwner();
        if (kinds[slot] == PLACEMENT) {
            throw new IllegalStateException(shares("parameters of its own"));
        }
        EffectData effectData = data[slot];
        int index = effectData.parameterIndex(parameter);
        if (index < 0) {
            throw new IllegalArgumentException(noParameter(parameter));
        }
        float[] block = parameters[slot];
        int offset = effectData.parameterOffset(index);
        block[offset] = a;
        if (parameter.size() == 4) {
            block[offset + 1] = b;
            block[offset + 2] = c;
            block[offset + 3] = d;
        }
        log(effect, PARAMETERS);
    }

    @AllowAllocation("diagnostics")
    private static String shares(String what) {
        return "A placement of a shared effect has no " + what + ": its variant is drawn at several placements";
    }

    @AllowAllocation("diagnostics")
    private static String noParameter(EffectParameter parameter) {
        return "The effect has no parameter " + parameter;
    }

    float get(long effect, EffectParameter.Float parameter) {
        int slot = slot(effect);
        if (slot < 0) {
            return Float.NaN;
        }
        int index = data[slot].parameterIndex(parameter);
        return index < 0 ? Float.NaN : parameters[slot][data[slot].parameterOffset(index)];
    }

    void timeScale(long effect, float timeScale) {
        int slot = slot(effect);
        if (slot >= 0) {
            checkOwner();
            scales[slot] = fixed(timeScale);
            log(effect, TIME);
        }
    }

    void pause(long effect, boolean pause) {
        int slot = slot(effect);
        if (slot >= 0) {
            checkOwner();
            paused[slot] = pause;
            log(effect, TIME);
        }
    }

    /**
     * Places playing effect {@code effect} at ({@code x}, {@code y}, {@code z}) in its node's frame (inert once it
     * ended), as {@link Effect#offset} does.
     */
    public void offset(long effect, double x, double y, double z) {
        int slot = slot(effect);
        if (slot >= 0) {
            checkOwner();
            offsets[3 * slot] = x;
            offsets[3 * slot + 1] = y;
            offsets[3 * slot + 2] = z;
            place(slot);
            log(effect, MOVED);
        }
    }

    void moveTo(long effect, double x, double y, double z) {
        int slot = slot(effect);
        if (slot >= 0) {
            checkOwner();
            nodes[slot] = NodeHandle.NONE;
            positions[3 * slot] = x;
            positions[3 * slot + 1] = y;
            positions[3 * slot + 2] = z;
            log(effect, MOVED);
        }
    }

    void inheritVelocity(long effect, float fraction) {
        int slot = slot(effect);
        if (slot >= 0) {
            checkOwner();
            if (kinds[slot] == PLACEMENT && fraction != 0) {
                throw new IllegalStateException(shares("velocity to inherit"));
            }
            inherit[slot] = fraction;
            log(effect, MOVED);
        }
    }

    void scale(long effect, float scale) {
        int slot = slot(effect);
        if (slot >= 0) {
            checkOwner();
            if (kinds[slot] != PLACEMENT) {
                throw new IllegalStateException("Only placements of shared effects draw scaled");
            }
            if (!(scale > 0)) {
                throw new IllegalArgumentException("Not a scale: " + scale);
            }
            drawScales[slot] = scale;
            log(effect, MOVED);
        }
    }

    void seed(long effect, int seed) {
        int slot = slot(effect);
        if (slot >= 0) {
            checkOwner();
            seeds[slot] = seed;
            log(effect, SEED);
        }
    }

    void stop(long effect) {
        int slot = slot(effect);
        if (slot >= 0 && states[slot] == PLAYING) {
            checkOwner();
            stop(slot);
        }
    }

    private void stop(int slot) {
        states[slot] = STOPPING;
        stops[slot] = times[slot];
        ends[slot] = Math.min(ends[slot], ticks(seconds(times[slot]) + data[slot].lingering()));
        log(id(slot), STOPPED);
    }

    void kill(long effect) {
        int slot = slot(effect);
        if (slot >= 0) {
            checkOwner();
            end(slot);
        }
    }

    private void end(int slot) {
        long id = id(slot);
        if (kinds[slot] == PLACEMENT) {
            int variant = slot(links[slot]);
            if (variant >= 0 && --placements[variant] == 0) {
                stop(variant); // its particles live out their lives, undrawn: new placements start a new variant
            }
            int near = slot(nears[slot]);
            if (near >= 0 && states[near] == PLAYING) {
                stop(near);
            }
        }
        states[slot] = FREE;
        data[slot] = null;
        shared[slot] = false;
        free[freeCount++] = slot;
        size--;
        log(id, REMOVED);
    }

    /**
     * Replaces {@code old} by {@code replacement} (a hot reload: the asset edited in a tool, or loaded again): every
     * instance of {@code old} restarts with it from time 0, keeping its id (so its handles), node, offset, position,
     * seed, inherited velocity, time scale and pause, and the values of the parameters both have (same name and kind;
     * the others take the replacement's defaults). Each is logged {@link #REPLACED}. Shared burst instances of
     * {@code old} end instead; later bursts go into instances of the replacement. Returns how many restarted.
     */
    @AllowAllocation("a hot reload: parameter blocks of the new data")
    public int replace(EffectData old, EffectData replacement) {
        checkOwner();
        Objects.requireNonNull(old, "old");
        Objects.requireNonNull(replacement, "replacement");
        int restarted = 0;
        for (int slot = 0; slot < slots; slot++) {
            if (states[slot] == FREE || !data[slot].equals(old)) {
                continue;
            }
            if (shared[slot] || kinds[slot] != OWN) {
                end(slot); // shared simulations start again from the placements played next
                continue;
            }
            float[] block = new float[replacement.parameterFloats()];
            replacement.defaults(block, 0);
            for (EffectParameter parameter : old.parameters()) {
                int index = replacement.parameterIndex(parameter);
                if (index >= 0) {
                    System.arraycopy(parameters[slot], old.parameterOffset(old.parameterIndex(parameter)), block,
                        replacement.parameterOffset(index), parameter.size());
                }
            }
            parameters[slot] = block;
            data[slot] = replacement;
            states[slot] = PLAYING;
            starts[slot] = now;
            times[slot] = 0;
            remainders[slot] = 0;
            held[slot] = false;
            stops[slot] = -1;
            ends[slot] = ticks(replacement.end());
            restarts[slot]++;
            log(id(slot), REPLACED);
            restarted++;
        }
        return restarted;
    }

    private static int fixed(float timeScale) {
        if (!(timeScale >= 0) || timeScale > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Not a time scale: " + timeScale);
        }
        return Math.round(timeScale * ONE);
    }

    /** Scales every effect's time by {@code timeScale}, on top of their own: slow motion. 1 by default. */
    public void timeScale(float timeScale) {
        scale = fixed(timeScale);
    }

    public float timeScale() {
        return (float) scale / ONE;
    }

    /** Pauses every effect, or resumes them: nothing spawns or moves, every view still draws them. */
    public void pause(boolean pause) {
        pausedAll = pause;
    }

    public boolean paused() {
        return pausedAll;
    }

    /**
     * The world's wind, a velocity in metres per second, which {@link Forces#WIND wind} modules pick up; none by
     * default. Any frame; allocates nothing.
     */
    public void wind(float x, float y, float z) {
        wind[0] = x;
        wind[1] = y;
        wind[2] = z;
    }

    /** The world's wind along {@code axis}. */
    public float wind(int axis) {
        return wind[axis];
    }

    /** The ticks advanced so far. */
    public long now() {
        return now;
    }

    /** Advances by {@code seconds} of the world clock, rounded to whole ticks; see {@link #advance(long)}. */
    public void advance(double seconds) {
        if (!(seconds >= 0) || seconds == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time step: " + seconds);
        }
        advance(Math.round(seconds * TICKS_PER_SECOND));
    }

    /**
     * Advances by {@code ticks} of the world clock, after {@code scene.update()}: each effect's time moves by them
     * times its scale (none while paused), effects on nodes take their nodes' positions (an effect whose node was
     * removed stays where it was, detached, and stops), and effects past their end end. Allocates nothing.
     */
    public void advance(long ticks) {
        checkOwner();
        if (ticks < 0) {
            throw new IllegalArgumentException("Not a time step: " + ticks);
        }
        now += ticks;
        colliders.update();
        for (int slot = 0; slot < slots; slot++) {
            if (states[slot] == FREE) {
                continue;
            }
            if (nodes[slot] != NodeHandle.NONE) {
                if (scene.contains(nodes[slot])) {
                    place(slot);
                } else {
                    nodes[slot] = NodeHandle.NONE;
                    log(id(slot), MOVED);
                    if (states[slot] == PLAYING) {
                        stop(slot);
                    }
                }
            }
            if (!pausedAll && !paused[slot] && !held[slot]) {
                long scaled = remainders[slot] + ticks * ((long) scales[slot] * scale >> SCALE_BITS);
                times[slot] += scaled >> SCALE_BITS;
                remainders[slot] = scaled & (ONE - 1);
            }
            if (times[slot] >= ends[slot]) {
                end(slot);
            }
        }
        for (int slot = 0; slot < slots; slot++) {
            int placement = kinds[slot] == NEAR && states[slot] != FREE ? slot(links[slot]) : -1;
            if (placement >= 0) { // where its placement is
                System.arraycopy(positions, 3 * placement, positions, 3 * slot, 3);
                System.arraycopy(rotations, 4 * placement, rotations, 4 * slot, 4);
            }
        }
    }

    // Placements near viewers, for the runtime.

    boolean placementAt(int slot) {
        return kinds[slot] == PLACEMENT;
    }

    boolean variantAt(int slot) {
        return kinds[slot] == VARIANT;
    }

    /** The slot of the variant the placement in {@code slot} draws, or -1. */
    int variantSlotOf(int slot) {
        return slot(links[slot]);
    }

    /** Whether the placement in {@code slot} has a simulation of its own that spawns. */
    boolean nearAt(int slot) {
        int own = slot(nears[slot]);
        return own >= 0 && states[own] == PLAYING;
    }

    /**
     * Gives the placement in {@code slot} a simulation of its own when it comes {@code near} (an effect where it is,
     * seeded as it is), or stops it when it goes far: its particles live out their lives. Called as that changes
     * ({@link #nearAt} says otherwise).
     */
    void near(int slot, boolean near) {
        if (near) {
            int s = claim(data[slot]);
            kinds[s] = NEAR;
            links[s] = id(slot);
            seeds[s] = seeds[slot];
            System.arraycopy(positions, 3 * slot, positions, 3 * s, 3);
            System.arraycopy(rotations, 4 * slot, rotations, 4 * s, 4);
            nears[slot] = id(s);
            log(id(s), ADDED);
        } else {
            stop(slot(nears[slot]));
        }
    }

    /** Takes an attached effect's position and rotation from its node's world transform and its offset. */
    private void place(int slot) {
        long node = nodes[slot];
        if (node == NodeHandle.NONE) {
            return;
        }
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        long base = NodeHandle.index(node) * world.stride();
        double qx = s.get(ValueLayout.JAVA_DOUBLE, base + 24);
        double qy = s.get(ValueLayout.JAVA_DOUBLE, base + 32);
        double qz = s.get(ValueLayout.JAVA_DOUBLE, base + 40);
        double qw = s.get(ValueLayout.JAVA_DOUBLE, base + 48);
        double ox = offsets[3 * slot];
        double oy = offsets[3 * slot + 1];
        double oz = offsets[3 * slot + 2];
        // The offset rotated by the node's rotation: v + 2w (q x v) + 2 q x (q x v).
        double tx = 2 * (qy * oz - qz * oy);
        double ty = 2 * (qz * ox - qx * oz);
        double tz = 2 * (qx * oy - qy * ox);
        positions[3 * slot] = s.get(ValueLayout.JAVA_DOUBLE, base) + ox + qw * tx + qy * tz - qz * ty;
        positions[3 * slot + 1] = s.get(ValueLayout.JAVA_DOUBLE, base + 8) + oy + qw * ty + qz * tx - qx * tz;
        positions[3 * slot + 2] = s.get(ValueLayout.JAVA_DOUBLE, base + 16) + oz + qw * tz + qx * ty - qy * tx;
        rotations[4 * slot] = qx;
        rotations[4 * slot + 1] = qy;
        rotations[4 * slot + 2] = qz;
        rotations[4 * slot + 3] = qw;
    }

    /**
     * Reports every effect that changed after {@code since} (a mark on the scene's clock), in the order of the changes;
     * an effect may be reported more than once. Returns false, reporting nothing, when changes that old are no longer
     * kept: rebuild from {@link #forEach} then.
     */
    public boolean changesSince(long since, Visitor visitor) {
        if (since < logIncompleteThrough) {
            return false;
        }
        for (int i = firstAfter(since); i < logSize; i++) {
            int entry = (logStart + i) % logStamps.length;
            visitor.changed(logEffects[entry], logChanges[entry]);
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

    private void log(long effect, int changes) {
        if (logSize == logStamps.length) {
            logIncompleteThrough = logStamps[logStart];
            logStart = (logStart + 1) % logStamps.length;
            logSize--;
        }
        int entry = (logStart + logSize) % logStamps.length;
        logStamps[entry] = clock.stamp();
        logEffects[entry] = effect;
        logChanges[entry] = changes;
        logSize++;
    }

    /** Throws unless the calling thread may change the scene: any, or its clock's owner. */
    private void checkOwner() {
        Thread owner = clock.owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(notOwner(owner));
        }
    }

    @AllowAllocation("diagnostics")
    private static String notOwner(Thread owner) {
        return "The effects change on " + owner.getName() + " only, not on " + Thread.currentThread().getName();
    }
}
