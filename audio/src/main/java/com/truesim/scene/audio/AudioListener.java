package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * A listener as a component on a node (application-api.md D46; saved as {@code truesim.audio.listener}): a device
 * listener, whose place the device mix is spatialized for, or a rendering listener mixing what it hears into a
 * {@link ListenerMix} of its own. {@link Listeners} is the world's view of them, and the engine's audio sync reads them
 * from the scene's components; a node has at most one.
 *
 * <pre>{@code
 * scene.components().set(head, AudioListener.TYPE, AudioListener.device());     // as sounds.listeners().device(head)
 * }</pre>
 *
 * <p>Where it is (as of the store's last advance) is kept with it.
 */
public final class AudioListener {

    /** The listener component type. */
    public static final ComponentType<AudioListener> TYPE = ComponentType.of("truesim.audio.listener",
        AudioListener.class);

    private final ListenerMix mix;
    final double[] place = {0, 0, 0, 0, 0, 0, 1}; // position, then rotation x, y, z, w

    private AudioListener(ListenerMix mix) {
        this.mix = mix;
    }

    /** A device listener. */
    @AllowAllocation("components are made when a scene is built")
    public static AudioListener device() {
        return new AudioListener(null);
    }

    /** A rendering listener mixing as {@code mix} says. */
    @AllowAllocation("components are made when a scene is built")
    public static AudioListener render(ListenerMix mix) {
        return new AudioListener(Objects.requireNonNull(mix, "mix"));
    }

    /** Whether it renders a mix of its own (else it is a device listener). */
    public boolean rendering() {
        return mix != null;
    }

    /** A rendering listener's mix; null for a device listener. */
    public ListenerMix mix() {
        return mix;
    }

    /** Component {@code axis} of where it is, as of its store's last advance. */
    public double position(int axis) {
        return place[Objects.checkIndex(axis, 3)];
    }

    /** Component {@code axis} (x, y, z, w) of its rotation, as of its store's last advance. */
    public double rotation(int axis) {
        return place[3 + Objects.checkIndex(axis, 4)];
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return mix == null ? "device listener" : "rendering listener";
    }
}
