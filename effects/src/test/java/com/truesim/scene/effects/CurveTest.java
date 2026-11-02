package com.truesim.scene.effects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** Curves and gradients: keys, interpolation, the key cap, equality. */
class CurveTest {

    @Test
    void curvesInterpolateStepLinearAndCubicKeys() {
        FloatCurve linear = FloatCurve.of(0, 0, 1, 2, 3, 2);
        assertThat(linear.evaluate(-1)).isZero();
        assertThat(linear.evaluate(0.5f)).isEqualTo(1);
        assertThat(linear.evaluate(2)).isEqualTo(2);
        assertThat(linear.evaluate(5)).isEqualTo(2);
        assertThat(linear.evaluate(Float.NaN)).as("NaN takes the last value").isEqualTo(2);
        FloatCurve step = FloatCurve.builder().step(0, 1).step(1, 5).build();
        assertThat(step.evaluate(0.99f)).isEqualTo(1);
        assertThat(step.evaluate(1)).isEqualTo(5);
        FloatCurve cubic = FloatCurve.builder().cubic(0, 0).cubic(1, 1).key(2, 0, Interpolation.CUBIC).build();
        assertThat(cubic.slope(0)).isEqualTo(1);
        assertThat(cubic.slope(1)).isZero();
        assertThat(cubic.slope(2)).isEqualTo(-1);
        assertThat(cubic.evaluate(0.5f)).isCloseTo(0.625f, within(1e-6f));
        assertThat(cubic.evaluate(1)).isEqualTo(1);
        assertThat(cubic.evaluate(1.5f)).isCloseTo(0.625f, within(1e-6f));
        // Searched among many keys.
        FloatCurve.Builder many = FloatCurve.builder();
        for (int i = 0; i < FloatCurve.MAX_KEYS; i++) {
            many.linear(i, i * i);
        }
        FloatCurve full = many.build();
        assertThat(full.size()).isEqualTo(FloatCurve.MAX_KEYS);
        for (int i = 0; i < FloatCurve.MAX_KEYS - 1; i++) {
            assertThat(full.evaluate(i + 0.5f)).isEqualTo((i * i + (i + 1) * (i + 1)) / 2f);
        }
        assertThat(full.min()).isZero();
        assertThat(full.max()).isEqualTo(31 * 31);
        assertThat(FloatCurve.of(0, 3, 1, -2).min()).isEqualTo(-2);
        assertThat(FloatCurve.of(0, 3, 1, -2).max()).isEqualTo(3);
    }

    @Test
    void aCurveOfOneKeyIsConstant() {
        FloatCurve one = FloatCurve.constant(4);
        assertThat(one.size()).isOne();
        assertThat(one.evaluate(-3)).isEqualTo(4);
        assertThat(one.evaluate(3)).isEqualTo(4);
        assertThat(one.slope(0)).isZero();
        assertThat(one.time(0)).isZero();
        assertThat(one.value(0)).isEqualTo(4);
        assertThat(one.interpolation(0)).isEqualTo(Interpolation.LINEAR);
    }

    @Test
    void curvesHaveAtMost32KeysAtIncreasingTimes() {
        FloatCurve.Builder b = FloatCurve.builder();
        for (int i = 0; i < FloatCurve.MAX_KEYS; i++) {
            b.linear(i, 0);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> b.linear(40, 0)).withMessageContaining("at most 32");
        assertThatIllegalArgumentException().isThrownBy(() -> FloatCurve.of(0, 1, 0, 2))
            .withMessageContaining("increase");
        assertThatIllegalArgumentException().isThrownBy(() -> FloatCurve.of(Float.NaN, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> FloatCurve.of(0, Float.POSITIVE_INFINITY))
            .withMessageContaining("finite");
        assertThatIllegalArgumentException().isThrownBy(() -> FloatCurve.of(0, 1, 2))
            .withMessageContaining("pairs");
        assertThatIllegalArgumentException().isThrownBy(() -> FloatCurve.of()).withMessageContaining("at least one");
    }

    @Test
    void curvesCompareByValue() {
        FloatCurve a = FloatCurve.of(0, 1, 1, 0);
        assertThat(a).isEqualTo(FloatCurve.of(0, 1, 1, 0)).hasSameHashCodeAs(FloatCurve.of(0, 1, 1, 0));
        assertThat(a).isNotEqualTo(FloatCurve.of(0, 1, 2, 0));
        assertThat(a).isNotEqualTo(FloatCurve.of(0, 1, 1, 1));
        assertThat(a).isNotEqualTo(FloatCurve.builder().linear(0, 1).step(1, 0).build());
        assertThat(a).isNotEqualTo("a curve");
        assertThat(a.toString()).isEqualTo("FloatCurve[0.0: 1.0 LINEAR, 1.0: 0.0 LINEAR]");
    }

    @Test
    void gradientsInterpolateEachComponent() {
        ColorGradient g = ColorGradient.builder().linear(0, 1, 0, 0, 1).step(1, 0, 1, 0, 1)
            .cubic(2, 0, 0, 1, 0).cubic(3, 0, 0, 0, 0).build();
        float[] rgba = new float[5];
        g.evaluate(-1, rgba, 1);
        assertThat(rgba).containsExactly(0, 1, 0, 0, 1);
        g.evaluate(0.5f, rgba, 0);
        assertThat(rgba).startsWith(0.5f, 0.5f, 0, 1);
        g.evaluate(1.5f, rgba, 0);
        assertThat(rgba).startsWith(0, 1, 0, 1);
        g.evaluate(2.5f, rgba, 0);
        assertThat(rgba[2]).isCloseTo(0.5f, within(0.2f));
        g.evaluate(9, rgba, 0);
        assertThat(rgba).startsWith(0, 0, 0, 0);
        assertThat(g.size()).isEqualTo(4);
        assertThat(g.time(3)).isEqualTo(3);
        assertThat(g.color(0, 3)).isEqualTo(1);
        assertThat(g.interpolation(1)).isEqualTo(Interpolation.STEP);
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> g.color(0, 4));
        ColorGradient white = ColorGradient.constant(1, 1, 1, 1);
        white.evaluate(0.5f, rgba, 0);
        assertThat(rgba).startsWith(1, 1, 1, 1);
    }

    @Test
    void gradientsHaveAtMost32KeysAndCompareByValue() {
        ColorGradient.Builder b = ColorGradient.builder();
        for (int i = 0; i < FloatCurve.MAX_KEYS; i++) {
            b.linear(i, 0, 0, 0, 0);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> b.linear(40, 0, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> ColorGradient.builder().build());
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ColorGradient.builder().linear(0, Float.NaN, 0, 0, 0).build());
        ColorGradient a = ColorGradient.builder().linear(0, 1, 0, 0, 1).linear(1, 0, 0, 0, 1).build();
        ColorGradient same = ColorGradient.builder().linear(0, 1, 0, 0, 1).linear(1, 0, 0, 0, 1).build();
        assertThat(a).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(a).isNotEqualTo(ColorGradient.builder().linear(0, 1, 0, 0, 1).linear(2, 0, 0, 0, 1).build());
        assertThat(a).isNotEqualTo(ColorGradient.builder().linear(0, 1, 0, 0, 1).linear(1, 0, 1, 0, 1).build());
        assertThat(a).isNotEqualTo(ColorGradient.builder().step(0, 1, 0, 0, 1).linear(1, 0, 0, 0, 1).build());
        assertThat(a).isNotEqualTo("a gradient");
        assertThat(a.toString()).isEqualTo("ColorGradient[2 keys from 0.0 to 1.0]");
    }
}
