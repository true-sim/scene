package com.truesim.scene.animation;

/**
 * Where the ground is, for {@link FootPlacement}: terrain heights or a collision query. Asked from the threads that
 * sample animators, so thread-safe and allocation-free.
 */
@FunctionalInterface
public interface Ground {

    /** The ground's height at world (x, z), or NaN where there is none to stand on. */
    double height(double x, double z);
}
