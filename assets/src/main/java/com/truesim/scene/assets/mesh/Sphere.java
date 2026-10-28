package com.truesim.scene.assets.mesh;

/** A bounding sphere in the mesh's space. */
public record Sphere(double x, double y, double z, double radius) {

    public Sphere {
        if (!(radius >= 0) || !Double.isFinite(x + y + z + radius)) {
            throw new IllegalArgumentException("A sphere needs a finite center and radius >= 0: " + x + ", " + y
                + ", " + z + ", " + radius);
        }
    }
}
