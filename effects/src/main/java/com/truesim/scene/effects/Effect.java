package com.truesim.scene.effects;

/**
 * A handle of a playing effect, made once when it is played and kept: calls take primitives, look nothing up by name
 * and allocate nothing. Once the effect has ended (by itself, or killed) the handle is inert: changes do nothing,
 * {@link #alive()} is false and reads give NaN, unlike a removed node's handle, which throws. Its id's generation keeps
 * it from touching the next effect in its slot.
 *
 * <pre>{@code
 * Effect torch = effects.play(TORCH_FIRE, torchNode).offset(0, 0.4, 0);
 * torch.set(INTENSITY, 0.8f);
 * torch.timeScale(0.5f);
 * torch.stop();     // stops spawning; particles live out their lives
 * torch.kill();     // gone now
 * }</pre>
 */
public final class Effect {

    private final Effects effects;
    private final long id;

    Effect(Effects effects, long id) {
        this.effects = effects;
        this.id = id;
    }

    /** Its id in its {@link Effects}: the slot and the generation. */
    public long id() {
        return id;
    }

    /** Whether it still plays (stopping too): false once it ended or was killed. */
    public boolean alive() {
        return effects.alive(id);
    }

    /** Whether it stopped spawning (or ended). */
    public boolean stopped() {
        return !alive() || effects.stopped(id);
    }

    /** Its own time since it started, in seconds; NaN once ended. */
    public double time() {
        return alive() ? (double) effects.time(id) / Effects.TICKS_PER_SECOND : Double.NaN;
    }

    /** Sets float user parameter {@code parameter}, which its effect must have. */
    public Effect set(EffectParameter.Float parameter, float value) {
        effects.set(id, parameter, value, 0, 0, 0);
        return this;
    }

    /** Sets color user parameter {@code parameter} to the linear RGBA color given. */
    public Effect set(EffectParameter.Color parameter, float r, float g, float b, float a) {
        effects.set(id, parameter, r, g, b, a);
        return this;
    }

    /** Float user parameter {@code parameter}'s value; NaN once ended or if its effect lacks it. */
    public float get(EffectParameter.Float parameter) {
        return effects.get(id, parameter);
    }

    /** Scales its time by {@code timeScale} (0 to 32767): slow motion, or fast. */
    public Effect timeScale(float timeScale) {
        effects.timeScale(id, timeScale);
        return this;
    }

    /** Pauses it, or resumes it: while paused nothing spawns or moves. */
    public Effect pause(boolean pause) {
        effects.pause(id, pause);
        return this;
    }

    /** Places it at ({@code x}, {@code y}, {@code z}) in its node's frame: a socket's offset. */
    public Effect offset(double x, double y, double z) {
        effects.offset(id, x, y, z);
        return this;
    }

    /** Moves it to the world position ({@code x}, {@code y}, {@code z}), detached from any node. */
    public Effect moveTo(double x, double y, double z) {
        effects.moveTo(id, x, y, z);
        return this;
    }

    /** Its particles inherit {@code fraction} of its node's velocity at birth. */
    public Effect inheritVelocity(float fraction) {
        effects.inheritVelocity(id, fraction);
        return this;
    }

    /** Draws a placement of a shared effect at {@code scale} times its size (only placements draw scaled). */
    public Effect scale(float scale) {
        effects.scale(id, scale);
        return this;
    }

    /** Seeds it: equal seeds look alike, on every client. Played effects get distinct seeds of their own. */
    public Effect seed(int seed) {
        effects.seed(id, seed);
        return this;
    }

    /** Stops spawning; particles alive live out their lives, then it ends. */
    public void stop() {
        effects.stop(id);
    }

    /** Ends it now, particles and all. */
    public void kill() {
        effects.kill(id);
    }

    @Override
    public String toString() {
        return "Effect[" + Long.toHexString(id) + (alive() ? "" : ", ended") + "]";
    }
}
