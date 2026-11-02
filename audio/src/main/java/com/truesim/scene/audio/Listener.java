package com.truesim.scene.audio;

/**
 * A handle of a listener, made once and kept: inert once the listener is removed ({@link #alive()} false).
 *
 * @see Listeners
 */
public final class Listener {

    private final Listeners listeners;
    private final long id;

    Listener(Listeners listeners, long id) {
        this.listeners = listeners;
        this.id = id;
    }

    /** Its id in its {@link Listeners}. */
    public long id() {
        return id;
    }

    public boolean alive() {
        return listeners.alive(id);
    }

    /** Removes it. */
    public void remove() {
        listeners.remove(id);
    }
}
