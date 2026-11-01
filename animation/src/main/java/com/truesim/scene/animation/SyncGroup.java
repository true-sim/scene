package com.truesim.scene.animation;

import java.util.Objects;

/**
 * Motions of an animator that keep in step, declared once as a constant: the heaviest playing (or one made to lead,
 * {@link ClipState#lead}) moves on, and the others follow its phase, marker by marker where their clips share
 * {@link SyncMarker}s, else by how far through they are. Followers fire no events. Motions join by their state
 * ({@link ClipState#sync}) or by default ({@link Clip#sync}, {@link BlendSpace1#sync}). Equal by identity.
 *
 * <pre>{@code
 * static final SyncGroup LOCOMOTION = SyncGroup.of("locomotion");
 * }</pre>
 */
public final class SyncGroup {

    private final String name;

    private SyncGroup(String name) {
        this.name = name;
    }

    /** A new group called {@code name}. */
    public static SyncGroup of(String name) {
        return new SyncGroup(Objects.requireNonNull(name, "name"));
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "SyncGroup[" + name + "]";
    }
}
