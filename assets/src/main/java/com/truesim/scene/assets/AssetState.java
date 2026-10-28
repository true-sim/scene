package com.truesim.scene.assets;

/** Where an asset stands in the cache. */
public enum AssetState {
    /** Not in the cache. */
    ABSENT,
    /** Loading or being generated in the background. */
    LOADING,
    /** Loaded: its data is available, referenced or kept for later. */
    LOADED,
    /** Its load failed; it stays failed while referenced. */
    FAILED
}
