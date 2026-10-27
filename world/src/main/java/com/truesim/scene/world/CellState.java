package com.truesim.scene.world;

/** Where a requested cell stands. */
public enum CellState {
    /** Not requested, or released. */
    ABSENT,
    /** Requested; its data is loading or waiting to be attached at the next frame boundary. */
    LOADING,
    /** Attached to the scene. */
    LOADED,
    /** Loading failed; it stays failed until released by everyone who requested it, then a new request retries. */
    FAILED
}
