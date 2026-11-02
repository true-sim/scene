package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Maths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The particles of CPU emitters (E1), simulated in Java on the job system's threads, and the reference the GPU kernel
 * is tested against: the same modules, the same hash, the same order and the same survivor copy. Each update, after
 * {@link EffectRuntime#update}, every CPU emitter's particles age and move by their effect's time since the last
 * update (at most {@value #MAX_STEP} s, so a hitch does not fling them), the dead ones leave with the order of the
 * others kept, and the frame's spawn records append new ones, initialized and moved on to the end of the frame.
 * Gameplay reads them through a {@link ParticleCursor}. Deterministic on any thread count; allocates nothing per frame.
 *
 * <pre>{@code
 * CpuSimulation simulation = new CpuSimulation(runtime, world, jobs);   // world: a ParticleWorld, or null
 * // each frame:
 * runtime.update(seconds);
 * simulation.update();
 * simulation.particles(grenade.id(), SHARDS, cursor);
 * while (cursor.next()) damage(cursor.x(), cursor.y(), cursor.z());
 * }</pre>
 *
 * <p>Motion is integrated exactly for constant forces: with gravity {@code a} and linear drag {@code k} over a step
 * {@code h}, {@code v' = a/k + (v - a/k) e^(-kh)} and {@code p' = p + h a/k + (v - a/k)(1 - e^(-kh))/k} ({@code p +
 * vh + ah^2/2} without drag), so positions do not depend on how frames split time. World-space particles are float
 * offsets from their effect's origin, its position when it started, moved to where the effect is once it is
 * {@value #REBASE_DISTANCE} m away (rebasing); they are born with the effect's {@link Effect#inheritVelocity inherited}
 * share of its velocity over the frame. Local-space ones live in the emitter's frame and move with it. Forces act in
 * the world: gravity pulls along world down and wind blows along the world's wind, which local-space particles take
 * turned into their frame. Forces that depend on where a particle is (curl noise, vortices, attractors) are taken as
 * constant over a step, from where it was at its start; orbits turn it about their axis exactly; the speed limit comes
 * last ({@link Forces}). Random values are {@link Hash#random} of the particle's seed and a salt: 16 per module, by its
 * place in its list ({@value #UPDATE_SALTS} on for updaters), its fields first in alphabetical order, then its own
 * draws from 8 on; noise (curl, gusts) takes {@code Hash.combine(emitter seed, salt + 8)} as its seed.
 */
public final class CpuSimulation {

    /** The longest step particles take in one update, in seconds. */
    public static final float MAX_STEP = 0.1f;
    /** Updaters' salts start here, initializers' at 0. */
    public static final int UPDATE_SALTS = 4096;
    /**
     * How far, in metres, an effect moves from the origin of its world-space particles before the origin moves to it
     * (rebasing): float offsets stay precise to a millimetre within it.
     */
    public static final double REBASE_DISTANCE = 4096;

    // Module operations.
    private static final int POINT = 0;
    private static final int SPHERE = 1;
    private static final int BOX = 2;
    private static final int CONE = 3;
    private static final int DISC = 4;
    private static final int LIFETIME = 5;
    private static final int SPEED = 6;
    private static final int VELOCITY = 7;
    private static final int SIZE = 8;
    private static final int COLOR = 9;
    private static final int GRAVITY = 10;
    private static final int DRAG = 11;
    private static final int OVER_SIZE = 12;
    private static final int OVER_COLOR = 13;
    private static final int WIND = 14;
    private static final int CURL = 15;
    private static final int VORTEX = 16;
    private static final int POINT_FORCE = 17;
    private static final int LINE = 18;
    private static final int ORBIT = 19;
    private static final int SPEED_LIMIT = 20;
    private static final float TAU = (float) Maths.TAU;
    /** How fast gusts of wind change: noise cells per second. */
    static final float GUST_RATE = 0.5f;
    /** Below this, a distance or a length is none: no direction to push along. */
    private static final double TINY = 1e-6;

    private final EffectRuntime runtime;
    private final Effects effects;
    private final ParticleWorld world;
    private final JobSystem jobs;
    private Slot[] slots = new Slot[0];
    private int[] recordFrom = new int[0];
    private int[] recordTo = new int[0];
    private final JobSystem.RangeTask task = (from, to, worker) -> {
        for (int slot = from; slot < to; slot++) {
            simulate(slot);
        }
    };

    /** Simulates the CPU emitters of {@code runtime}'s effects on the updating thread; {@code world} may be null. */
    public CpuSimulation(EffectRuntime runtime, ParticleWorld world) {
        this(runtime, world, null);
    }

    /** Simulates on {@code jobs} (null: on the updating thread). */
    @AllowAllocation("set up once")
    public CpuSimulation(EffectRuntime runtime, ParticleWorld world, JobSystem jobs) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        effects = runtime.effects();
        this.world = world;
        this.jobs = jobs;
    }

    /** What CPU emitters collide with, or null. */
    public ParticleWorld world() {
        return world;
    }

    /** Moves every CPU emitter's particles on to the effects' times and spawns the runtime's records. */
    public void update() {
        int n = effects.slots();
        if (slots.length < n) {
            grow(n);
        }
        Arrays.fill(recordFrom, 0, n, 0);
        Arrays.fill(recordTo, 0, n, 0);
        SpawnRecords records = runtime.spawns();
        for (int i = 0; i < records.size(); i++) {
            int slot = effects.slot(records.effect(i));
            if (slot >= 0) { // the game may have killed it since the runtime's update
                if (recordTo[slot] == recordFrom[slot]) {
                    recordFrom[slot] = i;
                }
                recordTo[slot] = i + 1;
            }
        }
        if (jobs != null && n > 1) {
            jobs.parallelFor(n, 1, task);
        } else {
            task.run(0, n, 0);
        }
    }

    @AllowAllocation("growing with the effects playing")
    private void grow(int n) {
        int old = slots.length;
        slots = Arrays.copyOf(slots, Math.max(n, 2 * old));
        for (int slot = old; slot < slots.length; slot++) {
            slots[slot] = new Slot();
        }
        recordFrom = new int[slots.length];
        recordTo = new int[slots.length];
    }

    /** Points {@code cursor} at the particles of CPU emitter {@code emitter} of {@code effect}; returns it. */
    public ParticleCursor particles(long effect, int emitter, ParticleCursor cursor) {
        Slot s = simulated(effect);
        Particles p = particles(s, emitter);
        boolean local = s.data.emitters().get(emitter).space() == Space.LOCAL;
        int slot = effects.slot(effect);
        if (local) {
            cursor.set(p, true, effects.positionAt(slot, 0), effects.positionAt(slot, 1), effects.positionAt(slot, 2),
                effects.rotation(effect, 0), effects.rotation(effect, 1), effects.rotation(effect, 2),
                effects.rotation(effect, 3));
        } else {
            cursor.set(p, false, s.ox, s.oy, s.oz, 0, 0, 0, 1);
        }
        return cursor;
    }

    /** How many particles CPU emitter {@code emitter} of {@code effect} has. */
    public int count(long effect, int emitter) {
        return particles(simulated(effect), emitter).count;
    }

    /** How many particles CPU emitter {@code emitter} of {@code effect} could not spawn for want of room. */
    public long dropped(long effect, int emitter) {
        return particles(simulated(effect), emitter).dropped;
    }

    private Slot simulated(long effect) {
        int slot = effects.slot(effect);
        if (slot < 0 || slot >= slots.length || slots[slot].id != effect) {
            throw new IllegalArgumentException(notSimulated(effect));
        }
        return slots[slot];
    }

    private static Particles particles(Slot s, int emitter) {
        Particles p = s.particles[Objects.checkIndex(emitter, s.particles.length)];
        if (p == null) {
            throw new IllegalArgumentException(notCpu(emitter));
        }
        return p;
    }

    @AllowAllocation("diagnostics")
    private static String notSimulated(long effect) {
        return "No effect " + Long.toHexString(effect) + " was simulated";
    }

    @AllowAllocation("diagnostics")
    private static String notCpu(int emitter) {
        return "Emitter " + emitter + " is not a CPU emitter";
    }

    // Simulating one effect.

    private void simulate(int slot) {
        Slot s = slots[slot];
        long id = effects.effectAt(slot);
        if (id != s.id || id != 0 && effects.restartsAt(slot) != s.restarts) {
            s.reset(id, id == 0 ? null : effects.dataAt(slot), effects, slot);
            s.restarts = id == 0 ? 0 : effects.restartsAt(slot);
        }
        if (!s.cpu) {
            return;
        }
        long now = effects.timeAt(slot);
        double frame = (now - s.time) * 1e-6;
        float dt = (float) Math.min(MAX_STEP, frame);
        float[] parameters = effects.parametersAt(slot);
        s.frame(effects, slot);
        s.seconds = (float) (now * 1e-6);
        rebase(s);
        int seed = effects.seedAt(slot);
        SpawnRecords records = runtime.spawns();
        for (int e = 0; e < s.particles.length; e++) {
            Particles p = s.particles[e];
            if (p == null) {
                continue;
            }
            Program program = s.programs[e];
            int emitterSeed = EffectRuntime.emitterSeed(seed, e);
            survive(s, p, program, parameters, dt, emitterSeed);
            for (int r = recordFrom[slot]; r < recordTo[slot]; r++) {
                if (records.emitter(r) == e) {
                    spawn(s, p, program, parameters, records, r, emitterSeed, frame);
                }
            }
        }
        s.time = now;
        s.px = s.x;
        s.py = s.y;
        s.pz = s.z;
    }

    /**
     * Moves the origin of the effect's world-space particles to where it is, once it is {@link #REBASE_DISTANCE} away
     * along an axis, shifting them by the difference: their world positions stay.
     */
    private static void rebase(Slot s) {
        if (Math.abs(s.x - s.ox) <= REBASE_DISTANCE && Math.abs(s.y - s.oy) <= REBASE_DISTANCE
            && Math.abs(s.z - s.oz) <= REBASE_DISTANCE) {
            return;
        }
        double sx = s.ox - s.x;
        double sy = s.oy - s.y;
        double sz = s.oz - s.z;
        for (int e = 0; e < s.particles.length; e++) {
            Particles p = s.particles[e];
            if (p == null || s.programs[e].local) {
                continue;
            }
            for (int i = 0; i < p.count; i++) {
                p.px[i] = (float) (p.px[i] + sx);
                p.py[i] = (float) (p.py[i] + sy);
                p.pz[i] = (float) (p.pz[i] + sz);
            }
        }
        s.ox = s.x;
        s.oy = s.y;
        s.oz = s.z;
    }

    /** Ages and moves the particles by {@code dt}; the dead leave, the others keep their order (the survivor copy). */
    private static void survive(Slot s, Particles p, Program program, float[] parameters, float dt,
            int emitterSeed) {
        int kept = 0;
        for (int i = 0; i < p.count; i++) {
            if (p.age[i] + dt >= p.lifetime[i]) {
                continue;
            }
            if (kept != i) {
                p.move(i, kept);
            }
            step(s, p, kept, program, parameters, dt, emitterSeed);
            kept++;
        }
        p.count = kept;
    }

    /** Spawns record {@code r}'s particles, while there is room. */
    private void spawn(Slot s, Particles p, Program program, float[] parameters, SpawnRecords records, int r,
            int emitterSeed, double frame) {
        int count = records.count(r);
        for (int k = 0; k < count; k++) {
            if (p.count == p.capacity) {
                p.dropped += count - k;
                return;
            }
            int i = p.count;
            long index = records.firstIndex(r) + k;
            int seed = EffectRuntime.particleSeed(emitterSeed, index);
            float age = (float) (records.age(r) - k * (double) records.interval(r));
            p.born(i, seed, index);
            s.direction(0, 0, 0); // no shape yet: speed alone moves nothing, as on the GPU
            for (int m = 0; m < program.init.length; m++) {
                init(s, p, i, program.init[m], parameters, seed);
            }
            if (age >= p.lifetime[i]) {
                continue; // dead before the end of the frame
            }
            place(s, p, i, records, r, age, frame, program.local);
            step(s, p, i, program, parameters, age, emitterSeed);
            p.count++;
        }
    }

    /** Runs initializer {@code op} on particle {@code i}: its shape, lifetime, velocity, size or color. */
    private static void init(Slot s, Particles p, int i, Op op, float[] parameters, int seed) {
        int salt = op.salt;
        switch (op.code) {
            case POINT -> {
                p.setPosition(i, 0, 0, 0);
                s.randomDirection(seed, salt + 8);
            }
            case SPHERE -> {
                float radius = value(op, 0, parameters, seed, p, i);
                float thickness = value(op, 1, parameters, seed, p, i);
                s.randomDirection(seed, salt + 8);
                float inner = 1 - Math.min(1, Math.max(0, thickness));
                float u = Hash.random(seed, salt + 10);
                float r = radius * (float) Maths.cbrt(inner * inner * inner + (1 - inner * inner * inner) * u);
                p.setPosition(i, s.dx * r, s.dy * r, s.dz * r);
            }
            case BOX -> {
                p.setPosition(i, (Hash.random(seed, salt + 8) - 0.5f) * value(op, 0, parameters, seed, p, i),
                    (Hash.random(seed, salt + 9) - 0.5f) * value(op, 1, parameters, seed, p, i),
                    (Hash.random(seed, salt + 10) - 0.5f) * value(op, 2, parameters, seed, p, i));
                s.randomDirection(seed, salt + 11);
            }
            case CONE -> {
                float angle = value(op, 0, parameters, seed, p, i);
                disc(s, p, i, value(op, 1, parameters, seed, p, i), seed, salt);
                float cos = 1 - Hash.random(seed, salt + 10) * (1 - (float) Maths.cos(angle));
                float sin = (float) Maths.sqrt(Math.max(0, 1 - cos * cos));
                float turn = TAU * Hash.random(seed, salt + 11);
                s.direction(sin * (float) Maths.cos(turn), cos, sin * (float) Maths.sin(turn));
            }
            case DISC -> {
                disc(s, p, i, value(op, 0, parameters, seed, p, i), seed, salt);
                s.direction(0, 1, 0);
            }
            case LIFETIME -> p.lifetime[i] = value(op, 0, parameters, seed, p, i);
            case SPEED -> {
                float speed = value(op, 0, parameters, seed, p, i);
                p.addVelocity(i, s.dx * speed, s.dy * speed, s.dz * speed);
            }
            case VELOCITY -> p.addVelocity(i, value(op, 0, parameters, seed, p, i),
                value(op, 1, parameters, seed, p, i), value(op, 2, parameters, seed, p, i));
            case SIZE -> {
                p.size[i] = value(op, 0, parameters, seed, p, i);
                p.size0[i] = p.size[i];
            }
            default -> { // COLOR
                color(op, parameters, seed, p, i, s.rgba);
                for (int c = 0; c < 4; c++) {
                    p.color[4 * i + c] = s.rgba[c];
                    p.color0[4 * i + c] = s.rgba[c];
                }
            }
        }
    }

    /** A position on a disc of {@code radius} in the XZ plane, uniform by area. */
    private static void disc(Slot s, Particles p, int i, float radius, int seed, int salt) {
        float rho = radius * (float) Maths.sqrt(Hash.random(seed, salt + 8));
        float turn = TAU * Hash.random(seed, salt + 9);
        p.setPosition(i, rho * (float) Maths.cos(turn), 0, rho * (float) Maths.sin(turn));
    }

    /**
     * Places a newborn particle in its space: world-space ones where the emitter was at its birth (along the frame's
     * motion, or at a burst call's position facing its normal), as offsets from the origin; local-space ones stay in
     * the emitter's frame.
     */
    private void place(Slot s, Particles p, int i, SpawnRecords records, int r, float age, double frame,
            boolean local) {
        int call = records.burst(r);
        double ex;
        double ey;
        double ez;
        if (call >= 0) {
            s.facing(effects.burstNormal(call, 0), effects.burstNormal(call, 1), effects.burstNormal(call, 2));
            ex = effects.burstPosition(call, 0);
            ey = effects.burstPosition(call, 1);
            ez = effects.burstPosition(call, 2);
        } else if (local) {
            return;
        } else {
            s.turn(s.qx, s.qy, s.qz, s.qw);
            double along = frame > 0 ? Math.max(0, Math.min(1, 1 - age / frame)) : 1;
            ex = s.px + (s.x - s.px) * along;
            ey = s.py + (s.y - s.py) * along;
            ez = s.pz + (s.z - s.pz) * along;
        }
        s.rotate(p.px[i], p.py[i], p.pz[i]);
        p.setPosition(i, (float) (ex - s.ox + s.rx), (float) (ey - s.oy + s.ry), (float) (ez - s.oz + s.rz));
        s.rotate(p.vx[i], p.vy[i], p.vz[i]);
        p.vx[i] = (float) s.rx;
        p.vy[i] = (float) s.ry;
        p.vz[i] = (float) s.rz;
        if (call < 0 && s.inherit != 0 && frame > 0) { // the emitter's velocity over the frame, in part
            double scale = s.inherit / frame;
            p.vx[i] += (float) ((s.x - s.px) * scale);
            p.vy[i] += (float) ((s.y - s.py) * scale);
            p.vz[i] += (float) ((s.z - s.pz) * scale);
        }
    }

    /**
     * Moves particle {@code i} on by {@code h} seconds under the updaters (forces, then orbits and limits), then
     * applies those over its life. Fields are read by their index in alphabetical order of their names.
     */
    private static void step(Slot s, Particles p, int i, Program program, float[] parameters, float h,
            int emitterSeed) {
        double ax = 0;
        double ay = 0;
        double az = 0;
        double k = 0;
        boolean local = program.local;
        boolean kill = false;
        int seed = p.seed[i];
        // Where the emitter is in its particles' space: its frame's origin, or its offset from theirs
        double cx = local ? 0 : s.x - s.ox;
        double cy = local ? 0 : s.y - s.oy;
        double cz = local ? 0 : s.z - s.oz;
        double x = p.px[i];
        double y = p.py[i];
        double z = p.pz[i];
        for (Op op : program.update) {
            switch (op.code) {
                case GRAVITY -> {
                    double g = Forces.G * value(op, 0, parameters, seed, p, i);
                    if (local) { // world down, turned into the emitter's frame
                        ax += s.downX * g;
                        ay += s.downY * g;
                        az += s.downZ * g;
                    } else {
                        ay -= g; // forces act in the world
                    }
                }
                case DRAG -> k += value(op, 0, parameters, seed, p, i);
                case WIND -> { // coupling, gusts, scale: a drag towards the wind, exact
                    double coupling = value(op, 0, parameters, seed, p, i);
                    CurlNoise.noise(s.seconds * GUST_RATE, 0, 0, Hash.combine(emitterSeed, op.salt + 8), s.noise);
                    double pull = coupling * value(op, 2, parameters, seed, p, i)
                        * (1 + value(op, 1, parameters, seed, p, i) * s.noise[0]);
                    k += coupling;
                    ax += pull * (local ? s.windX : s.windWorldX);
                    ay += pull * (local ? s.windY : s.windWorldY);
                    az += pull * (local ? s.windZ : s.windWorldZ);
                }
                case CURL -> { // frequency, octaves, speed, strength
                    int octaves = Math.max(1, Math.min(4, Math.round(value(op, 1, parameters, seed, p, i))));
                    CurlNoise.curl((float) x, (float) y, (float) z, value(op, 0, parameters, seed, p, i),
                        s.seconds * value(op, 2, parameters, seed, p, i), octaves,
                        Hash.combine(emitterSeed, op.salt + 8), s.noise, s.curl);
                    double strength = value(op, 3, parameters, seed, p, i);
                    ax += strength * s.curl[0];
                    ay += strength * s.curl[1];
                    az += strength * s.curl[2];
                }
                case VORTEX -> { // axis x, y, z, pull, strength
                    s.axis(value(op, 0, parameters, seed, p, i), value(op, 1, parameters, seed, p, i),
                        value(op, 2, parameters, seed, p, i), local);
                    s.across(x - cx, y - cy, z - cz);
                    double r = Maths.sqrt(s.rx * s.rx + s.ry * s.ry + s.rz * s.rz);
                    if (r > TINY) {
                        double around = value(op, 4, parameters, seed, p, i) / r;
                        double in = -value(op, 3, parameters, seed, p, i) / r;
                        ax += around * (s.uy * s.rz - s.uz * s.ry) + in * s.rx;
                        ay += around * (s.uz * s.rx - s.ux * s.rz) + in * s.ry;
                        az += around * (s.ux * s.ry - s.uy * s.rx) + in * s.rz;
                    }
                }
                case POINT_FORCE -> { // kill radius, radius, strength, x, y, z
                    s.axis(value(op, 3, parameters, seed, p, i), value(op, 4, parameters, seed, p, i),
                        value(op, 5, parameters, seed, p, i), local, false);
                    double dx = cx + s.ux - x;
                    double dy = cy + s.uy - y;
                    double dz = cz + s.uz - z;
                    double d = Maths.sqrt(dx * dx + dy * dy + dz * dz);
                    kill |= d < value(op, 0, parameters, seed, p, i);
                    double pull = value(op, 2, parameters, seed, p, i) * falloff(d, value(op, 1, parameters, seed,
                        p, i));
                    if (d > TINY) {
                        ax += pull * dx / d;
                        ay += pull * dy / d;
                        az += pull * dz / d;
                    }
                }
                case LINE -> { // axis x, y, z, radius, strength, x, y, z
                    s.axis(value(op, 5, parameters, seed, p, i), value(op, 6, parameters, seed, p, i),
                        value(op, 7, parameters, seed, p, i), local, false);
                    double ox = x - cx - s.ux;
                    double oy = y - cy - s.uy;
                    double oz = z - cz - s.uz;
                    s.axis(value(op, 0, parameters, seed, p, i), value(op, 1, parameters, seed, p, i),
                        value(op, 2, parameters, seed, p, i), local);
                    s.across(ox, oy, oz);
                    double d = Maths.sqrt(s.rx * s.rx + s.ry * s.ry + s.rz * s.rz);
                    if (d > TINY) {
                        double pull = -value(op, 4, parameters, seed, p, i)
                            * falloff(d, value(op, 3, parameters, seed, p, i)) / d;
                        ax += pull * s.rx;
                        ay += pull * s.ry;
                        az += pull * s.rz;
                    }
                }
                default -> { // over life, orbits and limits: below
                }
            }
        }
        double vx = p.vx[i];
        double vy = p.vy[i];
        double vz = p.vz[i];
        if (k == 0) {
            p.px[i] += (float) (vx * h + 0.5 * ax * h * h);
            p.py[i] += (float) (vy * h + 0.5 * ay * h * h);
            p.pz[i] += (float) (vz * h + 0.5 * az * h * h);
            p.vx[i] = (float) (vx + ax * h);
            p.vy[i] = (float) (vy + ay * h);
            p.vz[i] = (float) (vz + az * h);
        } else {
            double decay = Maths.exp(-k * h);
            double fade = (1 - decay) / k;
            double tx = ax / k;
            double ty = ay / k;
            double tz = az / k;
            p.px[i] += (float) (tx * h + (vx - tx) * fade);
            p.py[i] += (float) (ty * h + (vy - ty) * fade);
            p.pz[i] += (float) (tz * h + (vz - tz) * fade);
            p.vx[i] = (float) (tx + (vx - tx) * decay);
            p.vy[i] = (float) (ty + (vy - ty) * decay);
            p.vz[i] = (float) (tz + (vz - tz) * decay);
        }
        p.age[i] += h;
        for (Op op : program.update) {
            if (op.code == ORBIT) { // axis x, y, z, speed: a turn about the axis, exact
                s.axis(value(op, 0, parameters, seed, p, i), value(op, 1, parameters, seed, p, i),
                    value(op, 2, parameters, seed, p, i), local);
                double angle = value(op, 3, parameters, seed, p, i) * h;
                double cos = Maths.cos(angle);
                double sin = Maths.sin(angle);
                s.turnAbout(p.px[i] - cx, p.py[i] - cy, p.pz[i] - cz, cos, sin);
                p.px[i] = (float) (cx + s.rx);
                p.py[i] = (float) (cy + s.ry);
                p.pz[i] = (float) (cz + s.rz);
                s.turnAbout(p.vx[i], p.vy[i], p.vz[i], cos, sin);
                p.vx[i] = (float) s.rx;
                p.vy[i] = (float) s.ry;
                p.vz[i] = (float) s.rz;
            } else if (op.code == SPEED_LIMIT) {
                double limit = value(op, 0, parameters, seed, p, i);
                double speed = Maths.sqrt(p.vx[i] * p.vx[i] + p.vy[i] * p.vy[i] + p.vz[i] * p.vz[i]);
                if (speed > limit) {
                    double scale = Math.max(0, limit) / speed;
                    p.vx[i] = (float) (p.vx[i] * scale);
                    p.vy[i] = (float) (p.vy[i] * scale);
                    p.vz[i] = (float) (p.vz[i] * scale);
                }
            }
        }
        if (kill) {
            p.lifetime[i] = p.age[i]; // within an attractor's kill radius: dead from now, gone at the next step
        }
        if (program.overSize || program.overColor) {
            over(s, p, i, program, parameters);
        }
    }

    /** How much of an attractor's strength reaches {@code distance}: falling to 0 at {@code radius} (0: all of it). */
    private static double falloff(double distance, double radius) {
        return radius > 0 ? Math.max(0, 1 - distance / radius) : 1;
    }

    /** Sizes and colors at birth times the curves and gradients over the particle's normalized age. */
    private static void over(Slot s, Particles p, int i, Program program, float[] parameters) {
        float t = p.age[i] / p.lifetime[i];
        if (program.overSize) {
            p.size[i] = p.size0[i];
        }
        if (program.overColor) {
            System.arraycopy(p.color0, 4 * i, p.color, 4 * i, 4);
        }
        for (Op op : program.update) {
            if (op.code == OVER_SIZE) {
                p.size[i] *= op.curve.evaluate(t);
            } else if (op.code == OVER_COLOR) {
                op.gradient.evaluate(t, s.rgba, 0);
                for (int c = 0; c < 4; c++) {
                    p.color[4 * i + c] *= s.rgba[c];
                }
            }
        }
    }

    /** Field {@code field} of {@code op} for particle {@code i}: its binding evaluated. */
    private static float value(Op op, int field, float[] parameters, int seed, Particles p, int i) {
        Binding b = op.bindings[field];
        return switch (b.kind()) {
            case CONSTANT -> b.value() * b.scale();
            case PARAMETER -> parameters[op.offsets[field]] * b.scale();
            case AGE -> b.curve().evaluate(p.age[i] / p.lifetime[i]) * b.scale();
            case PARAMETER_CURVE -> b.curve().evaluate(parameters[op.offsets[field]]) * b.scale();
            case SPEED -> b.curve().evaluate((float) Maths.sqrt(p.vx[i] * p.vx[i] + p.vy[i] * p.vy[i]
                + p.vz[i] * p.vz[i])) * b.scale();
            case RANDOM -> b.range().at(Hash.random(seed, op.salt + field)) * b.scale();
        };
    }

    /** {@code op}'s color for particle {@code i}, into {@code rgba}. */
    private static void color(Op op, float[] parameters, int seed, Particles p, int i, float[] rgba) {
        ColorBinding c = op.color;
        switch (c.kind()) {
            case CONSTANT -> {
                rgba[0] = c.r();
                rgba[1] = c.g();
                rgba[2] = c.b();
                rgba[3] = c.a();
            }
            case PARAMETER -> System.arraycopy(parameters, op.offsets[0], rgba, 0, 4);
            case AGE -> c.gradient().evaluate(p.age[i] / p.lifetime[i], rgba, 0);
            default -> c.gradient().evaluate(Hash.random(seed, op.salt), rgba, 0); // RANDOM
        }
    }

    // State.

    /** The particles of one CPU emitter of one effect: structure of arrays, in spawn order. */
    static final class Particles {
        final int capacity;
        int count;
        long dropped;
        final float[] px;
        final float[] py;
        final float[] pz;
        final float[] vx;
        final float[] vy;
        final float[] vz;
        final float[] age;
        final float[] lifetime;
        final float[] size;
        final float[] size0;
        final float[] color;
        final float[] color0;
        final int[] seed;
        final long[] index;

        @AllowAllocation("an effect's particles, made when it starts")
        Particles(int capacity) {
            this.capacity = capacity;
            px = new float[capacity];
            py = new float[capacity];
            pz = new float[capacity];
            vx = new float[capacity];
            vy = new float[capacity];
            vz = new float[capacity];
            age = new float[capacity];
            lifetime = new float[capacity];
            size = new float[capacity];
            size0 = new float[capacity];
            color = new float[4 * capacity];
            color0 = new float[4 * capacity];
            seed = new int[capacity];
            index = new long[capacity];
        }

        /** A newborn at {@code i}: at the origin of its frame, at rest, with the modules' fallbacks. */
        void born(int i, int seed, long index) {
            this.seed[i] = seed;
            this.index[i] = index;
            setPosition(i, 0, 0, 0);
            vx[i] = 0;
            vy[i] = 0;
            vz[i] = 0;
            age[i] = 0;
            lifetime[i] = EmitterData.DEFAULT_LIFETIME;
            size[i] = 0.1f;
            size0[i] = 0.1f;
            Arrays.fill(color, 4 * i, 4 * i + 4, 1);
            Arrays.fill(color0, 4 * i, 4 * i + 4, 1);
        }

        void setPosition(int i, float x, float y, float z) {
            px[i] = x;
            py[i] = y;
            pz[i] = z;
        }

        void addVelocity(int i, float x, float y, float z) {
            vx[i] += x;
            vy[i] += y;
            vz[i] += z;
        }

        /** Moves particle {@code from} to {@code to}, an earlier slot. */
        void move(int from, int to) {
            px[to] = px[from];
            py[to] = py[from];
            pz[to] = pz[from];
            vx[to] = vx[from];
            vy[to] = vy[from];
            vz[to] = vz[from];
            age[to] = age[from];
            lifetime[to] = lifetime[from];
            size[to] = size[from];
            size0[to] = size0[from];
            System.arraycopy(color, 4 * from, color, 4 * to, 4);
            System.arraycopy(color0, 4 * from, color0, 4 * to, 4);
            seed[to] = seed[from];
            index[to] = index[from];
        }
    }

    /**
     * A module as the simulator runs it: an operation, its fields' bindings (with the parameter offsets they read),
     * its color, curve or gradient, and its salt.
     */
    private record Op(int code, Binding[] bindings, int[] offsets, ColorBinding color, FloatCurve curve,
            ColorGradient gradient, int salt) {
    }

    /** An emitter's modules as operations. */
    private record Program(Op[] init, Op[] update, boolean overSize, boolean overColor, boolean local) {

        @AllowAllocation("an emitter's program, made when its effect starts")
        static Program of(EffectData data, EmitterData emitter) {
            Op[] init = ops(data, emitter.init(), 0);
            Op[] update = ops(data, emitter.update(), UPDATE_SALTS);
            boolean size = false;
            boolean color = false;
            for (Op op : update) {
                size |= op.code == OVER_SIZE;
                color |= op.code == OVER_COLOR;
            }
            return new Program(init, update, size, color, emitter.space() == Space.LOCAL);
        }

        @AllowAllocation("an emitter's program, made when its effect starts")
        private static Op[] ops(EffectData data, List<Module> modules, int salts) {
            Op[] ops = new Op[modules.size()];
            for (int m = 0; m < ops.length; m++) {
                Module module = modules.get(m);
                ModuleType type = ModuleRegistry.BUILT_IN.type(module.type());
                int code = switch (module.type()) {
                    case Shape.POINT -> POINT;
                    case Shape.SPHERE -> SPHERE;
                    case Shape.BOX -> BOX;
                    case Shape.CONE -> CONE;
                    case Shape.DISC -> DISC;
                    case Init.LIFETIME -> LIFETIME;
                    case Init.SPEED -> SPEED;
                    case Init.VELOCITY -> VELOCITY;
                    case Init.SIZE -> SIZE;
                    case Init.COLOR -> COLOR;
                    case Forces.GRAVITY -> GRAVITY;
                    case Forces.DRAG -> DRAG;
                    case Over.SIZE -> OVER_SIZE;
                    case Over.COLOR -> OVER_COLOR;
                    case Forces.WIND -> WIND;
                    case Forces.CURL -> CURL;
                    case Forces.VORTEX -> VORTEX;
                    case Forces.POINT -> POINT_FORCE;
                    case Forces.LINE -> LINE;
                    case Forces.ORBIT -> ORBIT;
                    case Forces.SPEED_LIMIT -> SPEED_LIMIT;
                    default -> throw new IllegalStateException("No Java implementation of " + module.type());
                };
                List<String> names = type.fields().keySet().stream().sorted().toList();
                Binding[] bindings = new Binding[names.size()];
                int[] offsets = new int[names.size()];
                ColorBinding color = null;
                FloatCurve curve = null;
                ColorGradient gradient = null;
                for (int f = 0; f < names.size(); f++) {
                    Object value = module.value(names.get(f));
                    if (value == null) {
                        value = type.fields().get(names.get(f)).fallback();
                    }
                    switch (value) {
                        case Binding b -> {
                            bindings[f] = b;
                            offsets[f] = b.parameter() == null ? -1 : data.parameterOffset(b.parameter());
                        }
                        case ColorBinding c -> {
                            color = c;
                            offsets[f] = c.parameter() == null ? -1 : data.parameterOffset(c.parameter());
                        }
                        case FloatCurve c -> curve = c;
                        default -> gradient = (ColorGradient) value;
                    }
                }
                ops[m] = new Op(code, bindings, offsets, color, curve, gradient, salts + 16 * m);
            }
            return ops;
        }
    }

    /** The simulator's state of one effect slot, and the scratch its thread uses. */
    private static final class Slot {
        long id;
        /** The effect's restarts ({@link Effects#restarts}) when it was set up. */
        int restarts;
        EffectData data;
        boolean cpu;
        long time;
        Particles[] particles = new Particles[0];
        Program[] programs = new Program[0];
        /** The origin of world-space particles. */
        double ox;
        double oy;
        double oz;
        /** Where the effect was at the last update, and is now. */
        double px;
        double py;
        double pz;
        double x;
        double y;
        double z;
        /** How much of its velocity world-space particles inherit at birth. */
        float inherit;
        /** Its rotation now, and world down in its frame. */
        double qx;
        double qy;
        double qz;
        double qw;
        double downX;
        double downY;
        double downZ;
        /** The world's wind, and turned into the effect's frame; the effect's time in seconds. */
        double windWorldX;
        double windWorldY;
        double windWorldZ;
        double windX;
        double windY;
        double windZ;
        float seconds;
        // Scratch: an axis in the particles' space, noise and its curl.
        double ux;
        double uy;
        double uz;
        final float[] noise = new float[4];
        final float[] curl = new float[3];
        // Scratch: a direction, a rotation (as a quaternion) and a rotated vector, a color.
        float dx;
        float dy;
        float dz;
        double tx;
        double ty;
        double tz;
        double tw;
        double rx;
        double ry;
        double rz;
        final float[] rgba = new float[4];

        @AllowAllocation("an effect's particles, made when it starts")
        void reset(long id, EffectData data, Effects effects, int slot) {
            this.id = id;
            this.data = data;
            time = 0;
            cpu = false;
            if (data == null) {
                return;
            }
            List<EmitterData> emitters = data.emitters();
            particles = new Particles[emitters.size()];
            programs = new Program[emitters.size()];
            for (int e = 0; e < emitters.size(); e++) {
                EmitterData emitter = emitters.get(e);
                if (emitter.target() == Target.CPU) {
                    particles[e] = new Particles(emitter.capacity());
                    programs[e] = Program.of(data, emitter);
                    cpu = true;
                }
            }
            ox = effects.positionAt(slot, 0);
            oy = effects.positionAt(slot, 1);
            oz = effects.positionAt(slot, 2);
            px = ox;
            py = oy;
            pz = oz;
        }

        /** Takes the effect's position and rotation now, and world down in its frame. */
        void frame(Effects effects, int slot) {
            x = effects.positionAt(slot, 0);
            y = effects.positionAt(slot, 1);
            z = effects.positionAt(slot, 2);
            long effect = effects.effectAt(slot);
            inherit = effects.inheritVelocity(effect);
            qx = effects.rotation(effect, 0);
            qy = effects.rotation(effect, 1);
            qz = effects.rotation(effect, 2);
            qw = effects.rotation(effect, 3);
            turn(-qx, -qy, -qz, qw);
            rotate(0, -1, 0);
            downX = rx;
            downY = ry;
            downZ = rz;
            windWorldX = effects.wind(0);
            windWorldY = effects.wind(1);
            windWorldZ = effects.wind(2);
            rotate(windWorldX, windWorldY, windWorldZ);
            windX = rx;
            windY = ry;
            windZ = rz;
        }

        /** The unit axis ({@code x}, {@code y}, {@code z}) of the effect's frame in its particles' space. */
        void axis(double x, double y, double z, boolean local) {
            axis(x, y, z, local, true);
        }

        /**
         * The vector ({@code x}, {@code y}, {@code z}) of the effect's frame in its particles' space (turned by its
         * rotation for world-space ones), made unit length if {@code unit} (+Y if it has none): ux, uy, uz.
         */
        void axis(double x, double y, double z, boolean local, boolean unit) {
            if (!local) {
                double cx = 2 * (qy * z - qz * y);
                double cy = 2 * (qz * x - qx * z);
                double cz = 2 * (qx * y - qy * x);
                double tx = x + qw * cx + qy * cz - qz * cy;
                double ty = y + qw * cy + qz * cx - qx * cz;
                double tz = z + qw * cz + qx * cy - qy * cx;
                x = tx;
                y = ty;
                z = tz;
            }
            if (unit) {
                double length = Maths.sqrt(x * x + y * y + z * z);
                if (length > TINY) {
                    x /= length;
                    y /= length;
                    z /= length;
                } else {
                    x = 0;
                    y = 1;
                    z = 0;
                }
            }
            ux = x;
            uy = y;
            uz = z;
        }

        /** The part of ({@code x}, {@code y}, {@code z}) across the unit axis set: rx, ry, rz. */
        void across(double x, double y, double z) {
            double along = x * ux + y * uy + z * uz;
            rx = x - along * ux;
            ry = y - along * uy;
            rz = z - along * uz;
        }

        /** ({@code x}, {@code y}, {@code z}) turned about the unit axis set by an angle of that cosine and sine. */
        void turnAbout(double x, double y, double z, double cos, double sin) {
            double along = (x * ux + y * uy + z * uz) * (1 - cos);
            rx = x * cos + (uy * z - uz * y) * sin + ux * along;
            ry = y * cos + (uz * x - ux * z) * sin + uy * along;
            rz = z * cos + (ux * y - uy * x) * sin + uz * along;
        }

        /** A random unit direction, uniform on the sphere, from two draws from {@code salt} on. */
        void randomDirection(int seed, int salt) {
            float y = 1 - 2 * Hash.random(seed, salt);
            float r = (float) Maths.sqrt(Math.max(0, 1 - y * y));
            float turn = TAU * Hash.random(seed, salt + 1);
            direction(r * (float) Maths.cos(turn), y, r * (float) Maths.sin(turn));
        }

        void direction(float x, float y, float z) {
            dx = x;
            dy = y;
            dz = z;
        }

        /** Sets the rotation to rotate by. */
        void turn(double x, double y, double z, double w) {
            tx = x;
            ty = y;
            tz = z;
            tw = w;
        }

        /** Sets the rotation taking +Y to the unit normal ({@code nx}, {@code ny}, {@code nz}). */
        void facing(float nx, float ny, float nz) {
            double w = 1 + ny;
            if (w < 1e-6) {
                turn(1, 0, 0, 0); // straight down: half a turn about X
                return;
            }
            double length = Maths.sqrt(w * w + nz * nz + nx * nx);
            turn(nz / length, 0, -nx / length, w / length);
        }

        /** Rotates ({@code x}, {@code y}, {@code z}) by the rotation set, into rx, ry, rz. */
        void rotate(double x, double y, double z) {
            double cx = 2 * (ty * z - tz * y);
            double cy = 2 * (tz * x - tx * z);
            double cz = 2 * (tx * y - ty * x);
            rx = x + tw * cx + ty * cz - tz * cy;
            ry = y + tw * cy + tz * cx - tx * cz;
            rz = z + tw * cz + tx * cy - ty * cx;
        }
    }
}
