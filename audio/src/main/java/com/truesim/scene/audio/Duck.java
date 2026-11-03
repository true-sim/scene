package com.truesim.scene.audio;

/**
 * How far and how fast a bus is lowered while a sidechain bus plays ("dialogue ducks the music by 9 dB"): an envelope
 * follower on the sidechain drives the ducked bus's gain.
 *
 * <pre>{@code
 * .duck(MUSIC, VOICE, Duck.by(-9).attack(0.05).release(0.6))
 * }</pre>
 *
 * @param db      the gain at full duck, decibels (negative)
 * @param attack  seconds to duck
 * @param release seconds to recover
 */
public record Duck(float db, double attack, double release) {

    public Duck {
        if (!(db < 0) || Float.isInfinite(db)) {
            throw new IllegalArgumentException("A duck lowers by some decibels: " + db);
        }
        if (!(attack >= 0 && release >= 0) || Double.isInfinite(attack + release)) {
            throw new IllegalArgumentException("Not attack and release times: " + attack + ", " + release);
        }
    }

    /** Lowers by {@code db} decibels, in 50 ms, recovering in 500 ms. */
    public static Duck by(double db) {
        return new Duck((float) db, 0.05, 0.5);
    }

    /** A copy ducking in {@code seconds}. */
    public Duck attack(double seconds) {
        return new Duck(db, seconds, release);
    }

    /** A copy recovering in {@code seconds}. */
    public Duck release(double seconds) {
        return new Duck(db, attack, seconds);
    }
}
