package com.truesim.scene.animation;

/**
 * Where the ground is, for {@link FootPlacement}: terrain heights or a collision query. Asked on the thread updating
 * the {@link Animators}, animator by animator in their order, in the serial part of the update (decision A13): it need
 * not be thread-safe, but must allocate nothing.
 */
@FunctionalInterface
public interface Ground {

    /** The ground's height at world (x, z), or NaN where there is none to stand on. */
    double height(double x, double z);
}
