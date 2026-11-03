package com.truesim.scene.animation;

/**
 * Changes an animator's pose after its layers, overrides, inverse kinematics and look-ats ({@link Rig.Builder#modifier}):
 * foot placement ({@link FootPlacement}), and the game's own. Called for every animator of the rig on the threads that
 * sample them, so it keeps no state of its own between calls (or keeps it per animator) and is thread-safe; it must
 * allocate nothing.
 * ({@link FootPlacement} asks its {@link Ground} apart, on the updating thread, so the ground need not be thread-safe.)
 */
@FunctionalInterface
public interface PoseModifier {

    /** Changes {@code pose}, an animator's this update, by {@code pose.weight()}. */
    void modify(Pose pose);
}
