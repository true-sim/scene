package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A budget class shared by many effects (E20), as Niagara's effect types are: how far its effects are seen, how many
 * may play at once and which matter most, what happens to those culled, levels of detail by distance, spawn scales by
 * quality, and the cell size of shared bursts. Immutable, compared by value.
 *
 * <pre>{@code
 * static final EffectType IMPACTS = EffectType.builder("impacts")
 *     .maxDistance(80).maxInstances(64).significance(EffectType.Significance.AGE).whenCulled(EffectType.Culled.KILL)
 *     .lod(30, 0.5f, 2, 0.5f).spawnScale(Quality.LOW, 0.25f).burstCell(64).build();
 * }</pre>
 *
 * @param name its name
 * @param maxDistance beyond it from every viewer, its effects are culled, in metres
 * @param maxInstances how many of its effects play unculled at once; the most significant win
 * @param significance which of its effects matter most
 * @param whenCulled what happens to an effect while culled
 * @param priority types of higher priority keep their budget first
 * @param lods levels of detail beyond level 0 (full detail), by increasing distance
 * @param hysteresis how much further out than a distance an effect must be to go coarser (or culled), as a fraction
 * @param spawnScales the spawn scale per {@link Quality}, by its ordinal
 * @param burstCell the cell size of shared bursts, in metres: bursts in one cell share an instance
 */
public record EffectType(String name, double maxDistance, int maxInstances, Significance significance,
        Culled whenCulled, int priority, List<Lod> lods, double hysteresis, List<Float> spawnScales,
        double burstCell) {

    /** Which effects of a type matter most when its cap is hit. */
    public enum Significance {
        /** The nearest to a viewer. */
        DISTANCE,
        /** The youngest: new impacts replace old ones. */
        AGE
    }

    /** What happens to an effect while culled. */
    public enum Culled {
        /** It ends at once. */
        KILL,
        /** It stops in time: nothing spawns or moves until it comes back. */
        PAUSE,
        /** It simulates on, undrawn: a fire that is burning when looked at again. */
        SIMULATE
    }

    /**
     * A level of detail, from a distance on.
     *
     * @param distance from this distance to the nearest viewer on, in metres
     * @param spawnScale what spawn counts are multiplied by
     * @param updateEvery the simulation updates every this many frames, adding up the time between
     * @param drawnFraction the fraction of particles drawn
     */
    public record Lod(double distance, float spawnScale, int updateEvery, float drawnFraction) {
        public Lod {
            if (!(distance > 0) || !(spawnScale >= 0 && spawnScale <= 1) || updateEvery < 1
                || !(drawnFraction > 0 && drawnFraction <= 1)) {
                throw new IllegalArgumentException("Not a level of detail: " + distance + " m, spawn scale "
                    + spawnScale + ", every " + updateEvery + ", drawn " + drawnFraction);
            }
        }
    }

    /** Level 0: full detail. */
    public static final Lod FULL = new Lod(Double.MIN_VALUE, 1, 1, 1);

    /** The type of effects that name none: seen at any distance, uncapped, by distance, paused when culled. */
    public static final EffectType DEFAULT = builder("default").build();

    @AllowAllocation("effect types are made once")
    public EffectType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(significance, "significance");
        Objects.requireNonNull(whenCulled, "whenCulled");
        lods = List.copyOf(lods);
        spawnScales = List.copyOf(spawnScales);
        if (!(maxDistance > 0) || maxInstances < 0 || !(hysteresis >= 0) || !(burstCell > 0)
            || spawnScales.size() != Quality.values().length) {
            throw new IllegalArgumentException("Not an effect type: " + name);
        }
        for (float spawnScale : spawnScales) {
            if (!(spawnScale >= 0 && spawnScale <= 1)) {
                throw new IllegalArgumentException(name + ": spawn scales are 0 to 1, not " + spawnScale);
            }
        }
        for (int i = 1; i < lods.size(); i++) {
            if (!(lods.get(i).distance() > lods.get(i - 1).distance())) {
                throw new IllegalArgumentException(name + ": levels of detail by increasing distance");
            }
        }
    }

    /** Sets up an effect type called {@code name}. */
    @AllowAllocation("effect types are made once")
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Level of detail {@code level}: 0 is {@link #FULL}, the others as given. */
    public Lod lod(int level) {
        return level == 0 ? FULL : lods.get(level - 1);
    }

    /** How many levels of detail it has, full detail among them. */
    public int lodCount() {
        return lods.size() + 1;
    }

    /** The spawn scale at {@code quality}. */
    public float spawnScale(Quality quality) {
        return spawnScales.get(quality.ordinal());
    }

    /** Sets up an {@link EffectType}. */
    @AllowAllocation("effect types are made once")
    public static final class Builder {
        private final String name;
        private double maxDistance = Double.POSITIVE_INFINITY;
        private int maxInstances = Integer.MAX_VALUE;
        private Significance significance = Significance.DISTANCE;
        private Culled whenCulled = Culled.PAUSE;
        private int priority;
        private final List<Lod> lods = new ArrayList<>();
        private double hysteresis = 0.1;
        private final Float[] spawnScales = {1f, 1f, 1f, 1f};
        private double burstCell = 64;

        private Builder(String name) {
            this.name = name;
        }

        /** Culled beyond {@code metres} from every viewer; unlimited by default. */
        public Builder maxDistance(double metres) {
            maxDistance = metres;
            return this;
        }

        /** At most {@code count} effects of the type unculled at once; unlimited by default. */
        public Builder maxInstances(int count) {
            maxInstances = count;
            return this;
        }

        /** Which effects matter most: the nearest by default. */
        public Builder significance(Significance significance) {
            this.significance = significance;
            return this;
        }

        /** What happens to culled effects: paused by default. */
        public Builder whenCulled(Culled whenCulled) {
            this.whenCulled = whenCulled;
            return this;
        }

        /** Types of higher priority keep their budget first; 0 by default. */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** A level of detail from {@code distance} metres on; levels are added by increasing distance. */
        public Builder lod(double distance, float spawnScale, int updateEvery, float drawnFraction) {
            lods.add(new Lod(distance, spawnScale, updateEvery, drawnFraction));
            return this;
        }

        /** How much further out an effect must be to go coarser or be culled, as a fraction; 0.1 by default. */
        public Builder hysteresis(double fraction) {
            hysteresis = fraction;
            return this;
        }

        /** Spawn counts at {@code quality} times {@code scale}; 1 by default. */
        public Builder spawnScale(Quality quality, float scale) {
            spawnScales[quality.ordinal()] = scale;
            return this;
        }

        /** Shared bursts within cells of {@code metres}; 64 by default. */
        public Builder burstCell(double metres) {
            burstCell = metres;
            return this;
        }

        public EffectType build() {
            return new EffectType(name, maxDistance, maxInstances, significance, whenCulled, priority, lods, hysteresis,
                List.of(spawnScales), burstCell);
        }
    }
}
