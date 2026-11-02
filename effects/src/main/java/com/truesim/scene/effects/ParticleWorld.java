package com.truesim.scene.effects;

/**
 * What CPU emitters' particles collide with, supplied by the game, as animation's {@code Ground} is: terrain heights
 * and rays against the physics scene, typically over common-collision's {@code TerrainHeights} and a
 * {@code CollisionQuery} per worker thread. Asked from the job system's threads, so thread-safe and allocation-free.
 * {@link CpuSimulation} takes one; collision modules use it from step 12 of the design on.
 */
public interface ParticleWorld {

    /** The ground's height at world ({@code x}, {@code z}), or NaN where there is none. */
    double height(double x, double z);

    /**
     * Casts a ray from world ({@code x}, {@code y}, {@code z}) along the unit direction ({@code dx}, {@code dy},
     * {@code dz}) up to {@code maxDistance} metres; on a hit, fills {@code hit} and returns true.
     */
    boolean raycast(double x, double y, double z, double dx, double dy, double dz, double maxDistance, Hit hit);

    /** Where a ray hit: made once per thread and refilled by each hit. */
    final class Hit {
        private double distance;
        private float nx;
        private float ny;
        private float nz;
        private int material;

        /** Fills it: the hit {@code distance} metres along the ray, the surface's normal and material there. */
        public void set(double distance, float nx, float ny, float nz, int material) {
            this.distance = distance;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.material = material;
        }

        public double distance() {
            return distance;
        }

        public float nx() {
            return nx;
        }

        public float ny() {
            return ny;
        }

        public float nz() {
            return nz;
        }

        /** The material of the surface hit, as the collision world numbers them. */
        public int material() {
            return material;
        }
    }
}
