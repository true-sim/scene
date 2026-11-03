package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Clips, curves, ranges, attenuation and the small values sounds are made of. */
class ValuesTest {

    @Test
    void clipsHoldTheirSamplesUntilClosed() {
        AudioClip clip = AudioClip.builder(48_000, 2).pcm16(new short[] {0, 16384, -32768, 32767})
            .marker("b", 2).marker("a", 1).loudness(-20, 0.7f).build();
        assertThat(clip.frames()).isEqualTo(2);
        assertThat(clip.seconds()).isEqualTo(2 / 48_000.0);
        assertThat(clip.sample(0, 1)).isEqualTo(0.5f);
        assertThat(clip.sample(1, 0)).isEqualTo(-1);
        assertThat(clip.looped()).isFalse();
        assertThat(clip.loopStart()).isZero();
        assertThat(clip.loopEnd()).isEqualTo(2);
        assertThat(clip.markers()).extracting(SoundMarker::name).containsExactly("a", "b");
        assertThat(clip.loudness()).isEqualTo(-20);
        assertThat(clip.gain()).isEqualTo(0.7f);
        assertThat(clip.streamed()).isFalse();
        assertThat(clip.bytes()).isEqualTo(8);
        assertThat(clip.segment().getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, 1)).isEqualTo((short) 16384);
        assertThat(clip.toString()).isEqualTo("audio clip 2 ch 48000 Hz 2 frames PCM16");
        assertThat(AudioClip.Encoding.FLOAT.bytes()).isEqualTo(4);
        clip.close();
        clip.close();
        assertThatThrownBy(() -> clip.sample(0, 0)).isInstanceOf(IllegalStateException.class);

        try (AudioClip floats = AudioClip.builder(8_000, 1).floats(new float[] {0.25f, -0.5f, 1}).loop(1, 3).build()) {
            assertThat(floats.sample(1, 0)).isEqualTo(-0.5f);
            assertThat(floats.looped()).isTrue();
            assertThat(floats.loopStart()).isEqualTo(1);
            assertThat(floats.loopEnd()).isEqualTo(3);
            assertThat(floats.loudness()).isNaN();
            assertThat(floats.gain()).isEqualTo(1);
        }
        try (AudioClip empty = AudioClip.builder(8_000, 1).floats(new float[0]).build()) {
            assertThat(empty.frames()).isZero();
        }
    }

    @Test
    void clipsReadThroughDecoders() {
        try (AudioClip clip = AudioClip.builder(8_000, 2).floats(new float[] {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f})
                .build(); ClipDecoder d = clip.open()) {
            float[] out = new float[8];
            d.seek(1);
            assertThat(d.read(out, 1, 4)).isEqualTo(2);
            assertThat(out).startsWith(0, 0, 0.3f, 0.4f, 0.5f, 0.6f);
            assertThat(d.read(out, 0, 4)).isZero();
        }
        AudioClip.Opener opener = () -> null;
        try (AudioClip streamed = AudioClip.builder(48_000, 1).streamed(96_000, opener, 1234).loop(10, 20).build()) {
            assertThat(streamed.streamed()).isTrue();
            assertThat(streamed.frames()).isEqualTo(96_000);
            assertThat(streamed.bytes()).isEqualTo(1234);
            assertThat(streamed.encoding()).isEqualTo(AudioClip.Encoding.FLOAT);
            assertThat(streamed.open()).isNull();
        }
    }

    @Test
    void clipsRefuseWhatCannotBe() {
        assertThatThrownBy(() -> AudioClip.builder(0, 1)).hasMessage("Not a sample rate: 0");
        assertThatThrownBy(() -> AudioClip.builder(1, 0)).hasMessage("Clips have 1 to 8 channels, not 0");
        assertThatThrownBy(() -> AudioClip.builder(1, 9)).hasMessage("Clips have 1 to 8 channels, not 9");
        assertThatThrownBy(() -> AudioClip.builder(1, 2).pcm16(new short[3]))
            .hasMessage("3 samples are no whole number of 2-channel frames");
        assertThatThrownBy(() -> AudioClip.builder(1, 1).build()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AudioClip.builder(1, 1).loop(-1, 2)).hasMessage("Not a loop region: -1 to 2");
        assertThatThrownBy(() -> AudioClip.builder(1, 1).loop(2, 2)).hasMessage("Not a loop region: 2 to 2");
        assertThatThrownBy(() -> AudioClip.builder(1, 1).floats(new float[2]).loop(0, 3).build())
            .hasMessage("The loop ends past the clip: 3 > 2");
        assertThatThrownBy(() -> AudioClip.builder(1, 1).floats(new float[2]).marker("x", 3).build())
            .hasMessageContaining("A marker past the clip");
        assertThatThrownBy(() -> new SoundMarker("x", -1)).hasMessage("A marker before the clip: -1");
    }

    @Test
    void curvesInterpolateAndClamp() {
        Curve c = Curve.points(0, 1, 1, 3, 3, 0);
        assertThat(c.kind()).isEqualTo(Curve.Kind.POINTS);
        assertThat(c.at(-1)).isEqualTo(1);
        assertThat(c.at(0.5f)).isEqualTo(2);
        assertThat(c.at(2)).isEqualTo(1.5f);
        assertThat(c.at(5)).isZero();
        assertThat(c.gain(2, 0, 10)).isEqualTo(1.5f);
        assertThat(Curve.points(4, 2).at(9)).isEqualTo(2);
        assertThat(c).isEqualTo(Curve.points(0, 1, 1, 3, 3, 0)).hasSameHashCodeAs(Curve.points(0, 1, 1, 3, 3, 0))
            .isNotEqualTo(Curve.points(0, 1)).isNotEqualTo(Curve.LINEAR).isNotEqualTo("curve");
        assertThat(c.toString()).isEqualTo("curve [0.0, 1.0, 1.0, 3.0, 3.0, 0.0]");
        assertThat(Curve.INVERSE.toString()).isEqualTo("inverse");
        assertThatThrownBy(() -> Curve.points(1)).hasMessageContaining("x, y pairs");
        assertThatThrownBy(() -> Curve.points()).hasMessageContaining("x, y pairs");
        assertThatThrownBy(() -> Curve.points(1, 2, 3)).hasMessageContaining("x, y pairs");
        assertThatThrownBy(() -> Curve.points(1, 0, 1, 1)).hasMessageContaining("must increase");
    }

    @Test
    void distanceLawsRunFromOneAtTheMinimumToZeroAtTheMaximum() {
        assertThat(Curve.INVERSE.gain(0.5f, 1, 10)).isEqualTo(1);
        assertThat(Curve.INVERSE.gain(10, 1, 10)).isZero();
        assertThat(Curve.INVERSE.gain(20, 1, 10)).isZero();
        assertThat((double) Curve.INVERSE.gain(2, 1, 10)).isCloseTo((0.5 - 0.1) / 0.9, within(1e-6));
        assertThat(Curve.LINEAR.gain(5.5f, 1, 10)).isEqualTo(0.5f);
    }

    @Test
    void attenuationGivesGainFilterSpreadSendAndCone() {
        Attenuation a = Attenuation.builder().distance(2, 120).curve(Curve.LINEAR).lowPass(20_000, 5_000)
            .spread(3, 0.1).reverbSend(0.1f, 0.6f).cone(90, 180, 0.25).build();
        assertThat(a.gain(61)).isEqualTo(0.5f);
        assertThat((double) a.lowPass(2)).isCloseTo(20_000, within(0.01));
        assertThat((double) a.lowPass(61)).isCloseTo(10_000, within(0.5));
        assertThat((double) a.lowPass(500)).isCloseTo(5_000, within(0.01));
        assertThat(a.spread(1)).isEqualTo(1);
        assertThat(a.spread(6)).isEqualTo(0.5f);
        assertThat(a.spread(100)).isEqualTo(0.1f);
        assertThat(a.spread(0)).isEqualTo(1);
        assertThat(a.reverbSend(0)).isEqualTo(0.1f);
        assertThat(a.reverbSend(61)).isCloseTo(0.35f, within(1e-6f));
        assertThat(a.coneGain(30)).isEqualTo(1);
        assertThat(a.coneGain(-67.5f)).isEqualTo(0.625f);
        assertThat(a.coneGain(120)).isEqualTo(0.25f);
        Attenuation plain = Attenuation.near(1, 25);
        assertThat(plain.lowPass(10)).isZero();
        assertThat(plain.curve()).isEqualTo(Curve.INVERSE);
        assertThat(plain.coneGain(180)).isEqualTo(1);
        assertThat(plain.spread(10)).isZero();
        assertThatThrownBy(() -> Attenuation.near(0, 1)).hasMessageContaining("Not distances");
        assertThatThrownBy(() -> Attenuation.near(2, 2)).hasMessageContaining("Not distances");
        assertThatThrownBy(() -> Attenuation.near(1, Double.POSITIVE_INFINITY)).hasMessageContaining("Not distances");
        assertThatThrownBy(() -> Attenuation.builder().lowPass(-1, -1).build()).hasMessageContaining("Low-pass");
        assertThatThrownBy(() -> Attenuation.builder().lowPass(1, -1).build()).hasMessageContaining("Low-pass");
        assertThatThrownBy(() -> Attenuation.builder().lowPass(100, 0).build()).hasMessageContaining("Low-pass");
        assertThatThrownBy(() -> Attenuation.builder().spread(-1, 0).build()).hasMessageContaining("Not a spread");
        assertThatThrownBy(() -> Attenuation.builder().spread(1, -1).build()).hasMessageContaining("Not a spread");
        assertThatThrownBy(() -> Attenuation.builder().spread(1, 2).build()).hasMessageContaining("Not a spread");
        for (float[] send : new float[][] {{-1, 0}, {2, 0}, {0, -1}, {0, 2}}) {
            assertThatThrownBy(() -> Attenuation.builder().reverbSend(send[0], send[1]).build())
                .hasMessageContaining("Reverb sends");
        }
        for (double[] cone : new double[][] {{-1, 90, 0}, {90, 45, 0}, {90, 400, 0}, {90, 180, -1}, {90, 180, 2}}) {
            assertThatThrownBy(() -> Attenuation.builder().cone(cone[0], cone[1], cone[2]).build())
                .hasMessageContaining("Not a cone");
        }
    }

    @Test
    void rangesDrawGainsAndRates() {
        assertThat(Range.db(-6, 0).factor(0)).isCloseTo(0.501f, within(0.001f));
        assertThat(Range.db(-6, 0).factor(1)).isEqualTo(1);
        assertThat((double) Range.semitones(12, 12).factor(0.3f)).isCloseTo(2, within(1e-6));
        assertThat(Range.semitones(-12, 0).factor(0)).isEqualTo(0.5f);
        assertThatThrownBy(() -> Range.db(1, 0)).hasMessage("Not a range: 1.0 to 0.0");
        assertThatThrownBy(() -> Range.db(Double.NaN, 0)).hasMessageContaining("Not a range");
        assertThatThrownBy(() -> Range.db(0, Double.POSITIVE_INFINITY)).hasMessageContaining("Not a range");
        assertThatThrownBy(() -> Range.db(Double.NEGATIVE_INFINITY, 0)).hasMessageContaining("Not a range");
        assertThat(Decibels.gain(Float.NEGATIVE_INFINITY)).isZero();
        assertThat((double) Decibels.gain(20)).isCloseTo(10, within(1e-5));
        assertThat((double) Decibels.of(0.1f)).isCloseTo(-20, within(1e-4));
        assertThat(Decibels.of(0)).isEqualTo(Float.NEGATIVE_INFINITY);
    }

    @Test
    void parametersAndSwitchesAreTypedConstants() {
        SoundParameter rpm = SoundParameter.of("rpm", 800, 0, 9000);
        assertThat(rpm.clamp(10_000)).isEqualTo(9000);
        assertThat(rpm.clamp(-1)).isZero();
        assertThat(rpm.clamp(Float.NaN)).isEqualTo(800);
        assertThat(rpm.clamp(42)).isEqualTo(42);
        assertThatThrownBy(() -> SoundParameter.of("x", 2, 0, 1)).hasMessage("Parameter x: 2.0 is not in [0.0, 1.0]");
        assertThatThrownBy(() -> SoundParameter.of("x", -1, 0, 1)).hasMessageContaining("is not in");
        assertThat(SwitchGroup.of("s", Thread.State.class, Thread.State.NEW).fallback()).isEqualTo(Thread.State.NEW);
    }

    @Test
    void smallValues() {
        assertThat(Priority.of(4)).isEqualTo(Priority.HIGHEST);
        assertThat(Priority.LOWEST.weight()).isEqualTo(1 / 16f);
        assertThat(Priority.HIGHEST.weight()).isEqualTo(16);
        Concurrency c = Concurrency.max(4).group("impacts").retrigger(0.05).steal(Concurrency.Steal.REJECT_NEW);
        assertThat(c).isEqualTo(new Concurrency("impacts", 4, Concurrency.Steal.REJECT_NEW, 0.05));
        assertThatThrownBy(() -> Concurrency.max(0)).hasMessage("A group plays at least one: 0");
        assertThatThrownBy(() -> Concurrency.max(1).retrigger(-1)).hasMessage("Not a retrigger time: -1.0");
        assertThat(Release.crossfade(0.1).seconds()).isEqualTo(0.1);
        assertThat(Release.AT_MARKER.kind()).isEqualTo(Release.Kind.AT_MARKER);
        assertThatThrownBy(() -> Release.crossfade(-1)).hasMessage("Not a cross-fade: -1.0");
        assertThatThrownBy(() -> Release.crossfade(Double.POSITIVE_INFINITY)).hasMessageContaining("cross-fade");
        Duck d = Duck.by(-9).attack(0.1).release(0.6);
        assertThat(d).isEqualTo(new Duck(-9, 0.1, 0.6));
        assertThatThrownBy(() -> Duck.by(0)).hasMessageContaining("lowers");
        assertThatThrownBy(() -> Duck.by(Double.NEGATIVE_INFINITY)).hasMessageContaining("lowers");
        assertThatThrownBy(() -> Duck.by(-1).attack(-1)).hasMessageContaining("attack and release");
        assertThatThrownBy(() -> Duck.by(-1).release(-1)).hasMessageContaining("attack and release");
        assertThatThrownBy(() -> Duck.by(-1).release(Double.POSITIVE_INFINITY)).hasMessageContaining("attack");
        assertThat(Caption.of("explosion").key()).isEqualTo("explosion");
        assertThat(List.of(Spatial.values())).hasSize(3);
        assertThat(TimeScaling.valueOf("PITCH")).isEqualTo(TimeScaling.PITCH);
        assertThat(Occlusion.valueOf("RAYS")).isEqualTo(Occlusion.RAYS);
    }
}
