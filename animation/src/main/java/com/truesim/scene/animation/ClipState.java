package com.truesim.scene.animation;

/**
 * A motion playing on a layer of an animator: where it is, how fast it goes, whether it loops, how much it weighs and
 * how it fades. {@link Animator.Playback#play} gives one; change it any time between updates. Setters return it, so
 * calls chain. Valid while it plays: a layer reuses the states of motions that faded out.
 *
 * <pre>{@code
 * a.layer(base).play(walk, 0.2).speed(1.2).loop(true);
 * }</pre>
 */
public final class ClipState {

    private final double[] blendWeight = new double[1];
    private final Animator animator;
    Motion motion;
    double phase;          // 0 to 1 through the motion
    private double speed;
    private boolean loop;
    private boolean paused;
    private double weight;
    double fade;           // 0 to 1
    double fadeTarget;
    private double fadeRate; // per second
    double fadeOut;        // played once: seconds before its end to fade out over; -1 none
    boolean fresh;         // not advanced yet: events at its start fire
    long started;          // when it started, for the layer's newest

    ClipState(Animator animator) {
        this.animator = animator;
    }

    void start(Motion motion, double fadeIn, boolean loop, double fadeOut, long started) {
        this.motion = motion;
        this.phase = 0;
        this.speed = 1;
        this.loop = loop;
        this.paused = false;
        this.weight = 1;
        this.fade = fadeIn > 0 ? 0 : 1;
        this.fadeTarget = 1;
        this.fadeRate = fadeIn > 0 ? 1 / fadeIn : 0;
        this.fadeOut = fadeOut;
        this.fresh = true;
        this.started = started;
    }

    /** What it plays. */
    public Motion motion() {
        return motion;
    }

    /** Seconds it lasts at the animator's parameters (a blend space's depend on where its parameter is). */
    public double duration() {
        if (motion instanceof Clip clip) {
            return clip.duration();
        }
        BlendSpace1 space = (BlendSpace1) motion;
        int first = space.blend(animator.parameter(space), blendWeight);
        double a = blendWeight[0];
        double d = a * space.clip(first).duration();
        if (a < 1) {
            d += (1 - a) * space.clip(first + 1).duration();
        }
        return d;
    }

    /** Seconds into it, from 0 to its duration. */
    public double time() {
        return phase * duration();
    }

    /** Jumps to {@code seconds} into it: wrapped into it when looping, else clamped to it. */
    public ClipState time(double seconds) {
        if (!Double.isFinite(seconds)) {
            throw new IllegalArgumentException("Not a time: " + seconds);
        }
        double duration = duration();
        phase = place(duration > 0 ? seconds / duration : 0);
        return this;
    }

    /** How far through it is, 0 to 1. */
    public double phase() {
        return phase;
    }

    /** How many seconds of it play in a second: 1 at its own pace, negative backwards. */
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

    /** Holds where it is: the pose stays, time does not move (fades go on). */
    public ClipState pause() {
        paused = true;
        return this;
    }

    public ClipState resume() {
        paused = false;
        return this;
    }

    /** Its own weight, 0 to 1, times its fade: how much it counts among its layer's motions. */
    public double weight() {
        return weight;
    }

    public ClipState weight(double weight) {
        if (!(weight >= 0 && weight <= 1)) {
            throw new IllegalArgumentException("A weight from 0 to 1: " + weight);
        }
        this.weight = weight;
        return this;
    }

    /** How far it has faded in, 0 to 1. */
    public double fade() {
        return fade;
    }

    /** Whether it is fading out, to leave its layer. */
    public boolean fadingOut() {
        return fadeTarget == 0;
    }

    /** Whether it reached its end (its start, backwards) and does not loop. */
    public boolean finished() {
        return !loop && (speed >= 0 ? phase >= 1 : phase <= 0);
    }

    double effectiveWeight() {
        return weight * fade;
    }

    /** Fades it out over {@code seconds} (0: at once). */
    void fadeOutOver(double seconds) {
        fadeTarget = 0;
        fadeRate = seconds > 0 ? Math.max(fade, 1e-9) / seconds : 0;
        if (seconds <= 0) {
            fade = 0;
        }
    }

    /** Moves its fade and phase on by {@code seconds}; returns its phase before. */
    double advance(double seconds) {
        double before = phase;
        if (fade != fadeTarget) {
            double step = fadeRate * seconds;
            fade = fade < fadeTarget ? Math.min(fadeTarget, fade + step) : Math.max(fadeTarget, fade - step);
        }
        if (!paused) {
            double duration = duration();
            phase = place(duration > 0 ? phase + seconds * speed / duration : 0);
            if (fadeOut >= 0 && fadeTarget != 0
                && (speed >= 0 ? 1 - phase : phase) * duration <= fadeOut * Math.abs(speed)) { // instant: at once
                fadeOutOver(fadeOut);
            }
        }
        return before;
    }

    /** Whether it is done: faded out, or played once to its end. */
    boolean gone() {
        return fadeTarget == 0 && fade == 0;
    }

    private double place(double p) {
        if (!loop) {
            return Math.min(Math.max(p, 0), 1);
        }
        double wrapped = p - Math.floor(p);
        return wrapped >= 1 ? 0 : wrapped;
    }

    @Override
    public String toString() {
        return "ClipState[" + motion + ", phase " + phase + ", weight " + weight + ", fade " + fade + "]";
    }
}
