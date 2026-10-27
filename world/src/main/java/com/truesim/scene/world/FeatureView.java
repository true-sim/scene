package com.truesim.scene.world;

/**
 * Reads feature states: whether a {@link Feature} is on and its parameter. Loaders get one in
 * {@link CellLoader#load(Cell, FeatureView)}; what they read there is what their cell depends on.
 */
public interface FeatureView {

    /** Whether {@code feature} is on. */
    boolean isOn(Feature<?> feature);

    /** {@code feature}'s parameter, on or off; {@code null} for toggles. */
    <P> P value(Feature<P> feature);
}
