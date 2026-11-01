package com.truesim.scene.animation;

import java.util.Objects;

/**
 * A named moment in clips (a footstep, a hit), declared once as a constant and placed on clips
 * ({@link Clip#event}); listeners hear it as playback passes it ({@link Animators#listen}). Equal by identity.
 *
 * <pre>{@code
 * static final AnimationEvent FOOTSTEP = AnimationEvent.of("footstep");
 * }</pre>
 */
public final class AnimationEvent {

    private final String name;

    private AnimationEvent(String name) {
        this.name = name;
    }

    /** A new event called {@code name}. */
    public static AnimationEvent of(String name) {
        return new AnimationEvent(Objects.requireNonNull(name, "name"));
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "AnimationEvent[" + name + "]";
    }
}
