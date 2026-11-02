package com.truesim.scene.effects;

/**
 * Reads the particles of one CPU emitter of one effect, in spawn order, for gameplay: made once and refilled by
 * {@link CpuSimulation#particles}. Positions and velocities are in the world, whatever the emitter's space. Reading
 * allocates nothing; a cursor is valid until the next {@link CpuSimulation#update()}.
 *
 * <pre>{@code
 * ParticleCursor shards = new ParticleCursor();
 * simulation.particles(grenade.id(), SHARDS, shards);
 * while (shards.next()) {
 *     damage(shards.x(), shards.y(), shards.z());
 * }
 * }</pre>
 */
public final class ParticleCursor {

    private CpuSimulation.Particles particles;
    private int index = -1;
    private int count;
    private boolean local;
    // The frame of the particles: the origin (world space), or the emitter's position and rotation (local space).
    private double ox;
    private double oy;
    private double oz;
    private double qx;
    private double qy;
    private double qz;
    private double qw;
    // The current particle in the world.
    private double x;
    private double y;
    private double z;
    private float vx;
    private float vy;
    private float vz;

    void set(CpuSimulation.Particles particles, boolean local, double ox, double oy, double oz, double qx, double qy,
            double qz, double qw) {
        this.particles = particles;
        this.local = local;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.qx = qx;
        this.qy = qy;
        this.qz = qz;
        this.qw = qw;
        count = particles.count;
        index = -1;
    }

    /** How many particles it reads. */
    public int count() {
        return count;
    }

    /** Moves to the next particle; false past the last. */
    public boolean next() {
        if (index + 1 >= count) {
            index = count;
            return false;
        }
        index++;
        CpuSimulation.Particles p = particles;
        if (local) {
            // Positions and velocities in the emitter's frame, turned by its rotation.
            double px = p.px[index];
            double py = p.py[index];
            double pz = p.pz[index];
            x = ox + rotateX(px, py, pz);
            y = oy + rotateY(px, py, pz);
            z = oz + rotateZ(px, py, pz);
            vx = (float) rotateX(p.vx[index], p.vy[index], p.vz[index]);
            vy = (float) rotateY(p.vx[index], p.vy[index], p.vz[index]);
            vz = (float) rotateZ(p.vx[index], p.vy[index], p.vz[index]);
        } else {
            x = ox + p.px[index];
            y = oy + p.py[index];
            z = oz + p.pz[index];
            vx = p.vx[index];
            vy = p.vy[index];
            vz = p.vz[index];
        }
        return true;
    }

    /** Starts over before the first particle. */
    public void rewind() {
        index = -1;
    }

    // v + 2w (q x v) + 2 q x (q x v), by component.

    private double rotateX(double px, double py, double pz) {
        double tx = 2 * (qy * pz - qz * py);
        double ty = 2 * (qz * px - qx * pz);
        double tz = 2 * (qx * py - qy * px);
        return px + qw * tx + qy * tz - qz * ty;
    }

    private double rotateY(double px, double py, double pz) {
        double tx = 2 * (qy * pz - qz * py);
        double ty = 2 * (qz * px - qx * pz);
        double tz = 2 * (qx * py - qy * px);
        return py + qw * ty + qz * tx - qx * tz;
    }

    private double rotateZ(double px, double py, double pz) {
        double tx = 2 * (qy * pz - qz * py);
        double ty = 2 * (qz * px - qx * pz);
        double tz = 2 * (qx * py - qy * px);
        return pz + qw * tz + qx * ty - qy * tx;
    }

    /** The current particle's world position. */
    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** The current particle's world velocity, in metres per second. */
    public float vx() {
        return vx;
    }

    public float vy() {
        return vy;
    }

    public float vz() {
        return vz;
    }

    /** The current particle's age, in seconds. */
    public float age() {
        return particles.age[index];
    }

    /** How long the current particle lives, in seconds. */
    public float lifetime() {
        return particles.lifetime[index];
    }

    /** The current particle's size, in metres. */
    public float size() {
        return particles.size[index];
    }

    /** Component {@code component} (0 red to 3 alpha) of the current particle's linear color. */
    public float color(int component) {
        return particles.color[4 * index + component];
    }

    /** The current particle's spawn index: its order among its emitter's particles. */
    public long spawnIndex() {
        return particles.index[index];
    }

    /** The current particle's seed. */
    public int seed() {
        return particles.seed[index];
    }
}
