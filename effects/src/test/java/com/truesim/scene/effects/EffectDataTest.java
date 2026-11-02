package com.truesim.scene.effects;

import static com.truesim.scene.effects.ValuesTest.INTENSITY;
import static com.truesim.scene.effects.ValuesTest.TINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Effects as data, built device-free: emitters, parameters, capacities, ends and equality. */
class EffectDataTest {

    static final EffectType IMPACTS = EffectType.builder("impacts").maxDistance(80).maxInstances(64).build();

    /** Sparks bursting on impact, some of them dying into embers. */
    static EffectData sparks() {
        return EffectData.builder(IMPACTS).parameter(INTENSITY, 1).parameter(TINT, 1, 0.6f, 0.2f, 1)
            .emitter("sparks", e -> e.duration(0.2).spawn(Spawn.burst(0, Bind.param(INTENSITY).times(150)))
                .init(Shape.cone(0.5f, 0.02f), Init.lifetime(Bind.random(0.3f, 0.8f)), Init.speed(Bind.random(4, 11)),
                    Init.color(Bind.param(TINT)), Init.color(Bind.age(ColorGradient.constant(1, 1, 1, 1))))
                .update(Forces.gravity(), Forces.drag(0.6f), Over.size(FloatCurve.of(0, 1, 1, 0)))
                .event(ParticleEvent.onDeath("embers", Bind.constant(1)).chance(0.3f))
                .render(Renderer.billboard(Renderer.Facing.VELOCITY).additive()))
            .emitter("embers", e -> e.capacity(200).spawn(Spawn.fromEvents())
                .init(Init.lifetime(Bind.random(0.8f, 1.5f))).update(Forces.gravity(Bind.constant(0.3f))))
            .build();
    }

    @Test
    void effectsAreBuiltWithoutADevice() {
        EffectData sparks = sparks();
        assertThat(sparks.type()).isSameAs(IMPACTS);
        assertThat(sparks.parameters()).containsExactly(INTENSITY, TINT);
        assertThat(sparks.parameterIndex(TINT)).isOne();
        assertThat(sparks.parameterIndex(EffectParameter.ofFloat("tint"))).isEqualTo(-1);
        assertThat(sparks.parameterOffset(1)).isOne();
        assertThat(sparks.parameterOffset("tint")).isOne();
        assertThat(sparks.parameterOffset("heat")).isEqualTo(-1);
        assertThat(sparks.parameterFloats()).isEqualTo(5);
        float[] block = new float[6];
        sparks.defaults(block, 1);
        assertThat(block).containsExactly(0, 1, 1, 0.6f, 0.2f, 1);
        assertThat(sparks.emitters()).hasSize(2);
        assertThat(sparks.emitter("embers")).isOne();
        assertThat(sparks.emitter("smoke")).isEqualTo(-1);
        EmitterData s = sparks.emitters().get(0);
        assertThat(s.name()).isEqualTo("sparks");
        assertThat(s.target()).isEqualTo(Target.GPU);
        assertThat(s.space()).isEqualTo(Space.WORLD);
        assertThat(s.capacity()).as("150 at once, one loop").isEqualTo(150);
        assertThat(s.authoredCapacity()).isZero();
        assertThat(s.maxLifetime()).isEqualTo(0.8f);
        assertThat(s.capability()).isEqualTo(Capability.NONE);
        assertThat(s.init()).hasSize(5);
        assertThat(s.update()).hasSize(3);
        assertThat(s.events()).hasSize(1);
        assertThat(s.renderers()).hasSize(1);
        assertThat(s.minimumQuality()).isEqualTo(Quality.LOW);
        assertThat(s.spawns(Spawn.Kind.BURST)).isTrue();
        assertThat(s.spawns(Spawn.Kind.RATE)).isFalse();
        assertThat(s.toString()).isEqualTo("emitter sparks (GPU, WORLD)");
        // Ends: sparks spawn until 0.2 s and live 0.8 s; embers born by then live 1.5 s more.
        assertThat(sparks.eventDepth(0)).isZero();
        assertThat(sparks.eventDepth(1)).isOne();
        assertThat(sparks.maxEventDepth()).isEqualTo(EffectData.DEFAULT_EVENT_DEPTH);
        assertThat(sparks.emitterEnd(0)).isCloseTo(1.0, within(1e-6));
        assertThat(sparks.emitterEnd(1)).isCloseTo(2.5, within(1e-6));
        assertThat(sparks.end()).isCloseTo(2.5, within(1e-6));
        assertThat(sparks.emitterLingering(1)).isCloseTo(2.3, within(1e-6));
        assertThat(sparks.lingering()).isCloseTo(2.3, within(1e-6));
        assertThat(sparks.end(0.1)).isCloseTo(2.4, within(1e-6));
        assertThat(sparks.end(5)).isCloseTo(2.5, within(1e-6));
        assertThat(sparks.burstEnd()).isCloseTo(2.3, within(1e-6));
        assertThat(sparks.bytes()).isPositive();
        assertThat(sparks.toString()).isEqualTo("effect of type impacts with 2 emitters");
    }

    @Test
    void equalDataIsOneAsset() {
        EffectData a = sparks();
        EffectData b = sparks();
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.ref()).isEqualTo(b.ref());
        assertThat(a.ref().type()).isEqualTo(EffectAssets.EFFECT);
        assertThat(EffectAssets.EFFECT.name()).isEqualTo("effect");
        AssetRef<EffectData> ref = a.ref();
        assertThat(ref.isGenerated()).isTrue();
        EffectData other = EffectData.builder(IMPACTS).parameter(INTENSITY, 2).parameter(TINT, 1, 0.6f, 0.2f, 1)
            .emitter("sparks", e -> e.spawn(Spawn.rate(5))).build();
        assertThat(a).isNotEqualTo(other).isNotEqualTo("sparks");
        assertThat(a.emitters().get(0)).isEqualTo(b.emitters().get(0)).hasSameHashCodeAs(b.emitters().get(0))
            .isNotEqualTo(a.emitters().get(1)).isNotEqualTo("emitter");
        assertThat(a.ref()).isNotEqualTo(other.ref());
    }

    @Test
    void anInlineEffectLoadsAsItsData() throws InterruptedException {
        EffectData data = sparks();
        try (TaskScheduler tasks = new TaskScheduler(); AssetCache cache = new AssetCache(tasks)) {
            AssetRef<EffectData> ref = data.ref();
            cache.acquire(ref);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (cache.loadingCount() > 0) {
                tasks.drainCompleted();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(1);
            }
            assertThat(cache.get(ref)).isSameAs(data);
        }
    }

    @Test
    void capacitiesAreEstimatedFromRatesBurstsAndLifetimes() {
        EffectData fire = EffectData.builder()
            .emitter("flames", e -> e.spawn(Spawn.rate(40)).init(Init.lifetime(2)))
            .emitter("pops", e -> e.duration(1).loopDelay(0.5).loops(0).spawn(Spawn.burst(0.5, 10))
                .init(Init.lifetime(2.5f)))
            .emitter("three", e -> e.duration(1).loopDelay(0.5).loops(3).spawn(Spawn.burst(0.5, 10))
                .init(Init.lifetime(2.5f)))
            .emitter("once", e -> e.spawn(Spawn.burst(0, 7)).init(Module.of(Init.LIFETIME)))
            .emitter("lasting", e -> e.spawn(Spawn.burst(0, 7)).init(Init.lifetime(100)))
            .emitter("negative", e -> e.spawn(Spawn.rate(Bind.constant(-5)), Spawn.burst(0, 2)))
            .build();
        assertThat(fire.type()).isSameAs(EffectType.DEFAULT);
        assertThat(capacities(fire)).containsExactly(80, 20, 20, 7, 7, 2);
        assertThat(fire.emitters().get(3).maxLifetime()).isEqualTo(EmitterData.DEFAULT_LIFETIME);
        assertThat(fire.end()).isInfinite();
        assertThat(fire.emitterEnd(2)).isCloseTo(3 + 2 * 0.5 + 2.5, within(1e-9));
        assertThat(fire.emitterEnd(3)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(fire.burstEnd()).isCloseTo(0.5 + 100, within(1e-9));
        // Authored capacities above demand stand; parameters count at their defaults.
        EffectData authored = EffectData.builder().parameter(INTENSITY, 3)
            .emitter("rain", e -> e.capacity(500).spawn(Spawn.rate(Bind.param(INTENSITY).times(100))))
            .emitter("dust", e -> e.capacity(30).spawn(Spawn.perDistance(Bind.constant(4))))
            .build();
        assertThat(capacities(authored)).containsExactly(500, 30);
        assertThat(authored.emitters().get(1).spawns(Spawn.Kind.DISTANCE)).isTrue();
    }

    private static List<Integer> capacities(EffectData data) {
        return data.emitters().stream().map(EmitterData::capacity).toList();
    }

    @Test
    void timingPrewarmAndTemplatesCarryOver() {
        EffectData looped = EffectData.builder()
            .emitter("base", e -> e.target(Target.CPU).space(Space.LOCAL).delay(0.5).duration(2).loops(2)
                .loopDelay(1).prewarm(0.25).capacity(100).spawn(Spawn.rate(10)).minimumQuality(Quality.HIGH)
                .render(Renderer.ribbon()))
            .build();
        EmitterData base = looped.emitters().get(0);
        assertThat(base.delay()).isEqualTo(0.5);
        assertThat(base.duration()).isEqualTo(2);
        assertThat(base.loops()).isEqualTo(2);
        assertThat(base.loopDelay()).isOne();
        assertThat(base.prewarm()).isEqualTo(0.25);
        assertThat(base.period()).isEqualTo(3);
        assertThat(base.spawnEnd()).isEqualTo(0.5 + 4 + 1 - 0.25);
        assertThat(looped.end()).isEqualTo(5.25 + 1);
        EffectData copied = EffectData.builder()
            .emitter("copy", base, e -> e.spawn(Spawn.burst(0, 5)).init(Init.lifetime(1)).update(Forces.drag(1))
                .event().render(Renderer.beam()))
            .build();
        EmitterData copy = copied.emitters().get(0);
        assertThat(copy.name()).isEqualTo("copy");
        assertThat(copy.target()).isEqualTo(Target.CPU);
        assertThat(copy.space()).isEqualTo(Space.LOCAL);
        assertThat(copy.spawnEnd()).isEqualTo(base.spawnEnd());
        assertThat(copy.spawns()).hasSize(2);
        assertThat(copy.authoredCapacity()).isEqualTo(100);
        assertThat(copy.renderers()).hasSize(2);
        assertThat(copy.minimumQuality()).isEqualTo(Quality.HIGH);
        // Prewarmed past its end, an emitter ends at once.
        EffectData spent = EffectData.builder()
            .emitter("spent", e -> e.duration(1).prewarm(5).spawn(Spawn.burst(0, 1)).init(Init.lifetime(1))).build();
        assertThat(spent.end()).isZero();
        assertThat(spent.burstEnd()).isEqualTo(1);
    }

    @Test
    void timingIsChecked() {
        EffectData.Builder b = EffectData.builder();
        b.emitter("e", e -> {
            assertThatIllegalArgumentException().isThrownBy(() -> e.delay(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> e.delay(Double.NaN));
            assertThatIllegalArgumentException().isThrownBy(() -> e.prewarm(Double.POSITIVE_INFINITY));
            assertThatIllegalArgumentException().isThrownBy(() -> e.loopDelay(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> e.duration(0));
            assertThatIllegalArgumentException().isThrownBy(() -> e.loops(-1));
            assertThatIllegalArgumentException().isThrownBy(() -> e.capacity(0));
            e.spawn(Spawn.rate(1));
        });
        assertThatIllegalArgumentException().isThrownBy(() -> b.maxEventDepth(0));
        assertThat(b.maxEventDepth(2).build().maxEventDepth()).isEqualTo(2);
    }

    @Test
    void modulesNeedingRayQueriesSetTheEmittersCapability() {
        ModuleType rays = ModuleType.builder("game.rays", ModuleType.Stage.UPDATE).targets(Target.GPU)
            .capability(Capability.RAY_QUERY).build();
        ModuleType tag = ModuleType.builder("game.tag", ModuleType.Stage.INIT).build();
        EffectData data = EffectData.builder()
            .emitter("a", e -> e.spawn(Spawn.rate(1)).init(Module.of("game.tag")).update(Module.of("game.rays"),
                Forces.gravity()))
            .emitter("b", e -> e.spawn(Spawn.rate(1)).init(Module.of("game.tag")))
            .build(ModuleRegistry.BUILT_IN.with(rays, tag));
        assertThat(data.emitters().get(0).capability()).isEqualTo(Capability.RAY_QUERY);
        assertThat(data.emitters().get(1).capability()).isEqualTo(Capability.NONE);
    }
}
