package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * The states of all features at one moment: which are on and their parameters. Immutable, so loads in the background
 * read a consistent state while the main thread changes the next one. Features not set are off with their default.
 */
public final class FeatureState implements FeatureView {

    /** Every feature off, with its default. */
    static final FeatureState DEFAULTS = new FeatureState(new boolean[0], new Object[0]);

    private final boolean[] on;
    private final Object[] values; // null: the default

    private FeatureState(boolean[] on, Object[] values) {
        this.on = on;
        this.values = values;
    }

    @Override
    public boolean isOn(Feature<?> feature) {
        return isOn(feature.id());
    }

    @Override
    public <P> P value(Feature<P> feature) {
        Object value = feature.id() < values.length ? values[feature.id()] : null;
        return value == null ? feature.defaultValue() : feature.type().cast(value);
    }

    private boolean isOn(int id) {
        return id < on.length && on[id];
    }

    private Object valueAt(int id) {
        Object value = id < values.length ? values[id] : null;
        return value == null ? Feature.byId(id).defaultValue() : value;
    }

    /** Whether feature {@code id} is on with the same parameter in both states. */
    boolean same(int id, FeatureState other) {
        return isOn(id) == other.isOn(id) && Objects.equals(valueAt(id), other.valueAt(id));
    }

    /** The features that differ between two states, as bits by feature id. */
    @AllowAllocation("comparing states, when features change")
    static long[] changed(FeatureState a, FeatureState b) {
        int count = Math.max(a.on.length, b.on.length);
        long[] bits = new long[(count + 63) >>> 6];
        for (int id = 0; id < count; id++) {
            if (!a.same(id, b)) {
                bits[id >>> 6] |= 1L << id;
            }
        }
        return bits;
    }

    /** A state being built: this one's copy with settings applied. */
    @AllowAllocation("building a state, when features change")
    Draft edit() {
        return new Draft(this);
    }

    /** A mutable copy, published as a new state with {@link #build()}. */
    static final class Draft {
        private boolean[] on;
        private Object[] values;

        @AllowAllocation("building a state, when features change")
        Draft(FeatureState base) {
            int count = Math.max(Feature.count(), base.on.length);
            on = Arrays.copyOf(base.on, count);
            values = Arrays.copyOf(base.values, count);
        }

        /** Switches a feature on or off; {@code null} leaves it as it is. */
        void set(Feature<?> feature, Boolean enabled, boolean hasValue, Object value) {
            int id = feature.id();
            if (id >= on.length) { // declared after this draft started
                on = Arrays.copyOf(on, id + 1);
                values = Arrays.copyOf(values, id + 1);
            }
            if (enabled != null) {
                on[id] = enabled;
            }
            if (hasValue) {
                values[id] = value;
            }
        }

        FeatureState build() {
            return new FeatureState(on, values);
        }
    }
}
