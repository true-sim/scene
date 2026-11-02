package com.truesim.scene.effects;

import static com.truesim.scene.effects.ValuesTest.INTENSITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Events of CPU emitters (E9, step 14): fireworks bursting on the death of a burst, birth events a frame later,
 * periodic and collision events, chances, counts by every binding, what children inherit, room, and the same children
 * on any thread count.
 */
class EventsTest {

    private static final long FRAME = 10_000;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final ParticleCursor cursor = new ParticleCursor();

    private static void frames(EffectRuntime runtime, CpuSimulation simulation, int n) {
        for (int f = 0; f < n; f++) {
            runtime.update(FRAME);
            simulation.update();
        }
    }

    /** Fireworks: a rocket that bursts into 40 stars on its death, each bursting into 3 sparkles on theirs. */
    static EffectData fireworks(Target target) {
        return EffectData.builder().parameter(INTENSITY, 1)
            .emitter("rocket", e -> e.target(target).spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.495f),
                    Init.velocity(Bind.constant(0), Bind.constant(10), Bind.constant(0)), Init.color(Bind.color(1, 0.5f,
                        0.2f, 1)), Init.size(Bind.constant(0.4f)))
                .update(Forces.gravity()).event(ParticleEvent.onDeath("stars", Bind.constant(40))))
            .emitter("stars", e -> e.target(target).capacity(40).spawn(Spawn.fromEvents())
                .init(Shape.point(), Init.speed(Bind.constant(4)), Init.lifetime(0.495f), Init.inherit(0.5f))
                .update(Forces.gravity()).event(ParticleEvent.onDeath("sparkles", Bind.constant(3))))
            .emitter("sparkles", e -> e.target(target).capacity(120).spawn(Spawn.fromEvents())
                .init(Shape.sphere(0.1f), Init.lifetime(0.3f), Init.inherit(0, true, false)))
            .build();
    }

    @Test
    void fireworksBurstOnTheDeathOfABurst() {
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long effect = effects.play(fireworks(Target.CPU), 3, 1, -2).id();
        frames(runtime, simulation, 49);
        assertThat(simulation.count(effect, 0)).isOne();
        simulation.particles(effect, 0, cursor);
        cursor.next();
        double rx = cursor.x();
        double ry = cursor.y();
        double rvy = cursor.vy();
        frames(runtime, simulation, 1);
        assertThat(simulation.count(effect, 0)).as("the rocket is gone").isZero();
        assertThat(simulation.count(effect, 1)).as("forty stars").isEqualTo(40);
        simulation.particles(effect, 1, cursor);
        double mean = 0;
        for (int i = 0; cursor.next(); i++) {
            assertThat(cursor.spawnIndex()).isEqualTo(i);
            assertThat(cursor.x()).as("at the rocket").isCloseTo(rx, within(1e-3));
            assertThat(cursor.y()).isCloseTo(ry, within(1e-3));
            assertThat(cursor.color(1)).as("its color").isEqualTo(0.5f);
            assertThat(cursor.size()).as("its size").isCloseTo(0.04f, within(1e-6f));
            mean += cursor.vy() / 40;
        }
        assertThat(mean).as("half the rocket's velocity on top of their own").isCloseTo(0.5 * rvy, within(0.6));
        frames(runtime, simulation, 50);
        assertThat(simulation.count(effect, 1)).isZero();
        assertThat(simulation.count(effect, 2)).as("three sparkles per star").isEqualTo(120);
        simulation.particles(effect, 2, cursor);
        cursor.next();
        assertThat(cursor.color(0)).as("the star's color, which was the rocket's").isEqualTo(1);
        assertThat(cursor.size()).as("not the star's size").isEqualTo(0.1f);
    }

    @Test
    void theSameChildrenOnAnyThreadCount() throws Exception {
        long[] seeds = new long[2];
        for (int run = 0; run < 2; run++) {
            SceneGraph s = new SceneGraph(new ChangeClock());
            Effects fx = new Effects(s);
            try (JobSystem jobs = new JobSystem(run == 0 ? 1 : 4)) {
                EffectRuntime r = new EffectRuntime(fx, jobs);
                CpuSimulation simulation = new CpuSimulation(r, null, jobs);
                long[] ids = new long[6];
                for (int k = 0; k < ids.length; k++) {
                    ids[k] = fx.play(fireworks(Target.CPU), k, 0, 0).seed(k + 5).id();
                }
                frames(r, simulation, 75);
                for (long id : ids) {
                    simulation.particles(id, 2, cursor);
                    while (cursor.next()) {
                        seeds[run] = seeds[run] * 31 + cursor.seed() + Double.doubleToLongBits(cursor.x());
                    }
                }
            }
        }
        assertThat(seeds[1]).isEqualTo(seeds[0]);
    }

    /** One CPU emitter of a single particle sending {@code event} into a child emitter set up by {@code child}. */
    private EffectData parentAndChild(Consumer<EmitterData.Builder> parent, ParticleEvent event,
            Consumer<EmitterData.Builder> child) {
        return EffectData.builder().parameter(INTENSITY, 2)
            .emitter("parent", e -> parent.accept(e.target(Target.CPU).event(event)))
            .emitter("child", e -> child.accept(e.target(Target.CPU).capacity(50).spawn(Spawn.fromEvents())
                .init(Init.lifetime(10))))
            .build();
    }

    @Test
    void birthEventsGoOutTheFrameAfterAndPeriodicOnesOnTheirInterval() {
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long born = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 2)).init(Init.lifetime(5)),
            ParticleEvent.onBirth("child", Bind.constant(1)), e -> { }), 0, 0, 0).id();
        long periodic = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(5)),
            ParticleEvent.every(0.1, "child", Bind.param(INTENSITY)), e -> { }), 0, 0, 0).id();
        frames(runtime, simulation, 1);
        assertThat(simulation.count(born, 1)).as("born this frame: not yet").isZero();
        frames(runtime, simulation, 1);
        assertThat(simulation.count(born, 1)).isEqualTo(2);
        frames(runtime, simulation, 28);
        assertThat(simulation.count(born, 1)).as("once each").isEqualTo(2);
        assertThat(simulation.count(periodic, 1)).as("at 0.1 and 0.2 s, two at a time").isEqualTo(4);
    }

    @Test
    void collisionEventsChancesAndCounts() {
        effects.colliders().plane(node(), 1);
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long hits = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 4)).init(Init.lifetime(5))
                .update(Forces.gravity()).collide(Collide.colliders(1).kill()),
            ParticleEvent.onCollision("child", Bind.age(FloatCurve.constant(2))), e -> { }), 0, 1, 0).id();
        long never = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 4)).init(Init.lifetime(0.05f)),
            ParticleEvent.onDeath("child", Bind.constant(1)).chance(0), e -> { }), 0, 1, 0).id();
        long none = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 4)).init(Init.lifetime(0.05f)),
            ParticleEvent.onDeath("child", Bind.constant(0)), e -> { }), 0, 1, 0).id();
        long random = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 4)).init(Init.lifetime(0.05f)),
            ParticleEvent.onDeath("child", Bind.random(2, 3)), e -> { }), 0, 1, 0).id();
        long curve = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.05f)),
            ParticleEvent.onDeath("child", Bind.param(INTENSITY, FloatCurve.of(0, 0, 4, 4))), e -> { }), 0, 1, 0)
            .id();
        long speed = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.05f)),
            ParticleEvent.onDeath("child", Bind.speed(FloatCurve.constant(3))), e -> { }), 0, 1, 0).id();
        long emit = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.05f)),
            ParticleEvent.emit("child", Bind.constant(30)), e -> { }), 0, 1, 0).id();
        frames(runtime, simulation, 60);
        assertThat(simulation.count(hits, 1)).as("two each, on the floor").isEqualTo(8);
        assertThat(simulation.count(never, 1)).isZero();
        assertThat(simulation.count(none, 1)).isZero();
        assertThat(simulation.count(random, 1)).isBetween(8, 12);
        assertThat(simulation.count(curve, 1)).as("the parameter's 2 through the curve").isEqualTo(2);
        assertThat(simulation.count(speed, 1)).isEqualTo(3);
        assertThat(simulation.count(emit, 1)).as("emitting particles: step 19").isZero();
    }

    @Test
    void childrenWithoutRoomAreDroppedAndShortLivedOnesNotSpawned() {
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long full = effects.play(parentAndChild(e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.05f)),
            ParticleEvent.onDeath("child", Bind.constant(80)), e -> { }), 0, 0, 0).id();
        long stillborn = effects.play(EffectData.builder()
            .emitter("parent", e -> e.target(Target.CPU).spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.05f))
                .event(ParticleEvent.onDeath("child", Bind.constant(5))))
            .emitter("child", e -> e.target(Target.CPU).capacity(10).spawn(Spawn.fromEvents())
                .init(Init.lifetime(0)))
            .build(), 0, 0, 0).id();
        frames(runtime, simulation, 10);
        assertThat(simulation.count(full, 1)).isEqualTo(50);
        assertThat(simulation.dropped(full, 1)).isEqualTo(30);
        assertThat(simulation.count(stillborn, 1)).isZero();
    }

    @Test
    void localChildrenStartAtTheirWorldSpaceParent() {
        long holder = node();
        scene.setTranslation(holder, 2, 3, 4);
        scene.setRotation(holder, 0, Math.sin(0.6), 0, Math.cos(0.6));
        scene.update();
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        EffectData data = EffectData.builder()
            .emitter("parent", e -> e.target(Target.CPU).spawn(Spawn.burst(0, 1)).init(Init.lifetime(0.2f),
                    Init.velocity(Bind.constant(1), Bind.constant(0), Bind.constant(0)))
                .event(ParticleEvent.onDeath("child", Bind.constant(1))))
            .emitter("child", e -> e.target(Target.CPU).space(Space.LOCAL).capacity(5).spawn(Spawn.fromEvents())
                .init(Init.lifetime(10), Init.inherit(1)))
            .build();
        long effect = effects.play(data, holder).id();
        frames(runtime, simulation, 19);
        simulation.particles(effect, 0, cursor);
        cursor.next();
        double x = cursor.x();
        double y = cursor.y();
        double z = cursor.z();
        frames(runtime, simulation, 1);
        simulation.particles(effect, 1, cursor);
        assertThat(cursor.next()).isTrue();
        assertThat(cursor.x()).isCloseTo(x, within(1e-3));
        assertThat(cursor.y()).isCloseTo(y, within(1e-3));
        assertThat(cursor.z()).isCloseTo(z, within(1e-3));
        assertThat((double) cursor.vx()).as("its velocity, in the world").isCloseTo(Math.cos(1.2), within(1e-3));
    }

    @Test
    void localParentsSendFromWhereTheyAreInTheWorld() {
        long holder = node();
        scene.setTranslation(holder, 1, 2, 3);
        scene.setRotation(holder, Math.sin(0.4), 0, 0, Math.cos(0.4));
        scene.update();
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        EffectData data = EffectData.builder()
            .emitter("parent", e -> e.target(Target.CPU).space(Space.LOCAL).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(0.2f), Init.size(Bind.constant(2)),
                    Init.velocity(Bind.constant(0), Bind.constant(1), Bind.constant(0)))
                .event(ParticleEvent.onDeath("child", Bind.constant(1))))
            .emitter("child", e -> e.target(Target.CPU).capacity(5).spawn(Spawn.fromEvents())
                .init(Init.lifetime(10), Init.size(Bind.constant(0.5f)), Init.inherit(0, false, true)))
            .build();
        long effect = effects.play(data, holder).id();
        frames(runtime, simulation, 19);
        simulation.particles(effect, 0, cursor);
        cursor.next();
        double y = cursor.y();
        double z = cursor.z();
        frames(runtime, simulation, 1);
        simulation.particles(effect, 1, cursor);
        assertThat(cursor.next()).isTrue();
        assertThat(cursor.y()).isCloseTo(y, within(1e-3));
        assertThat(cursor.z()).isCloseTo(z, within(1e-3));
        assertThat(cursor.size()).as("its parent's size times its own").isEqualTo(1);
        assertThat(cursor.color(0)).isEqualTo(1);
    }

    private long node() {
        long node = scene.create();
        scene.update();
        return node;
    }
}
