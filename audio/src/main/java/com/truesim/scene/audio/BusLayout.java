package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The mixer as data: its buses with their volume, mute, effect chain and sends, and the ducks between them. Built once,
 * checked as a whole ({@link InvalidAudioException} lists every problem), immutable and equal by value. The buses come
 * in processing order: every bus after all buses feeding it (its children and the buses sending to it), the master
 * last.
 *
 * <pre>{@code
 * BusLayout mix = BusLayout.builder()
 *     .bus(Bus.MUSIC, b -> b.volume(-4))
 *     .bus(Bus.SFX).bus(FOOTSTEPS, b -> b.send(REVERB, -6))
 *     .aux(REVERB, b -> b.effect(AudioEffect.named("reverb").set("size", 0.6)))
 *     .duck(Bus.MUSIC, Bus.VOICE, Duck.by(-9).attack(0.05).release(0.6))
 *     .build();
 * }</pre>
 */
@AllowAllocation("set up once")
public final class BusLayout {

    /**
     * A send from a bus to an aux bus.
     *
     * @param aux       the aux bus fed
     * @param db        the send's level, decibels
     * @param preFader  whether it is taken before the bus's volume
     */
    public record Send(Bus aux, float db, boolean preFader) {
        public Send {
            Objects.requireNonNull(aux, "aux");
        }
    }

    /**
     * A duck: {@code ducked} lowered while {@code sidechain} plays.
     *
     * @param ducked    the bus lowered
     * @param sidechain the bus listened to
     * @param duck      how far and how fast
     */
    public record Ducking(Bus ducked, Bus sidechain, Duck duck) {
        public Ducking {
            Objects.requireNonNull(ducked, "ducked");
            Objects.requireNonNull(sidechain, "sidechain");
            Objects.requireNonNull(duck, "duck");
        }
    }

    /**
     * What one bus does.
     *
     * @param bus          the bus
     * @param volume       its volume, decibels
     * @param mute         whether it is muted
     * @param effects      its effect chain, in order
     * @param sends        its sends to aux buses
     * @param ignoresPause whether its sounds keep playing while their world is paused
     */
    public record Settings(Bus bus, float volume, boolean mute, List<AudioEffect> effects, List<Send> sends,
            boolean ignoresPause) {
        public Settings {
            Objects.requireNonNull(bus, "bus");
            effects = List.copyOf(effects);
            sends = List.copyOf(sends);
        }
    }

    /** The master, sfx, music, voice, ui and ambience buses at 0 dB, the ui bus ignoring pause. */
    public static final BusLayout DEFAULT = builder().bus(Bus.SFX).bus(Bus.MUSIC).bus(Bus.VOICE)
        .bus(Bus.UI, Builder.BusBuilder::ignoresPause).bus(Bus.AMBIENCE).build();

    private final List<Settings> buses;
    private final List<Ducking> ducks;
    private final Map<Bus, Integer> index = new HashMap<>();

    private BusLayout(List<Settings> buses, List<Ducking> ducks) {
        this.buses = List.copyOf(buses);
        this.ducks = List.copyOf(ducks);
        for (int i = 0; i < buses.size(); i++) {
            index.put(buses.get(i).bus(), i);
        }
    }

    /** A layout to describe; the master is in every layout. */
    public static Builder builder() {
        return new Builder();
    }

    /** The buses in processing order, the master last. */
    public List<Settings> buses() {
        return buses;
    }

    public List<Ducking> ducks() {
        return ducks;
    }

    /** Where {@code bus} is in {@link #buses()}, or -1 if the layout lacks it. */
    public int indexOf(Bus bus) {
        return index.getOrDefault(bus, -1);
    }

    /** Whether the layout has {@code bus}. */
    public boolean contains(Bus bus) {
        return index.containsKey(bus);
    }

    /** The names of its buses, for suggestions. */
    List<String> names() {
        List<String> names = new ArrayList<>();
        for (Settings s : buses) {
            names.add(s.bus().name());
        }
        return names;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BusLayout l && buses.equals(l.buses) && ducks.equals(l.ducks);
    }

    @Override
    public int hashCode() {
        return 31 * buses.hashCode() + ducks.hashCode();
    }

    @Override
    public String toString() {
        return "bus layout " + names();
    }

    /** Describes a layout. */
    public static final class Builder {

        private final Map<String, Settings> declared = new LinkedHashMap<>();
        private final List<Ducking> ducks = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        private Builder() {
        }

        /** Adds {@code bus} at 0 dB with no effects or sends. */
        public Builder bus(Bus bus) {
            return bus(bus, b -> { });
        }

        /** Adds {@code bus}, as {@code setup} describes it. */
        public Builder bus(Bus bus, Consumer<BusBuilder> setup) {
            if (bus.aux()) {
                problems.add("Bus " + bus + " is an aux bus: add it with aux()");
            }
            return add(bus, setup);
        }

        /** Adds aux bus {@code bus} (made with {@link Bus#aux}), as {@code setup} describes it. */
        public Builder aux(Bus bus, Consumer<BusBuilder> setup) {
            if (!bus.aux()) {
                problems.add("Bus " + bus + " is not an aux bus: make it with Bus.aux()");
            }
            return add(bus, setup);
        }

        private Builder add(Bus bus, Consumer<BusBuilder> setup) {
            BusBuilder b = new BusBuilder(bus);
            setup.accept(b);
            if (declared.putIfAbsent(bus.name(), b.build()) != null) {
                problems.add("Bus " + bus + " is in the layout twice");
            }
            return this;
        }

        /** Lowers {@code ducked} while {@code sidechain} plays, as {@code duck} says. */
        public Builder duck(Bus ducked, Bus sidechain, Duck duck) {
            ducks.add(new Ducking(ducked, sidechain, duck));
            return this;
        }

        /** The layout, its effects checked against the built-in ones. */
        public BusLayout build() {
            return build(AudioEffectTypes.builtIn());
        }

        /** The layout, its effects checked against {@code types}; throws with every problem found. */
        public BusLayout build(AudioEffectTypes types) {
            List<String> found = new ArrayList<>(problems);
            declared.putIfAbsent(Bus.MASTER.name(), new BusBuilder(Bus.MASTER).build());
            for (Settings s : declared.values()) {
                Bus parent = s.bus().parent();
                if (parent != null) {
                    known(parent, "Bus " + s.bus() + " sums into", found);
                }
                for (AudioEffect effect : s.effects()) {
                    types.check(effect, "Bus " + s.bus(), found);
                }
                for (Send send : s.sends()) {
                    known(send.aux(), "Bus " + s.bus() + " sends to", found);
                    if (!send.aux().aux()) {
                        found.add("Bus " + s.bus() + " sends to " + send.aux() + ", which is not an aux bus");
                    }
                }
            }
            for (Ducking d : ducks) {
                known(d.ducked(), "A duck lowers", found);
                known(d.sidechain(), "A duck listens to", found);
                if (d.ducked().equals(d.sidechain())) {
                    found.add("Bus " + d.ducked() + " cannot duck itself");
                }
            }
            List<Settings> order = order(found);
            InvalidAudioException.throwIf("bus layout", found);
            return new BusLayout(order, ducks);
        }

        private void known(Bus bus, String what, List<String> found) {
            Settings s = declared.get(bus.name());
            if (s == null) {
                found.add(what + " " + bus + ", which is not in the layout"
                    + Suggestions.didYouMean(bus.name(), declared.keySet()));
            } else if (!s.bus().equals(bus)) {
                found.add(what + " " + bus + ", which the layout has with another parent or kind");
            }
        }

        /** The buses with every bus after those feeding it; buses in a loop of sends are reported. */
        private List<Settings> order(List<String> found) {
            Map<String, Integer> feeders = new HashMap<>();
            for (String name : declared.keySet()) {
                feeders.put(name, 0);
            }
            for (Settings s : declared.values()) {
                for (String target : targets(s)) {
                    feeders.merge(target, 1, Integer::sum);
                }
            }
            List<Settings> order = new ArrayList<>();
            List<Settings> left = new ArrayList<>(declared.values());
            boolean progress = true;
            while (!left.isEmpty() && progress) {
                progress = false;
                for (int i = 0; i < left.size(); i++) {
                    Settings s = left.get(i);
                    if (feeders.get(s.bus().name()) == 0) {
                        left.remove(i--);
                        order.add(s);
                        progress = true;
                        for (String target : targets(s)) {
                            feeders.merge(target, -1, Integer::sum);
                        }
                    }
                }
            }
            if (!left.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Settings s : left) {
                    if (!s.sends().isEmpty()) { // the buses downstream of the loop wait too, but are not in it
                        names.add(s.bus().name());
                    }
                }
                found.add("Sends make a loop through " + String.join(", ", names));
            }
            return order;
        }

        /** The buses {@code s} feeds that are in the layout: its parent and its sends' targets. */
        private List<String> targets(Settings s) {
            List<String> targets = new ArrayList<>();
            if (s.bus().parent() != null && declared.containsKey(s.bus().parent().name())) {
                targets.add(s.bus().parent().name());
            }
            for (Send send : s.sends()) {
                if (declared.containsKey(send.aux().name())) {
                    targets.add(send.aux().name());
                }
            }
            return targets;
        }

        /** Describes one bus. */
        public static final class BusBuilder {

            private final Bus bus;
            private float volume;
            private boolean mute;
            private final List<AudioEffect> effects = new ArrayList<>();
            private final List<Send> sends = new ArrayList<>();
            private boolean ignoresPause;

            private BusBuilder(Bus bus) {
                this.bus = bus;
            }

            /** Its volume, decibels. */
            public BusBuilder volume(double db) {
                volume = (float) db;
                return this;
            }

            /** Mutes it. */
            public BusBuilder mute() {
                mute = true;
                return this;
            }

            /** Adds {@code effect} to the end of its chain. */
            public BusBuilder effect(AudioEffect effect) {
                effects.add(Objects.requireNonNull(effect, "effect"));
                return this;
            }

            /** Sends to {@code aux} at {@code db} decibels, after its volume. */
            public BusBuilder send(Bus aux, double db) {
                sends.add(new Send(aux, (float) db, false));
                return this;
            }

            /** Sends to {@code aux} at {@code db} decibels, before its volume. */
            public BusBuilder sendPreFader(Bus aux, double db) {
                sends.add(new Send(aux, (float) db, true));
                return this;
            }

            /** Keeps its sounds playing while their world is paused. */
            public BusBuilder ignoresPause() {
                ignoresPause = true;
                return this;
            }

            private Settings build() {
                return new Settings(bus, volume, mute, effects, sends, ignoresPause);
            }
        }
    }
}
