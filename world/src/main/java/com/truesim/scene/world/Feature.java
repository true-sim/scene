package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Something a world's content can depend on, e.g. a racetrack's chicane, its night lights or its barrier style: a typed
 * key that is on or off and carries a parameter. {@link Variant}s are named sets of feature states; the world's
 * {@link Features} hold the current ones, and loaders read them while loading, so a change reloads exactly the cells
 * that read the feature.
 *
 * <pre>{@code
 * static final Feature<Void> NIGHT_LIGHTS = Feature.toggle("night-lights");
 * static final Feature<Chicane> CHICANE = Feature.of("chicane", Chicane.class, Chicane.TIGHT);
 * static final Feature<Double> FLOODLIGHTS = Feature.live("floodlights", Double.class, 1.0); // applies without reloading
 * }</pre>
 *
 * <p>Features are off by default, with their default parameter. Like layers, they are compared by identity: declare
 * them as constants.
 *
 * @param <P> the parameter's type; {@link Void} for plain toggles
 */
public final class Feature<P> {

    private static final List<Feature<?>> ALL = new ArrayList<>();

    private final String name;
    private final Class<P> type;
    private final P defaultValue;
    private final boolean live;
    private final int id;

    @AllowAllocation("declaring a feature")
    private Feature(String name, Class<P> type, P defaultValue, boolean live) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = type;
        this.defaultValue = defaultValue;
        this.live = live;
        synchronized (ALL) {
            this.id = ALL.size();
            ALL.add(this);
        }
    }

    /** A feature that is only on or off. */
    public static Feature<Void> toggle(String name) {
        return new Feature<>(name, Void.class, null, false);
    }

    /** A feature with a parameter; changing either reloads the cells that read it. */
    public static <P> Feature<P> of(String name, Class<P> type, P defaultValue) {
        return new Feature<>(name, type, type.cast(Objects.requireNonNull(defaultValue, "defaultValue")), false);
    }

    /**
     * A feature that applies without reloading, e.g. a light's intensity: loads that read it do not depend on it;
     * changes reach {@link Features#listen listeners} instead.
     */
    public static <P> Feature<P> live(String name, Class<P> type, P defaultValue) {
        return new Feature<>(name, type, type.cast(Objects.requireNonNull(defaultValue, "defaultValue")), true);
    }

    public String name() {
        return name;
    }

    public Class<P> type() {
        return type;
    }

    /** The parameter while nothing sets one; {@code null} for toggles. */
    public P defaultValue() {
        return defaultValue;
    }

    /** Whether changes apply without reloading; see {@link #live}. */
    public boolean isLive() {
        return live;
    }

    /** Whether this feature carries no parameter; see {@link #toggle}. */
    public boolean isToggle() {
        return type == Void.class;
    }

    int id() {
        return id;
    }

    /** The feature numbered {@code id}. */
    static Feature<?> byId(int id) {
        synchronized (ALL) {
            return ALL.get(id);
        }
    }

    /** How many features exist. */
    static int count() {
        synchronized (ALL) {
            return ALL.size();
        }
    }

    /** {@code value}, checked to suit this feature. */
    P check(P value) {
        if (isToggle()) {
            throw new IllegalArgumentException(this + " is a toggle: it has no parameter");
        }
        return type.cast(Objects.requireNonNull(value, "value"));
    }

    @Override
    public String toString() {
        return "feature " + name;
    }
}
