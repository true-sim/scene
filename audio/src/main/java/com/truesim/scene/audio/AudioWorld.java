package com.truesim.scene.audio;

/**
 * What sounds pass through on their way to a listener (§2.11, AU13): traced by rays for sounds whose
 * {@link Occlusion} is {@code RAYS}. Implemented by the game, over physics ({@link CollisionAudioWorld}), or by the
 * engine over its ray tracing structures. Thread-safe and allocation-free: the engine traces on its job system.
 */
@FunctionalInterface
public interface AudioWorld {

    /**
     * Traces from ({@code x0}, {@code y0}, {@code z0}) to ({@code x1}, {@code y1}, {@code z1}), multiplying into
     * {@code out} (opened by the caller) what each surface crossed lets through. True when anything was crossed.
     */
    boolean trace(double x0, double y0, double z0, double x1, double y1, double z1, Transmission out);
}
