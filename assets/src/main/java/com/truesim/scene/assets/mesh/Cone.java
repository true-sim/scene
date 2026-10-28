package com.truesim.scene.assets.mesh;

/**
 * A meshlet's normal cone, for backface culling whole meshlets, as meshoptimizer computes it: the meshlet faces away
 * from a camera at {@code eye} if {@code dot(center - eye, axis) >= cutoff * |center - eye| + radius}, with the
 * meshlet's bounding sphere ({@code center}, {@code radius}). A cutoff of 1 never culls.
 */
public record Cone(double axisX, double axisY, double axisZ, double cutoff) {

    /** A cone that never culls, e.g. for meshlets with triangles facing all ways. */
    public static final Cone NONE = new Cone(0, 0, 1, 1);

    public Cone {
        if (!(cutoff >= -1 && cutoff <= 1) || !Double.isFinite(axisX + axisY + axisZ)) {
            throw new IllegalArgumentException("A cone needs a finite axis and a cutoff in [-1, 1]: " + cutoff);
        }
    }
}
