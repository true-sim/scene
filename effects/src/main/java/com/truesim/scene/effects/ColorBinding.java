package com.truesim.scene.effects;

import java.util.Objects;

/**
 * Where a color module input comes from: a constant color, a color user parameter, a gradient over normalized age, or a
 * random point of a gradient drawn per particle. Made by {@link Bind}; immutable, compared by value.
 *
 * @param kind where the color comes from
 * @param r the constant's linear red, for {@link Kind#CONSTANT}
 * @param g the constant's green
 * @param b the constant's blue
 * @param a the constant's alpha
 * @param parameter the color parameter's name, for {@link Kind#PARAMETER}
 * @param gradient the gradient, for {@link Kind#AGE} and {@link Kind#RANDOM}
 */
public record ColorBinding(Kind kind, float r, float g, float b, float a, String parameter, ColorGradient gradient) {

    /** Where a color binding's value comes from. */
    public enum Kind {
        /** A constant color. */
        CONSTANT,
        /** A color user parameter's value. */
        PARAMETER,
        /** A gradient over the particle's normalized age. */
        AGE,
        /** A random point of a gradient, drawn per particle from its seed. */
        RANDOM
    }

    public ColorBinding {
        Objects.requireNonNull(kind, "kind");
    }
}
