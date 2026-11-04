package com.truesim.scene.core;

/**
 * A shape picked precisely, in its node's space: a mesh's triangles ({@link PickMesh}), a skinned mesh posed as it is
 * now, a game's own. {@link ScenePicker} turns a world ray into the node's space (undoing its translation, rotation and
 * scale) and asks where it hits. Implementations allocate nothing per ray and are used on one thread.
 */
public interface PickGeometry {

    /**
     * Where the ray from {@code ox, oy, oz} along {@code dx, dy, dz} (the node's space) first hits the shape, in units
     * of the direction's length; {@link Double#POSITIVE_INFINITY} for a miss or beyond {@code max}.
     */
    double raycast(long node, double ox, double oy, double oz, double dx, double dy, double dz, double max);

    /** The part the last hit of {@link #raycast} struck (a triangle's index for a mesh), or -1. */
    default int part() {
        return -1;
    }
}
