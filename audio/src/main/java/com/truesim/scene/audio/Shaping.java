package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What every {@link SoundNode} may add to what it plays: a random volume and pitch, a delay, and volume and pitch
 * following parameters through curves (a curve's value multiplies the gain, or the playback rate).
 *
 * @param volume       volume drawn once per play
 * @param pitch        pitch drawn once per play
 * @param delay        seconds before it starts, after its parent
 * @param volumeCurves gains by parameter, multiplied together
 * @param pitchCurves  rate factors by parameter, multiplied together
 */
public record Shaping(Range volume, Range pitch, double delay, List<ParamCurve> volumeCurves,
        List<ParamCurve> pitchCurves) {

    /** Nothing added. */
    public static final Shaping NONE = new Shaping(Range.UNITY_DB, Range.UNITY_SEMITONES, 0, List.of(), List.of());

    /**
     * A curve from a parameter's value.
     *
     * @param parameter the parameter read
     * @param curve     from its value to a factor
     */
    public record ParamCurve(SoundParameter parameter, Curve curve) {
        public ParamCurve {
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(curve, "curve");
            if (curve.kind() != Curve.Kind.POINTS) {
                throw new IllegalArgumentException("A parameter curve goes through points, not " + curve);
            }
        }
    }

    public Shaping {
        if (!volume.decibels() || pitch.decibels()) {
            throw new IllegalArgumentException("Volume ranges are in decibels and pitch ranges in semitones");
        }
        if (!(delay >= 0) || Double.isInfinite(delay)) {
            throw new IllegalArgumentException("Not a delay: " + delay);
        }
        volumeCurves = List.copyOf(volumeCurves);
        pitchCurves = List.copyOf(pitchCurves);
    }

    Shaping withVolume(Range range) {
        return new Shaping(range, pitch, delay, volumeCurves, pitchCurves);
    }

    Shaping withPitch(Range range) {
        return new Shaping(volume, range, delay, volumeCurves, pitchCurves);
    }

    Shaping withDelay(double seconds) {
        return new Shaping(volume, pitch, seconds, volumeCurves, pitchCurves);
    }

    @AllowAllocation("set up once")
    Shaping withCurve(boolean ofVolume, SoundParameter parameter, Curve curve) {
        List<ParamCurve> curves = new ArrayList<>(ofVolume ? volumeCurves : pitchCurves);
        curves.add(new ParamCurve(parameter, curve));
        return ofVolume ? new Shaping(volume, pitch, delay, curves, pitchCurves)
            : new Shaping(volume, pitch, delay, volumeCurves, curves);
    }
}
