package com.truesim.scene.effects;

import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;

/**
 * Updaters accelerating particles every frame: gravity, drag, wind, curl noise, vortices, point and line attractors,
 * orbits, vector fields and a speed limit (effects.md §2.7). Forces act in the world: a local-space emitter's particles
 * fall down, not along its rotated frame. Shapes that forces have (a vortex's axis, an attractor's point and line)
 * are in the emitter's frame, about the emitter: they move and turn with it.
 *
 * <pre>{@code
 * e.update(Forces.gravity(), Forces.drag(0.6f), Forces.curl(2, 0.8f), Forces.vortex(3).pull(1))
 * }</pre>
 *
 * <p>How they integrate (the GPU kernel and {@link CpuSimulation} alike): gravity, drag and wind (a drag towards the
 * wind's velocity) exactly over a step; the forces that depend on where a particle is (curl noise, vortices,
 * attractors, vector fields) as constant over a step, from where it was at its start; orbits turn the particle about
 * their axis by their angle over the step, exactly, so its distance from the axis stays; the speed limit last.
 */
public final class Forces {

    /** Gravity's acceleration, in metres per second squared. */
    public static final float G = 9.81f;
    /** A downward acceleration of {@link #G} times a scale. */
    public static final String GRAVITY = "force.gravity";
    /** Linear drag: velocity falls by a coefficient per second, towards rest. */
    public static final String DRAG = "force.drag";
    /**
     * Wind: a drag of {@code coupling} per second towards the world's wind ({@link Effects#wind}) times {@code scale},
     * with gusts: the wind's speed varies by up to {@code gusts} of itself over time.
     */
    public static final String WIND = "force.wind";
    /**
     * Curl noise (Bridson et al. 2007): a divergence-free turbulence of {@code strength} m/s², features
     * {@code 1 / frequency} metres across, drifting at {@code speed} per second, of 1 to 4 {@code octaves}.
     */
    public static final String CURL = "force.curl";
    /**
     * A vortex about the emitter's axis ({@code axisX}, {@code axisY}, {@code axisZ}; +Y by default): {@code strength}
     * m/s² around it (counter-clockwise seen from the axis' tip), {@code pull} m/s² towards it.
     */
    public static final String VORTEX = "force.vortex";
    /**
     * A point attractor at ({@code x}, {@code y}, {@code z}) from the emitter: {@code strength} m/s² towards it
     * (negative repels), falling off linearly to 0 at {@code radius} (0: everywhere); particles nearer than
     * {@code killRadius} die.
     */
    public static final String POINT = "force.point";
    /**
     * A line attractor through ({@code x}, {@code y}, {@code z}) from the emitter along ({@code axisX}, {@code axisY},
     * {@code axisZ}): {@code strength} m/s² towards the nearest point of it, falling off to 0 at {@code radius}.
     */
    public static final String LINE = "force.line";
    /** An orbit: particles turn at {@code speed} radians a second about the emitter's axis, their distance kept. */
    public static final String ORBIT = "force.orbit";
    /**
     * A vector field: accelerations from a vector field asset ({@code field}, {@link EffectAssets#VECTOR_FIELD}: its
     * cells' vectors in m/s² times {@code strength}) over a box of {@code sizeX} by {@code sizeY} by {@code sizeZ} metres centered on the emitter, repeated beyond it
     * when {@code tile} is 1, else clamped. GPU emitters only: the CPU has no textures.
     */
    public static final String FIELD = "force.field";
    /** A speed limit: particles faster than {@code speed} m/s slow to it, after the forces. */
    public static final String SPEED_LIMIT = "limit.speed";
    /** The asset a vector field module has until one is given: none (refused when an effect is built). */
    public static final AssetRef<?> NO_FIELD = AssetRef.of(EffectAssets.EFFECT, new AssetId(0, 0));

    private Forces() {
    }

    /** Gravity: particles fall at {@link #G}. */
    public static Module gravity() {
        return gravity(Bind.constant(1));
    }

    /** Gravity scaled by {@code scale}: 0.3 for floating embers, -0.1 for rising smoke. */
    public static Module gravity(Binding scale) {
        return Module.of(GRAVITY).with("scale", scale);
    }

    /**
     * Linear drag of {@code coefficient} per second: velocity decays as {@code exp(-coefficient t)}, and with gravity
     * approaches a terminal velocity of {@code g / coefficient}.
     */
    public static Module drag(Binding coefficient) {
        return Module.of(DRAG).with("coefficient", coefficient);
    }

    /** Linear drag of {@code coefficient} per second. */
    public static Module drag(float coefficient) {
        return drag(Bind.constant(coefficient));
    }

    /** The world's wind, picked up at {@code coupling} per second: leaves and smoke at 1 to 3, sparks barely. */
    public static Module wind(float coupling) {
        return Module.of(WIND).with("coupling", coupling);
    }

    /** The world's wind times {@code scale}, picked up at {@code coupling} per second, varying by {@code gusts}. */
    public static Module wind(float coupling, float scale, float gusts) {
        return wind(coupling).with("scale", scale).with("gusts", gusts);
    }

    /** Curl noise of {@code strength} m/s² with features {@code 1 / frequency} metres across, one octave, still. */
    public static Module curl(float strength, float frequency) {
        return Module.of(CURL).with("strength", strength).with("frequency", frequency);
    }

    /** Curl noise of {@code strength} m/s², drifting at {@code speed} per second, of {@code octaves} (1 to 4). */
    public static Module curl(Binding strength, float frequency, float speed, int octaves) {
        return Module.of(CURL).with("strength", strength).with("frequency", frequency).with("speed", speed)
            .with("octaves", octaves);
    }

    /** A vortex of {@code strength} m/s² about the emitter's +Y axis. */
    public static Module vortex(float strength) {
        return Module.of(VORTEX).with("strength", strength);
    }

    /** A vortex of {@code strength} m/s² about the emitter's axis ({@code x}, {@code y}, {@code z}), pulling in. */
    public static Module vortex(Binding strength, float pull, float x, float y, float z) {
        return Module.of(VORTEX).with("strength", strength).with("pull", pull).with("axisX", x).with("axisY", y)
            .with("axisZ", z);
    }

    /** A point attractor of {@code strength} m/s² (negative repels) at ({@code x}, {@code y}, {@code z}). */
    public static Module point(Binding strength, float x, float y, float z) {
        return Module.of(POINT).with("strength", strength).with("x", x).with("y", y).with("z", z);
    }

    /** A line attractor of {@code strength} m/s² along the emitter's axis ({@code x}, {@code y}, {@code z}). */
    public static Module line(Binding strength, float x, float y, float z) {
        return Module.of(LINE).with("strength", strength).with("axisX", x).with("axisY", y).with("axisZ", z);
    }

    /** An orbit of {@code speed} radians a second about the emitter's +Y axis. */
    public static Module orbit(float speed) {
        return Module.of(ORBIT).with("speed", speed);
    }

    /** An orbit of {@code speed} radians a second about the emitter's axis ({@code x}, {@code y}, {@code z}). */
    public static Module orbit(Binding speed, float x, float y, float z) {
        return Module.of(ORBIT).with("speed", speed).with("axisX", x).with("axisY", y).with("axisZ", z);
    }

    /**
     * The vector field {@code field} ({@link EffectAssets#VECTOR_FIELD}) of {@code strength} over a box of
     * {@code size} metres a side.
     */
    public static Module field(AssetRef<?> field, float strength, float size) {
        return Module.of(FIELD).with("field", field).with("strength", strength).with("sizeX", size)
            .with("sizeY", size).with("sizeZ", size);
    }

    /** At most {@code speed} metres per second. */
    public static Module speedLimit(float speed) {
        return Module.of(SPEED_LIMIT).with("speed", speed);
    }
}
