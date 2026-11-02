package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * Spawns one-shots of a child at random positions around the listener (rain drops, birds), inside one instance.
 *
 * <pre>{@code
 * Scatter.of(DROPS).around(6).rate(INTENSITY, Curve.points(0, 2, 1, 40))
 * }</pre>
 *
 * @param sound     the one-shot spawned
 * @param radius    metres around the listener
 * @param rate      one-shots a second, when {@code rateCurve} is not given
 * @param rateCurve the rate by a parameter; {@code null} for the constant rate
 * @param shaping   volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Scatter(SoundNode sound, double radius, double rate, Shaping.ParamCurve rateCurve, Shaping shaping)
        implements SoundNode {

    public Scatter {
        Objects.requireNonNull(sound, "sound");
        Objects.requireNonNull(shaping, "shaping");
    }

    /** One-shots of {@code sound}, within 10 m at one a second, to tune. */
    public static Scatter of(SoundNode sound) {
        return new Scatter(sound, 10, 1, null, Shaping.NONE);
    }

    /** A copy spawning within {@code metres} of the listener. */
    public Scatter around(double metres) {
        return new Scatter(sound, metres, rate, rateCurve, shaping);
    }

    /** A copy spawning {@code perSecond} one-shots a second. */
    public Scatter rate(double perSecond) {
        return new Scatter(sound, radius, perSecond, null, shaping);
    }

    /** A copy spawning as many a second as {@code curve} gives at {@code parameter}'s value. */
    public Scatter rate(SoundParameter parameter, Curve curve) {
        return new Scatter(sound, radius, rate, new Shaping.ParamCurve(parameter, curve), shaping);
    }

    @Override
    public Scatter with(Shaping shaping) {
        return new Scatter(sound, radius, rate, rateCurve, shaping);
    }
}
