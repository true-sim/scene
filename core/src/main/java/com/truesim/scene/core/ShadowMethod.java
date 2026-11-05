package com.truesim.scene.core;

/** How a light casts shadows ({@link ShadowSpec}): the engine's shadow owner draws every light by its method. */
public enum ShadowMethod {
    /** No shadows. */
    NONE,
    /**
     * Shadow maps, filtered by PCF: one for a spot light, six (cube faces) for a point light, one fitted around the
     * camera for a directional light.
     */
    MAPS,
    /** Cascaded shadow maps for a directional light (others take {@link #MAPS}). */
    CASCADES,
    /** Hard shadows, one ray per pixel towards the light (where the device traces rays; else {@link #MAPS}). */
    RAY_TRACED,
    /**
     * Soft shadows: rays towards points of the light's disc or sphere ({@link ShadowSpec#softness()}), denoised
     * (where the device traces rays; else {@link #MAPS}).
     */
    SOFT_RAY_TRACED
}
