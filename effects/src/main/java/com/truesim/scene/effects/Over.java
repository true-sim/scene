package com.truesim.scene.effects;

/**
 * Updaters changing attributes over a particle's life: its size and color at birth times a curve or gradient over its
 * normalized age.
 *
 * <pre>{@code
 * e.update(Over.size(FloatCurve.of(0, 1, 1, 0)), Over.color(SPARK_GRADIENT))   // shrinking, cooling sparks
 * }</pre>
 */
public final class Over {

    /** The size at birth times a curve over normalized age. */
    public static final String SIZE = "over.size";
    /** The color at birth times a gradient over normalized age. */
    public static final String COLOR = "over.color";

    private Over() {
    }

    /** Sizes at birth times {@code curve} over normalized age. */
    public static Module size(FloatCurve curve) {
        return Module.of(SIZE).with("curve", curve);
    }

    /** Colors at birth times {@code gradient} over normalized age, component by component. */
    public static Module color(ColorGradient gradient) {
        return Module.of(COLOR).with("gradient", gradient);
    }
}
