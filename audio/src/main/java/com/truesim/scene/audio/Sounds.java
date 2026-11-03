package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.random.Hash;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The sounds playing in a world (AU11), shaped on scene effects' store: for each instance its sound, where it plays (a
 * node, or a world position), its seed, parameters, switches, volume, pitch, time scale, pause and input, on a clock of
 * whole ticks; changes logged on the scene's clock so the engine (and replication) follow what plays. It also holds
 * the world's {@link #listeners()} and {@link #reverbZones()}. {@link #ui()} makes the store of the user interface:
 * 2D sounds with no scene, never paused or time-scaled.
 *
 * <pre>{@code
 * Sounds sounds = new Sounds(scene);
 * SoundInstance rain = sounds.play(RAIN);                          // a handle, allocated
 * rain.set(INTENSITY, 0.8f);
 * sounds.fire(GUNSHOT, muzzleNode);                                 // hot path: an id, nothing allocated
 * // each frame, after scene.update():
 * sounds.advance(ticks);                                            // positions, time, pending plans
 * sounds.changesSince(since, (id, changes) -> ...);                 // ADDED, STOPPED, REMOVED, PARAMETERS, ...
 * }</pre>
 *
 * <p>Instances are slots: an id is a slot and its generation ({@code generation << 32 | slot}), so a handle of an
 * instance that ended never touches the next one in its slot: it is inert, {@link SoundInstance#alive()} says so.
 * An instance ends when the engine reports it ended ({@link #ended}), when killed, when stopped with a fade and the
 * fade has passed (without an outro), or at its sound's max duration; one-shots end only by the engine's report, since
 * the store does not know clip lengths. Each instance's voices are planned from its seed and switches once, at the
 * first {@link #advance} or read after it was played (so switches set right after {@code play} count), in the order
 * played; the plan uses the store's history of random picks (no-repeat windows, shuffles). Concurrency applies when an
 * instance is played: at its group's cap one gives way (killed, the engine fades it over 10 ms), or the new one is
 * rejected and its id is never alive.
 *
 * <p>Time is counted in ticks of {@value #TICKS_PER_SECOND} a second; an instance's own time moves by the delta times
 * its scale and the store's (for sounds following time as {@link TimeScaling#PITCH}), held while paused (unless its
 * sound ignores pause). Advancing, reading and setting allocate nothing; {@code play} allocates its handle, and the
 * first play of a sound flattens it once. Used from the scene's thread.
 */
public final class Sounds {

    /** Ticks per second: microseconds. */
    public static final long TICKS_PER_SECOND = 1_000_000;
    /** Seconds a parameter set without a ramp moves over. */
    public static final double DEFAULT_RAMP = 0.02;

    /** The instance started. */
    public static final int ADDED = 1;
    /** The instance was stopped: it fades out or plays its outro, then ends. */
    public static final int STOPPED = 1 << 1;
    /** The instance ended or was killed; its id is stale. Reported alone. */
    public static final int REMOVED = 1 << 2;
    /** A parameter, the volume or the pitch changed. */
    public static final int PARAMETERS = 1 << 3;
    /** Its time scale or pause changed, or the store's. */
    public static final int TIME = 1 << 4;
    /** Where it plays changed: its node or position. */
    public static final int MOVED = 1 << 5;
    /** Its seed changed (before its voices were planned). */
    public static final int SEED = 1 << 6;
    /** A switch value changed. */
    public static final int SWITCHES = 1 << 7;
    /** The input it plays changed. */
    public static final int INPUT = 1 << 8;

    /** Receives changed instances. */
    @FunctionalInterface
    public interface Visitor {
        void changed(long instance, int changes);
    }

    /** Receives playing instances. */
    @FunctionalInterface
    public interface InstanceVisitor {
        void visit(long instance);
    }

    private static final int SCALE_BITS = 16;
    private static final int ONE = 1 << SCALE_BITS;
    private static final byte FREE = 0;
    private static final byte PLAYING = 1;
    private static final byte STOPPING = 2;

    private final SceneGraph scene;
    private final ChangeClock clock;
    private final boolean ui;
    private final Listeners listeners;
    private final ReverbZones reverbZones;
    private final Plans plans = new Plans();
    private final Map<AssetRef<AudioClip>, Sound> clipSounds = new HashMap<>();

    private int size;
    private int slots;
    private int[] free = new int[16];
    private int freeCount;
    private byte[] states = new byte[16];
    private int[] generations = new int[16];
    private Sound[] sounds = new Sound[16];
    private Plans.Flat[] flats = new Plans.Flat[16];
    private Plans.Plan[] planned = new Plans.Plan[16];
    private boolean[] isPlanned = new boolean[16];
    private long[] nodes = new long[16];
    private double[] positions = new double[48];
    private double[] rotations = new double[64];
    private int[] seeds = new int[16];
    private long[] starts = new long[16];
    private long[] times = new long[16];
    private long[] remainders = new long[16];
    private int[] scales = new int[16];
    private boolean[] paused = new boolean[16];
    private long[] stopTimes = new long[16];
    private float[] stopFades = new float[16];
    private float[] volumes = new float[16];
    private float[] volumeRamps = new float[16];
    private float[] pitches = new float[16];
    private float[][] parameters = new float[16][];
    private float[] parameterRamps = new float[16];
    private int[][] switches = new int[16][];
    private long[] inputs = new long[16];
    private SoundField[] fields = new SoundField[16];
    private boolean[] outroSkipped = new boolean[16];
    private int played;
    private long now;
    private int scale = ONE;
    private boolean pausedAll;
    private int dropped;
    private int rejected;

    // Instances played and not yet planned, in the order played.
    private long[] pending = new long[16];
    private int pendingCount;

    // Concurrency: the last start of each group, by group key.
    private final Map<Object, long[]> groupStarts = new HashMap<>();

    // The change log: a ring of (stamp, instance, changes).
    private final long[] logStamps;
    private final long[] logInstances;
    private final int[] logChanges;
    private int logStart;
    private int logSize;
    private long logIncompleteThrough = -1;

    /** The sounds of {@code scene}'s world, logging changes on its clock. */
    public Sounds(SceneGraph scene) {
        this(Objects.requireNonNull(scene, "scene"), scene.clock(), SceneGraph.DEFAULT_LOG_CAPACITY);
    }

    /** The sounds of {@code scene}'s world, keeping the last {@code logCapacity} changes for consumers. */
    public Sounds(SceneGraph scene, int logCapacity) {
        this(Objects.requireNonNull(scene, "scene"), scene.clock(), logCapacity);
    }

    @AllowAllocation("created rarely")
    private Sounds(SceneGraph scene, ChangeClock clock, int logCapacity) {
        if (logCapacity < 1) {
            throw new IllegalArgumentException("The change log needs room: " + logCapacity);
        }
        this.scene = scene;
        this.clock = clock;
        ui = scene == null;
        listeners = new Listeners(scene, clock);
        reverbZones = new ReverbZones(scene, clock);
        logStamps = new long[logCapacity];
        logInstances = new long[logCapacity];
        logChanges = new int[logCapacity];
    }

    /**
     * The store of the user interface: no scene, a clock of its own, 2D sounds only (a clip plays on the UI bus),
     * never paused or time-scaled; it keeps working on a pause menu.
     */
    @AllowAllocation("created rarely")
    public static Sounds ui() {
        return new Sounds(null, new ChangeClock(), 4096);
    }

    /** The scene; {@code null} for the UI store. */
    public SceneGraph scene() {
        return scene;
    }

    /** The clock changes are logged on. */
    public ChangeClock clock() {
        return clock;
    }

    /** Whether this is the UI store. */
    public boolean isUi() {
        return ui;
    }

    /** The world's listeners. */
    public Listeners listeners() {
        return listeners;
    }

    /** The world's reverb zones. */
    public ReverbZones reverbZones() {
        return reverbZones;
    }

    // ---------------------------------------------------------------- playing

    /** Plays {@code sound} where nothing places it: 2D sounds, beds, the UI. */
    @AllowAllocation("a handle per sound played")
    public SoundInstance play(Sound sound) {
        return new SoundInstance(this, fire(sound));
    }

    /** Plays {@code clip} once with the defaults: 2D on the UI bus in {@link #ui()}, else 3D on the SFX bus. */
    @AllowAllocation("a handle per sound played")
    public SoundInstance play(AssetRef<AudioClip> clip) {
        return play(soundOf(clip));
    }

    /** Plays {@code sound} attached to {@code node}: it follows the node. */
    @AllowAllocation("a handle per sound played")
    public SoundInstance play(Sound sound, long node) {
        return new SoundInstance(this, fire(sound, node));
    }

    /** Plays {@code clip} once at {@code node}, with the defaults of {@link Sound#of}. */
    @AllowAllocation("a handle per sound played")
    public SoundInstance play(AssetRef<AudioClip> clip, long node) {
        return play(soundOf(clip), node);
    }

    /** Plays {@code sound} at the world position ({@code x}, {@code y}, {@code z}). */
    @AllowAllocation("a handle per sound played")
    public SoundInstance play(Sound sound, double x, double y, double z) {
        return new SoundInstance(this, fire(sound, x, y, z));
    }

    /** The default sound of {@code clip} for this store, made once per clip. */
    @AllowAllocation("once per clip")
    private Sound soundOf(AssetRef<AudioClip> clip) {
        return clipSounds.computeIfAbsent(clip, c -> ui
            ? Sound.builder().root(Play.clip(c)).bus(Bus.UI).spatial(Spatial.TWO_D).build() : Sound.of(c));
    }

    /** {@link #play(Sound)} without a handle: returns the instance's id (0 if rejected by its concurrency). */
    public long fire(Sound sound) {
        int slot = claim(sound);
        return slot < 0 ? 0 : added(slot);
    }

    /** {@link #play(Sound, long)} without a handle: returns the instance's id, allocating nothing once grown. */
    public long fire(Sound sound, long node) {
        placed();
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        Placement.place(scene, node, positions, rotations, slots); // where it would be, for concurrency
        int slot = claim(sound, positions[3 * slots], positions[3 * slots + 1], positions[3 * slots + 2]);
        if (slot < 0) {
            return 0;
        }
        nodes[slot] = node;
        Placement.place(scene, node, positions, rotations, slot);
        return added(slot);
    }

    /** {@link #play(Sound, double, double, double)} without a handle: returns the instance's id. */
    public long fire(Sound sound, double x, double y, double z) {
        placed();
        int slot = claim(sound, x, y, z);
        if (slot < 0) {
            return 0;
        }
        positions[3 * slot] = x;
        positions[3 * slot + 1] = y;
        positions[3 * slot + 2] = z;
        return added(slot);
    }

    /**
     * Plays {@code sound} (a loop) from every place of {@code field} (§2.18): one instance, a few voices at the nearest
     * places and a bed for the rest; the instance is at the field's centre for its concurrency.
     */
    @AllowAllocation("a handle per sound played")
    public SoundInstance field(Sound sound, SoundField field) {
        return new SoundInstance(this, fireField(sound, field));
    }

    /** {@link #field(Sound, SoundField)} without a handle: returns the instance's id (0 if rejected). */
    public long fireField(Sound sound, SoundField field) {
        Objects.requireNonNull(field, "field");
        long id = fire(sound, field.centre(0), field.centre(1), field.centre(2));
        if (id != 0) {
            fields[held(id)] = field;
        }
        return id;
    }

    private void placed() {
        if (ui) {
            throw new IllegalStateException("The UI store plays 2D sounds only: no nodes or positions");
        }
    }

    private int claim(Sound sound) {
        return claim(sound, Double.NaN, Double.NaN, Double.NaN);
    }

    private long added(int slot) {
        long id = id(slot);
        if (pendingCount == pending.length) {
            growPending();
        }
        pending[pendingCount++] = id;
        log(id, ADDED);
        return id;
    }

    @AllowAllocation("growing with the sounds played in a frame")
    private void growPending() {
        pending = Arrays.copyOf(pending, 2 * pending.length);
    }

    /**
     * A slot for an instance of {@code sound} starting now at ({@code x}, {@code y}, {@code z}) (NaN where not
     * placed), after its concurrency group made room; -1 if rejected.
     */
    private int claim(Sound sound, double x, double y, double z) {
        checkOwner();
        Objects.requireNonNull(sound, "sound");
        if (ui && sound.spatial() != Spatial.TWO_D) {
            throw new IllegalArgumentException("The UI store plays 2D sounds only: " + sound);
        }
        Plans.Flat flat = plans.flat(sound);
        if (!makeRoom(sound, x, y, z)) {
            rejected++;
            return -1;
        }
        int slot;
        if (freeCount > 0) {
            slot = free[--freeCount];
        } else {
            if (slots + 1 >= states.length) { // one spare slot past the last, for where a node is
                grow(2 * states.length);
            }
            slot = slots++;
        }
        size++;
        generations[slot]++;
        states[slot] = PLAYING;
        sounds[slot] = sound;
        flats[slot] = flat;
        if (planned[slot] == null) {
            planned[slot] = newPlan();
        }
        isPlanned[slot] = false;
        nodes[slot] = NodeHandle.NONE;
        Arrays.fill(positions, 3 * slot, 3 * slot + 3, 0);
        Arrays.fill(rotations, 4 * slot, 4 * slot + 4, 0);
        rotations[4 * slot + 3] = 1;
        seeds[slot] = Hash.pcg(played++);
        starts[slot] = now;
        times[slot] = 0;
        remainders[slot] = 0;
        scales[slot] = ONE;
        paused[slot] = false;
        stopTimes[slot] = -1;
        stopFades[slot] = 0;
        volumes[slot] = 0;
        volumeRamps[slot] = 0;
        pitches[slot] = 1;
        parameterRamps[slot] = 0;
        inputs[slot] = 0;
        fields[slot] = null;
        int count = sound.parameters().size();
        if (parameters[slot] == null || parameters[slot].length != count) {
            parameters[slot] = new float[count];
        }
        for (int i = 0; i < count; i++) {
            parameters[slot][i] = sound.parameters().get(i).fallback();
        }
        int switchCount = sound.switches().size();
        if (switches[slot] == null || switches[slot].length != switchCount) {
            switches[slot] = new int[switchCount];
        }
        for (int i = 0; i < switchCount; i++) {
            switches[slot][i] = sound.switches().get(i).fallback().ordinal();
        }
        return slot;
    }

    @AllowAllocation("a plan per slot, kept")
    private static Plans.Plan newPlan() {
        return new Plans.Plan();
    }

    /**
     * Makes room in {@code sound}'s concurrency group for an instance starting at ({@code x}, {@code y}, {@code z}):
     * false if the group rejects it (full with {@link Concurrency.Steal#REJECT_NEW}, or within its retrigger time).
     */
    private boolean makeRoom(Sound sound, double x, double y, double z) {
        Concurrency c = sound.concurrency();
        if (c.max() == Integer.MAX_VALUE && c.retrigger() == 0) {
            return true;
        }
        Object key = c.group() != null ? c.group() : sound;
        long[] last = groupStart(key);
        if (last[0] >= 0 && now - last[0] < Math.round(c.retrigger() * TICKS_PER_SECOND)) {
            return false;
        }
        int count = 0;
        int victim = -1;
        double victimScore = 0;
        for (int slot = 0; slot < slots; slot++) {
            if (states[slot] != PLAYING || !inGroup(slot, key)) {
                continue;
            }
            count++;
            double score = switch (c.steal()) { // the highest score gives way
                case OLDEST, REJECT_NEW -> now - starts[slot];
                case QUIETEST -> -sounds[slot].attenuation().gain((float) Math.sqrt(nearestListener(slot)));
                case FARTHEST -> nearestListener(slot);
                case LOWEST_PRIORITY -> (4 - sounds[slot].priority().ordinal()) * 1e15 + now - starts[slot];
            };
            if (victim < 0 || score > victimScore) {
                victim = slot;
                victimScore = score;
            }
        }
        if (count >= c.max()) {
            if (c.steal() == Concurrency.Steal.REJECT_NEW) {
                return false;
            }
            end(victim);
        }
        last[0] = now;
        return true;
    }

    private boolean inGroup(int slot, Object key) {
        Concurrency c = sounds[slot].concurrency();
        return c.group() != null ? c.group().equals(key) : sounds[slot] == key;
    }

    @AllowAllocation("once per concurrency group")
    private long[] groupStart(Object key) {
        return groupStarts.computeIfAbsent(key, k -> new long[] {-1});
    }

    /** The squared distance from the instance in {@code slot} to its nearest device listener; 0 without one. */
    private double nearestListener(int slot) {
        double best = Double.POSITIVE_INFINITY;
        double[] lp = listeners.positions();
        for (int l = 0; l < listeners.slots(); l++) {
            if (listeners.deviceAt(l)) {
                best = Math.min(best, Placement.distanceSquared(positions, slot, lp, l));
            }
        }
        return best == Double.POSITIVE_INFINITY ? 0 : best;
    }

    @AllowAllocation("growing with the sounds playing")
    private void grow(int length) {
        free = Arrays.copyOf(free, length);
        states = Arrays.copyOf(states, length);
        generations = Arrays.copyOf(generations, length);
        sounds = Arrays.copyOf(sounds, length);
        flats = Arrays.copyOf(flats, length);
        planned = Arrays.copyOf(planned, length);
        isPlanned = Arrays.copyOf(isPlanned, length);
        nodes = Arrays.copyOf(nodes, length);
        positions = Arrays.copyOf(positions, 3 * length);
        rotations = Arrays.copyOf(rotations, 4 * length);
        seeds = Arrays.copyOf(seeds, length);
        starts = Arrays.copyOf(starts, length);
        times = Arrays.copyOf(times, length);
        remainders = Arrays.copyOf(remainders, length);
        scales = Arrays.copyOf(scales, length);
        paused = Arrays.copyOf(paused, length);
        stopTimes = Arrays.copyOf(stopTimes, length);
        stopFades = Arrays.copyOf(stopFades, length);
        volumes = Arrays.copyOf(volumes, length);
        volumeRamps = Arrays.copyOf(volumeRamps, length);
        pitches = Arrays.copyOf(pitches, length);
        parameters = Arrays.copyOf(parameters, length);
        parameterRamps = Arrays.copyOf(parameterRamps, length);
        switches = Arrays.copyOf(switches, length);
        inputs = Arrays.copyOf(inputs, length);
        fields = Arrays.copyOf(fields, length);
        outroSkipped = Arrays.copyOf(outroSkipped, length);
    }

    private long id(int slot) {
        return (long) generations[slot] << 32 | slot;
    }

    /** The slot of a playing instance's id, or -1 for an ended (or never played) one. */
    int slot(long instance) {
        int slot = (int) instance;
        return slot >= 0 && slot < slots && states[slot] != FREE && generations[slot] == (int) (instance >>> 32)
            ? slot : -1;
    }

    /** Whether {@code instance} plays (stopping ones too): false once it ended or was killed. */
    public boolean alive(long instance) {
        return slot(instance) >= 0;
    }

    /** How many instances play. */
    public int size() {
        return size;
    }

    /** How many plays concurrency rejected. */
    public int rejected() {
        return rejected;
    }

    /** How many instances were dropped because their clips loaded too late ({@link #drop}). */
    public int dropped() {
        return dropped;
    }

    /** Visits every playing instance, in slot order. */
    public void forEach(InstanceVisitor visitor) {
        for (int slot = 0; slot < slots; slot++) {
            if (states[slot] != FREE) {
                visitor.visit(id(slot));
            }
        }
    }

    // ---------------------------------------------------------------- reading

    private int held(long instance) {
        int slot = slot(instance);
        if (slot < 0) {
            throw new IllegalArgumentException(notPlaying(instance));
        }
        return slot;
    }

    @AllowAllocation("diagnostics")
    private static String notPlaying(long instance) {
        return "No sound plays as " + Long.toHexString(instance);
    }

    public Sound sound(long instance) {
        return sounds[held(instance)];
    }

    /** The node it follows, or {@link NodeHandle#NONE}. */
    public long node(long instance) {
        return nodes[held(instance)];
    }

    /** Whether it is placed (on a node or at a position) rather than 2D. */
    public boolean placed(long instance) {
        int slot = held(instance);
        return !ui && sounds[slot].spatial() != Spatial.TWO_D;
    }

    /** Component {@code axis} of where it is, as of the last advance. */
    public double position(long instance, int axis) {
        return positions[3 * held(instance) + Objects.checkIndex(axis, 3)];
    }

    /** Component {@code axis} (x, y, z, w) of its rotation, as of the last advance. */
    public double rotation(long instance, int axis) {
        return rotations[4 * held(instance) + Objects.checkIndex(axis, 4)];
    }

    public int seed(long instance) {
        return seeds[held(instance)];
    }

    /** When it started, in ticks of this store's clock. */
    public long start(long instance) {
        return starts[held(instance)];
    }

    /** Its own time since it started, in ticks: scaled, and still while paused. */
    public long time(long instance) {
        return times[held(instance)];
    }

    /** Whether it was stopped. */
    public boolean stopped(long instance) {
        return states[held(instance)] == STOPPING;
    }

    /** The fade it was stopped with, seconds (its sound's release fade for {@code stop()}); 0 while playing. */
    public float stopFade(long instance) {
        return stopFades[held(instance)];
    }

    /** Its volume, decibels. */
    public float volume(long instance) {
        return volumes[held(instance)];
    }

    /** Seconds its last volume change moves over. */
    public float volumeRamp(long instance) {
        return volumeRamps[held(instance)];
    }

    /** Its pitch, a playback-rate ratio. */
    public float pitch(long instance) {
        return pitches[held(instance)];
    }

    /** Parameter {@code index} of its sound's {@link Sound#parameters()}. */
    public float parameter(long instance, int index) {
        return parameters[held(instance)][index];
    }

    /** Seconds its last parameter change moves over. */
    public float parameterRamp(long instance) {
        return parameterRamps[held(instance)];
    }

    /** Switch {@code index} of its sound's {@link Sound#switches()}, as an ordinal. */
    public int switchValue(long instance, int index) {
        return switches[held(instance)][index];
    }

    /** The field it plays from (§2.18); {@code null} for a sound at one place. */
    public SoundField field(long instance) {
        return fields[held(instance)];
    }

    /** The rendering listener whose mix it plays as input; 0 for none. */
    public long input(long instance) {
        return inputs[held(instance)];
    }

    /** Its own time scale (the store's apart). */
    public float timeScaleOf(long instance) {
        return (float) scales[held(instance)] / ONE;
    }

    /** Whether it is paused (the store's pause apart). */
    public boolean paused(long instance) {
        return paused[held(instance)];
    }

    /** Whether it holds still now: paused, or its store is and its sound does not ignore pause. */
    public boolean holding(long instance) {
        int slot = held(instance);
        return paused[slot] || pausedAll && !sounds[slot].ignoresPause();
    }

    /** The rate its time moves at now: its scale times the store's (for sounds following it), 0 while holding. */
    public float rate(long instance) {
        int slot = held(instance);
        return holding(instance) ? 0 : (float) scaleAt(slot) / ONE;
    }

    private long scaleAt(int slot) {
        return sounds[slot].timeScaling() == TimeScaling.PITCH ? (long) scales[slot] * scale >> SCALE_BITS
            : scales[slot];
    }

    // ---------------------------------------------------------------- voices

    /** How many voices its sound planned. */
    public int voices(long instance) {
        return plan(held(instance)).count;
    }

    private Plans.Plan plan(int slot) {
        if (!isPlanned[slot]) {
            planPending();
        }
        return planned[slot];
    }

    /** Plans the instances played since the last plan, in the order played. */
    private void planPending() {
        for (int i = 0; i < pendingCount; i++) {
            int slot = slot(pending[i]);
            if (slot >= 0) { // gone already if killed (its slot maybe reused, by an id further on)
                plans.plan(flats[slot], seeds[slot], switches[slot], planned[slot]);
                isPlanned[slot] = true;
            }
        }
        pendingCount = 0;
    }

    private Dialogue dialogue;

    /** The world's dialogue: lines said by speakers under rules (§2.15); made on first use. */
    @AllowAllocation("made once")
    public Dialogue dialogue() {
        if (dialogue == null) {
            dialogue = new Dialogue(this);
        }
        return dialogue;
    }

    /** The clip voice {@code v} plays; {@code null} for an input, a patch or a scatter. */
    public AssetRef<AudioClip> voiceClip(long instance, int v) {
        int slot = held(instance);
        int clip = plan(slot).clip[v];
        return clip < 0 ? null : sounds[slot].clips().get(clip);
    }

    /** What voice {@code v} plays: the node it comes from ({@link Play}, {@link Patch} or {@link Scatter}). */
    public SoundNode voiceNode(long instance, int v) {
        return plan(held(instance)).node[v];
    }

    /** Seconds voice {@code v} starts after the instance (or after the voice it follows ends). */
    public float voiceDelay(long instance, int v) {
        return plan(held(instance)).delay[v];
    }

    /** The voice {@code v} starts after (in a sequence, or a loop after its intro); -1 for none. */
    public int voiceFollows(long instance, int v) {
        return plan(held(instance)).follows[v];
    }

    /** Whether voice {@code v} loops until the instance stops. */
    public boolean voiceLoops(long instance, int v) {
        return plan(held(instance)).looped[v];
    }

    /** Whether voice {@code v} is an outro, playing when the instance stops. */
    public boolean voiceOutro(long instance, int v) {
        return plan(held(instance)).role[v] == Plans.OUTRO;
    }

    /** How a loop voice of a {@link Segments} ends when the instance stops; {@code null} for other voices. */
    public Release voiceRelease(long instance, int v) {
        return plan(held(instance)).release[v];
    }

    /** Voice {@code v}'s gain now: drawn, times its curves and blends at the parameters' values. */
    public float voiceGain(long instance, int v) {
        int slot = held(instance);
        return Plans.gain(plan(slot), v, sounds[slot], parameters[slot]);
    }

    /** Voice {@code v}'s playback rate now: drawn, times its pitch curves (pitch and time scale apart). */
    public float voiceRate(long instance, int v) {
        int slot = held(instance);
        return Plans.rate(plan(slot), v, sounds[slot], parameters[slot]);
    }

    // ---------------------------------------------------------------- changes through handles: inert once ended

    /** Sets {@code parameter} (clamped to its range), moving over {@code seconds}. */
    public void set(long instance, SoundParameter parameter, float value, double seconds) {
        int slot = slot(instance);
        if (slot < 0) {
            return;
        }
        checkOwner();
        int index = sounds[slot].parameterIndex(parameter);
        if (index < 0) {
            throw new IllegalArgumentException(noParameter(parameter));
        }
        parameters[slot][index] = parameter.clamp(value);
        parameterRamps[slot] = (float) seconds;
        log(instance, PARAMETERS);
    }

    /**
     * Fires trigger {@code parameter} ({@link SoundParameter#trigger}): its count goes up by one (wrapping), and a
     * patch reading it fires once at the sample the change is heard.
     */
    public void trigger(long instance, SoundParameter parameter) {
        float now = get(instance, parameter);
        if (now == now) {
            set(instance, parameter, now + 1 >= SoundParameter.TRIGGER_WRAP ? 0 : now + 1, 0);
        }
    }

    @AllowAllocation("diagnostics")
    private static String noParameter(Object what) {
        return "The sound reads no " + what;
    }

    /** Parameter {@code parameter}'s value; NaN once ended or if its sound reads no such parameter. */
    public float get(long instance, SoundParameter parameter) {
        int slot = slot(instance);
        int index = slot < 0 ? -1 : sounds[slot].parameterIndex(parameter);
        return index < 0 ? Float.NaN : parameters[slot][index];
    }

    /** Sets switch {@code group} to {@code value}: counted when its voices are planned (the frame it was played). */
    public <E extends Enum<E>> void set(long instance, SwitchGroup<E> group, E value) {
        int slot = slot(instance);
        if (slot < 0) {
            return;
        }
        checkOwner();
        int index = sounds[slot].switchIndex(group);
        if (index < 0) {
            throw new IllegalArgumentException(noParameter(group));
        }
        switches[slot][index] = value.ordinal();
        log(instance, SWITCHES);
    }

    /** Sets its volume to {@code db} decibels over {@code seconds}. */
    public void volume(long instance, double db, double seconds) {
        int slot = slot(instance);
        if (slot >= 0) {
            checkOwner();
            volumes[slot] = (float) db;
            volumeRamps[slot] = (float) seconds;
            log(instance, PARAMETERS);
        }
    }

    /** Sets its pitch, a playback-rate ratio (1 unchanged). */
    public void pitch(long instance, float ratio) {
        int slot = slot(instance);
        if (slot >= 0) {
            checkOwner();
            if (!(ratio > 0) || Float.isInfinite(ratio)) {
                throw new IllegalArgumentException("Not a pitch ratio: " + ratio);
            }
            pitches[slot] = ratio;
            log(instance, PARAMETERS);
        }
    }

    /** Pauses or resumes it. */
    public void pause(long instance, boolean pause) {
        int slot = slot(instance);
        if (slot >= 0) {
            checkOwner();
            paused[slot] = pause;
            log(instance, TIME);
        }
    }

    /** Scales its time by {@code timeScale}, on top of the store's (for sounds following time). */
    public void timeScale(long instance, float timeScale) {
        int slot = slot(instance);
        if (slot >= 0) {
            checkOwner();
            scales[slot] = fixed(timeScale);
            log(instance, TIME);
        }
    }

    /** Sets its seed: its random choices, if its voices are not planned yet. */
    public void seed(long instance, int seed) {
        int slot = slot(instance);
        if (slot >= 0) {
            checkOwner();
            seeds[slot] = seed;
            log(instance, SEED);
        }
    }

    /** Plays rendering listener {@code listener}'s mix as its input (0 for none). */
    public void input(long instance, long listener) {
        int slot = slot(instance);
        if (slot >= 0) {
            checkOwner();
            if (listener != 0 && !(listeners.alive(listener) && listeners.rendering(listener))) {
                throw new IllegalArgumentException("Not a rendering listener: " + Long.toHexString(listener));
            }
            inputs[slot] = listener;
            log(instance, INPUT);
        }
    }

    /** Moves it to the world position ({@code x}, {@code y}, {@code z}), detaching it from its node. */
    public void moveTo(long instance, double x, double y, double z) {
        int slot = slot(instance);
        if (slot >= 0) {
            checkOwner();
            nodes[slot] = NodeHandle.NONE;
            positions[3 * slot] = x;
            positions[3 * slot + 1] = y;
            positions[3 * slot + 2] = z;
            log(instance, MOVED);
        }
    }

    /** Stops it: the outro if it has one, else its sound's release fade. */
    public void stop(long instance) {
        int slot = slot(instance);
        if (slot >= 0) {
            stop(slot, sounds[slot].releaseFade(), false);
        }
    }

    /** Stops it fading out over {@code seconds}, skipping any outro. */
    public void stop(long instance, double seconds) {
        int slot = slot(instance);
        if (slot >= 0) {
            if (!(seconds >= 0) || Double.isInfinite(seconds)) {
                throw new IllegalArgumentException("Not a fade: " + seconds);
            }
            stop(slot, seconds, true);
        }
    }

    /** Stops the instance in {@code slot} (again, with a new fade, if stopping already). */
    private void stop(int slot, double fade, boolean skipOutro) {
        checkOwner();
        outroSkipped[slot] |= skipOutro;
        states[slot] = STOPPING;
        stopTimes[slot] = times[slot];
        stopFades[slot] = (float) fade;
        log(id(slot), STOPPED);
    }

    /** Ends it now (the engine fades it over 10 ms). */
    public void kill(long instance) {
        int slot = slot(instance);
        if (slot >= 0) {
            checkOwner();
            end(slot);
        }
    }

    /** Ends {@code instance} because the engine reports it ended (its last voice finished). */
    public void ended(long instance) {
        kill(instance);
    }

    /** Ends {@code instance} because its clips loaded later than its sound's max start delay, counting it. */
    public void drop(long instance) {
        if (alive(instance)) {
            dropped++;
            kill(instance);
        }
    }

    private void end(int slot) {
        long id = id(slot);
        states[slot] = FREE;
        sounds[slot] = null;
        flats[slot] = null;
        outroSkipped[slot] = false;
        free[freeCount++] = slot;
        size--;
        log(id, REMOVED);
    }

    private static int fixed(float timeScale) {
        if (!(timeScale >= 0) || timeScale > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Not a time scale: " + timeScale);
        }
        return Math.round(timeScale * ONE);
    }

    // ---------------------------------------------------------------- the store

    /** Scales the time of its sounds that follow time ({@link TimeScaling#PITCH}): slow motion. 1 by default. */
    public void timeScale(float timeScale) {
        notUi("time-scaled");
        int s = fixed(timeScale);
        if (s != scale) {
            scale = s;
            logAll(TIME);
        }
    }

    public float timeScale() {
        return (float) scale / ONE;
    }

    /** Pauses its sounds (those whose sounds ignore pause go on), or resumes them; they hold where they are. */
    public void pause(boolean pause) {
        notUi("paused");
        if (pause != pausedAll) {
            pausedAll = pause;
            logAll(TIME);
        }
    }

    public boolean paused() {
        return pausedAll;
    }

    private void notUi(String what) {
        if (ui) {
            throw new IllegalStateException("The UI store is never " + what);
        }
    }

    private void logAll(int changes) {
        checkOwner();
        for (int slot = 0; slot < slots; slot++) {
            if (states[slot] != FREE) {
                log(id(slot), changes);
            }
        }
    }

    /** The ticks advanced so far. */
    public long now() {
        return now;
    }

    /** Advances by {@code seconds}, rounded to whole ticks; see {@link #advance(long)}. */
    public void advance(double seconds) {
        if (!(seconds >= 0) || seconds == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time step: " + seconds);
        }
        advance(Math.round(seconds * TICKS_PER_SECOND));
    }

    /**
     * Advances by {@code ticks} after {@code scene.update()}: plans the voices of instances played since, moves
     * instances on nodes with them (one whose node was removed stays where it was and stops), moves each instance's
     * time by the ticks times its rate, ends instances past their stop fade or max duration, and updates listeners,
     * reverb zones and the listener graph. Allocates nothing.
     */
    public void advance(long ticks) {
        checkOwner();
        if (ticks < 0) {
            throw new IllegalArgumentException("Not a time step: " + ticks);
        }
        now += ticks;
        planPending();
        for (int slot = 0; slot < slots; slot++) {
            if (states[slot] == FREE) {
                continue;
            }
            if (nodes[slot] != NodeHandle.NONE) {
                if (scene.contains(nodes[slot])) {
                    Placement.place(scene, nodes[slot], positions, rotations, slot);
                } else {
                    nodes[slot] = NodeHandle.NONE;
                    log(id(slot), MOVED);
                    if (states[slot] == PLAYING) {
                        stop(slot, sounds[slot].releaseFade(), false);
                    }
                }
            }
            if (!paused[slot] && !(pausedAll && !sounds[slot].ignoresPause())) {
                long scaled = remainders[slot] + ticks * scaleAt(slot);
                times[slot] += scaled >> SCALE_BITS;
                remainders[slot] = scaled & (ONE - 1);
            }
            Sound sound = sounds[slot];
            boolean faded = states[slot] == STOPPING && (outroSkipped[slot] || !hasOutro(slot))
                && times[slot] - stopTimes[slot] >= Math.round(stopFades[slot] * TICKS_PER_SECOND);
            if (faded || times[slot] >= ticks(sound.maxDuration())) {
                end(slot);
            }
        }
        if (!ui) {
            listeners.update();
            reverbZones.update();
            graph();
        }
        if (dialogue != null) {
            dialogue.update();
        }
    }

    private boolean hasOutro(int slot) {
        Plans.Plan plan = planned[slot];
        for (int v = 0; v < plan.count; v++) {
            if (plan.role[v] == Plans.OUTRO) {
                return true;
            }
        }
        return false;
    }

    private static long ticks(double seconds) {
        return seconds >= Long.MAX_VALUE / (double) TICKS_PER_SECOND ? Long.MAX_VALUE
            : Math.round(seconds * TICKS_PER_SECOND);
    }

    /** The listener graph: rendering listener L hears M when a sound playing M's mix is within L's reach. */
    private void graph() {
        listeners.clearGraph();
        double[] lp = listeners.positions();
        for (int slot = 0; slot < slots; slot++) {
            int from = states[slot] == FREE || inputs[slot] == 0 ? -1 : listeners.slot(inputs[slot]);
            if (from < 0) {
                continue;
            }
            for (int to = 0; to < listeners.slots(); to++) {
                if (to != from && listeners.renderingAt(to)) {
                    ListenerMix mix = listeners.mixAt(to);
                    double reach = mix.maxDistance();
                    if (mix.hears(sounds[slot].bus())
                        && Placement.distanceSquared(positions, slot, lp, to) <= reach * reach) {
                        listeners.edge(from, to);
                    }
                }
            }
        }
        listeners.solveGraph();
    }

    // ---------------------------------------------------------------- the change log

    /**
     * Reports every instance that changed after {@code since} (a mark on the store's clock), in the order of the
     * changes; an instance may be reported more than once. Returns false, reporting nothing, when changes that old are
     * no longer kept: rebuild from {@link #forEach} then.
     */
    public boolean changesSince(long since, Visitor visitor) {
        if (since < logIncompleteThrough) {
            return false;
        }
        for (int i = firstAfter(since); i < logSize; i++) {
            int entry = (logStart + i) % logStamps.length;
            visitor.changed(logInstances[entry], logChanges[entry]);
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

    private void log(long instance, int changes) {
        if (logSize == logStamps.length) {
            logIncompleteThrough = logStamps[logStart];
            logStart = (logStart + 1) % logStamps.length;
            logSize--;
        }
        int entry = (logStart + logSize) % logStamps.length;
        logStamps[entry] = clock.stamp();
        logInstances[entry] = instance;
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
        return "The sounds change on " + owner.getName() + " only, not on " + Thread.currentThread().getName();
    }
}
