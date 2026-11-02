package com.truesim.scene.effects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Shared simulations (E23): eligibility from modules, variants and placements, near placements simulating on their
 * own; and effects step 18's scalability in the runtime: budgets in bytes, drawn fractions fading, id-based plays.
 */
class SharingTest {

    private static final long FRAME = 16_667;

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);
    private final EffectRuntime runtime = new EffectRuntime(effects);

    /** A torch in its own frame: shareable. */
    private static EffectData.Builder torch() {
        return EffectData.builder().parameter(ValuesTest.INTENSITY, 1)
            .emitter("flames", e -> e.space(Space.LOCAL).spawn(Spawn.rate(20)).init(Init.lifetime(0.5f))
                .render(Renderer.billboard(Renderer.Facing.POSITION)));
    }

    private List<Long> playing() {
        List<Long> all = new ArrayList<>();
        effects.forEach(all::add);
        return all;
    }

    @Test
    void eligibilityComesFromTheModules() {
        EffectData shared = torch().shared(3, 8).build();
        assertThat(shared.variants()).isEqualTo(3);
        assertThat(shared.nearDistance()).isEqualTo(8);
        assertThat(shared.sharingRefusal()).isNull();
        assertThat(torch().shared(2).build().nearDistance()).isZero();
        assertThat(torch().build().variants()).isZero();
        assertThat(torch().build().sharingRefusal()).isNull();
        assertThat(shared).isNotEqualTo(torch().shared(2, 8).build()).isNotEqualTo(torch().build());
        assertThat(refusal(e -> e.target(Target.CPU))).contains("CPU emitter");
        assertThat(refusal(e -> e.space(Space.WORLD))).contains("world space");
        assertThat(refusal(e -> e.collide(Collide.height().bounce(0.5f)))).contains("collide.height");
        assertThat(refusal(e -> e.init(Shape.mesh(Shape.SURFACE)))).contains("shape.mesh");
        assertThat(refusal(e -> e.init(Shape.bones(0.1f)))).contains("shape.bones");
        assertThat(refusal(e -> e.render(Renderer.light(2, 1)))).contains("lights");
        assertThat(refusal(e -> e.render(Renderer.beam()))).contains("beams");
        com.truesim.scene.assets.AssetRef<?> rock = com.truesim.scene.assets.AssetRef.of(EffectAssets.EFFECT,
            new com.truesim.scene.assets.AssetId(5, 2));
        assertThat(refusal(e -> e.render(Renderer.mesh(rock, rock)))).contains("meshs");
        assertThatIllegalArgumentException().isThrownBy(() -> torch().shared(0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> torch().shared(1, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> torch().shared(1, Double.NaN));
    }

    /** Why a shareable torch with one more of {@code setup} on a second emitter is refused sharing. */
    private static String refusal(java.util.function.Consumer<EmitterData.Builder> setup) {
        EffectData data = torch().emitter("more", e -> {
            e.space(Space.LOCAL).spawn(Spawn.rate(5)).init(Init.lifetime(0.5f));
            setup.accept(e);
        }).shared(2).build();
        assertThat(data.variants()).as("simulated per instance").isZero();
        return data.sharingRefusal();
    }

    @Test
    void placementsShareTheirVariantsInTurnAndRefuseWhatIsTheirsAlone() {
        EffectData shared = torch().shared(2).build();
        long a = effects.start(shared, 1, 0, 0);
        long b = effects.start(shared, 2, 0, 0);
        long c = effects.start(shared, 3, 0, 0);
        assertThat(playing()).as("three placements, two variants").hasSize(5);
        assertThat(effects.placement(a)).isTrue();
        assertThat(effects.variant(a)).isFalse();
        long va = effects.variantOf(a);
        assertThat(effects.variant(va)).isTrue();
        assertThat(effects.placement(va)).isFalse();
        assertThat(effects.variantOf(va)).as("not a placement").isZero();
        assertThat(effects.variantOf(b)).isNotEqualTo(va);
        assertThat(effects.variantOf(c)).isEqualTo(va);
        assertThat(effects.position(va, 0)).as("at the origin").isZero();
        assertThat(effects.seed(va)).isEqualTo(Hash.combine(shared.hashCode(), 0));
        assertThat(effects.nearOf(a)).isZero();
        assertThat(effects.nearOf(va)).isZero();
        assertThat(effects.scale(a)).isEqualTo(1);
        new Effect(effects, a).scale(2);
        assertThat(effects.scale(a)).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> effects.scale(a, 0));
        assertThatIllegalStateException().isThrownBy(() -> effects.scale(va, 2));
        effects.scale(Long.MAX_VALUE, 2); // an ended one: inert
        assertThatIllegalStateException().isThrownBy(() -> effects.set(a, ValuesTest.INTENSITY, 2));
        assertThatIllegalStateException().isThrownBy(() -> new Effect(effects, a).inheritVelocity(0.5f));
        new Effect(effects, a).inheritVelocity(0); // nothing to inherit: fine
        effects.set(va, ValuesTest.INTENSITY, 2); // the variant's own: as an effect of its own
        // Placements ending leave their variant to its last; a variant of none stops
        effects.kill(a);
        assertThat(effects.stopped(va)).isFalse();
        effects.kill(c);
        assertThat(effects.stopped(va)).as("no placement left: stopped, its particles live out").isTrue();
        long d = effects.start(shared, 4, 0, 0);
        assertThat(effects.variantOf(d)).as("a new variant: the stopped one only lingers").isNotEqualTo(va);
        // A replaced asset: its placements and variants end
        assertThat(effects.replace(shared, torch().build())).isZero();
        assertThat(effects.alive(b)).isFalse();
        assertThat(effects.alive(va)).isFalse();
    }

    @Test
    void placementsFollowTheirNodesAndEndWithTheirVariantsTime() {
        EffectData once = EffectData.builder().emitter("e", e -> e.space(Space.LOCAL).duration(0.5)
            .spawn(Spawn.burst(0, 3)).init(Init.lifetime(0.2f))).shared(1).build();
        long node = scene.create();
        scene.setTranslation(node, 5, 0, 0);
        scene.update();
        long p = effects.start(once, node);
        long v = effects.variantOf(p);
        assertThat(effects.position(p, 0)).isEqualTo(5);
        for (int f = 0; f < 60; f++) {
            runtime.update(FRAME);
        }
        assertThat(effects.alive(v)).as("a one-shot variant ends").isFalse();
        assertThat(effects.alive(p)).as("its placement too, at the same time").isFalse();
        // A placement played later outlives its variant: it draws nothing then, and ends on its own clock
        long first = effects.start(once, node);
        for (int f = 0; f < 18; f++) {
            runtime.update(FRAME);
        }
        long later = effects.start(once, node);
        assertThat(effects.variantOf(later)).isEqualTo(effects.variantOf(first));
        for (int f = 0; f < 30; f++) {
            runtime.update(FRAME);
        }
        assertThat(effects.alive(later)).isTrue();
        assertThat(effects.variantOf(later)).as("its variant ended").isZero();
        effects.kill(later);
        assertThat(effects.alive(later)).isFalse();
    }

    @Test
    void variantsAreAsNearAsTheirNearestPlacementShownAndSpawnForThem() {
        EffectData shared = torch().shared(1).build();
        EffectType far = EffectType.builder("far").maxDistance(50).lod(20, 0.5f, 2, 0.5f).build();
        EffectData sharedFar = EffectData.builder(far).emitter("flames", e -> e.space(Space.LOCAL)
            .spawn(Spawn.rate(20)).init(Init.lifetime(0.5f))).shared(1).build();
        runtime.addViewer(0, 0, 0);
        long near = effects.start(shared, 30, 0, 0);
        long farOne = effects.start(sharedFar, 40, 0, 0);
        long farther = effects.start(sharedFar, 100, 0, 0);
        runtime.update(FRAME);
        long v = effects.variantOf(near);
        long vf = effects.variantOf(farOne);
        assertThat(runtime.distance(v)).isEqualTo(30);
        assertThat(runtime.distance(vf)).as("its nearest placement shown").isEqualTo(40);
        assertThat(runtime.culled(farther)).isTrue();
        assertThat(runtime.lod(vf)).isEqualTo(1);
        assertThat(runtime.updateEvery(vf)).isEqualTo(2);
        assertThat(runtime.placementWeight(near)).isEqualTo(1);
        assertThat(runtime.placementWeight(v)).as("not a placement").isZero();
        boolean spawned = false;
        boolean placementSpawned = false;
        for (int f = 0; f < 10; f++) {
            runtime.update(FRAME);
            for (int i = 0; i < runtime.spawns().size(); i++) {
                spawned |= runtime.spawns().effect(i) == v;
                placementSpawned |= runtime.spawns().effect(i) == near;
            }
        }
        assertThat(spawned).as("the variant spawns").isTrue();
        assertThat(placementSpawned).as("placements never").isFalse();
        // All its placements culled: the variant is too
        effects.kill(farOne);
        runtime.update(FRAME);
        assertThat(runtime.culled(vf)).isTrue();
    }

    @Test
    void nearPlacementsSimulateOnTheirOwnAndFadeTheirVariantOut() {
        EffectData shared = torch().shared(2, 10).build();
        runtime.addViewer(0, 0, 0);
        long p = effects.start(shared, 5, 0, 0);
        runtime.update(FRAME);
        long own = effects.nearOf(p);
        assertThat(own).as("near: a simulation of its own").isNotZero();
        assertThat(effects.placement(own)).isFalse();
        assertThat(effects.seed(own)).isEqualTo(effects.seed(p));
        assertThat(runtime.placementWeight(p)).as("fading out").isLessThan(1);
        for (int f = 0; f < 60; f++) {
            runtime.update(FRAME);
        }
        assertThat(runtime.placementWeight(p)).isZero();
        // It follows its placement
        new Effect(effects, p).moveTo(6, 0, 0);
        runtime.update(FRAME);
        assertThat(effects.position(own, 0)).isEqualTo(6);
        // Far again (beyond the hysteresis): its own stops, the variant fades back in
        new Effect(effects, p).moveTo(20, 0, 0);
        runtime.update(FRAME);
        assertThat(effects.stopped(own)).isTrue();
        for (int f = 0; f < 60; f++) {
            runtime.update(FRAME);
        }
        assertThat(runtime.placementWeight(p)).isEqualTo(1);
        // Near again: a new one; then ended, the placement's own stops with it
        new Effect(effects, p).moveTo(1, 0, 0);
        runtime.update(FRAME);
        long again = effects.nearOf(p);
        assertThat(again).isNotZero().isNotEqualTo(own);
        runtime.update(FRAME);
        assertThat(effects.nearOf(p)).isEqualTo(again);
        effects.kill(p);
        assertThat(effects.stopped(again)).isTrue();
        // A stopped placement fades its variant out, then ends
        long q = effects.start(shared, 30, 0, 0);
        runtime.update(FRAME);
        new Effect(effects, q).stop();
        runtime.update(FRAME);
        assertThat(runtime.placementWeight(q)).isLessThan(1);
        // Ended while its own lingers: nothing more to stop
        long r = effects.start(shared, 2, 0, 0);
        runtime.update(FRAME);
        long lingering = effects.nearOf(r);
        new Effect(effects, r).moveTo(40, 0, 0);
        runtime.update(FRAME);
        assertThat(effects.stopped(lingering)).isTrue();
        effects.kill(r);
        assertThat(effects.alive(lingering)).as("lives out its particles").isTrue();
    }

    @Test
    void theBudgetHalvesThenCullsTheLeastSignificantByPriority() {
        EffectType low = EffectType.builder("low").build();
        EffectType high = EffectType.builder("high").priority(1).build();
        EffectData cheap = EffectData.builder(low).emitter("e", e -> e.spawn(Spawn.rate(10)).init(Init.lifetime(1)))
            .build();
        EffectData important = EffectData.builder(high).emitter("e", e -> e.spawn(Spawn.rate(10))
            .init(Init.lifetime(1))).build();
        EffectData placed = torch().shared(1).build();
        runtime.addViewer(0, 0, 0);
        runtime.budget(400, data -> 100);
        long[] near = new long[4];
        for (int i = 0; i < 4; i++) {
            near[i] = effects.start(cheap, 10 + i, 0, 0);
        }
        long vip = effects.start(important, 100, 0, 0);
        long placement = effects.start(placed, 5, 0, 0);
        runtime.update(FRAME);
        assertThat(runtime.culled(vip)).as("first by priority, however far").isFalse();
        assertThat(runtime.spawnScale(vip)).isEqualTo(1);
        assertThat(runtime.spawnScale(near[1])).as("within three quarters").isEqualTo(1);
        assertThat(runtime.spawnScale(near[2])).as("near the budget: halved").isEqualTo(0.5f);
        assertThat(runtime.culled(near[2])).as("not over it").isFalse();
        assertThat(runtime.culled(near[3])).as("culled").isTrue();
        assertThat(runtime.culled(placement)).as("placements cost nothing").isFalse();
        runtime.update(FRAME);
        assertThat(runtime.culled(near[3])).as("costs known: the same").isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> runtime.budget(-1, data -> 0));
        runtime.budget(Long.MAX_VALUE, data -> 0);
        runtime.update(FRAME);
        assertThat(runtime.culled(near[3])).isFalse();
    }

    @Test
    void drawnFractionsFadeOverTheirTime() {
        EffectType lod = EffectType.builder("lod").lod(10, 1, 1, 0.4f).build();
        EffectData data = EffectData.builder(lod).emitter("e", e -> e.spawn(Spawn.rate(10)).init(Init.lifetime(1)))
            .build();
        runtime.addViewer(0, 0, 0);
        long e = effects.start(data, 5, 0, 0);
        runtime.update(FRAME);
        assertThat(runtime.drawn(e)).as("decided at once at its start").isEqualTo(1);
        new Effect(effects, e).moveTo(50, 0, 0);
        runtime.update(FRAME);
        assertThat((double) runtime.drawn(e)).as("a frame of the fade").isCloseTo(1 - FRAME * 1e-6 / 0.3,
            within(1e-4));
        for (int f = 0; f < 30; f++) {
            runtime.update(FRAME);
        }
        assertThat(runtime.drawn(e)).isEqualTo(0.4f);
        new Effect(effects, e).moveTo(5, 0, 0);
        runtime.update(FRAME);
        assertThat(runtime.drawn(e)).as("fading back").isBetween(0.4f, 1f);
    }

    @Test
    void equalDataIsEqualFirstByIdentityThenByHash() {
        EffectData a = torch().build();
        assertThat(a).isEqualTo(a).isEqualTo(torch().build()).isNotEqualTo(torch().shared(2).build());
        assertThat(a.emitters().getFirst()).isEqualTo(a.emitters().getFirst())
            .isEqualTo(torch().build().emitters().getFirst());
    }

    @Test
    void particlesKeepAtMostFourEmitAccumulators() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EffectData.builder()
            .emitter("p", e -> e.spawn(Spawn.burst(0, 1)).event(ParticleEvent.emit("c", Bind.constant(1)),
                ParticleEvent.emit("c", Bind.constant(1)), ParticleEvent.emit("c", Bind.constant(1)),
                ParticleEvent.emit("c", Bind.constant(1)), ParticleEvent.emit("c", Bind.constant(1))))
            .emitter("c", e -> e.capacity(10).spawn(Spawn.fromEvents())).build())
            .isInstanceOf(InvalidEffectException.class).hasMessageContaining("5 emit events, more than the 4");
    }

    @Test
    void rotationsAndFlipbookStartsAreDrawnPerParticleOnTheCpu() {
        EffectData data = EffectData.builder().emitter("e", e -> e.target(Target.CPU).spawn(Spawn.burst(0, 20))
            .init(Init.lifetime(5), Init.randomRotation(), Init.randomFrame())).build();
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long effect = effects.start(data, 0, 0, 0);
        runtime.update(FRAME);
        simulation.update();
        ParticleCursor cursor = new ParticleCursor();
        simulation.particles(effect, 0, cursor);
        java.util.Set<Float> rotations = new java.util.HashSet<>();
        while (cursor.next()) {
            assertThat(cursor.rotation()).isBetween(0f, (float) (2 * Math.PI));
            assertThat(cursor.frame()).isBetween(0f, 1f);
            rotations.add(cursor.rotation());
        }
        assertThat(rotations).as("each its own").hasSize(20);
        assertThat(Init.rotation(Bind.constant(1)).value("angle")).isEqualTo(Bind.constant(1));
        assertThat(Init.frame(Bind.constant(0.5f)).value("frame")).isEqualTo(Bind.constant(0.5f));
        assertThat(Shape.mesh(Shape.VERTICES, 0.5f).value("upward")).isEqualTo(Bind.constant(0.5f));
        assertThatIllegalArgumentException().isThrownBy(() -> Shape.mesh(Shape.SURFACE, 1.5f));
        assertThatIllegalArgumentException().isThrownBy(() -> Shape.mesh(Shape.SURFACE, -0.1f));
    }

    @Test
    void effectsStartedByIdTakeOffsetsAndParameters() {
        long node = scene.create();
        long e = effects.start(torch().build(), node);
        effects.offset(e, 0, 1, 0);
        effects.set(e, ValuesTest.INTENSITY, 3);
        assertThat(effects.offset(e, 1)).isEqualTo(1);
        assertThat(new Effect(effects, e).get(ValuesTest.INTENSITY)).isEqualTo(3);
        assertThatIllegalArgumentException().isThrownBy(() -> effects.start(torch().build(), Long.MAX_VALUE));
        effects.set(Long.MAX_VALUE, ValuesTest.INTENSITY, 1); // ended: inert
    }
}
