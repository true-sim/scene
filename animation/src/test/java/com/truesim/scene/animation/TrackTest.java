package com.truesim.scene.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.animation.Track.Interpolation;
import com.truesim.scene.animation.Track.Property;
import org.junit.jupiter.api.Test;

class TrackTest {

    private static final float HALF = (float) Math.sqrt(0.5);

    private final int[] cursor = new int[1];
    private final double[] out = new double[4];

    @Test
    void refusesWhatIsNotATrack() {
        float[] one = {0};
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Track("", 0, Property.WEIGHTS, Interpolation.LINEAR, one, new float[0], 0));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Track("", 0, Property.SCALE, Interpolation.LINEAR, new float[0], new float[0], 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Track("", 0, Property.SCALE, Interpolation.LINEAR,
            new float[] {1, 1}, new float[6], 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Track("", 0, Property.SCALE, Interpolation.LINEAR,
            new float[] {Float.NaN, 1}, new float[6], 0));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Track("", 0, Property.SCALE, Interpolation.LINEAR, one, new float[4], 0));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Track("", 0, Property.SCALE, Interpolation.CUBIC_SPLINE, one, new float[3], 0));
    }

    @Test
    void describesItself() {
        float[] times = {0, 2};
        Track track = new Track("arm", 3, Property.WEIGHTS, Interpolation.STEP, times, new float[4], 2);
        times[1] = 5; // copied
        assertThat(track.nodeName()).isEqualTo("arm");
        assertThat(track.node()).isEqualTo(3);
        assertThat(track.property()).isEqualTo(Property.WEIGHTS);
        assertThat(track.interpolation()).isEqualTo(Interpolation.STEP);
        assertThat(track.components()).isEqualTo(2);
        assertThat(track.keyCount()).isEqualTo(2);
        assertThat(track.end()).isEqualTo(2);
        assertThat(track.bytes()).isEqualTo(64 + 8 + 16);
        assertThat(new Track("", 0, Property.ROTATION, Interpolation.LINEAR, new float[] {0}, new float[] {0, 0, 0, 1},
            9).components()).isEqualTo(4);
    }

    @Test
    void stepsHoldTheEarlierKey() {
        Track track = new Track("", 0, Property.TRANSLATION, Interpolation.STEP, new float[] {1, 2, 3},
            new float[] {1, 0, 0, 2, 0, 0, 3, 0, 0}, 0);
        assertThat(x(track, 0)).isEqualTo(1); // before the first key: the first
        assertThat(x(track, 1.5)).isEqualTo(1);
        assertThat(x(track, 2)).isEqualTo(2);
        assertThat(x(track, 2.99)).isEqualTo(2);
        assertThat(x(track, 3)).isEqualTo(3);
        assertThat(cursor[0]).isEqualTo(2);
        assertThat(x(track, 9)).isEqualTo(3); // after the last: the last
    }

    @Test
    void linesGoStraightBetweenKeys() {
        Track track = new Track("", 0, Property.SCALE, Interpolation.LINEAR, new float[] {0, 1},
            new float[] {1, 1, 1, 3, 5, 1}, 0);
        track.sample(0.25, cursor, 0, out);
        assertThat(out[0]).isEqualTo(1.5);
        assertThat(out[1]).isEqualTo(2);
        assertThat(out[2]).isEqualTo(1);
    }

    @Test
    void findsKeysForwardFromTheCursorAndAnywhereBySearch() {
        float[] times = new float[20];
        float[] values = new float[60];
        for (int k = 0; k < 20; k++) {
            times[k] = k;
            values[3 * k] = k;
        }
        Track track = new Track("", 0, Property.TRANSLATION, Interpolation.LINEAR, times, values, 0);
        assertThat(x(track, 0.5)).isEqualTo(0.5);
        assertThat(cursor[0]).isZero();
        assertThat(x(track, 2.5)).isEqualTo(2.5); // a few keys on: stepped to
        assertThat(cursor[0]).isEqualTo(2);
        assertThat(x(track, 15.5)).isEqualTo(15.5); // far on: searched
        assertThat(cursor[0]).isEqualTo(15);
        assertThat(x(track, 4.25)).isEqualTo(4.25); // back: searched
        assertThat(cursor[0]).isEqualTo(4);
        cursor[0] = -1;
        assertThat(x(track, 7.5)).isEqualTo(7.5);
        cursor[0] = 19; // the last key: no key after it to step to
        assertThat(x(track, 8.5)).isEqualTo(8.5);
        assertThat(x(track, 8.75)).isEqualTo(8.75);
    }

    @Test
    void rotationsTurnTheShortWayAtAnEvenPace() {
        // 0 to 90 degrees about y: halfway is 45.
        Track track = rotations(Interpolation.LINEAR, 0, 0, 0, 1, 0, HALF, 0, HALF);
        track.sample(0.5, cursor, 0, out);
        double half = Math.sin(Math.PI / 8);
        assertThat(out).containsExactly(new double[] {0, half, 0, Math.cos(Math.PI / 8)}, within(1e-6));
        // The same end as its negation: still the short way, not round the long one.
        Track negated = rotations(Interpolation.LINEAR, 0, 0, 0, 1, 0, -HALF, 0, -HALF);
        negated.sample(0.5, cursor, 0, out);
        assertThat(out).containsExactly(new double[] {0, half, 0, Math.cos(Math.PI / 8)}, within(1e-6));
        // Nearly the same rotation: straight, normalized.
        Track close = rotations(Interpolation.LINEAR, 0, 0, 0, 1, 0, 0.001f, 0, 1);
        close.sample(0.5, cursor, 0, out);
        assertThat(length(out)).isCloseTo(1, within(1e-9));
        assertThat(out[1]).isCloseTo(0.0005, within(1e-6));
    }

    @Test
    void keysAreNormalizedRotations() {
        Track track = rotations(Interpolation.STEP, 0, 0, 0, 2, 0, 0, 0, 0);
        track.sample(0, cursor, 0, out);
        assertThat(out).containsExactly(0, 0, 0, 1);
        track.sample(1, cursor, 0, out); // no direction: left as it is
        assertThat(out).containsExactly(0, 0, 0, 0);
    }

    @Test
    void cubicSplinesFollowTheirTangents() {
        // One component from 0 to 1 over 2 s, leaving at slope 1 and arriving at slope 0.
        Track track = new Track("", 0, Property.WEIGHTS, Interpolation.CUBIC_SPLINE, new float[] {0, 2},
            new float[] {9, 0, 1, 0, 1, 9}, 1);
        // p(u) = h00 * 0 + h10 * dt * 1 + h01 * 1 + h11 * dt * 0 at u = 0.5: 0.125 * 2 + 0.5
        track.sample(1, cursor, 0, out);
        assertThat(out[0]).isCloseTo(0.75, within(1e-9));
        track.sample(0, cursor, 0, out);
        assertThat(out[0]).isZero();
        track.sample(2, cursor, 0, out);
        assertThat(out[0]).isEqualTo(1);
        // Rotations come out normalized.
        Track turning = new Track("", 0, Property.ROTATION, Interpolation.CUBIC_SPLINE, new float[] {0, 1},
            new float[] {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, HALF, 0, HALF, 0, 0, 0, 0},
            0);
        turning.sample(0.5, cursor, 0, out);
        assertThat(length(out)).isCloseTo(1, within(1e-9));
        assertThat(out[1]).isPositive();
    }

    @Test
    void aSingleKeyHoldsForever() {
        Track track = new Track("", 0, Property.TRANSLATION, Interpolation.LINEAR, new float[] {1},
            new float[] {4, 5, 6}, 0);
        assertThat(x(track, 7)).isEqualTo(4);
    }

    private double x(Track track, double time) {
        track.sample(time, cursor, 0, out);
        return out[0];
    }

    private static Track rotations(Interpolation interpolation, float... values) {
        return new Track("", 0, Property.ROTATION, interpolation, new float[] {0, 1}, values, 0);
    }

    private static double length(double[] q) {
        return Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    }
}
