package com.truesim.scene.effects;

/**
 * The particle a {@link ModuleTwin} runs on, and its module's inputs: its float fields evaluated for it, the step and
 * its draws' salt. Positions and velocities are in its emitter's particle space, as the module's Slang struct sees them
 * (offsets from the effect's origin, or in the emitter's frame). Valid only during the twin's call; the simulator
 * reuses it.
 */
public final class TwinParticle {

    CpuSimulation.Particles p;
    int i;
    CpuSimulation.Op op;
    float[] parameters;
    float h;
    float time;

    TwinParticle() {
    }

    /** Float field {@code field} of the module (by its index in alphabetical order of their names), for this particle. */
    public float value(int field) {
        return CpuSimulation.value(op, field, parameters, p.seed[i], p, i);
    }

    /** The step, in seconds: 0 at birth. */
    public float h() {
        return h;
    }

    /** Its effect's time, in seconds. */
    public float time() {
        return time;
    }

    /** Its seed: {@code Hash.random(seed(), salt() + 8)} on are the module's own draws. */
    public int seed() {
        return p.seed[i];
    }

    /** The module's first salt: its fields draw from {@code salt() + field}, its own draws from {@code salt() + 8}. */
    public int salt() {
        return op.salt();
    }

    public float x() {
        return p.px[i];
    }

    public float y() {
        return p.py[i];
    }

    public float z() {
        return p.pz[i];
    }

    /** Puts it at ({@code x}, {@code y}, {@code z}). */
    public void position(float x, float y, float z) {
        p.setPosition(i, x, y, z);
    }

    public float vx() {
        return p.vx[i];
    }

    public float vy() {
        return p.vy[i];
    }

    public float vz() {
        return p.vz[i];
    }

    /** Sets its velocity, in metres per second. */
    public void velocity(float x, float y, float z) {
        p.vx[i] = x;
        p.vy[i] = y;
        p.vz[i] = z;
    }

    /** Component {@code c} (0 to 3: red, green, blue, alpha) of its color. */
    public float color(int c) {
        return p.color[4 * i + c];
    }

    /** Sets its color, linear. */
    public void color(float r, float g, float b, float a) {
        p.color[4 * i] = r;
        p.color[4 * i + 1] = g;
        p.color[4 * i + 2] = b;
        p.color[4 * i + 3] = a;
    }

    /** Its size, in metres across. */
    public float size() {
        return p.size[i];
    }

    public void size(float size) {
        p.size[i] = size;
    }

    /** Its age, in seconds. */
    public float age() {
        return p.age[i];
    }

    /** How long it lives, in seconds: at its age or below it dies. */
    public float lifetime() {
        return p.lifetime[i];
    }

    public void lifetime(float lifetime) {
        p.lifetime[i] = lifetime;
    }
}
