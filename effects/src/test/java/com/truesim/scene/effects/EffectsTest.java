package com.truesim.scene.effects;

import static com.truesim.scene.effects.ValuesTest.INTENSITY;
import static com.truesim.scene.effects.ValuesTest.TINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The effects playing in a scene: handles, time, ends, slots and the change log. */
class EffectsTest {

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);

    /** An effect that spawns for a second, its particles living a second: it ends at 2 s. */
    static EffectData puff() {
        return EffectData.builder().parameter(INTENSITY, 1).parameter(TINT, 1, 1, 1, 1)
            .emitter("puff", e -> e.duration(1).spawn(Spawn.rate(10)).init(Init.lifetime(1))).build();
    }

    /** An effect that spawns until stopped. */
    static EffectData fire() {
        return EffectData.builder().emitter("flames", e -> e.spawn(Spawn.rate(10)).init(Init.lifetime(2))).build();
    }

    private List<String> changes(long since) {
        List<String> seen = new ArrayList<>();
        assertThat(effects.changesSince(since, (effect, changes) -> seen.add(Long.toHexString(effect) + ":" + changes)))
            .isTrue();
        return seen;
    }

    @Test
    void effectsPlayOnNodesAndAtPositions() {
        long node = scene.create();
        scene.setTranslation(node, 1, 2, 3);
        scene.setRotation(node, 0, Math.sqrt(0.5), 0, Math.sqrt(0.5)); // a quarter turn about +Y
        scene.update();
        Effect torch = effects.play(fire(), node).offset(1, 0, 0);
        long id = torch.id();
        assertThat(effects.alive(id)).isTrue();
        assertThat(effects.size()).isOne();
        assertThat(effects.scene()).isSameAs(scene);
        assertThat(effects.node(id)).isEqualTo(node);
        assertThat(effects.position(id, 0)).isCloseTo(1, within(1e-12));
        assertThat(effects.position(id, 2)).as("the offset turned with the node").isCloseTo(2, within(1e-12));
        assertThat(effects.rotation(id, 3)).isCloseTo(Math.sqrt(0.5), within(1e-12));
        assertThat(effects.offset(id, 0)).isEqualTo(1);
        // Following the node.
        scene.setTranslation(node, 5, 2, 3);
        scene.update();
        effects.advance(0.1);
        assertThat(effects.position(id, 0)).isCloseTo(5, within(1e-12));
        // At a position, detached.
        Effect shot = effects.play(puff(), 7, 8, 9);
        assertThat(effects.node(shot.id())).isEqualTo(NodeHandle.NONE);
        assertThat(effects.position(shot.id(), 1)).isEqualTo(8);
        assertThat(effects.rotation(shot.id(), 3)).isEqualTo(1);
        shot.moveTo(1, 1, 1).offset(0, 1, 0);
        assertThat(effects.position(shot.id(), 1)).isOne();
        torch.moveTo(0, 0, 0);
        assertThat(effects.node(id)).isEqualTo(NodeHandle.NONE);
        assertThatIllegalArgumentException().isThrownBy(() -> effects.play(fire(), NodeHandle.of(9, 1)));
    }

    @Test
    void handlesSetParametersSeedsAndVelocityInheritance() {
        EffectData puff = puff();
        Effect e = effects.play(puff, 0, 0, 0);
        long id = e.id();
        assertThat(e.get(INTENSITY)).isOne();
        e.set(INTENSITY, 0.5f).set(TINT, 1, 0.5f, 0.25f, 1);
        assertThat(e.get(INTENSITY)).isEqualTo(0.5f);
        assertThat(effects.parameter(id, puff.parameterOffset(1) + 2)).isEqualTo(0.25f);
        float[] block = new float[5];
        effects.parameters(id, block, 0);
        assertThat(block).containsExactly(0.5f, 1, 0.5f, 0.25f, 1);
        assertThat(e.get(EffectParameter.ofFloat("heat"))).isNaN();
        assertThatIllegalArgumentException().isThrownBy(() -> e.set(EffectParameter.ofFloat("heat"), 1));
        int seed = effects.seed(id);
        assertThat(effects.seed(effects.play(puff, 0, 0, 0).id())).as("each played effect its own seed")
            .isNotEqualTo(seed);
        e.seed(42).inheritVelocity(0.3f);
        assertThat(effects.seed(id)).isEqualTo(42);
        assertThat(effects.inheritVelocity(id)).isEqualTo(0.3f);
        assertThat(effects.data(id)).isSameAs(puff);
        assertThat(effects.shared(id)).isFalse();
        assertThat(e.toString()).isEqualTo("Effect[" + Long.toHexString(id) + "]");
    }

    @Test
    void effectsEndByThemselvesAndTheirHandlesGoInert() {
        Effect first = effects.play(puff(), 0, 0, 0);
        long id = first.id();
        effects.advance(1.5);
        assertThat(first.alive()).isTrue();
        assertThat(first.time()).isEqualTo(1.5);
        assertThat(effects.end(id)).isEqualTo(2 * Effects.TICKS_PER_SECOND);
        effects.advance(0.5);
        assertThat(first.alive()).as("its last particle died at 2 s").isFalse();
        assertThat(effects.size()).isZero();
        // The slot is reused, under a new generation; the old handle touches nothing.
        Effect second = effects.play(puff(), 0, 0, 0);
        assertThat((int) second.id()).isEqualTo((int) id);
        assertThat(second.id()).isNotEqualTo(id);
        first.set(INTENSITY, 9).set(TINT, 0, 0, 0, 0).timeScale(3).pause(true).offset(1, 1, 1).moveTo(2, 2, 2)
            .inheritVelocity(1).seed(7);
        first.stop();
        first.kill();
        assertThat(first.alive()).isFalse();
        assertThat(first.stopped()).isTrue();
        assertThat(first.time()).isNaN();
        assertThat(first.get(INTENSITY)).isNaN();
        assertThat(first.toString()).endsWith(", ended]");
        assertThat(second.get(INTENSITY)).isOne();
        assertThat(effects.timeScaleOf(second.id())).isOne();
        assertThat(effects.paused(second.id())).isFalse();
        assertThat(second.alive()).isTrue();
        assertThat(effects.alive(0)).isFalse();
        assertThat(effects.alive(id | 0xFFFF)).isFalse();
        assertThat(effects.alive(-1)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> effects.data(id)).withMessageContaining("No effect");
    }

    @Test
    void stoppedEffectsLiveOutTheirParticles() {
        Effect torch = effects.play(fire(), 0, 0, 0);
        assertThat(effects.end(torch.id())).as("never by itself").isEqualTo(Long.MAX_VALUE);
        effects.advance(3.0);
        torch.stop();
        torch.stop(); // once is enough
        assertThat(torch.stopped()).isTrue();
        assertThat(effects.stopTime(torch.id())).isEqualTo(3 * Effects.TICKS_PER_SECOND);
        assertThat(effects.end(torch.id())).isEqualTo(5 * Effects.TICKS_PER_SECOND);
        effects.advance(1.999999);
        assertThat(torch.alive()).isTrue();
        effects.advance(0.000001);
        assertThat(torch.alive()).isFalse();
        Effect killed = effects.play(fire(), 0, 0, 0);
        assertThat(killed.stopped()).isFalse();
        assertThat(effects.stopTime(killed.id())).isEqualTo(-1);
        killed.kill();
        assertThat(killed.alive()).isFalse();
    }

    @Test
    void timeScalesAndPausesMoveEffectTime() {
        Effect slow = effects.play(fire(), 0, 0, 0).timeScale(0.5f);
        Effect paused = effects.play(fire(), 0, 0, 0).pause(true);
        effects.advance(1.0);
        assertThat(slow.time()).isEqualTo(0.5);
        assertThat(paused.time()).isZero();
        assertThat(effects.paused(paused.id())).isTrue();
        effects.timeScale(2);
        assertThat(effects.timeScale()).isEqualTo(2);
        effects.advance(1.0);
        assertThat(slow.time()).isEqualTo(1.5);
        effects.pause(true);
        assertThat(effects.paused()).isTrue();
        effects.advance(1.0);
        assertThat(slow.time()).isEqualTo(1.5);
        assertThat(effects.now()).isEqualTo(3 * Effects.TICKS_PER_SECOND);
        assertThat(effects.start(slow.id())).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> effects.timeScale(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> effects.timeScale(Float.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> slow.timeScale(40_000));
        assertThatIllegalArgumentException().isThrownBy(() -> effects.advance(-1.0));
        assertThatIllegalArgumentException().isThrownBy(() -> effects.advance(Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> effects.advance(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> effects.advance(-1L));
    }

    @Test
    void scaledTimeIsTheSameHoweverFramesSplitTheTicks() {
        Effect whole = effects.play(fire(), 0, 0, 0).timeScale(0.3f);
        Effects other = new Effects(scene);
        Effect split = other.play(fire(), 0, 0, 0).timeScale(0.3f);
        effects.advance(1_000_000L);
        for (long ticks : new long[] {333_333, 1, 333_332, 333_334}) {
            other.advance(ticks);
        }
        assertThat(split.time()).isEqualTo(whole.time()).isCloseTo(0.3, within(1e-4));
    }

    @Test
    void effectsOnRemovedNodesStopWhereTheyWere() {
        long node = scene.create();
        scene.setTranslation(node, 4, 0, 0);
        scene.update();
        Effect torch = effects.play(fire(), node);
        Effect stopped = effects.play(fire(), node);
        stopped.stop();
        scene.remove(node);
        scene.update();
        effects.advance(0.1);
        assertThat(torch.stopped()).isTrue();
        assertThat(effects.node(torch.id())).isEqualTo(NodeHandle.NONE);
        assertThat(effects.position(torch.id(), 0)).isEqualTo(4);
        assertThat(stopped.alive()).isTrue();
    }

    @Test
    void changesAreLoggedInOrder() {
        long since = scene.clock().now();
        Effect a = effects.play(fire(), 0, 0, 0);
        Effect b = effects.play(puff(), 0, 0, 0);
        b.set(INTENSITY, 2);
        a.timeScale(2);
        a.pause(false);
        b.offset(0, 1, 0);
        b.seed(3);
        a.stop();
        b.kill();
        String ida = Long.toHexString(a.id());
        String idb = Long.toHexString(b.id());
        assertThat(changes(since)).containsExactly(ida + ":" + Effects.ADDED, idb + ":" + Effects.ADDED,
            idb + ":" + Effects.PARAMETERS, ida + ":" + Effects.TIME, ida + ":" + Effects.TIME,
            idb + ":" + Effects.MOVED, idb + ":" + Effects.SEED, ida + ":" + Effects.STOPPED,
            idb + ":" + Effects.REMOVED);
        long mark = scene.clock().now();
        effects.advance(10.0);
        assertThat(changes(mark)).containsExactly(ida + ":" + Effects.REMOVED);
        List<Long> playing = new ArrayList<>();
        effects.play(fire(), 0, 0, 0);
        effects.forEach(playing::add);
        assertThat(playing).hasSize(1);
    }

    @Test
    void consumersThatFellBehindTheLogRebuild() {
        Effects small = new Effects(scene, 2);
        long since = scene.clock().now();
        small.play(fire(), 0, 0, 0);
        small.play(fire(), 0, 0, 0);
        small.play(fire(), 0, 0, 0);
        assertThat(small.changesSince(since, (effect, changes) -> { })).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new Effects(scene, 0));
    }

    @Test
    void burstsShareAnInstancePerAssetAndCell() {
        EffectData impact = EffectData.builder(EffectType.builder("hits").burstCell(10).build())
            .emitter("chips", e -> e.delay(0.1).duration(1).spawn(Spawn.burst(0.2, 12)).init(Init.lifetime(0.5f)))
            .build();
        effects.play(fire(), 0, 0, 0);
        long first = effects.burst(impact, 1, 0, 1, 0, 1, 0, 7);
        assertThat(effects.burst(impact, 1, 15, 1, 0, 1, 0, 0)).isNotEqualTo(first);
        assertThat(effects.burst(impact, 1, 0, 15, 0, 1, 0, 0)).isNotEqualTo(first);
        effects.dropBursts(2);
        effects.burst(impact, 1, 0, 1, 0, 1, 0, 7);
        effects.dropBursts(1);
        long same = effects.burst(impact, 9, 9, 9, 0, 0, 1, 8);
        long other = effects.burst(impact, -1, 0, 1, 0, 1, 0, 9);
        long otherAsset = effects.burst(puff(), 1, 0, 1, 0, 1, 0, 0);
        assertThat(same).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
        assertThat(otherAsset).isNotEqualTo(first);
        assertThat(effects.shared(first)).isTrue();
        assertThat(effects.position(first, 0)).as("the cell's center").isEqualTo(5);
        assertThat(effects.position(other, 0)).isEqualTo(-5);
        assertThat(effects.burstCount()).isEqualTo(4);
        assertThat(effects.burstEffect(1)).isEqualTo(first);
        assertThat(effects.burstPosition(1, 2)).isEqualTo(9);
        assertThat(effects.burstNormal(1, 2)).isOne();
        assertThat(effects.burstPayload(2)).isEqualTo(9);
        assertThat(effects.burstTime(0)).isZero();
        effects.advance(0.5);
        effects.burst(impact, 1, 0, 1, 0, 1, 0, 10);
        assertThat(effects.burstTime(4)).isEqualTo(500_000);
        effects.dropBursts(3);
        assertThat(effects.burstCount()).isEqualTo(2);
        assertThat(effects.burstPayload(1)).isEqualTo(10);
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> effects.burstPayload(2));
        // A shared instance ends once its last burst's particles have died: 0.3 s after it, plus 0.5 s.
        effects.advance(0.79);
        assertThat(effects.alive(first)).isTrue();
        assertThat(effects.alive(other)).isFalse();
        effects.advance(0.01);
        assertThat(effects.alive(first)).isFalse();
        // Many bursts grow the record.
        for (int i = 0; i < 40; i++) {
            effects.burst(impact, i, 0, 0, 0, 1, 0, i);
        }
        assertThat(effects.burstPayload(41)).isEqualTo(39);
    }

    @Test
    void manyEffectsGrowTheSlots() {
        List<Effect> played = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            played.add(effects.play(puff(), i, 0, 0));
        }
        assertThat(effects.size()).isEqualTo(40);
        assertThat(effects.position(played.get(39).id(), 0)).isEqualTo(39);
    }

    @Test
    void onlyTheScenesOwnerChangesEffects() throws InterruptedException {
        Thread other = new Thread(() -> { });
        SceneGraph owned = new SceneGraph(new ChangeClock().ownedBy(other));
        Effects guarded = new Effects(owned);
        assertThatIllegalStateException().isThrownBy(() -> guarded.play(fire(), 0, 0, 0))
            .withMessageContaining("change on");
        Effect mine = effects.play(fire(), 0, 0, 0);
        assertThat(mine.alive()).isTrue();
        Thread current = Thread.currentThread();
        SceneGraph ownedByMe = new SceneGraph(new ChangeClock().ownedBy(current));
        assertThat(new Effects(ownedByMe).play(fire(), 0, 0, 0).alive()).isTrue();
    }
}
