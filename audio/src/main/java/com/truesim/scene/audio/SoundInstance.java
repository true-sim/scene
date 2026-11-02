package com.truesim.scene.audio;

/**
 * A handle of a playing sound, made once when it is played and kept: calls take primitives, look nothing up by name
 * and allocate nothing. Once the sound has ended (by itself, stopped, or killed) the handle is inert: changes do
 * nothing, {@link #alive()} is false and reads give NaN. Its id's generation keeps it from touching the next sound in
 * its slot.
 *
 * <pre>{@code
 * SoundInstance rain = sounds.play(RAIN);
 * rain.set(INTENSITY, 0.8f);                    // ramped over the default ramp
 * rain.set(INTENSITY, 0.2f, 4.0);               // over 4 s
 * rain.volume(-6, 1.0).pitch(1.0f).pause(true);
 * rain.stop();                                  // outro if any, else the sound's release fade
 * rain.stop(2.0);                               // fade out over 2 s
 * rain.kill();                                  // now (10 ms anti-click fade)
 * }</pre>
 */
public final class SoundInstance {

    private final Sounds sounds;
    private final long id;

    SoundInstance(Sounds sounds, long id) {
        this.sounds = sounds;
        this.id = id;
    }

    /** Its id in its {@link Sounds}; 0 if its concurrency rejected it. */
    public long id() {
        return id;
    }

    /** Whether it still plays (stopping too). */
    public boolean alive() {
        return sounds.alive(id);
    }

    /** Its own time since it started, in seconds; NaN once ended. */
    public double time() {
        return alive() ? (double) sounds.time(id) / Sounds.TICKS_PER_SECOND : Double.NaN;
    }

    /** Sets {@code parameter}, moving over {@link Sounds#DEFAULT_RAMP}. */
    public SoundInstance set(SoundParameter parameter, float value) {
        sounds.set(id, parameter, value, Sounds.DEFAULT_RAMP);
        return this;
    }

    /** Sets {@code parameter}, moving over {@code seconds}. */
    public SoundInstance set(SoundParameter parameter, float value, double seconds) {
        sounds.set(id, parameter, value, seconds);
        return this;
    }

    /** Sets switch {@code group}: counted if set the frame it was played, before its voices are planned. */
    public <E extends Enum<E>> SoundInstance set(SwitchGroup<E> group, E value) {
        sounds.set(id, group, value);
        return this;
    }

    /** {@code parameter}'s value; NaN once ended or if its sound reads none. */
    public float get(SoundParameter parameter) {
        return sounds.get(id, parameter);
    }

    /** Sets its volume to {@code db} decibels over {@code seconds}. */
    public SoundInstance volume(double db, double seconds) {
        sounds.volume(id, db, seconds);
        return this;
    }

    /** Sets its pitch, a playback-rate ratio. */
    public SoundInstance pitch(float ratio) {
        sounds.pitch(id, ratio);
        return this;
    }

    /** Pauses or resumes it. */
    public SoundInstance pause(boolean pause) {
        sounds.pause(id, pause);
        return this;
    }

    /** Scales its time, on top of its world's. */
    public SoundInstance timeScale(float timeScale) {
        sounds.timeScale(id, timeScale);
        return this;
    }

    /** Plays {@code listener}'s mix as its input (a camera on a television). */
    public SoundInstance input(Listener listener) {
        sounds.input(id, listener.id());
        return this;
    }

    /** Moves it to a world position, detaching it from its node. */
    public SoundInstance moveTo(double x, double y, double z) {
        sounds.moveTo(id, x, y, z);
        return this;
    }

    /** Sets its seed, if its voices are not planned yet. */
    public SoundInstance seed(int seed) {
        sounds.seed(id, seed);
        return this;
    }

    /** Stops it: the outro if it has one, else its sound's release fade. */
    public void stop() {
        sounds.stop(id);
    }

    /** Stops it fading out over {@code seconds}, skipping any outro. */
    public void stop(double seconds) {
        sounds.stop(id, seconds);
    }

    /** Ends it now (a 10 ms anti-click fade). */
    public void kill() {
        sounds.kill(id);
    }
}
