package com.truesim.scene.effects;

import static com.truesim.scene.effects.ValuesTest.INTENSITY;
import static com.truesim.scene.effects.ValuesTest.TINT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Every problem building an effect finds, each in a sentence naming its emitter. */
class ValidationTest {

    private static final ModuleType RAYS = ModuleType.builder("game.rays", ModuleType.Stage.UPDATE)
        .targets(Target.GPU).capability(Capability.RAY_QUERY).build();

    /** The problems of an effect with one emitter set up by {@code setup}. */
    private static InvalidEffectException problems(Consumer<EmitterData.Builder> setup) {
        return problems(EffectData.builder().parameter(INTENSITY, 1).parameter(TINT, 1, 1, 1, 1).emitter("e", setup));
    }

    private static InvalidEffectException problems(EffectData.Builder b) {
        return catchThrowableOfType(InvalidEffectException.class, () -> b.build(ModuleRegistry.BUILT_IN.with(RAYS)));
    }

    @Test
    void unknownModulesAndFieldsSuggestNames() {
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).init(Module.of("shape.sphear"))).problems())
            .containsExactly("e: no module is called shape.sphear (did you mean shape.sphere?).");
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).init(Module.of("vortex"))).problems())
            .containsExactly("e: no module is called vortex.");
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).init(Shape.sphere(1).with("radus", 2f))).problems())
            .containsExactly("e: shape.sphere has no field radus (did you mean radius?).");
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).init(Shape.sphere(1).with("radius", FloatCurve.constant(1))))
            .problems()).containsExactly("e: shape.sphere.radius takes a BINDING, not a CURVE.");
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).init(Module.of(Init.LIFETIME)
            .with("lifetime", ColorGradient.constant(1, 1, 1, 1)))).problems())
            .containsExactly("e: init.lifetime.lifetime takes a BINDING, not a GRADIENT.");
    }

    @Test
    void modulesRunOnlyAtTheirStageAndOnTheirTargets() {
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).init(Forces.gravity())).problems())
            .containsExactly("e: force.gravity runs at UPDATE, not at INIT.");
        assertThat(problems(e -> e.target(Target.CPU).spawn(Spawn.rate(1)).update(Module.of("game.rays")))
            .problems()).containsExactly("e: game.rays does not run on CPU emitters.");
    }

    @Test
    void bindingsReadParametersOfTheEffectOfTheirKind() {
        EffectParameter.Float misspelt = EffectParameter.ofFloat("intensty");
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).init(Init.size(Bind.param(misspelt)))).problems())
            .containsExactly("e: init.size.size reads no parameter of the effect: intensty (did you mean intensity?).");
        EffectParameter.Color colored = EffectParameter.ofColor("intensity");
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).init(Init.color(Bind.param(colored)))).problems())
            .containsExactly("e: init.color.color reads intensity, which is not a color.");
        assertThat(problems(e -> e.spawn(Spawn.rate(Bind.param(EffectParameter.ofFloat("tint"))))).problems())
            .containsExactly("e: rate reads tint, which is not a float.", "e spawns nothing.");
        assertThat(problems(e -> e.spawn(Spawn.rate(Bind.param(EffectParameter.ofFloat("heat"))))).problems())
            .containsExactly("e: rate reads no parameter of the effect: heat.", "e spawns nothing.");
        assertThat(problems(e -> e.spawn(Spawn.rate(1)).event(ParticleEvent.onBirth("e", Bind.param(
            EffectParameter.ofFloat("heat"))))).problems())
            .contains("e: birth event reads no parameter of the effect: heat.");
    }

    @Test
    void spawnAmountsAreTheSameForEveryParticle() {
        assertThat(problems(e -> e.spawn(Spawn.rate(Bind.age(FloatCurve.constant(5))))).problems())
            .containsExactly("e: rate is bound by AGE, which differs per particle.");
        assertThat(problems(e -> e.duration(1).spawn(Spawn.burst(1.5, 3))).problems())
            .containsExactly("e: burst at 1.5 s is after the end of its loop at 1.0 s.");
    }

    @Test
    void capacitiesCoverDemand() {
        assertThat(problems(e -> e.capacity(10).spawn(Spawn.rate(100))).problems())
            .containsExactly("e: its capacity of 10 is below its demand of 100 (rates times lifetime, plus bursts).");
        assertThat(problems(e -> e.spawn(Spawn.perDistance(Bind.constant(2)))).problems())
            .containsExactly("e needs a capacity: it spawns per distance or from events.");
        assertThat(problems(e -> e.init(Shape.point())).problems()).containsExactly("e spawns nothing.");
    }

    @Test
    void eventsSpawnEmittersOfTheEffectThatSpawnFromEvents() {
        InvalidEffectException p = problems(EffectData.builder()
            .emitter("sparks", e -> e.spawn(Spawn.rate(1)).event(ParticleEvent.onDeath("ember", Bind.constant(1)),
                ParticleEvent.onDeath("smoke", Bind.constant(1)), ParticleEvent.onDeath("shards", Bind.constant(1))))
            .emitter("embers", e -> e.capacity(10).spawn(Spawn.fromEvents()))
            .emitter("smoke", e -> e.spawn(Spawn.rate(1)))
            .emitter("shards", e -> e.target(Target.CPU).capacity(10).spawn(Spawn.fromEvents())));
        assertThat(p.problems()).containsExactly(
            "sparks: death event spawns no emitter of the effect: ember (did you mean embers?).",
            "sparks: death event spawns smoke, which does not spawn from events.",
            "sparks: death event: a GPU emitter cannot spawn into the CPU emitter shards.");
        assertThat(p.getMessage()).startsWith("The effect has 3 problems:\n  sparks: death event spawns no emitter");
        // A CPU emitter may spawn into a GPU one.
        EffectData mixed = EffectData.builder()
            .emitter("shards", e -> e.target(Target.CPU).spawn(Spawn.burst(0, 4))
                .event(ParticleEvent.onDeath("dust", Bind.constant(1))))
            .emitter("dust", e -> e.capacity(8).spawn(Spawn.fromEvents()))
            .build();
        assertThat(mixed.eventDepth(1)).isOne();
        // Emitters may come before those spawning them.
        EffectData childFirst = EffectData.builder()
            .emitter("dust", e -> e.capacity(8).spawn(Spawn.fromEvents()))
            .emitter("shards", e -> e.spawn(Spawn.burst(0, 4)).event(ParticleEvent.onDeath("dust", Bind.constant(1))))
            .build();
        assertThat(childFirst.eventDepth(0)).isOne();
    }

    @Test
    void eventsGoRoundInNoCycle() {
        InvalidEffectException p = problems(EffectData.builder()
            .emitter("seed", e -> e.spawn(Spawn.rate(1)).event(ParticleEvent.onDeath("a", Bind.constant(1))))
            .emitter("a", e -> e.capacity(9).spawn(Spawn.fromEvents())
                .event(ParticleEvent.onDeath("b", Bind.constant(1))))
            .emitter("b", e -> e.capacity(9).spawn(Spawn.fromEvents())
                .event(ParticleEvent.onDeath("a", Bind.constant(1)))));
        assertThat(p.problems()).containsExactly("Events go round in a cycle: a -> b -> a.");
        assertThat(p.getMessage()).isEqualTo("The effect has 1 problem:\n  Events go round in a cycle: a -> b -> a.");
        assertThat(problems(e -> e.capacity(5).spawn(Spawn.rate(1), Spawn.fromEvents())
            .event(ParticleEvent.onBirth("e", Bind.constant(1)))).problems())
            .containsExactly("Events go round in a cycle: e -> e.");
    }

    @Test
    void eventsNestNoDeeperThanAllowed() {
        EffectData.Builder chain = EffectData.builder()
            .emitter("l0", e -> e.spawn(Spawn.rate(1)).event(ParticleEvent.onDeath("l1", Bind.constant(1))));
        for (int level = 1; level <= 5; level++) {
            String next = "l" + (level + 1);
            chain.emitter("l" + level, e -> e.capacity(4).spawn(Spawn.fromEvents())
                .event(ParticleEvent.onDeath(next, Bind.constant(1))));
        }
        chain.emitter("l6", e -> e.capacity(4).spawn(Spawn.fromEvents()));
        assertThat(problems(chain).problems()).containsExactly(
            "l5 is 5 levels of events deep, more than the 4 allowed.",
            "l6 is 6 levels of events deep, more than the 4 allowed.");
        EffectData deep = chain.maxEventDepth(6).build();
        assertThat(deep.eventDepth(6)).isEqualTo(6);
        assertThat(deep.end()).isInfinite();
        assertThat(deep.lingering()).isEqualTo(7);
        // A diamond: d is two levels below the root either way.
        EffectData diamond = EffectData.builder()
            .emitter("root", e -> e.duration(1).spawn(Spawn.burst(0, 1)).event(
                ParticleEvent.onDeath("left", Bind.constant(1)), ParticleEvent.onDeath("right", Bind.constant(1))))
            .emitter("left", e -> e.capacity(1).spawn(Spawn.fromEvents())
                .event(ParticleEvent.onDeath("d", Bind.constant(1))))
            .emitter("right", e -> e.capacity(1).spawn(Spawn.fromEvents()).init(Init.lifetime(3))
                .event(ParticleEvent.onDeath("d", Bind.constant(1))))
            .emitter("d", e -> e.capacity(2).spawn(Spawn.fromEvents()))
            .build();
        assertThat(diamond.eventDepth(3)).isEqualTo(2);
        assertThat(diamond.emitterEnd(3)).isEqualTo(1 + 1 + 3 + 1);
        assertThat(diamond.emitterLingering(3)).isEqualTo(1 + 3 + 1);
    }

    @Test
    void namesAreUnique() {
        assertThat(problems(EffectData.builder().parameter(INTENSITY, 1).parameter(INTENSITY, 2)
            .emitter("e", e -> e.spawn(Spawn.rate(1))).emitter("e", e -> e.spawn(Spawn.rate(1)))).problems())
            .containsExactly("Two parameters are called intensity.", "Two emitters are called e.");
    }
}
