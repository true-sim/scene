package com.truesim.scene.effects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The values effects are made of: bindings, ranges, parameters, spawns, events, renderers, modules and types. */
class ValuesTest {

    static final EffectParameter.Float INTENSITY = EffectParameter.ofFloat("intensity");
    static final EffectParameter.Color TINT = EffectParameter.ofColor("tint");

    @Test
    void bindingsSayWhereValuesComeFromAndHowLargeTheyGet() {
        FloatCurve rising = FloatCurve.of(0, 1, 1, 3);
        assertThat(Bind.constant(2).max(9)).isEqualTo(2);
        assertThat(Bind.constant(2).times(-1).max(9)).isEqualTo(-2);
        assertThat(Bind.param(INTENSITY).times(150).max(2)).isEqualTo(300);
        assertThat(Bind.param(INTENSITY, rising).max(0.5f)).isEqualTo(2);
        assertThat(Bind.age(rising).max(0)).isEqualTo(3);
        assertThat(Bind.speed(rising).times(-1).max(0)).isEqualTo(-1);
        assertThat(Bind.random(1, 4).max(0)).isEqualTo(4);
        assertThat(Bind.random(Range.of(1, 4))).isEqualTo(Bind.random(1, 4));
        assertThat(Bind.param(INTENSITY).parameter()).isEqualTo("intensity");
        for (Binding.Kind kind : Binding.Kind.values()) {
            assertThat(kind.perInstance()).as(kind.name())
                .isEqualTo(kind != Binding.Kind.AGE && kind != Binding.Kind.SPEED);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> Bind.constant(Float.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> Bind.constant(1).times(Float.POSITIVE_INFINITY));
        assertThatNullPointerException().isThrownBy(() -> new Binding(null, 0, null, null, null, 1));
        assertThat(Bind.color(1, 0.5f, 0, 1).g()).isEqualTo(0.5f);
        assertThat(Bind.param(TINT).parameter()).isEqualTo("tint");
        ColorGradient fire = ColorGradient.constant(1, 0.3f, 0, 1);
        assertThat(Bind.age(fire).kind()).isEqualTo(ColorBinding.Kind.AGE);
        assertThat(Bind.random(fire).gradient()).isEqualTo(fire);
        assertThatNullPointerException().isThrownBy(() -> new ColorBinding(null, 0, 0, 0, 0, null, null));
    }

    @Test
    void rangesAreOrderedAndFinite() {
        assertThat(Range.of(2, 4).at(0.5f)).isEqualTo(3);
        assertThat(Range.of(2, 2).at(0.9f)).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> Range.of(4, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> Range.of(Float.NaN, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> Range.of(0, Float.NaN));
    }

    @Test
    void parametersAreEqualByNameAndKind() {
        assertThat(EffectParameter.ofFloat("a")).isEqualTo(EffectParameter.ofFloat("a"));
        assertThat((EffectParameter) EffectParameter.ofFloat("a")).isNotEqualTo(EffectParameter.ofColor("a"));
        assertThat(INTENSITY.size()).isOne();
        assertThat(TINT.size()).isEqualTo(4);
        assertThatNullPointerException().isThrownBy(() -> EffectParameter.ofFloat(null));
        assertThatNullPointerException().isThrownBy(() -> EffectParameter.ofColor(null));
    }

    @Test
    void spawnsAndEventsCheckTheirValues() {
        assertThat(Spawn.rate(5).amount()).isEqualTo(Bind.constant(5));
        assertThat(Spawn.burst(0.5, 3).time()).isEqualTo(0.5);
        assertThat(Spawn.perDistance(Bind.constant(2)).kind()).isEqualTo(Spawn.Kind.DISTANCE);
        assertThat(Spawn.fromEvents().amount()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> Spawn.burst(-1, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> Spawn.burst(Double.POSITIVE_INFINITY, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> Spawn.burst(Double.NaN, 3));
        Binding one = Bind.constant(1);
        assertThat(ParticleEvent.onBirth("b", one).kind()).isEqualTo(ParticleEvent.Kind.BIRTH);
        assertThat(ParticleEvent.onDeath("b", one).chance(0.3f).chance()).isEqualTo(0.3f);
        assertThat(ParticleEvent.onCollision("b", one).kind()).isEqualTo(ParticleEvent.Kind.COLLISION);
        assertThat(ParticleEvent.every(0.5, "b", one).interval()).isEqualTo(0.5);
        assertThat(ParticleEvent.emit("b", one).kind()).isEqualTo(ParticleEvent.Kind.EMIT);
        assertThatIllegalArgumentException().isThrownBy(() -> ParticleEvent.onBirth("b", one).chance(1.5f));
        assertThatIllegalArgumentException().isThrownBy(() -> ParticleEvent.onBirth("b", one).chance(-0.5f));
        assertThatIllegalArgumentException().isThrownBy(() -> ParticleEvent.every(0, "b", one));
        assertThatNullPointerException().isThrownBy(() -> new ParticleEvent(null, "b", one, 1, 0));
    }

    @Test
    void renderersCarryTheirSettings() {
        AssetRef<String> texture = AssetRef.of(com.truesim.scene.assets.AssetType.of("texture", String.class,
            s -> 1), AssetId.parse("4f1c09d2a7e35b6c81f0e2d4c6a8b9e1"));
        Renderer sparks = Renderer.billboard(Renderer.Facing.VELOCITY).set("stretch", 0.04f).asset("texture", texture)
            .additive();
        assertThat(sparks.alphaToAdd()).isOne();
        assertThat(sparks.sorted()).isFalse();
        assertThat(sparks.settings()).containsEntry("stretch", 0.04f).containsEntry("texture", texture);
        Renderer smoke = Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.SIX_WAY)
            .glass(Renderer.GlassOrder.BEFORE).alphaToAdd(0.2f).sorted(true).set("frames", 16);
        assertThat(smoke.lighting()).isEqualTo(Renderer.Lighting.SIX_WAY);
        assertThat(smoke.glass()).isEqualTo(Renderer.GlassOrder.BEFORE);
        assertThat(smoke.settings()).containsEntry("frames", 16);
        Renderer rocks = Renderer.mesh(texture, texture).castsShadows(true);
        assertThat(rocks.castsShadows()).isTrue();
        assertThat(rocks.facing()).isNull();
        assertThat(Renderer.ribbon().kind()).isEqualTo(Renderer.Kind.RIBBON);
        assertThat(Renderer.trail(8).settings()).containsEntry("points", 8);
        assertThat(Renderer.beam().kind()).isEqualTo(Renderer.Kind.BEAM);
        assertThatIllegalArgumentException().isThrownBy(() -> Renderer.trail(1));
        assertThatIllegalArgumentException().isThrownBy(() -> Renderer.trail(33));
        assertThatIllegalArgumentException().isThrownBy(() -> sparks.alphaToAdd(2));
        assertThatIllegalArgumentException().isThrownBy(() -> sparks.alphaToAdd(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Renderer(Renderer.Kind.MESH,
            Renderer.Facing.POSITION, Renderer.Lighting.UNLIT, Renderer.GlassOrder.AFTER, 0, true, false, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Renderer(Renderer.Kind.BILLBOARD, null,
            Renderer.Lighting.UNLIT, Renderer.GlassOrder.AFTER, 0, true, false, Map.of()));
    }

    @Test
    void modulesHoldValuesByFieldName() {
        Module m = Module.of("game.gust").with("strength", 2f).with("tint", Bind.color(1, 1, 1, 1))
            .with("curve", FloatCurve.constant(1)).with("gradient", ColorGradient.constant(1, 1, 1, 1))
            .with("speed", Bind.param(INTENSITY));
        assertThat(m.value("strength")).isEqualTo(Bind.constant(2));
        assertThat(m.value("missing")).isNull();
        assertThat(m).isEqualTo(Module.of("game.gust").with("strength", 2f).with("tint", Bind.color(1, 1, 1, 1))
            .with("curve", FloatCurve.constant(1)).with("gradient", ColorGradient.constant(1, 1, 1, 1))
            .with("speed", Bind.param(INTENSITY)));
        assertThat(ModuleType.Kind.of(Bind.constant(1))).isEqualTo(ModuleType.Kind.BINDING);
        assertThat(ModuleType.Kind.of(Bind.color(1, 1, 1, 1))).isEqualTo(ModuleType.Kind.COLOR);
        assertThat(ModuleType.Kind.of(FloatCurve.constant(1))).isEqualTo(ModuleType.Kind.CURVE);
        assertThat(ModuleType.Kind.of(ColorGradient.constant(1, 1, 1, 1))).isEqualTo(ModuleType.Kind.GRADIENT);
        // The module classes.
        assertThat(Shape.point().type()).isEqualTo(Shape.POINT);
        assertThat(Shape.sphere(2).value("thickness")).isEqualTo(Bind.constant(1));
        assertThat(Shape.box(1, 2, 3).value("y")).isEqualTo(Bind.constant(2));
        assertThat(Shape.cone(0.3f, 0.1f).value("angle")).isEqualTo(Bind.constant(0.3f));
        assertThat(Shape.disc(4).value("radius")).isEqualTo(Bind.constant(4));
        assertThat(Init.lifetime(2).value("lifetime")).isEqualTo(Bind.constant(2));
        assertThat(Init.speed(Bind.constant(3)).type()).isEqualTo(Init.SPEED);
        assertThat(Init.velocity(Bind.constant(1), Bind.constant(2), Bind.constant(3)).value("z"))
            .isEqualTo(Bind.constant(3));
        assertThat(Init.size(Bind.constant(1)).type()).isEqualTo(Init.SIZE);
        assertThat(Init.color(Bind.color(1, 0, 0, 1)).type()).isEqualTo(Init.COLOR);
        assertThat(Forces.gravity().value("scale")).isEqualTo(Bind.constant(1));
        assertThat(Forces.drag(0.5f).value("coefficient")).isEqualTo(Bind.constant(0.5f));
        assertThat(Over.size(FloatCurve.constant(1)).type()).isEqualTo(Over.SIZE);
        assertThat(Over.color(ColorGradient.constant(1, 1, 1, 1)).type()).isEqualTo(Over.COLOR);
    }

    @Test
    void registriesKnowModuleTypesByName() {
        ModuleType rays = ModuleType.builder("game.rays", ModuleType.Stage.UPDATE).targets(Target.GPU)
            .capability(Capability.RAY_QUERY).field("bounce", Bind.constant(0.5f))
            .field("tint", Bind.color(1, 1, 1, 1)).field("curve", FloatCurve.constant(1))
            .field("gradient", ColorGradient.constant(1, 1, 1, 1)).build();
        assertThat(rays.runsOn(Target.GPU)).isTrue();
        assertThat(rays.runsOn(Target.CPU)).isFalse();
        assertThat(rays.fields().get("tint").kind()).isEqualTo(ModuleType.Kind.COLOR);
        assertThat(rays.fields().get("bounce").fallback()).isEqualTo(Bind.constant(0.5f));
        ModuleRegistry modules = ModuleRegistry.BUILT_IN.with(rays);
        assertThat(modules.type("game.rays")).isSameAs(rays);
        assertThat(ModuleRegistry.BUILT_IN.type("game.rays")).isNull();
        assertThat(ModuleRegistry.BUILT_IN.names()).contains(Shape.CONE, Forces.DRAG, Over.COLOR).hasSize(27);
        assertThat(ModuleRegistry.BUILT_IN.type(Shape.SPHERE).runsOn(Target.CPU)).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> modules.with(rays)).withMessageContaining("already");
        assertThatIllegalArgumentException().isThrownBy(() -> new ModuleType("x", ModuleType.Stage.INIT,
            java.util.Set.of(), Capability.NONE, Map.of())).withMessageContaining("no target");
    }

    @Test
    void effectTypesCheckTheirBudgets() {
        EffectType impacts = EffectType.builder("impacts").maxDistance(80).maxInstances(64)
            .significance(EffectType.Significance.AGE).whenCulled(EffectType.Culled.KILL).priority(2)
            .lod(30, 0.5f, 2, 0.5f).lod(60, 0.25f, 4, 0.25f).hysteresis(0.15).spawnScale(Quality.LOW, 0.25f)
            .burstCell(32).build();
        assertThat(impacts.lodCount()).isEqualTo(3);
        assertThat(impacts.lod(0)).isEqualTo(EffectType.FULL);
        assertThat(impacts.lod(2).updateEvery()).isEqualTo(4);
        assertThat(impacts.spawnScale(Quality.LOW)).isEqualTo(0.25f);
        assertThat(impacts.spawnScale(Quality.EPIC)).isOne();
        assertThat(impacts).isEqualTo(EffectType.builder("impacts").maxDistance(80).maxInstances(64)
            .significance(EffectType.Significance.AGE).whenCulled(EffectType.Culled.KILL).priority(2)
            .lod(30, 0.5f, 2, 0.5f).lod(60, 0.25f, 4, 0.25f).hysteresis(0.15).spawnScale(Quality.LOW, 0.25f)
            .burstCell(32).build());
        assertThat(EffectType.DEFAULT.maxInstances()).isEqualTo(Integer.MAX_VALUE);
        assertThatIllegalArgumentException().isThrownBy(() -> EffectType.builder("x").lod(60, 1, 1, 1)
            .lod(30, 1, 1, 1).build()).withMessageContaining("increasing");
        assertThatIllegalArgumentException().isThrownBy(() -> EffectType.builder("x").lod(30, 1, 1, 1)
            .lod(30, 1, 1, 1).build());
        assertThatIllegalArgumentException().isThrownBy(() -> EffectType.builder("x").maxDistance(0).build());
        assertThatIllegalArgumentException().isThrownBy(() -> EffectType.builder("x").maxInstances(-1).build());
        assertThatIllegalArgumentException().isThrownBy(() -> EffectType.builder("x").hysteresis(-1).build());
        assertThatIllegalArgumentException().isThrownBy(() -> EffectType.builder("x").burstCell(0).build());
        assertThatIllegalArgumentException().isThrownBy(() -> EffectType.builder("x")
            .spawnScale(Quality.HIGH, 1.5f).build());
        assertThatIllegalArgumentException().isThrownBy(() -> EffectType.builder("x")
            .spawnScale(Quality.HIGH, -1).build());
        assertThatIllegalArgumentException().isThrownBy(() -> new EffectType("x", 1, 1,
            EffectType.Significance.AGE, EffectType.Culled.KILL, 0, java.util.List.of(), 0, java.util.List.of(1f),
            1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EffectType.Lod(0, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EffectType.Lod(1, -1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EffectType.Lod(1, 2, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EffectType.Lod(1, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new EffectType.Lod(1, 1, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new EffectType.Lod(1, 1, 1, 2));
    }

    @Test
    void suggestionsAreTheClosestNameIfCloseEnough() {
        assertThat(Suggestions.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(Suggestions.distance("", "abc")).isEqualTo(3);
        assertThat(Suggestions.didYouMean("shape.sphear", ModuleRegistry.BUILT_IN.names()))
            .isEqualTo(" (did you mean shape.sphere?)");
        assertThat(Suggestions.didYouMean("wind", ModuleRegistry.BUILT_IN.names())).isEmpty();
    }
}
