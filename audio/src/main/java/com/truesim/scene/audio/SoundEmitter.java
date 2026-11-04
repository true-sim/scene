package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * A sound a node plays, as a component of the node (application-api.md D46; saved as {@code truesim.audio.emitter}): a
 * torch's crackle, a machine's hum. The world's {@link Sounds} plays it on the node when the component is added (or
 * replaced) and stops it when the component or its node goes; a looping sound loops for as long as the component is
 * there, a one-shot plays once. One-shot events stay {@link Sounds#play}'s and {@link Sounds#fire}'s.
 *
 * <pre>{@code
 * scene.components().set(torch, SoundEmitter.TYPE, SoundEmitter.of(CRACKLE));
 * long playing = sounds.emitted(torch);                 // its instance, 0 for none
 * }</pre>
 */
public final class SoundEmitter {

    /** The sound emitter component type. */
    public static final ComponentType<SoundEmitter> TYPE = ComponentType.of("truesim.audio.emitter",
        SoundEmitter.class);

    private final Sound sound;

    private SoundEmitter(Sound sound) {
        this.sound = Objects.requireNonNull(sound, "sound");
    }

    /** An emitter of {@code sound}. */
    @AllowAllocation("components are made when a scene is built")
    public static SoundEmitter of(Sound sound) {
        return new SoundEmitter(sound);
    }

    /** The sound it plays. */
    public Sound sound() {
        return sound;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return "sound emitter";
    }
}
