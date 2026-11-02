package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A bus of the mixer, by name: sounds play into one, buses sum into their parent, and aux buses (reverbs) take sends.
 * Declared once as a constant; equal by value, so a game's {@code Bus.of("sfx")} is {@link #SFX}. What a bus does
 * (volume, effects, sends) is in a {@link BusLayout}.
 *
 * <pre>{@code
 * static final Bus FOOTSTEPS = Bus.child(Bus.SFX, "footsteps"), REVERB = Bus.aux("reverb");
 * }</pre>
 *
 * @param name   its name, unique in a layout
 * @param parent the bus it sums into; {@code null} for the master
 * @param aux    whether it is an aux bus, fed by sends
 */
public record Bus(String name, Bus parent, boolean aux) {

    /** What a snapshot changes on a bus. */
    public enum Property {
        /** Its volume, decibels (−80 to +24). */
        VOLUME,
        /** A low-pass cutoff, Hz (10 to 24,000). */
        LOW_PASS,
        /** A high-pass cutoff, Hz (10 to 24,000). */
        HIGH_PASS
    }

    /** The master bus: every bus ends in it. */
    public static final Bus MASTER = new Bus("master", null, false);
    /** Sound effects: where world sounds play unless they say otherwise. */
    public static final Bus SFX = of("sfx");
    /** Music. */
    public static final Bus MUSIC = of("music");
    /** Dialogue. */
    public static final Bus VOICE = of("voice");
    /** The user interface: never paused with a world. */
    public static final Bus UI = of("ui");
    /** Ambience beds. */
    public static final Bus AMBIENCE = of("ambience");

    /** A bus's volume, for snapshots. */
    public static final Property VOLUME = Property.VOLUME;
    /** A bus's low-pass cutoff, for snapshots. */
    public static final Property LOW_PASS = Property.LOW_PASS;
    /** A bus's high-pass cutoff, for snapshots. */
    public static final Property HIGH_PASS = Property.HIGH_PASS;

    public Bus {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A bus has a name");
        }
    }

    /** A bus called {@code name} summing into the master. */
    @AllowAllocation("set up once")
    public static Bus of(String name) {
        return new Bus(name, MASTER, false);
    }

    /** A bus called {@code name} summing into {@code parent}. */
    @AllowAllocation("set up once")
    public static Bus child(Bus parent, String name) {
        return new Bus(name, Objects.requireNonNull(parent, "parent"), false);
    }

    /** An aux bus called {@code name} (a reverb, say) summing into the master, fed by sends. */
    @AllowAllocation("set up once")
    public static Bus aux(String name) {
        return new Bus(name, MASTER, true);
    }

    @Override
    @AllowAllocation("formatting")
    public String toString() {
        return name;
    }
}
