package com.truesim.scene.effects;

import java.util.Objects;

/**
 * How an emitter spawns particles: at a rate, in bursts at times of its loop, per metre it travels, or from the events
 * of other emitters. Amounts are bindings fixed for the instance at a time: constants, user parameters (or curves of
 * them) and random ranges (drawn once per loop). Immutable.
 *
 * <pre>{@code
 * e.spawn(Spawn.rate(40), Spawn.burst(0, Bind.param(INTENSITY).times(150)), Spawn.perDistance(Bind.constant(4)))
 * }</pre>
 *
 * @param kind how it spawns
 * @param time when in the loop a burst fires, in seconds from the loop's start
 * @param amount particles per second for a rate, per burst for a burst, per metre for a distance; none from events
 */
public record Spawn(Kind kind, double time, Binding amount) {

    /** How an emitter spawns. */
    public enum Kind {
        /** Particles per second, while the emitter runs. */
        RATE,
        /** Particles at once, at a time of each loop. */
        BURST,
        /** Particles per metre the emitter moves, while it runs. */
        DISTANCE,
        /** Particles other emitters' events ask for. */
        EVENTS
    }

    public Spawn {
        Objects.requireNonNull(kind, "kind");
        if (!(time >= 0) || time == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time in the loop: " + time);
        }
    }

    /** {@code perSecond} particles per second. */
    public static Spawn rate(Binding perSecond) {
        return new Spawn(Kind.RATE, 0, Objects.requireNonNull(perSecond, "perSecond"));
    }

    /** {@code perSecond} particles per second. */
    public static Spawn rate(float perSecond) {
        return rate(Bind.constant(perSecond));
    }

    /** {@code count} particles at {@code time} seconds into each loop. */
    public static Spawn burst(double time, Binding count) {
        return new Spawn(Kind.BURST, time, Objects.requireNonNull(count, "count"));
    }

    /** {@code count} particles at {@code time} seconds into each loop. */
    public static Spawn burst(double time, int count) {
        return burst(time, Bind.constant(count));
    }

    /** {@code perMetre} particles per metre the emitter moves: dust behind a wheel, a trail of footprints. */
    public static Spawn perDistance(Binding perMetre) {
        return new Spawn(Kind.DISTANCE, 0, Objects.requireNonNull(perMetre, "perMetre"));
    }

    /** Particles as other emitters' events ask ({@link ParticleEvent}): the emitter needs a capacity then. */
    public static Spawn fromEvents() {
        return new Spawn(Kind.EVENTS, 0, null);
    }
}
