package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An emitter of an effect, as data: where it simulates and in which frame, its timing, how it spawns, its
 * initializers and updaters (modules by name), the events its particles send, and how it is drawn. Made through
 * {@link EffectData.Builder#emitter}, which checks it against the effect and the module registry and settles its
 * capacity. Immutable, compared by value.
 *
 * <p>Timing: the emitter starts {@link #delay()} seconds after its effect, then runs {@link #loops()} loops of
 * {@link #duration()} seconds (0 loops: forever), {@link #loopDelay()} seconds apart; an infinite duration runs until
 * the effect stops. {@link #prewarm()} starts it that many seconds into its timeline, so a looping fire starts full.
 */
@AllowAllocation("emitter data is made when effects are set up")
public final class EmitterData {

    /** How long particles live without an {@link Init#lifetime} module, in seconds. */
    public static final float DEFAULT_LIFETIME = 1;

    private final String name;
    private final Target target;
    private final Space space;
    private final double delay;
    private final double duration;
    private final int loops;
    private final double loopDelay;
    private final double prewarm;
    private final List<Spawn> spawns;
    private final int authoredCapacity;
    private final List<Module> init;
    private final List<Module> update;
    private final List<ParticleEvent> events;
    private final List<Renderer> renderers;
    private final Quality minimumQuality;
    private final List<Object> key;
    // Settled by the effect.
    private final int capacity;
    private final float maxLifetime;
    private final Capability capability;

    EmitterData(Builder b, int capacity, float maxLifetime, Capability capability) {
        name = b.name;
        target = b.target;
        space = b.space;
        delay = b.delay;
        duration = b.duration;
        loops = b.loops;
        loopDelay = b.loopDelay;
        prewarm = b.prewarm;
        spawns = List.copyOf(b.spawns);
        authoredCapacity = b.capacity;
        init = List.copyOf(b.init);
        update = List.copyOf(b.update);
        events = List.copyOf(b.events);
        renderers = List.copyOf(b.renderers);
        minimumQuality = b.minimumQuality;
        this.capacity = capacity;
        this.maxLifetime = maxLifetime;
        this.capability = capability;
        key = List.of(name, target, space, delay, duration, loops, loopDelay, prewarm, spawns, authoredCapacity, init,
            update, events, renderers, minimumQuality);
    }

    /** Its name, unique in its effect. */
    public String name() {
        return name;
    }

    /** Where its particles simulate. */
    public Target target() {
        return target;
    }

    /** The frame its particles live in. */
    public Space space() {
        return space;
    }

    /** Seconds after its effect starts before its first loop. */
    public double delay() {
        return delay;
    }

    /** Seconds per loop; infinite for an emitter running until its effect stops. */
    public double duration() {
        return duration;
    }

    /** How many loops it runs; 0 for forever. */
    public int loops() {
        return loops;
    }

    /** Seconds between loops. */
    public double loopDelay() {
        return loopDelay;
    }

    /** Seconds into its timeline it starts at. */
    public double prewarm() {
        return prewarm;
    }

    /** How it spawns particles. */
    public List<Spawn> spawns() {
        return spawns;
    }

    /** Whether it spawns {@code kind}. */
    public boolean spawns(Spawn.Kind kind) {
        for (Spawn spawn : spawns) {
            if (spawn.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /** The most particles it holds at once: as authored, else estimated from its rates, bursts and lifetimes. */
    public int capacity() {
        return capacity;
    }

    /** The capacity as authored, 0 for estimated. */
    public int authoredCapacity() {
        return authoredCapacity;
    }

    /** Its initializers, run in order at each particle's birth. */
    public List<Module> init() {
        return init;
    }

    /** Its updaters, run in order on every particle every frame. */
    public List<Module> update() {
        return update;
    }

    /** The events its particles send. */
    public List<ParticleEvent> events() {
        return events;
    }

    /** How it is drawn. */
    public List<Renderer> renderers() {
        return renderers;
    }

    /** The least quality it runs at: below it the emitter spawns nothing. */
    public Quality minimumQuality() {
        return minimumQuality;
    }

    /** The longest its particles live, in seconds, with parameters at their defaults. */
    public float maxLifetime() {
        return maxLifetime;
    }

    /** What its modules need of the device: the kernel variant it runs in. */
    public Capability capability() {
        return capability;
    }

    /** Seconds a loop and the delay after it take. */
    public double period() {
        return duration + loopDelay;
    }

    /**
     * When it stops spawning by its timing, in seconds after its effect starts (prewarm counted): infinite for an
     * emitter that loops forever or runs until stopped.
     */
    public double spawnEnd() {
        if (duration == Double.POSITIVE_INFINITY || loops == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return delay + loops * duration + (loops - 1) * loopDelay - prewarm;
    }

    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof EmitterData e && key.equals(e.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "emitter " + name + " (" + target + ", " + space + ")";
    }

    /** Sets up an {@link EmitterData}; {@link EffectData.Builder#emitter} hands one out. */
    @AllowAllocation("emitter data is made when effects are set up")
    public static final class Builder {
        final String name;
        private Target target = Target.GPU;
        private Space space = Space.WORLD;
        private double delay;
        private double duration = Double.POSITIVE_INFINITY;
        private int loops = 1;
        private double loopDelay;
        private double prewarm;
        private final List<Spawn> spawns = new ArrayList<>();
        private int capacity;
        private final List<Module> init = new ArrayList<>();
        private final List<Module> update = new ArrayList<>();
        private final List<ParticleEvent> events = new ArrayList<>();
        private final List<Renderer> renderers = new ArrayList<>();
        private Quality minimumQuality = Quality.LOW;

        Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** A builder of an emitter called {@code name} starting from {@code template}'s settings. */
        Builder(String name, EmitterData template) {
            this(name);
            target = template.target;
            space = template.space;
            delay = template.delay;
            duration = template.duration;
            loops = template.loops;
            loopDelay = template.loopDelay;
            prewarm = template.prewarm;
            spawns.addAll(template.spawns);
            capacity = template.authoredCapacity;
            init.addAll(template.init);
            update.addAll(template.update);
            events.addAll(template.events);
            renderers.addAll(template.renderers);
            minimumQuality = template.minimumQuality;
        }

        /** Where its particles simulate: the GPU by default. */
        public Builder target(Target target) {
            this.target = Objects.requireNonNull(target, "target");
            return this;
        }

        /** The frame its particles live in: the world by default. */
        public Builder space(Space space) {
            this.space = Objects.requireNonNull(space, "space");
            return this;
        }

        /** Starts {@code seconds} after its effect; 0 by default. */
        public Builder delay(double seconds) {
            delay = time(seconds, "delay");
            return this;
        }

        /** Loops last {@code seconds}; infinite by default: running until the effect stops. */
        public Builder duration(double seconds) {
            if (!(seconds > 0)) {
                throw new IllegalArgumentException("Not a duration: " + seconds);
            }
            duration = seconds;
            return this;
        }

        /** Runs {@code count} loops, 0 for forever; 1 by default. */
        public Builder loops(int count) {
            if (count < 0) {
                throw new IllegalArgumentException("Not a loop count: " + count);
            }
            loops = count;
            return this;
        }

        /** Waits {@code seconds} between loops; 0 by default. */
        public Builder loopDelay(double seconds) {
            loopDelay = time(seconds, "loop delay");
            return this;
        }

        /** Starts {@code seconds} into its timeline; 0 by default. */
        public Builder prewarm(double seconds) {
            prewarm = time(seconds, "prewarm");
            return this;
        }

        private static double time(double seconds, String what) {
            if (!(seconds >= 0) || seconds == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("Not a " + what + ": " + seconds);
            }
            return seconds;
        }

        /** Spawns as {@code spawns} say, added to how it spawned. */
        public Builder spawn(Spawn... spawns) {
            Collections.addAll(this.spawns, spawns);
            return this;
        }

        /** Holds at most {@code particles} at once; estimated from rates, bursts and lifetimes by default. */
        public Builder capacity(int particles) {
            if (particles < 1) {
                throw new IllegalArgumentException("Not a capacity: " + particles);
            }
            capacity = particles;
            return this;
        }

        /** Initializers, added after those it had. */
        public Builder init(Module... modules) {
            Collections.addAll(init, modules);
            return this;
        }

        /** Updaters, added after those it had. */
        public Builder update(Module... modules) {
            Collections.addAll(update, modules);
            return this;
        }

        /**
         * A collision chain ({@link Collide}), added after the updaters it had: the first of its methods the target and
         * the device have collides.
         */
        public Builder collide(Collide.Collision collision) {
            update.addAll(collision.modules());
            return this;
        }

        /** Events its particles send, added to those it had. */
        public Builder event(ParticleEvent... events) {
            Collections.addAll(this.events, events);
            return this;
        }

        /** Renderers, added to those it had. */
        public Builder render(Renderer... renderers) {
            Collections.addAll(this.renderers, renderers);
            return this;
        }

        /** Spawns nothing below {@code quality}; LOW by default. */
        public Builder minimumQuality(Quality quality) {
            minimumQuality = Objects.requireNonNull(quality, "quality");
            return this;
        }

        List<Spawn> spawns() {
            return spawns;
        }

        boolean spawnsFromEvents() {
            for (Spawn spawn : spawns) {
                if (spawn.kind() == Spawn.Kind.EVENTS) {
                    return true;
                }
            }
            return false;
        }

        List<Module> initializers() {
            return init;
        }

        List<Module> updaters() {
            return update;
        }

        List<ParticleEvent> events() {
            return events;
        }

        Target target() {
            return target;
        }

        int capacity() {
            return capacity;
        }

        double duration() {
            return duration;
        }

        int loops() {
            return loops;
        }

        double period() {
            return duration + loopDelay;
        }
    }
}
