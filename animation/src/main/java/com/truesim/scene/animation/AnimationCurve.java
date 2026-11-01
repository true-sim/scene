package com.truesim.scene.animation;

import java.util.Objects;

/**
 * A named value clips animate besides joints (a blink, how much a foot is planted), declared once as a constant and
 * keyed on clips ({@link Clip#curve}); blended as the clips are, and read each frame ({@link Animator#curve}). Equal by
 * identity.
 *
 * <pre>{@code
 * static final AnimationCurve BLINK = AnimationCurve.of("blink");
 * }</pre>
 */
public final class AnimationCurve {

    private final String name;

    private AnimationCurve(String name) {
        this.name = name;
    }

    /** A new curve called {@code name}. */
    public static AnimationCurve of(String name) {
        return new AnimationCurve(Objects.requireNonNull(name, "name"));
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "AnimationCurve[" + name + "]";
    }
}
