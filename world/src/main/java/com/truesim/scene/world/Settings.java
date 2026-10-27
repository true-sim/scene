package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.IdentityHashMap;
import java.util.Map;

/** Feature settings on top of a base state, as a variant or the world's overrides make them. */
final class Settings {

    /** One feature's setting: on or off ({@code null}: unchanged) and a parameter ({@code hasValue}). */
    private record Setting(Boolean on, boolean hasValue, Object value) {
    }

    private final Map<Feature<?>, Setting> settings = new IdentityHashMap<>();

    @AllowAllocation("changing features")
    void enable(Feature<?> feature, boolean on) {
        Setting old = settings.get(feature);
        settings.put(feature, old == null ? new Setting(on, false, null) : new Setting(on, old.hasValue, old.value));
    }

    @AllowAllocation("changing features")
    <P> void set(Feature<P> feature, P value) {
        Object checked = feature.check(value);
        Setting old = settings.get(feature);
        settings.put(feature, new Setting(old == null ? null : old.on, true, checked));
    }

    void clear(Feature<?> feature) {
        settings.remove(feature);
    }

    void clear() {
        settings.clear();
    }

    /** {@code base} with these settings applied. */
    @AllowAllocation("changing features")
    FeatureState applyTo(FeatureState base) {
        if (settings.isEmpty()) {
            return base;
        }
        FeatureState.Draft draft = base.edit();
        settings.forEach((feature, setting) -> draft.set(feature, setting.on, setting.hasValue, setting.value));
        return draft.build();
    }
}
