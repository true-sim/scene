package com.truesim.scene.audio;

import java.util.Objects;

/**
 * How many instances of a sound (or of a named group of sounds) may play at once, and which gives way at the cap.
 * Applied when instances start; a stolen instance fades out over 10 ms.
 *
 * <pre>{@code
 * .concurrency(Concurrency.max(12).steal(Steal.FARTHEST))
 * .concurrency(Concurrency.max(4).group("impacts").retrigger(0.05))
 * }</pre>
 *
 * @param group     the group shared by sounds naming it; {@code null} for the sound's own
 * @param max       instances at once
 * @param steal     which instance gives way at the cap
 * @param retrigger seconds within which a new start of the group is dropped
 */
public record Concurrency(String group, int max, Steal steal, double retrigger) {

    /** Which instance gives way when a group is full. */
    public enum Steal {
        /** The one playing longest. */
        OLDEST,
        /** The quietest (by its gain at its listener when started). */
        QUIETEST,
        /** The farthest from the listener. */
        FARTHEST,
        /** The one of lowest priority, the oldest among equals. */
        LOWEST_PRIORITY,
        /** None: the new one does not start. */
        REJECT_NEW
    }

    /** No cap. */
    public static final Concurrency UNLIMITED = new Concurrency(null, Integer.MAX_VALUE, Steal.OLDEST, 0);

    public Concurrency {
        Objects.requireNonNull(steal, "steal");
        if (max < 1) {
            throw new IllegalArgumentException("A group plays at least one: " + max);
        }
        if (!(retrigger >= 0)) {
            throw new IllegalArgumentException("Not a retrigger time: " + retrigger);
        }
    }

    /** At most {@code max} at once, stealing the oldest. */
    public static Concurrency max(int max) {
        return new Concurrency(null, max, Steal.OLDEST, 0);
    }

    /** A copy giving way as {@code steal} says. */
    public Concurrency steal(Steal steal) {
        return new Concurrency(group, max, steal, retrigger);
    }

    /** A copy shared by the sounds naming {@code group}. */
    public Concurrency group(String group) {
        return new Concurrency(Objects.requireNonNull(group, "group"), max, steal, retrigger);
    }

    /** A copy dropping starts within {@code seconds} of the group's last. */
    public Concurrency retrigger(double seconds) {
        return new Concurrency(group, max, steal, seconds);
    }
}
