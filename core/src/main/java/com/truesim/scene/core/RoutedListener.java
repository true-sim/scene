package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A front end's listeners on a node, as one set in the scene's single event route (a transient component,
 * {@code truesim.routed-listener}): the router (the engine's scene interaction) calls {@link #listener} at each node of
 * an event's route after the node's own listeners, in the capture phase for the types of {@link #captureMask}, at the
 * target for either mask, in the bubble phase for {@link #mask}. A consume on either side stops the route for both. The
 * UI's scene bridge registers its {@code Interactive} nodes so, without depending on the engine.
 *
 * <pre>{@code
 * components.set(node, RoutedListener.TYPE, RoutedListener.of(SceneEventType.CLICK.bit(), 0, e -> ...));
 * }</pre>
 */
public final class RoutedListener {

    /** The routed listener component type (transient: listeners are code, not saved). */
    public static final ComponentType<RoutedListener> TYPE = ComponentType.transientOf("truesim.routed-listener",
        RoutedListener.class);

    /** Receives routed events. */
    @FunctionalInterface
    public interface Listener {

        void handle(NodeEvent event);
    }

    private final int mask;
    private final int captureMask;
    private final Listener listener;

    private RoutedListener(int mask, int captureMask, Listener listener) {
        this.mask = mask;
        this.captureMask = captureMask;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** {@code listener} for the types of {@code mask} (target and bubble) and {@code captureMask} (capture, target). */
    @AllowAllocation("listeners are added when a scene is built")
    public static RoutedListener of(int mask, int captureMask, Listener listener) {
        return new RoutedListener(mask, captureMask, listener);
    }

    /** The types heard at the target and while bubbling. */
    public int mask() {
        return mask;
    }

    /** The types heard in the capture phase (and at the target). */
    public int captureMask() {
        return captureMask;
    }

    public Listener listener() {
        return listener;
    }

    /** Whether it hears an event of {@code type} in {@code phase}. */
    public boolean hears(SceneEventType type, NodeEvent.Phase phase) {
        int bit = type.bit();
        return switch (phase) {
            case CAPTURE -> (captureMask & bit) != 0;
            case TARGET -> ((mask | captureMask) & bit) != 0;
            case BUBBLE -> (mask & bit) != 0;
        };
    }
}
