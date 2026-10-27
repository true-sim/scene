package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Features, variants and the world's feature states, apart from loading. */
class FeatureTest {

    enum Chicane { TIGHT, WIDE }

    private static final Feature<Void> NIGHT_LIGHTS = Feature.toggle("night-lights");
    private static final Feature<Chicane> CHICANE = Feature.of("chicane", Chicane.class, Chicane.TIGHT);
    private static final Feature<Double> FLOODLIGHTS = Feature.live("floodlights", Double.class, 1.0);
    private static final Feature<Void> PIT_LANE = Feature.toggle("long-pit-lane");

    private static final Variant BASE = Variant.builder("base").enable(CHICANE).enable(PIT_LANE).build();
    private static final Variant GRAND_PRIX = Variant.builder("grand-prix").basedOn(BASE)
        .enable(CHICANE, Chicane.WIDE)
        .disable(PIT_LANE)
        .set(FLOODLIGHTS, 0.5)
        .build();

    @Test
    void featuresAreTypedKeys() {
        assertThat(NIGHT_LIGHTS.isToggle()).isTrue();
        assertThat(NIGHT_LIGHTS.type()).isEqualTo(Void.class);
        assertThat(NIGHT_LIGHTS.defaultValue()).isNull();
        assertThat(CHICANE.name()).isEqualTo("chicane");
        assertThat(CHICANE.isToggle()).isFalse();
        assertThat(CHICANE.isLive()).isFalse();
        assertThat(FLOODLIGHTS.isLive()).isTrue();
        assertThat(CHICANE).hasToString("feature chicane");
        assertThat(Feature.byId(CHICANE.id())).isSameAs(CHICANE);
        assertThatNullPointerException().isThrownBy(() -> Feature.of("x", Chicane.class, null));
        assertThatNullPointerException().isThrownBy(() -> Feature.live("x", Double.class, null));
        assertThatNullPointerException().isThrownBy(() -> Feature.toggle(null));
        assertThatIllegalArgumentException().isThrownBy(() -> NIGHT_LIGHTS.check(null))
            .withMessageContaining("toggle");
        assertThatNullPointerException().isThrownBy(() -> CHICANE.check(null));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void parametersMustSuitTheFeature() {
        Feature raw = CHICANE;
        assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> raw.check("wide"));
        assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> Feature.of("x", (Class) Double.class, "1"));
    }

    @Test
    void variantsBuildOnEachOther() {
        assertThat(Variant.NONE.isOn(CHICANE)).isFalse();
        assertThat(Variant.NONE.value(CHICANE)).isEqualTo(Chicane.TIGHT);
        assertThat(Variant.NONE.base()).isNull();
        assertThat(BASE.isOn(CHICANE)).isTrue();
        assertThat(BASE.value(CHICANE)).isEqualTo(Chicane.TIGHT);
        assertThat(BASE.isOn(PIT_LANE)).isTrue();
        assertThat(GRAND_PRIX.base()).isSameAs(BASE);
        assertThat(GRAND_PRIX.name()).isEqualTo("grand-prix");
        assertThat(GRAND_PRIX).hasToString("variant grand-prix");
        assertThat(GRAND_PRIX.value(CHICANE)).isEqualTo(Chicane.WIDE);
        assertThat(GRAND_PRIX.isOn(PIT_LANE)).isFalse();
        assertThat(GRAND_PRIX.isOn(FLOODLIGHTS)).isFalse(); // a parameter set, the feature left off
        assertThat(GRAND_PRIX.value(FLOODLIGHTS)).isEqualTo(0.5);
        assertThat(GRAND_PRIX.includes(BASE)).isTrue();
        assertThat(GRAND_PRIX.includes(Variant.NONE)).isTrue();
        assertThat(BASE.includes(GRAND_PRIX)).isFalse();
        assertThat(Variant.builder("empty").build().state()).isSameAs(Variant.NONE.state());
        // Features declared after a variant are off in it, with their default.
        Feature<Integer> later = Feature.of("later", Integer.class, 7);
        assertThat(GRAND_PRIX.isOn(later)).isFalse();
        assertThat(GRAND_PRIX.value(later)).isEqualTo(7);
        assertThatNullPointerException().isThrownBy(() -> Variant.builder(null));
        assertThatNullPointerException().isThrownBy(() -> Variant.builder("x").basedOn(null));
    }

    @Test
    void theWorldsFeaturesAreAVariantWithChangesOnTop() {
        Features features = new Features();
        assertThat(features.variant()).isSameAs(Variant.NONE);
        assertThat(features.pending()).isFalse();
        features.select(GRAND_PRIX);
        assertThat(features.pending()).isTrue();
        assertThat(features.state()).isSameAs(GRAND_PRIX.state());
        assertThat(features.applied()).isNotSameAs(GRAND_PRIX.state());
        features.enable(PIT_LANE);
        features.set(CHICANE, Chicane.TIGHT);
        features.enable(NIGHT_LIGHTS);
        features.disable(NIGHT_LIGHTS);
        assertThat(features.isOn(PIT_LANE)).isTrue();
        assertThat(features.value(CHICANE)).isEqualTo(Chicane.TIGHT);
        assertThat(features.isOn(CHICANE)).isTrue();
        assertThat(features.isOn(NIGHT_LIGHTS)).isFalse();
        features.enable(FLOODLIGHTS, 2.0);
        assertThat(features.value(FLOODLIGHTS)).isEqualTo(2.0);
        features.reset(FLOODLIGHTS);
        assertThat(features.value(FLOODLIGHTS)).isEqualTo(0.5);
        assertThat(features.isOn(FLOODLIGHTS)).isFalse();
        features.select(BASE); // drops the changes
        assertThat(features.isOn(PIT_LANE)).isTrue();
        assertThat(features.value(CHICANE)).isEqualTo(Chicane.TIGHT);
        assertThatNullPointerException().isThrownBy(() -> features.select(null));
        assertThatIllegalArgumentException().isThrownBy(() -> features.set(NIGHT_LIGHTS, null));
    }

    @Test
    void listenersHearOfAppliedChanges() {
        Features features = new Features();
        List<String> heard = new ArrayList<>();
        features.listen(FLOODLIGHTS, (feature, on, value) -> heard.add(feature.name() + " " + on + " " + value));
        features.listen(NIGHT_LIGHTS, (feature, on, value) -> heard.add(feature.name() + " " + on));
        assertThatNullPointerException().isThrownBy(() -> features.listen(CHICANE, null));
        features.enable(FLOODLIGHTS, 0.3);
        features.enable(CHICANE);
        FeatureState before = features.apply();
        assertThat(features.pending()).isFalse();
        features.notifyChanged(FeatureState.changed(before, features.applied()));
        assertThat(heard).containsExactly("floodlights true 0.3");
        features.notifyChanged(new long[0]); // nothing changed
        assertThat(heard).hasSize(1);
    }

    @Test
    void statesCompareByValue() {
        FeatureState a = Variant.builder("a").set(CHICANE, Chicane.TIGHT).build().state(); // the default, set
        assertThat(FeatureState.changed(FeatureState.DEFAULTS, a)).containsOnly(0L);
        FeatureState b = Variant.builder("b").set(CHICANE, Chicane.WIDE).build().state();
        long[] changed = FeatureState.changed(a, b);
        assertThat(changed[CHICANE.id() >>> 6] & 1L << CHICANE.id()).isNotZero();
        // A draft started before a feature was declared still takes it.
        FeatureState.Draft draft = FeatureState.DEFAULTS.edit();
        Feature<Void> late = Feature.toggle("late");
        draft.set(late, true, false, null);
        assertThat(draft.build().isOn(late)).isTrue();
    }

    @Test
    void readsAreRecorded() {
        FeatureReads reads = new FeatureReads(GRAND_PRIX.state());
        assertThat(reads.value(CHICANE)).isEqualTo(Chicane.WIDE);
        assertThat(reads.isOn(FLOODLIGHTS)).isFalse(); // live: not recorded
        Feature<Void> late = Feature.toggle("read-late");
        for (int i = 0; i < 64; i++) {
            Feature.toggle("filler " + i); // the late one's bit lies beyond the words the reads started with
        }
        Feature<Void> later = Feature.toggle("read-later");
        assertThat(reads.isOn(late)).isFalse();
        assertThat(reads.isOn(later)).isFalse();
        long[] chicane = new long[CHICANE.id() / 64 + 1];
        chicane[CHICANE.id() >>> 6] = 1L << CHICANE.id();
        assertThat(FeatureReads.intersects(reads.bits(), chicane)).isTrue();
        long[] floodlights = new long[FLOODLIGHTS.id() / 64 + 1];
        floodlights[FLOODLIGHTS.id() >>> 6] = 1L << FLOODLIGHTS.id();
        assertThat(FeatureReads.intersects(reads.bits(), floodlights)).isFalse();
        assertThat(reads.outdated(GRAND_PRIX.state())).isFalse();
        assertThat(reads.outdated(BASE.state())).isTrue(); // the chicane differs
        assertThat(reads.outdated(Variant.builder("lit").basedOn(GRAND_PRIX).enable(FLOODLIGHTS).build().state()))
            .isFalse(); // only a live feature differs
    }

    @Test
    void loadersImplementOneLoadMethod() {
        CellLoader<String> loader = (data, content) -> { };
        Cell cell = new Cell(Layer.of("x", String.class, Levels.single(1)), 0, 0, 0, 0);
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> loader.load(cell, Variant.NONE));
    }
}
