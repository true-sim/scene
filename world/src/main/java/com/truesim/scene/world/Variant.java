package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A named set of feature states, e.g. a racetrack's layouts or event scenarios: the same world and its shared content,
 * with the features that differ. A variant can build on another one, so a base holds what most share and each variant
 * only its differences. Immutable; select one with {@link Features#select}.
 *
 * <pre>{@code
 * static final Variant BASE = Variant.builder("base")
 *     .enable(BARRIERS, BarrierStyle.TYRES)
 *     .build();
 * static final Variant GRAND_PRIX = Variant.builder("grand-prix").basedOn(BASE)
 *     .enable(CHICANE, Chicane.TIGHT)
 *     .enable(LONG_PIT_LANE)
 *     .build();
 * static final Variant NIGHT_RACE = Variant.builder("night-race").basedOn(GRAND_PRIX)
 *     .enable(NIGHT_LIGHTS)
 *     .build();
 * }</pre>
 */
public final class Variant implements FeatureView {

    /** Every feature off, with its default. */
    public static final Variant NONE = new Variant("none", null, FeatureState.DEFAULTS);

    private final String name;
    private final Variant base;
    private final FeatureState state;

    private Variant(String name, Variant base, FeatureState state) {
        this.name = name;
        this.base = base;
        this.state = state;
    }

    /** Starts a variant called {@code name}, on top of {@link #NONE} unless {@link Builder#basedOn} says otherwise. */
    @AllowAllocation("declaring a variant")
    public static Builder builder(String name) {
        return new Builder(Objects.requireNonNull(name, "name"));
    }

    public String name() {
        return name;
    }

    /** The variant this one builds on; {@code null} for {@link #NONE}. */
    public Variant base() {
        return base;
    }

    /** Whether this is {@code variant} or builds on it, directly or not. */
    public boolean includes(Variant variant) {
        for (Variant v = this; v != null; v = v.base) {
            if (v == variant) {
                return true;
            }
        }
        return false;
    }

    /** The feature states this variant stands for. */
    public FeatureState state() {
        return state;
    }

    @Override
    public boolean isOn(Feature<?> feature) {
        return state.isOn(feature);
    }

    @Override
    public <P> P value(Feature<P> feature) {
        return state.value(feature);
    }

    @Override
    public String toString() {
        return "variant " + name;
    }

    /** Collects a variant's feature settings; later settings of the same feature win. */
    public static final class Builder {
        private final String name;
        private Variant base = NONE;
        private final Settings settings = new Settings();

        private Builder(String name) {
            this.name = name;
        }

        /** Starts from {@code variant}'s states instead of all off. */
        public Builder basedOn(Variant variant) {
            this.base = Objects.requireNonNull(variant, "variant");
            return this;
        }

        /** Switches {@code feature} on, keeping its parameter. */
        public Builder enable(Feature<?> feature) {
            settings.enable(feature, true);
            return this;
        }

        /** Switches {@code feature} on with {@code value} as its parameter. */
        public <P> Builder enable(Feature<P> feature, P value) {
            settings.set(feature, value);
            settings.enable(feature, true);
            return this;
        }

        /** Switches {@code feature} off, e.g. one the base switches on. */
        public Builder disable(Feature<?> feature) {
            settings.enable(feature, false);
            return this;
        }

        /** Sets {@code feature}'s parameter, leaving it on or off. */
        public <P> Builder set(Feature<P> feature, P value) {
            settings.set(feature, value);
            return this;
        }

        @AllowAllocation("declaring a variant")
        public Variant build() {
            return new Variant(name, base, settings.applyTo(base.state));
        }
    }
}
