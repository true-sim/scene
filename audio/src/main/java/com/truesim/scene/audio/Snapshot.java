package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A change to the mix, started and stopped like a sound (a pause menu muffling the world): targets for bus volumes and
 * filters, faded in and out; several blend by intensity in priority order, as FMOD's snapshots do. Immutable, equal by
 * value; {@link #check} tests it against a layout.
 *
 * <pre>{@code
 * static final Snapshot PAUSE_MENU = Snapshot.builder("pause").set(Bus.SFX, Bus.VOLUME, -60)
 *     .set(Bus.MUSIC, Bus.LOW_PASS, 1_200).fadeIn(0.2).fadeOut(0.4).priority(10).build();
 * }</pre>
 *
 * @param name     its name
 * @param targets  what it sets
 * @param fadeIn   seconds to reach its targets
 * @param fadeOut  seconds to leave them
 * @param priority the order snapshots apply in, lowest first
 */
@AllowAllocation("set up once")
public record Snapshot(String name, List<Target> targets, double fadeIn, double fadeOut, int priority) {

    /**
     * A value a snapshot sets.
     *
     * @param bus      the bus
     * @param property what of it
     * @param value    decibels for volume, Hz for filters
     */
    public record Target(Bus bus, Bus.Property property, float value) {
        public Target {
            Objects.requireNonNull(bus, "bus");
            Objects.requireNonNull(property, "property");
        }
    }

    public Snapshot {
        Objects.requireNonNull(name, "name");
        targets = List.copyOf(targets);
    }

    /** A snapshot called {@code name}, to give targets. */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Throws, with every problem, if a target names a bus {@code layout} lacks. */
    public void check(BusLayout layout) {
        List<String> problems = new ArrayList<>();
        for (Target t : targets) {
            if (!layout.contains(t.bus())) {
                problems.add("It sets " + t.property() + " of " + t.bus() + ", which is not in the layout"
                    + Suggestions.didYouMean(t.bus().name(), layout.names()));
            }
        }
        InvalidAudioException.throwIf("snapshot " + name, problems);
    }

    /** Describes a snapshot. */
    public static final class Builder {

        private final String name;
        private final List<Target> targets = new ArrayList<>();
        private double fadeIn = 0.1;
        private double fadeOut = 0.1;
        private int priority;

        private Builder(String name) {
            this.name = name;
        }

        /** Sets {@code property} of {@code bus} to {@code value}: decibels for volume, Hz for filters. */
        public Builder set(Bus bus, Bus.Property property, double value) {
            targets.add(new Target(bus, property, (float) value));
            return this;
        }

        /** Reaches its targets in {@code seconds}. */
        public Builder fadeIn(double seconds) {
            fadeIn = seconds;
            return this;
        }

        /** Leaves its targets in {@code seconds}. */
        public Builder fadeOut(double seconds) {
            fadeOut = seconds;
            return this;
        }

        /** Applies after snapshots of lower priority. */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** The snapshot; throws with every problem found. */
        public Snapshot build() {
            List<String> problems = new ArrayList<>();
            if (!(fadeIn >= 0 && fadeOut >= 0) || Double.isInfinite(fadeIn + fadeOut)) {
                problems.add("Fades are seconds, not " + fadeIn + " and " + fadeOut);
            }
            for (int i = 0; i < targets.size(); i++) {
                Target t = targets.get(i);
                boolean volume = t.property() == Bus.Property.VOLUME;
                if (volume ? !(t.value() >= -80 && t.value() <= 24) : !(t.value() >= 10 && t.value() <= 24_000)) {
                    problems.add(t.property() + " of " + t.bus() + " cannot be " + t.value()
                        + (volume ? " (-80 to 24 dB)" : " (10 to 24,000 Hz)"));
                }
                for (int j = 0; j < i; j++) {
                    if (targets.get(j).bus().equals(t.bus()) && targets.get(j).property() == t.property()) {
                        problems.add(t.property() + " of " + t.bus() + " is set twice");
                    }
                }
            }
            InvalidAudioException.throwIf("snapshot " + name, problems);
            return new Snapshot(name, targets, fadeIn, fadeOut, priority);
        }
    }
}
