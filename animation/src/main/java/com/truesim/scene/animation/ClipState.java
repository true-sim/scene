package com.truesim.scene.animation;

import java.util.Objects;

/**
 * A clip playing on an animator: where it is, how fast it goes, whether it loops. {@link Animator#play} gives one;
 * change it any time between updates. Setters return it, so calls chain.
 *
 * <pre>{@code
 * animator.play(wave).speed(0.5).loop(false);
 * }</pre>
 */
public final class ClipState {

    private final AnimationClip clip;
    private double time;
    private double speed = 1;
    private boolean loop = true;
    private boolean paused;

    ClipState(AnimationClip clip) {
        this.clip = Objects.requireNonNull(clip, "clip");
    }

    public AnimationClip clip() {
        return clip;
    }

    /** Seconds into the clip, from 0 to its duration. */
    public double time() {
        return time;
    }

    /** Jumps to {@code seconds} into the clip: wrapped into it when looping, else clamped to it. */
    public ClipState time(double seconds) {
        if (!Double.isFinite(seconds)) {
            throw new IllegalArgumentException("Not a time: " + seconds);
        }
        time = place(seconds);
        return this;
    }

    /** How many seconds of the clip play in a second: 1 at its own pace, negative backwards. */
    public double speed() {
        return speed;
    }

    public ClipState speed(double speed) {
        if (!Double.isFinite(speed)) {
            throw new IllegalArgumentException("Not a speed: " + speed);
        }
        this.speed = speed;
        return this;
    }

    public boolean looping() {
        return loop;
    }

    /** Whether it starts over at its end (at its start, playing backwards); else it holds its last pose. */
    public ClipState loop(boolean loop) {
        this.loop = loop;
        return this;
    }

    public boolean paused() {
        return paused;
    }

    /** Holds where it is: the pose stays, time does not move. */
    public ClipState pause() {
        paused = true;
        return this;
    }

    public ClipState resume() {
        paused = false;
        return this;
    }

    /** Whether it reached its end (its start, backwards) and does not loop. */
    public boolean finished() {
        double duration = clip.duration();
        return !loop && (speed >= 0 ? time >= duration : time <= 0);
    }

    /** Moves time on by {@code seconds} of the game's clock. */
    void advance(double seconds) {
        if (!paused) {
            time = place(time + seconds * speed);
        }
    }

    private double place(double seconds) {
        double duration = clip.duration();
        if (duration <= 0) {
            return 0;
        }
        if (!loop) {
            return Math.min(Math.max(seconds, 0), duration);
        }
        double wrapped = seconds % duration;
        return wrapped < 0 ? wrapped + duration : wrapped;
    }
}
