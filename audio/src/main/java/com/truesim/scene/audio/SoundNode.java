package com.truesim.scene.audio;

/**
 * A node of a {@link Sound}'s tree: a clip ({@link Play}), a container choosing or combining children
 * ({@link Random}, {@link Sequence}, {@link Switch}, {@link Layer}, {@link Blend}, {@link Segments},
 * {@link Scatter}) or a procedural source ({@link Patch}). Immutable and equal by value; the setters here return a
 * copy with the {@link Shaping} changed.
 *
 * <pre>{@code
 * Random.of(GRAVEL_1, GRAVEL_2, GRAVEL_3).noRepeat(1).volume(Range.db(-3, 0)).pitch(Range.semitones(-1, 1))
 * Play.clip(RAIN_LIGHT).volume(INTENSITY, Curve.points(0, 1, 0.6, 0))
 * }</pre>
 */
public sealed interface SoundNode permits Play, Random, Sequence, Switch, Layer, Blend, Segments, Scatter, Patch {

    /** What it adds to what it plays: volume, pitch, delay and curves. */
    Shaping shaping();

    /** A copy with {@code shaping}. */
    SoundNode with(Shaping shaping);

    /** A copy with a random volume per play. */
    default SoundNode volume(Range range) {
        return with(shaping().withVolume(range));
    }

    /** A copy with a random pitch per play. */
    default SoundNode pitch(Range range) {
        return with(shaping().withPitch(range));
    }

    /** A copy starting {@code seconds} after its parent. */
    default SoundNode delay(double seconds) {
        return with(shaping().withDelay(seconds));
    }

    /** A copy whose gain is multiplied by {@code curve} at {@code parameter}'s value. */
    default SoundNode volume(SoundParameter parameter, Curve curve) {
        return with(shaping().withCurve(true, parameter, curve));
    }

    /** A copy whose playback rate is multiplied by {@code curve} at {@code parameter}'s value. */
    default SoundNode pitch(SoundParameter parameter, Curve curve) {
        return with(shaping().withCurve(false, parameter, curve));
    }
}
