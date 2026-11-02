package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Emitter logic on the CPU (E2): each frame it advances the {@link Effects} and computes, per effect and emitter, the
 * particles born in the frame, as {@link SpawnRecords}: rates with their fractions carried in fixed point, bursts at
 * their times in each loop (across loop ends too), particles per metre moved, prewarm, and birth times within the
 * frame. It decides each effect's significance against its {@link EffectType}: distance culling and levels of detail
 * with hysteresis, instance caps, what culled effects do, and spawn scales by {@link Quality}. Deterministic: counts
 * follow from whole ticks and integers, so the same ticks give the same spawns however frames split them, on any
 * thread count. Allocates nothing per frame.
 *
 * <pre>{@code
 * EffectRuntime runtime = new EffectRuntime(effects, jobs);
 * runtime.quality(Quality.HIGH);
 * // each frame, after scene.update():
 * runtime.clearViewers();
 * runtime.addViewer(camera.x, camera.y, camera.z);
 * runtime.update(seconds);                  // advances the effects, then spawns
 * SpawnRecords spawns = runtime.spawns();   // to the GPU's spawn ring, and to CpuSimulation
 * }</pre>
 *
 * <p>Timing: an emitter's own time is its effect's time minus its delay plus its prewarm; it runs loops of its
 * duration, its loop delay apart, until its loop count or its effect's stop. A rate spawns {@code floor(rate x active
 * time)} particles by any time, where active time counts the loops' durations only: the fraction carries over loop ends
 * and frames, in 32.32 fixed point per tick. A burst fires when its time in a loop is passed. Prewarmed emitters spawn
 * on their first update what they would have spawned, back-dated (flagged {@link SpawnRecords#PREWARM}); particles too
 * old to be alive only use up their spawn indices. Per-metre spawns count the distance the effect moved in the frame,
 * while its emitter runs. A record spawns at most its emitter's capacity: the youngest. Shared burst instances spawn
 * each burst call's emitters' bursts after their delays.
 */
public final class EffectRuntime {

    /** Particles times 2^32, per second of ticks: a rate's fixed point unit. */
    private static final long UNIT = (1L << 32) * Effects.TICKS_PER_SECOND;
    private static final double SECONDS = 1.0 / Effects.TICKS_PER_SECOND;
    /** The largest rate in fixed point, so that products stay in range. */
    private static final long MAX_RATE = 1L << 60;
    /** Effects per job. */
    private static final int GRAIN = 16;

    private final Effects effects;
    private final JobSystem jobs;
    private Quality quality = Quality.HIGH;
    private double[] viewers = new double[12];
    private int viewerCount;
    private Instance[] instances = new Instance[0];
    private final SpawnRecords records = new SpawnRecords(256);
    // Burst calls by slot: the first call of each slot, and the next call of each call's slot.
    private int[] callHeads = new int[16];
    private int[] callNext = new int[16];
    /** Burst calls of this serial on came after the last update. */
    private int seenSerial;
    // Significance: the types seen this frame, and the effects in the running for their caps.
    private EffectType[] types = new EffectType[4];
    private int typeCount;
    private int[] order = new int[16];
    private int candidates;
    private final JobSystem.RangeTask spawnTask = (from, to, worker) -> {
        for (int slot = from; slot < to; slot++) {
            spawn(slot);
        }
    };

    /** A runtime of {@code effects}, spawning on the updating thread. */
    public EffectRuntime(Effects effects) {
        this(effects, null);
    }

    /** A runtime of {@code effects}, spawning on {@code jobs} (null: on the updating thread). */
    @AllowAllocation("set up once")
    public EffectRuntime(Effects effects, JobSystem jobs) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.jobs = jobs;
    }

    Effects effects() {
        return effects;
    }

    /** The quality effects spawn at: effect types scale spawns by it, emitters may need more. HIGH by default. */
    public void quality(Quality quality) {
        this.quality = Objects.requireNonNull(quality, "quality");
    }

    public Quality quality() {
        return quality;
    }

    /** Forgets the viewers: with none, every effect counts as at distance 0. */
    public void clearViewers() {
        viewerCount = 0;
    }

    /** A viewer (a camera, a headset's eyes' center) at ({@code x}, {@code y}, {@code z}), for distances. */
    public void addViewer(double x, double y, double z) {
        if (3 * viewerCount == viewers.length) {
            growViewers();
        }
        viewers[3 * viewerCount] = x;
        viewers[3 * viewerCount + 1] = y;
        viewers[3 * viewerCount + 2] = z;
        viewerCount++;
    }

    @AllowAllocation("room for more viewers")
    private void growViewers() {
        viewers = Arrays.copyOf(viewers, 2 * viewers.length);
    }

    public int viewerCount() {
        return viewerCount;
    }

    /** The spawns of the last update, valid until the next. */
    public SpawnRecords spawns() {
        return records;
    }

    /** Advances by {@code seconds} of the world clock, rounded to whole ticks; see {@link #update(long)}. */
    public void update(double seconds) {
        if (!(seconds >= 0) || seconds == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time step: " + seconds);
        }
        update(Math.round(seconds * Effects.TICKS_PER_SECOND));
    }

    /**
     * Advances the effects by {@code ticks} ({@link Effects#advance(long)}), decides their significance and computes
     * the frame's spawns, after {@code scene.update()}. Allocates nothing once its arrays have grown to the effects
     * playing.
     */
    public void update(long ticks) {
        dropFinishedCalls();
        effects.advance(ticks);
        int slots = effects.slots();
        if (instances.length < slots) {
            grow(slots);
        }
        for (int slot = 0; slot < slots; slot++) {
            sync(slot);
        }
        linkCalls(slots);
        significance(slots);
        if (jobs != null && slots > GRAIN) {
            jobs.parallelFor(slots, GRAIN, spawnTask);
        } else {
            spawnTask.run(0, slots, 0);
        }
        records.clear();
        for (int slot = 0; slot < slots; slot++) {
            SpawnRecords own = instances[slot].records;
            for (int i = 0; i < own.size(); i++) {
                records.add(own, i);
            }
        }
        seenSerial = effects.nextBurstSerial();
    }

    @AllowAllocation("growing with the effects playing")
    private void grow(int slots) {
        int old = instances.length;
        instances = Arrays.copyOf(instances, Math.max(slots, 2 * old));
        for (int slot = old; slot < instances.length; slot++) {
            instances[slot] = new Instance();
        }
        order = Arrays.copyOf(order, instances.length);
        callHeads = Arrays.copyOf(callHeads, instances.length);
    }

    /** Follows the effect in {@code slot}: a new one starts from scratch. */
    private void sync(int slot) {
        Instance in = instances[slot];
        long id = effects.effectAt(slot);
        if (id != in.id) {
            in.reset(id, id == 0 ? null : effects.dataAt(slot));
            if (id != 0) {
                in.x = effects.positionAt(slot, 0);
                in.y = effects.positionAt(slot, 1);
                in.z = effects.positionAt(slot, 2);
            }
        }
    }

    // Burst calls into shared instances.

    /**
     * Forgets the burst calls at the front whose bursts have all fired, at the effects' times of the last update; calls
     * made since then wait for this update.
     */
    private void dropFinishedCalls() {
        int done = 0;
        while (done < effects.burstCount() && effects.burstSerial(done) < seenSerial) {
            int slot = effects.slot(effects.burstEffect(done));
            if (slot >= 0 && effects.burstTime(done) + lastBurst(effects.dataAt(slot)) > effects.timeAt(slot)) {
                break;
            }
            done++;
        }
        effects.dropBursts(done);
    }

    /** The latest any burst of {@code data} fires after a call, in ticks. */
    private static long lastBurst(EffectData data) {
        long last = 0;
        for (EmitterData e : data.emitters()) {
            for (Spawn spawn : e.spawns()) {
                if (spawn.kind() == Spawn.Kind.BURST) {
                    last = Math.max(last, ticks(e.delay() + spawn.time()));
                }
            }
        }
        return last;
    }

    private void linkCalls(int slots) {
        Arrays.fill(callHeads, 0, slots, -1);
        int calls = effects.burstCount();
        if (callNext.length < calls) {
            growCalls(calls);
        }
        for (int call = calls - 1; call >= 0; call--) {
            int slot = effects.slot(effects.burstEffect(call));
            if (slot >= 0) { // calls into ended instances wait to be dropped
                callNext[call] = callHeads[slot];
                callHeads[slot] = call;
            }
        }
    }

    @AllowAllocation("room for more burst calls")
    private void growCalls(int calls) {
        callNext = new int[Math.max(calls, 2 * callNext.length)];
    }

    // Significance.

    private void significance(int slots) {
        typeCount = 0;
        candidates = 0;
        for (int slot = 0; slot < slots; slot++) {
            Instance in = instances[slot];
            if (in.id == 0) {
                continue;
            }
            EffectType type = effects.dataAt(slot).type();
            in.type = typeIndex(type);
            in.distance = nearestViewer(slot);
            double widen = 1 + type.hysteresis();
            in.far = in.distance > type.maxDistance() * (in.far ? 1 : widen);
            int lod = in.lod;
            while (lod + 1 < type.lodCount() && in.distance > type.lod(lod + 1).distance() * widen) {
                lod++;
            }
            while (lod > 0 && in.distance < type.lod(lod).distance()) {
                lod--;
            }
            in.lod = lod;
            in.key = type.significance() == EffectType.Significance.AGE ? effects.timeAt(slot)
                : in.culled ? in.distance : in.distance / widen;
            if (!in.far) {
                order[candidates++] = slot;
            }
        }
        sortCandidates();
        int type = -1;
        int kept = 0;
        for (int i = 0; i < candidates; i++) {
            Instance in = instances[order[i]];
            kept = in.type == type ? kept + 1 : 1;
            type = in.type;
            in.capped = kept > types[type].maxInstances();
        }
        for (int slot = 0; slot < slots; slot++) {
            Instance in = instances[slot];
            if (in.id != 0) {
                cull(slot, in);
            }
        }
    }

    private void cull(int slot, Instance in) {
        in.culled = in.far || in.capped;
        in.capped = false;
        switch (types[in.type].whenCulled()) {
            case KILL -> {
                if (in.culled) {
                    effects.killAt(slot);
                    in.reset(0, null);
                }
            }
            case PAUSE -> effects.hold(slot, in.culled);
            default -> {
            }
        }
    }

    private int typeIndex(EffectType type) {
        for (int i = 0; i < typeCount; i++) {
            if (types[i].equals(type)) {
                return i;
            }
        }
        if (typeCount == types.length) {
            growTypes();
        }
        types[typeCount] = type;
        return typeCount++;
    }

    @AllowAllocation("room for more effect types")
    private void growTypes() {
        types = Arrays.copyOf(types, 2 * types.length);
    }

    /** The distance from the effect in {@code slot} to the nearest viewer; 0 without viewers. */
    private double nearestViewer(int slot) {
        if (viewerCount == 0) {
            return 0;
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (int v = 0; v < viewerCount; v++) {
            double dx = effects.positionAt(slot, 0) - viewers[3 * v];
            double dy = effects.positionAt(slot, 1) - viewers[3 * v + 1];
            double dz = effects.positionAt(slot, 2) - viewers[3 * v + 2];
            nearest = Math.min(nearest, dx * dx + dy * dy + dz * dz);
        }
        return Math.sqrt(nearest);
    }

    /** Heap sort of the candidates by type, then significance, then slot: no allocation, deterministic. */
    private void sortCandidates() {
        for (int i = candidates / 2 - 1; i >= 0; i--) {
            sift(i, candidates);
        }
        for (int end = candidates - 1; end > 0; end--) {
            int top = order[0];
            order[0] = order[end];
            order[end] = top;
            sift(0, end);
        }
    }

    private void sift(int i, int size) {
        while (2 * i + 1 < size) {
            int child = 2 * i + 1;
            if (child + 1 < size && before(order[child], order[child + 1])) {
                child++;
            }
            if (!before(order[i], order[child])) {
                return;
            }
            int swap = order[i];
            order[i] = order[child];
            order[child] = swap;
            i = child;
        }
    }

    /** Whether slot {@code a} sorts before slot {@code b}. */
    private boolean before(int a, int b) {
        Instance x = instances[a];
        Instance y = instances[b];
        if (x.type != y.type) {
            return x.type < y.type;
        }
        if (x.key != y.key) {
            return x.key < y.key;
        }
        return a < b;
    }

    // Spawning.

    private void spawn(int slot) {
        Instance in = instances[slot];
        in.records.clear();
        if (in.id == 0) {
            return;
        }
        EffectData data = effects.dataAt(slot);
        EffectType type = data.type();
        long now = effects.timeAt(slot);
        boolean emit = !in.culled || type.whenCulled() == EffectType.Culled.SIMULATE;
        float scale = type.spawnScale(quality) * type.lod(in.lod).spawnScale();
        int seed = effects.seedAt(slot);
        List<EmitterData> emitters = data.emitters();
        for (int e = 0; e < emitters.size(); e++) {
            EmitterData emitter = emitters.get(e);
            float emitterScale = emitter.minimumQuality().compareTo(quality) > 0 ? 0 : scale;
            int emitterSeed = emitterSeed(seed, e);
            if (effects.sharedAt(slot)) {
                calls(in, slot, emitter, e, emitterSeed, now, emit, emitterScale);
            } else {
                timeline(in, slot, data, emitter, e, emitterSeed, now, emit, emitterScale);
            }
        }
        in.time = now;
        in.first = false;
        in.x = effects.positionAt(slot, 0);
        in.y = effects.positionAt(slot, 1);
        in.z = effects.positionAt(slot, 2);
    }

    /** Spawns emitter {@code e} of a shared instance: each burst call's bursts after the emitter's delay. */
    private void calls(Instance in, int slot, EmitterData emitter, int e, int seed, long now, boolean emit,
            float scale) {
        long stop = effects.stopAt(slot);
        long delay = ticks(emitter.delay());
        List<Spawn> spawns = emitter.spawns();
        for (int call = callHeads[slot]; call >= 0; call = callNext[call]) {
            boolean fresh = effects.burstSerial(call) >= seenSerial;
            for (int j = 0; j < spawns.size(); j++) {
                Spawn spawn = spawns.get(j);
                long fire = effects.burstTime(call) + delay + ticks(spawn.time());
                if (spawn.kind() == Spawn.Kind.BURST && (fresh || fire > in.time) && fire <= now && fire < stop) {
                    int salt = 64 * effects.burstSerial(call) + j;
                    int count = Math.round(amount(spawn.amount(), slot, seed, salt) * scale);
                    births(in, emitter, e, count, (now - fire) * SECONDS, 0, emit, 0, call);
                }
            }
        }
    }

    /** Spawns emitter {@code e} by its timing, from the effect's time at the last update to {@code now}. */
    private void timeline(Instance in, int slot, EffectData data, EmitterData emitter, int e, int seed, long now,
            boolean emit, float scale) {
        long shift = ticks(emitter.prewarm()) - ticks(emitter.delay());
        long a = in.time + shift;
        long b = now + shift;
        long stop = effects.stopAt(slot);
        long end = stop == Long.MAX_VALUE ? Long.MAX_VALUE : stop + shift;
        Span span = in.span.set(emitter, e, seed, slot, end, b, scale);
        if (in.first && shift > 0) {
            long oldest = Math.max(0, shift - ticks(emitter.maxLifetime()));
            if (oldest > 0) {
                interval(in, span, 0, oldest, true, false, 0);
            }
            interval(in, span, oldest, shift, oldest == 0, emit, SpawnRecords.PREWARM);
            interval(in, span, shift, b, false, emit, 0);
        } else {
            interval(in, span, a, b, in.first, emit, 0);
        }
        perDistance(in, span, slot, a, b, emit);
    }

    /**
     * Spawns the rates and bursts of {@code span}'s emitter in its own time (a, b], and at a too if {@code inclusive}:
     * per loop, within the loop's duration and before the stop.
     */
    private void interval(Instance in, Span span, long a, long b, boolean inclusive, boolean emit, int flag) {
        EmitterData emitter = span.emitter;
        long duration = emitter.duration() == Double.POSITIVE_INFINITY ? Long.MAX_VALUE
            : Math.max(1, ticks(emitter.duration()));
        long period = duration == Long.MAX_VALUE ? Long.MAX_VALUE : duration + ticks(emitter.loopDelay());
        int loops = emitter.loops();
        List<Spawn> spawns = emitter.spawns();
        long after = inclusive ? a - 1 : a; // bursts fire after this tick
        for (long k = a <= 0 ? 0 : a / period; ; k++) {
            long start = k * period;
            if (start > b || loops != 0 && k >= loops || start >= span.stop) {
                return;
            }
            long finish = duration == Long.MAX_VALUE ? Long.MAX_VALUE : start + duration;
            long from = Math.max(a, start);
            long to = Math.min(Math.min(b, finish), span.stop);
            for (int j = 0; j < spawns.size(); j++) {
                Spawn spawn = spawns.get(j);
                int salt = (int) (64 * k + j);
                if (spawn.kind() == Spawn.Kind.RATE && to > from) {
                    float rate = amount(spawn.amount(), span.slot, span.seed, salt) * span.scale;
                    accumulate(in, span, in.offsets[span.index] + j, rate, to - from, from, 1, emit, flag);
                } else if (spawn.kind() == Spawn.Kind.BURST) {
                    long fire = start + ticks(spawn.time());
                    if (fire > after && fire <= b && fire < span.stop) {
                        int count = Math.round(amount(spawn.amount(), span.slot, span.seed, salt) * span.scale);
                        births(in, emitter, span.index, count, (span.end - fire) * SECONDS, 0, emit, flag, -1);
                    }
                }
            }
            if (period == Long.MAX_VALUE) {
                return;
            }
        }
    }

    /** Spawns per metre moved over the frame (a, b] of the emitter's own time, for the part of it that it runs. */
    private void perDistance(Instance in, Span span, int slot, long a, long b, boolean emit) {
        if (in.first || b <= a) {
            return;
        }
        double dx = effects.positionAt(slot, 0) - in.x;
        double dy = effects.positionAt(slot, 1) - in.y;
        double dz = effects.positionAt(slot, 2) - in.z;
        double running = (double) (active(span.emitter, Math.min(b, span.stop))
            - active(span.emitter, Math.min(a, span.stop))) / (b - a);
        long micrometres = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * running * Effects.TICKS_PER_SECOND);
        List<Spawn> spawns = span.emitter.spawns();
        for (int j = 0; j < spawns.size(); j++) {
            Spawn spawn = spawns.get(j);
            if (spawn.kind() == Spawn.Kind.DISTANCE && micrometres > 0) {
                float perMetre = amount(spawn.amount(), slot, span.seed, j) * span.scale;
                // Spread evenly over the frame: a micrometre of the way is this many ticks of it.
                accumulate(in, span, in.offsets[span.index] + j, perMetre, micrometres, a,
                    (double) (b - a) / micrometres, emit, 0);
            }
        }
    }

    /** Ticks emitter {@code e} has run by its own time {@code t}: the loops' durations only. */
    static long active(EmitterData e, long t) {
        if (t <= 0) {
            return 0;
        }
        if (e.duration() == Double.POSITIVE_INFINITY) {
            return t;
        }
        long duration = Math.max(1, ticks(e.duration()));
        long period = duration + ticks(e.loopDelay());
        long loop = t / period;
        long within = t - loop * period;
        if (e.loops() != 0 && loop >= e.loops()) {
            return e.loops() * duration;
        }
        return loop * duration + Math.min(within, duration);
    }

    /**
     * Adds {@code amount} per unit (a second, or a metre) over {@code units} units (ticks or micrometres) to spawn
     * entry {@code entry}'s fraction, carried in 32.32 fixed point, and spawns what it passes: born from tick
     * {@code from} of the emitter's time on, a unit lasting {@code ticksPerUnit} ticks.
     */
    private void accumulate(Instance in, Span span, int entry, float amount, long units, long from,
            double ticksPerUnit, boolean emit, int flag) {
        long rate = amount > 0 ? Math.min(MAX_RATE, (long) (amount * 4294967296.0)) : 0;
        if (rate == 0) {
            return;
        }
        long fraction = in.accumulators[entry];
        long start = fraction;
        long born = 0;
        long step = Math.max(1, (Long.MAX_VALUE >> 1) / rate);
        for (long left = units; left > 0; left -= Math.min(left, step)) {
            fraction += Math.min(left, step) * rate;
            born += fraction / UNIT;
            fraction %= UNIT;
        }
        in.accumulators[entry] = fraction;
        if (born > 0) {
            double first = from + (UNIT - start) / (double) rate * ticksPerUnit;
            births(in, span.emitter, span.index, born, (span.end - first) * SECONDS,
                (float) (UNIT / (double) rate * ticksPerUnit * SECONDS), emit, flag, -1);
        }
    }

    /** Spawns {@code count} particles of emitter {@code e}, the first {@code age} seconds old, at most its capacity. */
    private static void births(Instance in, EmitterData emitter, int e, long count, double age, float interval,
            boolean emit, int flag, int call) {
        if (count <= 0) {
            return;
        }
        long first = in.spawned[e];
        in.spawned[e] += count;
        if (!emit) {
            return;
        }
        long skipped = Math.max(0, count - emitter.capacity());
        in.records.add(in.id, e, (int) (count - skipped), first + skipped, (float) (age - skipped * interval), interval,
            flag, call);
    }

    /** A spawn amount's value now: a constant, a parameter (or a curve of it), or a random value salted so. */
    private float amount(Binding amount, int slot, int seed, int salt) {
        return switch (amount.kind()) {
            case PARAMETER -> parameter(slot, amount.parameter()) * amount.scale();
            case PARAMETER_CURVE -> amount.curve().evaluate(parameter(slot, amount.parameter())) * amount.scale();
            case RANDOM -> amount.range().at(Hash.random(seed, salt)) * amount.scale();
            case CONSTANT, AGE, SPEED -> amount.value() * amount.scale(); // per-particle kinds are refused when built
        };
    }

    private float parameter(int slot, String name) {
        EffectData data = effects.dataAt(slot);
        return effects.parametersAt(slot)[data.parameterOffset(name)];
    }

    /** Ticks of {@code seconds}, rounded. */
    static long ticks(double seconds) {
        return Math.round(seconds * Effects.TICKS_PER_SECOND);
    }

    /** The seed of emitter {@code emitter} of an effect seeded {@code effectSeed}. */
    public static int emitterSeed(int effectSeed, int emitter) {
        return Hash.combine(effectSeed, emitter);
    }

    /** The seed of the particle of spawn index {@code spawnIndex} of an emitter seeded {@code emitterSeed}. */
    public static int particleSeed(int emitterSeed, long spawnIndex) {
        return Hash.combine(emitterSeed, (int) spawnIndex);
    }

    // What the runtime decided, per effect.

    private Instance updated(long effect) {
        int slot = effects.slot(effect);
        if (slot < 0 || slot >= instances.length || instances[slot].id != effect) {
            throw new IllegalArgumentException(notUpdated(effect));
        }
        return instances[slot];
    }

    @AllowAllocation("diagnostics")
    private static String notUpdated(long effect) {
        return "No effect " + Long.toHexString(effect) + " was updated";
    }

    /** The level of detail of {@code effect} as of the last update: 0 for full detail. */
    public int lod(long effect) {
        return updated(effect).lod;
    }

    /** Whether {@code effect} is culled: too far, or over its type's cap. */
    public boolean culled(long effect) {
        return updated(effect).culled;
    }

    /** The distance from {@code effect} to the nearest viewer, as of the last update. */
    public double distance(long effect) {
        return updated(effect).distance;
    }

    /** What {@code effect}'s spawns are multiplied by: its type's scale at the quality, times its level's. */
    public float spawnScale(long effect) {
        Instance in = updated(effect);
        EffectType type = types[in.type];
        return type.spawnScale(quality) * type.lod(in.lod).spawnScale();
    }

    /** The fraction of {@code effect}'s particles drawn: its level's, 0 while culled. */
    public float drawnFraction(long effect) {
        Instance in = updated(effect);
        return in.culled ? 0 : types[in.type].lod(in.lod).drawnFraction();
    }

    /** {@code effect}'s simulation updates every this many frames, by its level of detail. */
    public int updateEvery(long effect) {
        Instance in = updated(effect);
        return types[in.type].lod(in.lod).updateEvery();
    }

    /** How many particles emitter {@code emitter} of {@code effect} has spawned so far: its next spawn index. */
    public long spawned(long effect, int emitter) {
        return updated(effect).spawned[emitter];
    }

    /** The runtime's state of one effect slot. */
    private static final class Instance {
        long id;
        long time;
        boolean first;
        double x;
        double y;
        double z;
        boolean far;
        boolean capped;
        boolean culled;
        int lod;
        int type;
        double distance;
        double key;
        /** Fractions per spawn entry, in fixed point. */
        long[] accumulators = new long[4];
        /** Particles spawned per emitter. */
        long[] spawned = new long[2];
        /** The first spawn entry of each emitter. */
        int[] offsets = new int[2];
        final SpawnRecords records = new SpawnRecords(4);
        final Span span = new Span();

        @AllowAllocation("an effect with more emitters than this slot had")
        void reset(long id, EffectData data) {
            this.id = id;
            time = 0;
            first = true;
            far = false;
            capped = false;
            culled = false;
            lod = 0;
            records.clear();
            if (data == null) {
                return;
            }
            List<EmitterData> emitters = data.emitters();
            if (spawned.length < emitters.size()) {
                spawned = new long[emitters.size()];
                offsets = new int[emitters.size()];
            }
            int entries = 0;
            for (int e = 0; e < emitters.size(); e++) {
                offsets[e] = entries;
                entries += emitters.get(e).spawns().size();
            }
            if (accumulators.length < entries) {
                accumulators = new long[entries];
            }
            Arrays.fill(spawned, 0);
            Arrays.fill(accumulators, 0);
        }
    }

    /** What spawning an emitter over a frame needs, kept per slot so threads share nothing. */
    private static final class Span {
        EmitterData emitter;
        int index;
        int seed;
        int slot;
        /** The emitter's own time it stops at. */
        long stop;
        /** The emitter's own time at the end of the frame. */
        long end;
        float scale;

        Span set(EmitterData emitter, int index, int seed, int slot, long stop, long end, float scale) {
            this.emitter = emitter;
            this.index = index;
            this.seed = seed;
            this.slot = slot;
            this.stop = stop;
            this.end = end;
            this.scale = scale;
            return this;
        }
    }
}
