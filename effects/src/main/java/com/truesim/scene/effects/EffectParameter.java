package com.truesim.scene.effects;

import java.util.Objects;

/**
 * A user parameter of effects: a value the game sets per playing effect ({@link Effect#set}), which module inputs read
 * through {@link Bind#param}. Declared once as a typed constant; an effect gives it a default
 * ({@link EffectData.Builder#parameter}). Equal by name and kind.
 *
 * <pre>{@code
 * static final EffectParameter.Float INTENSITY = EffectParameter.ofFloat("intensity");
 * static final EffectParameter.Color TINT = EffectParameter.ofColor("tint");
 * }</pre>
 */
public sealed interface EffectParameter {

    /** Its name, unique within an effect. */
    String name();

    /** How many floats its value takes: 1 for a float, 4 for a color. */
    int size();

    /** A float parameter called {@code name}. */
    static Float ofFloat(String name) {
        return new Float(name);
    }

    /** A color parameter (linear RGBA) called {@code name}. */
    static Color ofColor(String name) {
        return new Color(name);
    }

    /**
     * A float parameter.
     *
     * @param name its name
     */
    record Float(String name) implements EffectParameter {
        public Float {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public int size() {
            return 1;
        }
    }

    /**
     * A linear RGBA color parameter.
     *
     * @param name its name
     */
    record Color(String name) implements EffectParameter {
        public Color {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public int size() {
            return 4;
        }
    }
}
