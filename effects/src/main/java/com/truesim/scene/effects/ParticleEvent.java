package com.truesim.scene.effects;

import java.util.Objects;

/**
 * An event an emitter's particles send, spawning particles of another emitter of the same effect, which reads the
 * sender's attributes at the spawn (E9): sparks bursting into embers on death, a trail emitted by every spark. The
 * target emitter spawns {@link Spawn#fromEvents()}. Events nest a frame per level; cycles are refused. Immutable.
 *
 * <pre>{@code
 * e.event(ParticleEvent.onDeath("embers", Bind.constant(3)).chance(0.3f))
 * }</pre>
 *
 * @param kind when it is sent
 * @param target the name of the emitter it spawns
 * @param count how many particles each event spawns (per second, for {@link Kind#EMIT})
 * @param chance the chance that an event is sent at all, 0 to 1
 * @param interval for {@link Kind#PERIODIC}, the seconds between events
 */
public record ParticleEvent(Kind kind, String target, Binding count, float chance, double interval) {

    /** When a particle sends an event. */
    public enum Kind {
        /** At its birth. */
        BIRTH,
        /** At its death. */
        DEATH,
        /** When it collides. */
        COLLISION,
        /** Every interval of its life. */
        PERIODIC,
        /** Every frame, spawning its count per second: particles as emitters (E2). */
        EMIT
    }

    public ParticleEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(count, "count");
        if (!(chance >= 0 && chance <= 1)) {
            throw new IllegalArgumentException("Not a chance: " + chance);
        }
        if (kind == Kind.PERIODIC && !(interval > 0)) {
            throw new IllegalArgumentException("Not an interval: " + interval);
        }
    }

    /** {@code count} particles of {@code target} at each particle's birth. */
    public static ParticleEvent onBirth(String target, Binding count) {
        return new ParticleEvent(Kind.BIRTH, target, count, 1, 0);
    }

    /** {@code count} particles of {@code target} at each particle's death. */
    public static ParticleEvent onDeath(String target, Binding count) {
        return new ParticleEvent(Kind.DEATH, target, count, 1, 0);
    }

    /** {@code count} particles of {@code target} at each collision. */
    public static ParticleEvent onCollision(String target, Binding count) {
        return new ParticleEvent(Kind.COLLISION, target, count, 1, 0);
    }

    /** {@code count} particles of {@code target} every {@code seconds} of each particle's life. */
    public static ParticleEvent every(double seconds, String target, Binding count) {
        return new ParticleEvent(Kind.PERIODIC, target, count, 1, seconds);
    }

    /** {@code perSecond} particles of {@code target} per second from each particle, every frame. */
    public static ParticleEvent emit(String target, Binding perSecond) {
        return new ParticleEvent(Kind.EMIT, target, perSecond, 1, 0);
    }

    /** This event, sent with chance {@code chance} only. */
    public ParticleEvent chance(float chance) {
        return new ParticleEvent(kind, target, count, chance, interval);
    }
}
