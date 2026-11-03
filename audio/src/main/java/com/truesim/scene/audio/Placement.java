package com.truesim.scene.audio;

import com.truesim.common.math.TransformArray;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** Reads where nodes are: world positions and rotations from the scene's transforms, in doubles. Allocates nothing. */
final class Placement {

    private Placement() {
    }

    /** Writes {@code node}'s world position to {@code positions[3 * at..]} and rotation to {@code rotations[4 * at..]}. */
    static void place(SceneGraph scene, long node, double[] positions, double[] rotations, int at) {
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        long base = NodeHandle.index(node) * world.stride();
        for (int i = 0; i < 3; i++) {
            positions[3 * at + i] = s.get(ValueLayout.JAVA_DOUBLE, base + 8L * i);
        }
        for (int i = 0; i < 4; i++) {
            rotations[4 * at + i] = s.get(ValueLayout.JAVA_DOUBLE, base + 24 + 8L * i);
        }
    }

    /** The squared distance between point {@code a} of {@code pa} and point {@code b} of {@code pb}. */
    static double distanceSquared(double[] pa, int a, double[] pb, int b) {
        double dx = pa[3 * a] - pb[3 * b];
        double dy = pa[3 * a + 1] - pb[3 * b + 1];
        double dz = pa[3 * a + 2] - pb[3 * b + 2];
        return dx * dx + dy * dy + dz * dz;
    }
}
