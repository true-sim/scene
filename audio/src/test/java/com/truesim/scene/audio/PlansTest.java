package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/** Container trees planned into voices from the seed: random picks, sequences, segments, blends and switches. */
class PlansTest {

    static final SoundParameter SPEED = SoundParameter.of("speed", 0, 0, 50);
    static final SoundParameter LOAD = SoundParameter.of("load", 1, 0, 2);

    private static AssetRef<AudioClip> clip(int n) {
        return SoundTest.clip(n);
    }

    /** The clips {@code count} plays of {@code sound} pick, in a fresh store. */
    private static List<AssetRef<AudioClip>> picks(Sound sound, int count) {
        Sounds sounds = new Sounds(new SceneGraph(new ChangeClock()));
        List<AssetRef<AudioClip>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = sounds.fire(sound, 0, 0, 0);
            out.add(sounds.voiceClip(id, 0));
            sounds.kill(id);
        }
        return out;
    }

    @Test
    void theSameSeedsPickTheSameClipsOnAnyThread() throws ExecutionException, InterruptedException {
        Sound sound = Sound.builder().root(Random.of(clip(1), clip(2), clip(3), clip(4)).noRepeat(2)
            .pitch(Range.semitones(-2, 2))).volume(Range.db(-6, 0)).build();
        List<AssetRef<AudioClip>> here = picks(sound, 200);
        List<AssetRef<AudioClip>> there = CompletableFuture.supplyAsync(() -> picks(sound, 200)).get();
        assertThat(there).isEqualTo(here);
        assertThat(new HashSet<>(here)).hasSize(4);
    }

    @Test
    void noRepeatWindowsAvoidTheLastPicks() {
        List<AssetRef<AudioClip>> picks = picks(Sound.builder().root(Random.of(clip(1), clip(2), clip(3), clip(4))
            .noRepeat(3)).build(), 300);
        for (int i = 3; i < picks.size(); i++) {
            assertThat(Set.of(picks.get(i - 1), picks.get(i - 2), picks.get(i - 3))).doesNotContain(picks.get(i));
        }
        List<AssetRef<AudioClip>> free = picks(Sound.builder().root(Random.of(clip(1), clip(2))).build(), 100);
        boolean repeated = false;
        for (int i = 1; i < free.size(); i++) {
            repeated |= free.get(i).equals(free.get(i - 1));
        }
        assertThat(repeated).as("without a window, repeats happen").isTrue();
    }

    @Test
    void shufflesPlayEveryChildOncePerRound() {
        List<AssetRef<AudioClip>> picks = picks(Sound.builder().root(Random.of(clip(1), clip(2), clip(3))
            .shuffle()).build(), 30);
        for (int round = 0; round < 10; round++) {
            assertThat(Set.copyOf(picks.subList(3 * round, 3 * round + 3))).hasSize(3);
        }
    }

    @Test
    void weightsBiasThePicks() {
        List<AssetRef<AudioClip>> picks = picks(Sound.builder().root(Random.of(clip(1), clip(2)).weights(9, 1))
            .build(), 1000);
        long ones = picks.stream().filter(clip(1)::equals).count();
        assertThat(ones).isBetween(850L, 950L);
    }

    @Test
    void containersBecomeVoices() {
        Sounds sounds = new Sounds(new SceneGraph(new ChangeClock()));
        Sound tree = Sound.builder().root(Layer.of(
            Sequence.of(Play.clip(clip(1)), Play.clip(clip(2)).delay(0.5)).delay(0.25),
            Segments.intro(clip(3)).loop(clip(4)).outro(clip(5)),
            Segments.loop(clip(6)).delay(1),
            Play.input(), Patch.named("v8"), Scatter.of(Play.clip(clip(7))),
            Switch.on(SoundTest.SURFACE).when(SoundTest.Surface.WOOD, clip(8)).otherwise(Play.clip(clip(9)))))
            .build();
        long id = sounds.fire(tree, 0, 0, 0);
        long killed = sounds.fire(tree, 0, 0, 0);
        sounds.kill(killed); // played and gone before it was planned: never planned
        assertThat(sounds.voices(id)).isEqualTo(10);
        assertThat(sounds.voiceClip(id, 0)).isEqualTo(clip(1));
        assertThat(sounds.voiceDelay(id, 0)).isEqualTo(0.25f);
        assertThat(sounds.voiceFollows(id, 0)).isEqualTo(-1);
        assertThat(sounds.voiceClip(id, 1)).isEqualTo(clip(2));
        assertThat(sounds.voiceDelay(id, 1)).as("after the first, plus its own").isEqualTo(0.5f);
        assertThat(sounds.voiceFollows(id, 1)).isEqualTo(0);
        assertThat(sounds.voiceClip(id, 2)).isEqualTo(clip(3));
        assertThat(sounds.voiceLoops(id, 2)).isFalse();
        assertThat(sounds.voiceClip(id, 3)).isEqualTo(clip(4));
        assertThat(sounds.voiceFollows(id, 3)).isEqualTo(2);
        assertThat(sounds.voiceLoops(id, 3)).isTrue();
        assertThat(sounds.voiceOutro(id, 3)).isFalse();
        assertThat(sounds.voiceClip(id, 4)).isEqualTo(clip(5));
        assertThat(sounds.voiceOutro(id, 4)).isTrue();
        assertThat(sounds.voiceRelease(id, 3)).isEqualTo(Release.AT_LOOP_END);
        assertThat(sounds.voiceRelease(id, 4)).isNull();
        assertThat(sounds.voiceFollows(id, 4)).isEqualTo(-1);
        assertThat(sounds.voiceClip(id, 5)).isEqualTo(clip(6));
        assertThat(sounds.voiceDelay(id, 5)).isEqualTo(1);
        assertThat(sounds.voiceLoops(id, 5)).isTrue();
        assertThat(sounds.voiceClip(id, 6)).isNull();
        assertThat(sounds.voiceNode(id, 6)).isEqualTo(Play.input());
        assertThat(sounds.voiceNode(id, 7)).isEqualTo(Patch.named("v8"));
        assertThat(sounds.voiceNode(id, 8)).isInstanceOf(Scatter.class);
        assertThat(sounds.voiceClip(id, 9)).as("the switch's default has no case: otherwise").isEqualTo(clip(9));

        long wood = sounds.fire(tree, 0, 0, 0);
        sounds.set(wood, SoundTest.SURFACE, SoundTest.Surface.WOOD);
        assertThat(sounds.voices(wood)).as("switches set before planning count").isEqualTo(10);
        assertThat(sounds.voiceClip(wood, 9)).isEqualTo(clip(8));
        sounds.set(wood, SoundTest.SURFACE, SoundTest.Surface.DIRT);
        assertThat(sounds.voices(wood)).as("planned once").isEqualTo(10);
    }

    @Test
    void aSwitchCoveringEveryValueNeedsNoOtherwise() {
        Sounds sounds = new Sounds(new SceneGraph(new ChangeClock()));
        Sound steps = Sound.builder().root(Switch.on(SoundTest.SURFACE).when(SoundTest.Surface.DIRT, clip(1))
            .when(SoundTest.Surface.GRAVEL, clip(2)).when(SoundTest.Surface.WOOD, clip(3))).build();
        long id = sounds.fire(steps, 0, 0, 0);
        sounds.set(id, SoundTest.SURFACE, SoundTest.Surface.GRAVEL);
        assertThat(sounds.voiceClip(id, 0)).isEqualTo(clip(2));
        sounds.kill(id);
        long again = sounds.fire(steps, 0, 0, 0); // its slot's switch values reused
        assertThat(sounds.voiceClip(again, 0)).isEqualTo(clip(1));
        long more = sounds.fire(Sound.of(clip(4)), 0, 0, 0);
        sounds.kill(again);
        long other = sounds.fire(Sound.builder().root(Switch.on(SwitchGroup.of("two", Thread.State.class,
            Thread.State.NEW)).otherwise(Play.clip(clip(5)))).build(), 0, 0, 0);
        assertThat(sounds.voiceClip(other, 0)).isEqualTo(clip(5));
        assertThat(sounds.voiceClip(more, 0)).isEqualTo(clip(4));
        sounds.kill(other);
        long plain = sounds.fire(Sound.of(clip(6)), 0, 0, 0); // a slot that held switches, now none
        assertThat(sounds.voiceClip(plain, 0)).isEqualTo(clip(6));
    }

    @Test
    void curvesAndBlendsFollowTheParameters() {
        Sounds sounds = new Sounds(new SceneGraph(new ChangeClock()));
        Sound engine = Sound.builder().root(Blend.by(SPEED).at(0, Play.clip(clip(1)))
                .at(10, Play.clip(clip(2)).pitch(SPEED, Curve.points(10, 1, 30, 2)))
                .volume(LOAD, Curve.points(0, 0, 2, 1)))
            .loop().build();
        long id = sounds.fire(engine, 0, 0, 0);
        assertThat(sounds.voices(id)).isEqualTo(2);
        assertThat(sounds.voiceLoops(id, 0)).isTrue();
        assertThat(sounds.voiceGain(id, 0)).isEqualTo(0.5f);
        assertThat(sounds.voiceGain(id, 1)).isZero();
        sounds.set(id, SPEED, 20, 0);
        assertThat(sounds.voiceGain(id, 0)).isZero();
        assertThat(sounds.voiceGain(id, 1)).isEqualTo(0.5f);
        assertThat(sounds.voiceRate(id, 1)).isEqualTo(1.5f);
        assertThat(sounds.voiceRate(id, 0)).isEqualTo(1);
        sounds.set(id, LOAD, 2, 0);
        assertThat(sounds.voiceGain(id, 1)).isEqualTo(1);
    }

    @Test
    void drawsStayInTheirRanges() {
        Sound sound = Sound.builder().root(Play.clip(clip(1)).volume(Range.db(-12, -6)).pitch(Range.semitones(12,
            12))).volume(Range.db(-6, -6)).build();
        Sounds sounds = new Sounds(new SceneGraph(new ChangeClock()));
        for (int i = 0; i < 50; i++) {
            long id = sounds.fire(sound, 0, 0, 0);
            assertThat((double) sounds.voiceGain(id, 0)).isBetween(0.125, 0.2512);
            assertThat((double) sounds.voiceRate(id, 0)).isCloseTo(2, within(1e-6));
        }
    }

    @Test
    void manyVoicesAndDeepTreesGrowThePlans() {
        SoundNode deep = Play.clip(clip(1)).volume(LOAD, Curve.points(0, 1));
        for (int i = 0; i < 20; i++) {
            deep = Layer.of(deep).volume(LOAD, Curve.points(0, 1));
        }
        SoundNode[] wide = new SoundNode[12];
        for (int i = 0; i < wide.length; i++) {
            wide[i] = Play.clip(clip(i)).pitch(LOAD, Curve.points(0, 1));
        }
        Sounds sounds = new Sounds(new SceneGraph(new ChangeClock()));
        long id = sounds.fire(Sound.builder().root(Layer.of(deep, Layer.of(wide))).build(), 0, 0, 0);
        assertThat(sounds.voices(id)).isEqualTo(13);
        assertThat(sounds.voiceGain(id, 0)).isEqualTo(1);
        // a second sound with Random nodes grows the history past its first size
        SoundNode[] many = new SoundNode[70];
        for (int i = 0; i < many.length; i++) {
            many[i] = Play.clip(clip(i));
        }
        long big = sounds.fire(Sound.builder().root(Random.of(many).noRepeat(5)).build(), 0, 0, 0);
        assertThat(sounds.voices(big)).isEqualTo(1);
    }
}
