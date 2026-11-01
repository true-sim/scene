package com.truesim.scene.animation;

import java.util.Objects;

/**
 * A named moment clips share (left foot down, right foot down), declared once as a constant and placed on clips
 * ({@link Clip#marker}): motions of a {@link SyncGroup} line up marker by marker, so a walk and a run blended put their
 * feet down together whatever their timing. Equal by identity.
 *
 * <pre>{@code
 * static final SyncMarker LEFT_DOWN = SyncMarker.of("leftFootDown"), RIGHT_DOWN = SyncMarker.of("rightFootDown");
 * }</pre>
 */
public final class SyncMarker {

    private final String name;

    private SyncMarker(String name) {
        this.name = name;
    }

    /** A new marker called {@code name}. */
    public static SyncMarker of(String name) {
        return new SyncMarker(Objects.requireNonNull(name, "name"));
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "SyncMarker[" + name + "]";
    }
}
