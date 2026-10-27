package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A world's current feature states: the selected {@link Variant}, with single features changed on top of it. Changes
 * apply on the next {@link World#update()}: cells whose loads read a changed feature reload in the background and swap
 * in when ready, so the old content stays until the new one is there; {@link Feature#live live} features reach their
 * listeners instead. Used on the main thread.
 *
 * <pre>{@code
 * world.features().select(GRAND_PRIX);           // the variant's features, overrides cleared
 * world.features().disable(LONG_PIT_LANE);       // one feature changed on top
 * world.features().set(FLOODLIGHTS, 0.6);        // a live feature: listeners only, nothing reloads
 * world.update();                                // dependent cells start reloading
 * }</pre>
 *
 * <p>Reading through this view gives the states as changed so far, including changes not yet applied; loads see the
 * {@link #applied()} states.
 */
public final class Features implements FeatureView {

    /** Hears of a feature's changes, on the main thread, when the world applies them. */
    @FunctionalInterface
    public interface Listener<P> {
        void changed(Feature<P> feature, boolean on, P value);
    }

    private Variant variant = Variant.NONE;
    private final Settings overrides = new Settings();
    private FeatureState current = FeatureState.DEFAULTS;
    private FeatureState applied = FeatureState.DEFAULTS;
    private final Map<Feature<?>, List<Listener<?>>> listeners = new IdentityHashMap<>();

    Features() {
    }

    /** Selects {@code selected}'s feature states, dropping the single changes made on top of the previous one. */
    public void select(Variant selected) {
        variant = Objects.requireNonNull(selected, "variant");
        overrides.clear();
        current = variant.state();
    }

    /** The selected variant. */
    public Variant variant() {
        return variant;
    }

    /** Switches {@code feature} on, keeping its parameter. */
    public void enable(Feature<?> feature) {
        overrides.enable(feature, true);
        recompute();
    }

    /** Switches {@code feature} on with {@code value} as its parameter. */
    public <P> void enable(Feature<P> feature, P value) {
        overrides.set(feature, value);
        overrides.enable(feature, true);
        recompute();
    }

    /** Switches {@code feature} off. */
    public void disable(Feature<?> feature) {
        overrides.enable(feature, false);
        recompute();
    }

    /** Sets {@code feature}'s parameter, leaving it on or off. */
    public <P> void set(Feature<P> feature, P value) {
        overrides.set(feature, value);
        recompute();
    }

    /** Takes back the changes to {@code feature}: it is as the variant says again. */
    public void reset(Feature<?> feature) {
        overrides.clear(feature);
        recompute();
    }

    private void recompute() {
        current = overrides.applyTo(variant.state());
    }

    @Override
    public boolean isOn(Feature<?> feature) {
        return current.isOn(feature);
    }

    @Override
    public <P> P value(Feature<P> feature) {
        return current.value(feature);
    }

    /** The states as changed so far. */
    public FeatureState state() {
        return current;
    }

    /** The states the world applied last, the ones loads read. */
    public FeatureState applied() {
        return applied;
    }

    /** Calls {@code listener} whenever the world applies a change to {@code feature}. */
    @AllowAllocation("adding a listener")
    public <P> void listen(Feature<P> feature, Listener<P> listener) {
        listeners.computeIfAbsent(feature, f -> new ArrayList<>()).add(Objects.requireNonNull(listener, "listener"));
    }

    /** Whether there are changes the world has not applied. */
    boolean pending() {
        return current != applied;
    }

    /** Makes the current states the applied ones; returns the previous. */
    FeatureState apply() {
        FeatureState before = applied;
        applied = current;
        return before;
    }

    /** Tells the listeners of the features in {@code changed} (bits by feature id). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @AllowAllocation("applying feature changes")
    void notifyChanged(long[] changed) {
        listeners.forEach((feature, list) -> {
            int id = feature.id();
            if (id >>> 6 < changed.length && (changed[id >>> 6] & 1L << id) != 0) {
                for (Listener listener : list) {
                    listener.changed((Feature) feature, applied.isOn(feature), applied.value(feature));
                }
            }
        });
    }
}
