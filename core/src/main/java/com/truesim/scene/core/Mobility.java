package com.truesim.scene.core;

/**
 * Whether a node is expected to move. Spatial queries index static nodes once (per streamed cell) and refit only the
 * dynamic ones each frame, so marking level geometry {@link #STATIC} keeps per-frame work proportional to what moves.
 * A static node may still move; it then costs a rebuild of its part of the static index.
 */
public enum Mobility {
    /** Placed once: terrain, buildings, props. */
    STATIC,
    /** Moves often: characters, vehicles, physics objects. The default. */
    DYNAMIC
}
