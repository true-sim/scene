package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Sounds as data: trees of nodes, defaults, equality as assets, and every problem with its suggestion. */
class SoundTest {

    enum Surface { DIRT, GRAVEL, WOOD }

    static final SwitchGroup<Surface> SURFACE = SwitchGroup.of("surface", Surface.class, Surface.DIRT);
    static final SoundParameter SPEED = SoundParameter.of("speed", 0, 0, 50);
    static final SoundParameter INTENSITY = SoundParameter.of("intensity", 0, 0, 1);

    static AssetRef<AudioClip> clip(int n) {
        return AssetRef.of(AudioAssets.CLIP, new AssetId(7, n));
    }

    @Test
    void equalDataMakesOneAsset() {
        Sound a = footsteps();
        Sound b = footsteps();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo("footsteps");
        assertThat(a.ref()).isEqualTo(b.ref());
        assertThat(a.ref().type()).isSameAs(AudioAssets.SOUND);
        assertThat(Sound.builder().root(Play.clip(clip(1))).caption(Caption.of("x")).build())
            .isNotEqualTo(Sound.builder().root(Play.clip(clip(1))).build());
        assertThat(a.bytes()).isGreaterThan(256);
        assertThat(a.toString()).startsWith("sound Switch");
    }

    private static Sound footsteps() {
        return Sound.builder().bus(Bus.child(Bus.SFX, "footsteps")).attenuation(Attenuation.near(1, 25))
            .priority(Priority.LOW).concurrency(Concurrency.max(12).steal(Concurrency.Steal.FARTHEST))
            .root(Switch.on(SURFACE)
                .when(Surface.GRAVEL, Random.of(clip(1), clip(2), clip(3)).noRepeat(1))
                .when(Surface.WOOD, clip(4))
                .otherwise(Random.of(clip(5), clip(6)).weights(1, 2).shuffle()))
            .volume(Range.db(-2, 0)).pitch(Range.semitones(-0.5, 0.5)).build();
    }

    @Test
    void aSoundListsWhatItsTreeReads() {
        Sound s = Sound.builder()
            .root(Layer.of(
                Play.clip(clip(1)).volume(INTENSITY, Curve.points(0, 1, 0.6, 0)),
                Blend.by(SPEED).at(0, Play.clip(clip(2))).at(10, Play.clip(clip(1))).pitch(SPEED, Curve.points(0, 1)),
                Sequence.of(Play.clip(clip(3)), Play.input()),
                Segments.intro(clip(4)).loop(clip(5)).outro(clip(6)).release(Release.crossfade(0.03)),
                Segments.loop(clip(7)),
                Segments.intro(Play.clip(clip(8))).loop(Play.clip(clip(8))).outro(Play.clip(clip(9))),
                Scatter.of(Play.clip(clip(10))).around(6).rate(INTENSITY, Curve.points(0, 2, 1, 40)),
                Scatter.of(Play.clip(clip(11))).rate(3),
                Switch.on(SURFACE).otherwise(Play.clip(clip(12))),
                Patch.named("v8").delay(0.5)))
            .loop().ignoresPause().build();
        assertThat(s.parameters()).containsExactly(INTENSITY, SPEED);
        assertThat(s.parameterIndex(SPEED)).isEqualTo(1);
        assertThat(s.parameterIndex(SoundParameter.of("rpm", 0, 0, 1))).isEqualTo(-1);
        assertThat(s.switches()).containsExactly(SURFACE);
        assertThat(s.switchIndex(SURFACE)).isZero();
        assertThat(s.clips()).hasSize(12).startsWith(clip(1), clip(2), clip(3));
        assertThat(s.loop()).isTrue();
        assertThat(s.ignoresPause()).isTrue();
        Layer layer = (Layer) s.root();
        assertThat(((Patch) layer.children().get(9)).shaping().delay()).isEqualTo(0.5);
        assertThat(((Sequence) layer.children().get(2)).children().get(1)).isEqualTo(Play.input());
        assertThat(Play.input().isInput()).isTrue();
        Segments segments = (Segments) layer.children().get(3);
        assertThat(segments.release().kind()).isEqualTo(Release.Kind.CROSSFADE);
        assertThat(segments.outro()).isEqualTo(Play.clip(clip(6)));
        assertThat(((Scatter) layer.children().get(7)).rateCurve()).isNull();
    }

    @Test
    void defaultsFollowHowASoundIsPlaced() {
        Sound world = Sound.of(clip(1));
        assertThat(world.bus()).isEqualTo(Bus.SFX);
        assertThat(world.spatial()).isEqualTo(Spatial.THREE_D);
        assertThat(world.timeScaling()).isEqualTo(TimeScaling.PITCH);
        assertThat(world.doppler()).isEqualTo(1);
        assertThat(world.attenuation()).isEqualTo(Attenuation.DEFAULT);
        assertThat(world.concurrency()).isEqualTo(Concurrency.UNLIMITED);
        assertThat(world.priority()).isEqualTo(Priority.NORMAL);
        assertThat(world.virtualization()).isEqualTo(Virtualization.KILL_IF_FINITE);
        assertThat(world.occlusion()).isEqualTo(Occlusion.NONE);
        assertThat(world.caption()).isNull();
        assertThat(world.maxStartDelay()).isEqualTo(0.1);
        assertThat(world.maxDuration()).isInfinite();
        assertThat(world.releaseFade()).isEqualTo(0.05);
        assertThat(world.volume()).isEqualTo(Range.UNITY_DB);
        assertThat(world.pitch()).isEqualTo(Range.UNITY_SEMITONES);
        assertThat(world.effects()).isEmpty();
        for (Sound flat : List.of(
                Sound.builder().root(Play.clip(clip(1))).spatial(Spatial.TWO_D).build(),
                Sound.builder().root(Play.clip(clip(1))).bus(Bus.UI).build(),
                Sound.builder().root(Play.clip(clip(1))).bus(Bus.VOICE).build(),
                Sound.builder().root(Play.clip(clip(1))).bus(Bus.MUSIC).build())) {
            assertThat(flat.timeScaling()).isEqualTo(TimeScaling.NONE);
            assertThat(flat.doppler()).isZero();
        }
        Sound set = Sound.builder().root(Play.clip(clip(1))).spatial(Spatial.HRTF).occlusion(Occlusion.RAYS)
            .timeScaling(TimeScaling.NONE).doppler(0.5).virtualization(Virtualization.KEEP_TIME)
            .maxStartDelay(0).maxDuration(3).releaseFade(0.2).effect(AudioEffect.named("eq.highPass").set("cutoff", 90))
            .build();
        assertThat(set.timeScaling()).isEqualTo(TimeScaling.NONE);
        assertThat(set.doppler()).isEqualTo(0.5f);
        assertThat(set.spatial()).isEqualTo(Spatial.HRTF);
        assertThat(set.occlusion()).isEqualTo(Occlusion.RAYS);
        assertThat(set.virtualization()).isEqualTo(Virtualization.KEEP_TIME);
        assertThat(set.maxDuration()).isEqualTo(3);
        assertThat(set.effects()).containsExactly(AudioEffect.named("eq.highPass").set("cutoff", 90));
    }

    @Test
    void everyProblemIsReportedWithWhereItIs() {
        assertThatThrownBy(() -> Sound.builder().build()).isInstanceOf(InvalidAudioException.class)
            .hasMessage("The sound has 1 problem:\n  It has no root: give it one with root()");
        InvalidAudioException e = (InvalidAudioException) org.assertj.core.api.Assertions.catchThrowable(() ->
            Sound.builder()
                .root(Layer.of(
                    Random.of(clip(1), clip(2)).weights(1).noRepeat(2),
                    Random.of(clip(1)).weights(0).noRepeat(-1),
                    Random.of(new SoundNode[0]).weights(Double.POSITIVE_INFINITY),
                    Sequence.of(),
                    Switch.on(SURFACE).when(Surface.WOOD, clip(1)).when(Surface.WOOD, clip(2)),
                    Blend.by(SPEED).at(5, Play.clip(clip(1))).at(5, Play.clip(clip(2))).at(1, Play.clip(clip(3))),
                    Scatter.of(Play.clip(clip(1))).around(0).rate(-1),
                    Scatter.of(Play.clip(clip(1))).around(Double.POSITIVE_INFINITY),
                    Patch.named(""),
                    Scatter.of(Play.clip(clip(1))).rate(-1),
                    Play.clip(clip(1)).volume(SoundParameter.of("speed", 1, 0, 2), Curve.points(0, 1))))
                .volume(new Range(0, 0, false)).maxStartDelay(-1).maxDuration(0).releaseFade(Double.NaN).doppler(-1)
                .spatial(Spatial.TWO_D).occlusion(Occlusion.RAYS)
                .effect(AudioEffect.named("eq.hihgPass").set("cutoff", 90))
                .effect(AudioEffect.named("compressor").set("treshold", -18).set("ratio", 3))
                .build());
        assertThat(e.problems()).containsExactly(
            "root > layer[0]: 1 weights for 2 children",
            "root > layer[0]: no repeat of 2 picks among 2 children (at most 1)",
            "root > layer[1]: a weight of 0.0 (weights are positive)",
            "root > layer[1]: no repeat of -1 picks among 1 children (at most 0)",
            "root > layer[2] > random: no children",
            "root > layer[2]: 1 weights for 0 children",
            "root > layer[2]: a weight of Infinity (weights are positive)",
            "root > layer[3] > sequence: no children",
            "root > layer[4] > switch on surface: two cases for WOOD",
            "root > layer[4] > switch on surface: no case for DIRT and no otherwise",
            "root > layer[4] > switch on surface: no case for GRAVEL and no otherwise",
            "root > layer[5]: blend points must increase: [5.0, 5.0, 1.0]",
            "root > layer[6]: a scatter within 0.0 m at -1.0 a second",
            "root > layer[7]: a scatter within Infinity m at 1.0 a second",
            "root > layer[8]: a patch without a name",
            "root > layer[9]: a scatter within 10.0 m at -1.0 a second",
            "root > layer[10]: two parameters called speed, " + SPEED + " and SoundParameter[name=speed, fallback=1.0,"
                + " min=0.0, max=2.0]",
            "Its volume is a range of decibels and its pitch one of semitones",
            "Its max start delay is seconds, not -1.0",
            "Its max duration is seconds, not 0.0",
            "Its release fade is seconds, not NaN",
            "Its Doppler amount is 0 or more, not -1.0",
            "A 2D sound is not occluded: it has no place",
            "Its chain: no effect is called eq.hihgPass (did you mean eq.highPass?)",
            "Its chain: effect compressor has no field treshold (did you mean threshold?)");
        assertThat(e.getMessage()).startsWith("The sound has 25 problems:\n  root > layer[0]");
    }

    @Test
    void lesserProblems() {
        assertThatThrownBy(() -> Sound.builder().root(Play.input()).pitch(Range.db(0, 0)).build())
            .hasMessageContaining("its pitch one of semitones");
        assertThatThrownBy(() -> Sound.builder().root(Play.input()).releaseFade(Double.POSITIVE_INFINITY).build())
            .hasMessageContaining("release fade");
    }

    @Test
    void anInlineSoundLoadsAsItself() throws InterruptedException {
        Sound sound = Sound.of(clip(1));
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            AssetRef<Sound> ref = sound.ref();
            cache.acquire(ref);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cache.loadingCount() > 0) {
                tasks.drainCompleted();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
            }
            assertThat(cache.get(ref)).isSameAs(sound);
        }
    }

    @Test
    void parametersSharedByNameMustAgree() {
        SoundParameter other = SoundParameter.of("speed", 1, 0, 2);
        assertThatThrownBy(() -> Sound.builder().root(Blend.by(other).at(0, Play.clip(clip(1)))
                .pitch(SPEED, Curve.points(0, 1))).build())
            .hasMessageContaining("two parameters called speed");
        assertThatThrownBy(() -> Sound.builder().root(Scatter.of(Play.clip(clip(1))).rate(other, Curve.points(0, 1))
                .volume(SPEED, Curve.points(0, 1))).build())
            .hasMessageContaining("two parameters called speed");
        Sound shared = Sound.builder().root(Layer.of(Play.clip(clip(1)).volume(SPEED, Curve.points(0, 1)),
            Play.clip(clip(1)).pitch(SPEED, Curve.points(0, 1)), Switch.on(SURFACE).otherwise(Play.clip(clip(1))),
            Switch.on(SURFACE).otherwise(Play.clip(clip(2))))).build(new AudioEffectTypes());
        assertThat(shared.parameters()).containsExactly(SPEED);
        assertThat(shared.switches()).containsExactly(SURFACE);
        assertThat(shared.clips()).containsExactly(clip(1), clip(2));
    }

    @Test
    void nodesAreValuesWithShaping() {
        SoundNode node = Play.clip(clip(1)).volume(Range.db(-3, 0)).pitch(Range.semitones(-1, 1)).delay(0.25)
            .volume(INTENSITY, Curve.points(0, 0, 1, 1)).pitch(SPEED, Curve.points(0, 1, 50, 2));
        assertThat(node.shaping().volume()).isEqualTo(Range.db(-3, 0));
        assertThat(node.shaping().pitch()).isEqualTo(Range.semitones(-1, 1));
        assertThat(node.shaping().delay()).isEqualTo(0.25);
        assertThat(node.shaping().volumeCurves()).containsExactly(new Shaping.ParamCurve(INTENSITY,
            Curve.points(0, 0, 1, 1)));
        assertThat(node.shaping().pitchCurves()).hasSize(1);
        for (SoundNode n : List.<SoundNode>of(Random.of(clip(1)), Sequence.of(Play.clip(clip(1))),
                Layer.of(Play.clip(clip(1))), Switch.on(SURFACE).otherwise(Play.clip(clip(1))),
                Blend.by(SPEED).at(0, Play.clip(clip(1))), Segments.loop(clip(1)), Scatter.of(Play.clip(clip(1))),
                Patch.named("p"))) {
            SoundNode delayed = n.delay(1);
            assertThat(delayed).isNotEqualTo(n).isInstanceOf(n.getClass());
            assertThat(delayed.shaping().delay()).isEqualTo(1);
        }
        assertThatThrownBy(() -> new Shaping(Range.semitones(0, 0), Range.UNITY_SEMITONES, 0, List.of(), List.of()))
            .hasMessageContaining("decibels");
        assertThatThrownBy(() -> new Shaping(Range.UNITY_DB, Range.UNITY_DB, 0, List.of(), List.of()))
            .hasMessageContaining("decibels");
        assertThatThrownBy(() -> Play.input().delay(-1)).hasMessage("Not a delay: -1.0");
        assertThatThrownBy(() -> Play.input().delay(Double.POSITIVE_INFINITY)).hasMessageContaining("Not a delay");
        assertThatThrownBy(() -> Play.input().volume(SPEED, Curve.INVERSE)).hasMessageContaining("through points");
    }

    @Test
    void switchesAndBlendsPickTheirChildren() {
        Switch<Surface> s = Switch.on(SURFACE).when(Surface.GRAVEL, clip(1)).otherwise(Play.clip(clip(2)));
        assertThat(s.child(Surface.GRAVEL.ordinal())).isEqualTo(Play.clip(clip(1)));
        assertThat(s.child(Surface.WOOD.ordinal())).isEqualTo(Play.clip(clip(2)));
        assertThat(Switch.on(SURFACE).child(0)).isNull();
        Blend b = Blend.by(SPEED).at(0, Play.clip(clip(1))).at(10, Play.clip(clip(2))).at(30, Play.clip(clip(3)));
        assertThat(b.gain(0, -5)).isEqualTo(1);
        assertThat(b.gain(0, 0)).isEqualTo(1);
        assertThat(b.gain(0, 5)).isEqualTo(0.5f);
        assertThat(b.gain(0, 20)).isZero();
        assertThat(b.gain(1, 5)).isEqualTo(0.5f);
        assertThat(b.gain(1, 20)).isEqualTo(0.5f);
        assertThat(b.gain(1, -1)).isZero();
        assertThat(b.gain(2, 40)).isEqualTo(1);
        assertThat(b.gain(2, 5)).isZero();
    }
}
