package com.truesim.scene.effects;

import static com.truesim.scene.effects.ValuesTest.INTENSITY;
import static com.truesim.scene.effects.ValuesTest.TINT;
import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The Java reference simulator: motion against analytic paths, order across deaths, spaces, shapes and the hash. */
class CpuSimulationTest {

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final CpuSimulation simulation = new CpuSimulation(runtime, null);
    private final ParticleCursor cursor = new ParticleCursor();

    /** One CPU emitter set up by {@code setup}. */
    private static EffectData cpu(Consumer<EmitterData.Builder> setup) {
        return EffectData.builder().parameter(INTENSITY, 1).parameter(TINT, 1, 0.5f, 0.25f, 1)
            .emitter("cpu", e -> setup.accept(e.target(Target.CPU))).build();
    }

    private void frame(long ticks) {
        runtime.update(ticks);
        simulation.update();
    }

    @Test
    void theHashMatchesItsFixedVectors() throws IOException {
        int checked = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                Hash.class.getResourceAsStream("hash-vectors.csv"), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split(",", -1);
                int a = Integer.parseUnsignedInt(f[1], 16);
                int expected = Integer.parseUnsignedInt(f[3], 16);
                int actual = switch (f[0]) {
                    case "pcg" -> Hash.pcg(a);
                    case "combine" -> Hash.combine(a, Integer.parseUnsignedInt(f[2], 16));
                    default -> Float.floatToRawIntBits(Hash.random(a, Integer.parseUnsignedInt(f[2], 16)));
                };
                assertThat(Integer.toHexString(actual)).as(line).isEqualTo(Integer.toHexString(expected));
                checked++;
            }
        }
        assertThat(checked).isEqualTo(26);
    }

    /** Checks a particle thrown at (3, 10, 0) m/s against the analytic path, with drag {@code k} (0: none). */
    private void ballistic(float k, long[] frames) {
        EffectData thrown = cpu(e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(10),
            Init.velocity(Bind.constant(3), Bind.constant(10), Bind.constant(0)))
            .update(k == 0 ? new Module[] {Forces.gravity()} : new Module[] {Forces.gravity(), Forces.drag(k)}));
        long effect = effects.play(thrown, 100, 50, -20).id();
        double t = 0;
        for (long ticks : frames) {
            frame(ticks);
            t += Math.min(CpuSimulation.MAX_STEP, ticks * 1e-6);
            simulation.particles(effect, 0, cursor);
            assertThat(cursor.next()).isTrue();
            assertThat((double) cursor.age()).isCloseTo(t, within(1e-4));
            double x;
            double y;
            double vy;
            if (k == 0) {
                x = 3 * t;
                y = 10 * t - 0.5 * Forces.G * t * t;
                vy = 10 - Forces.G * t;
            } else {
                double terminal = -Forces.G / k;
                double fade = (1 - Math.exp(-k * t)) / k;
                x = 3 * fade;
                y = terminal * t + (10 - terminal) * fade;
                vy = terminal + (10 - terminal) * Math.exp(-k * t);
            }
            assertThat(cursor.x()).as("x at " + t).isCloseTo(100 + x, within(2e-3));
            assertThat(cursor.y()).as("y at " + t).isCloseTo(50 + y, within(2e-3));
            assertThat(cursor.z()).isCloseTo(-20, within(1e-9));
            assertThat((double) cursor.vy()).as("vy at " + t).isCloseTo(vy, within(2e-3));
            assertThat(cursor.next()).isFalse();
        }
    }

    @Test
    void ballisticParticlesFollowTheirAnalyticPathsAtAnyFrameRate() {
        for (int hz : new int[] {30, 60, 90, 144}) {
            long[] frames = new long[2 * hz];
            java.util.Arrays.fill(frames, Math.round(1e6 / hz));
            ballistic(0, frames);
            ballistic(0.8f, frames);
        }
        ballistic(0, new long[] {16_667, 50_000, 2_000, 99_000, 16_667});
        ballistic(1.5f, new long[] {16_667, 50_000, 2_000, 99_000, 16_667});
    }

    @Test
    void aHitchMovesParticlesAtMostOneMaximumStep() {
        ballistic(0, new long[] {16_667, 500_000, 16_667});
    }

    @Test
    void survivorsKeepTheirSpawnOrderAcrossDeaths() {
        EffectData sparks = cpu(e -> e.capacity(60).spawn(Spawn.rate(50))
            .init(Init.lifetime(Bind.random(0.1f, 1))));
        long effect = effects.play(sparks, 0, 0, 0).seed(5).id();
        int emitterSeed = EffectRuntime.emitterSeed(5, 0);
        long t = 0;
        for (int f = 0; f < 180; f++) {
            frame(16_667L);
            t += 16_667;
            List<Long> expected = new ArrayList<>();
            for (long i = 0; i < runtime.spawned(effect, 0); i++) {
                double born = (i + 1) / 50.0;
                float lifetime = Range.of(0.1f, 1).at(Hash.random(EffectRuntime.particleSeed(emitterSeed, i), 0));
                if (t * 1e-6 - born < lifetime) {
                    expected.add(i);
                }
            }
            List<Long> alive = new ArrayList<>();
            simulation.particles(effect, 0, cursor);
            while (cursor.next()) {
                alive.add(cursor.spawnIndex());
                assertThat(cursor.seed()).isEqualTo(EffectRuntime.particleSeed(emitterSeed, cursor.spawnIndex()));
                assertThat(cursor.lifetime()).isBetween(0.1f, 1f);
            }
            assertThat(alive).as("frame " + f).isEqualTo(expected);
            assertThat(simulation.count(effect, 0)).isEqualTo(expected.size());
        }
        assertThat(simulation.dropped(effect, 0)).isZero();
    }

    @Test
    void worldSpaceParticlesStayWhereBornAndLocalOnesMoveWithTheirNode() {
        long node = scene.create();
        scene.setTranslation(node, 1, 0, 0);
        scene.update();
        EffectData both = EffectData.builder()
            .emitter("world", e -> e.target(Target.CPU).spawn(Spawn.burst(0, 1)).init(Init.lifetime(10),
                Init.velocity(Bind.constant(1), Bind.constant(0), Bind.constant(0))))
            .emitter("local", e -> e.target(Target.CPU).space(Space.LOCAL).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(10), Init.velocity(Bind.constant(1), Bind.constant(0), Bind.constant(0))))
            .build();
        long effect = effects.play(both, node).id();
        frame(1L);
        scene.setTranslation(node, 6, 0, 0);
        scene.setRotation(node, 0, Math.sqrt(0.5), 0, Math.sqrt(0.5)); // a quarter turn about +Y
        scene.update();
        frame(99_999L);
        for (int i = 0; i < 9; i++) {
            frame(100_000L);
        }
        simulation.particles(effect, 0, cursor);
        cursor.next();
        assertThat(cursor.x()).as("born at 1, moving 1 m/s for a second").isCloseTo(2, within(1e-5));
        assertThat(cursor.z()).isCloseTo(0, within(1e-6));
        simulation.particles(effect, 1, cursor);
        cursor.next();
        assertThat(cursor.x()).as("with the node").isCloseTo(6, within(1e-5));
        assertThat(cursor.z()).as("turned with it").isCloseTo(-1, within(1e-5));
        assertThat((double) cursor.vz()).isCloseTo(-1, within(1e-6));
        assertThat((double) cursor.vx()).isCloseTo(0, within(1e-6));
        assertThat((double) cursor.vy()).isCloseTo(0, within(1e-6));
        assertThat(cursor.y()).isCloseTo(0, within(1e-6));
        // Born now, a world-space particle starts turned by its node.
        Effect late = effects.play(both, node);
        frame(1L);
        simulation.particles(late.id(), 0, cursor);
        cursor.next();
        assertThat((double) cursor.vz()).isCloseTo(-1, within(1e-6));
        // Gravity is world down for local particles too.
        scene.setRotation(node, Math.sqrt(0.5), 0, 0, Math.sqrt(0.5)); // a quarter turn about +X
        scene.update();
        EffectData falling = EffectData.builder()
            .emitter("local", e -> e.target(Target.CPU).space(Space.LOCAL).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(10)).update(Forces.gravity()))
            .build();
        long drop = effects.play(falling, node).id();
        frame(1L);
        frame(100_000L);
        simulation.particles(drop, 0, cursor);
        cursor.next();
        assertThat(cursor.y()).isCloseTo(-0.5 * Forces.G * 0.1 * 0.1, within(1e-5));
        assertThat(cursor.z()).isCloseTo(0, within(1e-6));
    }

    @Test
    void forcesActInTheWorldOnWorldSpaceParticlesAndInTheFrameOnLocalOnes() {
        long node = scene.create();
        scene.setTranslation(node, 3, 0, 0);
        scene.setRotation(node, Math.sqrt(0.5), 0, 0, Math.sqrt(0.5)); // a quarter turn about +X: its down is +Z
        scene.update();
        EffectData falling = EffectData.builder()
            .emitter("world", e -> e.target(Target.CPU).spawn(Spawn.burst(0, 1)).init(Init.lifetime(10))
                .update(Forces.gravity()))
            .emitter("local", e -> e.target(Target.CPU).space(Space.LOCAL).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(10)).update(Forces.gravity()))
            .build();
        long effect = effects.play(falling, node).id();
        frame(1L);
        frame(100_000L);
        double drop = -0.5 * Forces.G * 0.1 * 0.1;
        simulation.particles(effect, 0, cursor);
        cursor.next();
        assertThat(cursor.y()).as("along world -Y, the frame turned").isCloseTo(drop, within(1e-5));
        assertThat(cursor.x()).isCloseTo(3, within(1e-6));
        assertThat(cursor.z()).isCloseTo(0, within(1e-6));
        assertThat((double) cursor.vy()).isCloseTo(-Forces.G * 0.1, within(1e-5));
        simulation.particles(effect, 1, cursor);
        cursor.next();
        assertThat(cursor.y()).as("world down too, through its frame").isCloseTo(drop, within(1e-5));
        assertThat(cursor.z()).isCloseTo(0, within(1e-6));
    }

    @Test
    void worldSpaceParticlesInheritTheirEffectsVelocity() {
        long node = scene.create();
        scene.update();
        EffectData puffs = EffectData.builder()
            .emitter("world", e -> e.target(Target.CPU).spawn(Spawn.rate(10)).init(Init.lifetime(10)))
            .emitter("local", e -> e.target(Target.CPU).space(Space.LOCAL).spawn(Spawn.rate(10))
                .init(Init.lifetime(10)))
            .build();
        Effect moving = effects.play(puffs, node).inheritVelocity(0.5f);
        frame(1L);
        scene.setTranslation(node, 0.4, 0, 0); // 4 m/s over the next tenth of a second
        scene.update();
        frame(100_000L);
        simulation.particles(moving.id(), 0, cursor);
        assertThat(cursor.next()).isTrue();
        assertThat((double) cursor.vx()).as("half of 4 m/s").isCloseTo(2, within(1e-4));
        simulation.particles(moving.id(), 1, cursor);
        assertThat(cursor.next()).isTrue();
        assertThat((double) cursor.vx()).as("local ones move with it already").isZero();
        // Bursts into shared instances inherit nothing.
        EffectData hits = EffectData.builder().emitter("hit", e -> e.target(Target.CPU).spawn(Spawn.burst(0, 1))
            .init(Init.lifetime(10))).build();
        long shared = effects.burst(hits, 1, 0, 0, 0, 1, 0, 0);
        effects.play(hits, 0, 0, 0).inheritVelocity(1); // born in its first, empty frame: no velocity yet
        frame(0L);
        simulation.particles(shared, 0, cursor);
        assertThat(cursor.next()).isTrue();
        assertThat((double) cursor.vx()).isZero();
    }

    @Test
    void farFromTheirOriginWorldSpaceParticlesRebaseKeepingTheirPlaces() {
        long node = scene.create();
        scene.update();
        EffectData trail = EffectData.builder()
            .emitter("world", e -> e.target(Target.CPU).spawn(Spawn.burst(0, 1)).init(Init.lifetime(100)))
            .emitter("local", e -> e.target(Target.CPU).space(Space.LOCAL).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(100)))
            .emitter("gpu", e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(100)))
            .build();
        long effect = effects.play(trail, node).id();
        frame(1L);
        double r = CpuSimulation.REBASE_DISTANCE;
        // Each axis alone past the distance from the origin, and all within it.
        for (double[] at : new double[][] {{1000, 0, 0}, {r + 1, 0, 0}, {0, r + 1, 0}, {0, r + 1, r + 2},
            {r, r, r}, {r, 2 * r + 2, r}, {3 * r, 0, 3 * r}}) {
            scene.setTranslation(node, at[0], at[1], at[2]);
            scene.update();
            frame(1L);
            simulation.particles(effect, 0, cursor);
            cursor.next();
            assertThat(cursor.x()).as("the particle stays where born").isCloseTo(0, within(1e-3));
            assertThat(cursor.y()).isCloseTo(0, within(1e-3));
            assertThat(cursor.z()).isCloseTo(0, within(1e-3));
        }
        // Born out there, it is as precise as at the origin, however far its effect then goes.
        scene.setTranslation(node, 7_000_000.25, 0, 0);
        scene.update();
        long far = effects.play(trail, node).id();
        frame(1L);
        scene.setTranslation(node, 7_000_000.25 + 2 * CpuSimulation.REBASE_DISTANCE, 0, 0);
        scene.update();
        frame(1L);
        simulation.particles(far, 0, cursor);
        cursor.next();
        assertThat(cursor.x()).isEqualTo(7_000_000.25);
        simulation.particles(far, 1, cursor);
        cursor.next();
        assertThat(cursor.x()).as("local ones are in the frame")
            .isEqualTo(7_000_000.25 + 2 * CpuSimulation.REBASE_DISTANCE);
    }

    @Test
    void replacedEffectsStartTheirParticlesOver() {
        EffectData one = cpu(e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(10)));
        EffectData three = cpu(e -> e.spawn(Spawn.burst(0, 3)).init(Init.lifetime(10)));
        long effect = effects.play(one, 0, 0, 0).id();
        frame(1L);
        frame(1_000L);
        assertThat(simulation.count(effect, 0)).isOne();
        effects.replace(one, three);
        frame(1L);
        assertThat(simulation.count(effect, 0)).as("the old particles gone, the new burst fired").isEqualTo(3);
        assertThat(runtime.spawned(effect, 0)).isEqualTo(3);
        assertThat(runtime.effects()).isSameAs(effects);
    }

    /** {@code count} particles of an emitter with {@code shape}, moving at 1 m/s along its directions. */
    private ParticleCursor shaped(Module shape, int count) {
        EffectData data = cpu(e -> e.spawn(Spawn.burst(0, count)).init(shape, Init.speed(Bind.constant(1))));
        long effect = effects.play(data, 0, 0, 0).id();
        frame(1L);
        return simulation.particles(effect, 0, new ParticleCursor());
    }

    @Test
    void shapesPlaceAndDirectParticles() {
        double eps = 1e-5;
        ParticleCursor c = shaped(Shape.point(), 100);
        assertThat(c.count()).isEqualTo(100);
        while (c.next()) {
            assertThat(Math.hypot(c.x(), Math.hypot(c.y(), c.z()))).isLessThan(eps);
            assertThat(Math.hypot(c.vx(), Math.hypot(c.vy(), c.vz()))).isCloseTo(1, within(1e-5));
        }
        c = shaped(Shape.sphere(2, 0), 100);
        while (c.next()) {
            double r = Math.hypot(c.x(), Math.hypot(c.y(), c.z()));
            assertThat(r).isCloseTo(2, within(1e-4));
            double outwards = (c.x() * c.vx() + c.y() * c.vy() + c.z() * c.vz()) / r;
            assertThat(outwards).isCloseTo(1, within(1e-4));
        }
        c = shaped(Shape.sphere(2), 100);
        int inner = 0;
        while (c.next()) {
            double r = Math.hypot(c.x(), Math.hypot(c.y(), c.z()));
            assertThat(r).isLessThanOrEqualTo(2 + eps);
            inner += r < 1 ? 1 : 0;
        }
        assertThat(inner).as("an eighth of a ball's volume is within half its radius").isBetween(3, 30);
        c = shaped(Shape.box(1, 2, 3), 100);
        while (c.next()) {
            assertThat(Math.abs(c.x())).isLessThanOrEqualTo(0.5 + eps);
            assertThat(Math.abs(c.y())).isLessThanOrEqualTo(1 + eps);
            assertThat(Math.abs(c.z())).isLessThanOrEqualTo(1.5 + eps);
        }
        c = shaped(Shape.cone(0.3f, 0.5f), 100);
        while (c.next()) {
            assertThat(Math.abs(c.y())).isLessThan(eps);
            assertThat(Math.hypot(c.x(), c.z())).isLessThanOrEqualTo(0.5 + eps);
            assertThat(Math.acos(Math.min(1, c.vy()))).isLessThanOrEqualTo(0.3 + 1e-3);
        }
        c = shaped(Shape.disc(1), 100);
        while (c.next()) {
            assertThat(Math.abs(c.y())).isLessThan(eps);
            assertThat(Math.hypot(c.x(), c.z())).isLessThanOrEqualTo(1 + eps);
            assertThat(c.vy()).isEqualTo(1);
        }
        c.rewind();
        assertThat(c.next()).isTrue();
        c = shaped(Module.of(Shape.SPHERE), 50); // a ball of the fallback radius, 1
        while (c.next()) {
            assertThat(Math.hypot(c.x(), Math.hypot(c.y(), c.z()))).isLessThanOrEqualTo(1 + eps);
        }
    }

    @Test
    void sizesAndColorsChangeOverLife() {
        ColorGradient dimming = ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(1, 0, 0, 0, 0).build();
        EffectData puffs = cpu(e -> e.spawn(Spawn.burst(0, 1)).init(Init.lifetime(1), Init.size(Bind.constant(2)),
            Init.color(Bind.param(TINT))).update(Over.size(FloatCurve.of(0, 1, 1, 0)), Over.color(dimming)));
        long effect = effects.play(puffs, 0, 0, 0).id();
        frame(1L);
        frame(99_999L);
        for (int i = 0; i < 4; i++) {
            frame(100_000L);
        }
        simulation.particles(effect, 0, cursor);
        cursor.next();
        assertThat(cursor.size()).isCloseTo(1, within(1e-5f));
        assertThat(cursor.color(0)).isCloseTo(0.5f, within(1e-5f));
        assertThat(cursor.color(1)).isCloseTo(0.25f, within(1e-5f));
        assertThat(cursor.color(3)).isCloseTo(0.5f, within(1e-5f));
    }

    @Test
    void everyKindOfBindingFeedsTheModules() {
        EffectData mixed = cpu(e -> e.spawn(Spawn.burst(0, 20)).init(Init.lifetime(Bind.random(1, 2)),
                Init.velocity(Bind.param(INTENSITY), Bind.param(INTENSITY, FloatCurve.of(0, 0, 1, 4)),
                    Bind.random(-1, 1)),
                Init.color(Bind.age(ColorGradient.constant(0.5f, 0.5f, 0.5f, 1))),
                Init.color(Bind.random(ColorGradient.builder().linear(0, 0, 0, 0, 1).linear(1, 1, 1, 1, 1).build())),
                Init.color(Bind.color(0.2f, 0.4f, 0.6f, 0.8f)), Init.size(Bind.age(FloatCurve.constant(0.3f))))
            .update(Forces.gravity(Bind.age(FloatCurve.of(0, 0, 1, 2))),
                Forces.drag(Bind.speed(FloatCurve.of(0, 0, 10, 1))), Over.size(FloatCurve.constant(1))));
        long effect = effects.play(mixed, 0, 0, 0).set(INTENSITY, 2).id();
        frame(1L);
        frame(200_000L);
        simulation.particles(effect, 0, cursor);
        assertThat(cursor.count()).isEqualTo(20);
        while (cursor.next()) {
            assertThat(cursor.vx()).as("drag slows it").isLessThan(2).isPositive();
            assertThat(cursor.color(2)).isEqualTo(0.6f);
            assertThat(cursor.size()).isEqualTo(0.3f);
        }
    }

    @Test
    void burstCallsPlaceParticlesFacingTheirNormals() {
        EffectData impact = EffectData.builder()
            .emitter("chips", e -> e.target(Target.CPU).duration(1).spawn(Spawn.burst(0, 1)).init(Init.lifetime(5),
                Init.velocity(Bind.constant(0), Bind.constant(2), Bind.constant(0))))
            .emitter("local", e -> e.target(Target.CPU).space(Space.LOCAL).duration(1).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(5)))
            .build();
        long up = effects.burst(impact, 10, 0, 0, 0, 1, 0, 0);
        long side = effects.burst(impact, 100, 0, 0, 1, 0, 0, 0);
        long down = effects.burst(impact, 300, 0, 0, 0, -1, 0, 0);
        frame(1L);
        float[][] velocities = new float[3][];
        long[] ids = {up, side, down};
        for (int i = 0; i < 3; i++) {
            simulation.particles(ids[i], 0, cursor);
            cursor.next();
            velocities[i] = new float[] {cursor.vx(), cursor.vy(), cursor.vz()};
        }
        assertThat(velocities[0][1]).isCloseTo(2, within(1e-5f));
        assertThat(velocities[1][0]).isCloseTo(2, within(1e-5f));
        assertThat(velocities[2][1]).isCloseTo(-2, within(1e-5f));
        simulation.particles(side, 0, cursor);
        cursor.next();
        assertThat(cursor.x()).isCloseTo(100, within(1e-4));
        simulation.particles(side, 1, cursor);
        cursor.next();
        assertThat(cursor.x()).as("local to the cell, still at the call").isCloseTo(100, within(1e-4));
    }

    @Test
    void particlesBornAndDeadWithinAFrameAreNotSpawned() {
        EffectData flicker = cpu(e -> e.spawn(Spawn.rate(100), Spawn.burst(0, 1))
            .init(Init.lifetime(Bind.random(0.01f, 0.05f))).update(Over.color(ColorGradient.constant(1, 0, 0, 1))));
        long effect = effects.play(flicker, 0, 0, 0).id();
        frame(0L);
        assertThat(simulation.count(effect, 0)).as("the burst at 0, born now").isOne();
        frame(100_000L);
        simulation.particles(effect, 0, cursor);
        assertThat(cursor.count()).isLessThan(5);
        while (cursor.next()) {
            assertThat(cursor.age()).isLessThan(cursor.lifetime());
            assertThat(cursor.color(1)).isZero();
        }
    }

    @Test
    void particlesWithoutRoomAreDroppedAndCounted() {
        EffectData driven = cpu(e -> e.spawn(Spawn.rate(Bind.param(INTENSITY).times(10))));
        Effect e = effects.play(driven, 0, 0, 0).set(INTENSITY, 5);
        frame(200_000L);
        assertThat(simulation.count(e.id(), 0)).isEqualTo(10);
        frame(100_000L);
        assertThat(simulation.count(e.id(), 0)).isEqualTo(10);
        assertThat(simulation.dropped(e.id(), 0)).isEqualTo(5);
    }

    @Test
    void theSameParticlesOnAnyThreadCount() throws Exception {
        List<List<Double>> runs = new ArrayList<>();
        try (JobSystem one = new JobSystem(1); JobSystem four = new JobSystem(4)) {
            for (JobSystem jobs : new JobSystem[] {null, one, four}) {
                Effects store = new Effects(scene);
                EffectRuntime r = new EffectRuntime(store, jobs);
                CpuSimulation s = new CpuSimulation(r, null, jobs);
                EffectData data = cpu(e -> e.capacity(40).spawn(Spawn.rate(30)).init(Shape.sphere(1),
                    Init.speed(Bind.random(1, 3)), Init.lifetime(Bind.random(0.5f, 1)))
                    .update(Forces.gravity(), Forces.drag(0.5f)));
                List<Long> played = new ArrayList<>();
                for (int i = 0; i < 30; i++) {
                    played.add(store.play(data, i, 0, 0).id());
                }
                for (int f = 0; f < 40; f++) {
                    r.update(16_667L);
                    s.update();
                }
                List<Double> positions = new ArrayList<>();
                ParticleCursor c = new ParticleCursor();
                for (long effect : played) {
                    s.particles(effect, 0, c);
                    while (c.next()) {
                        positions.add(c.x());
                        positions.add(c.y());
                    }
                }
                runs.add(positions);
            }
        }
        assertThat(runs.get(0)).isNotEmpty();
        assertThat(runs.get(1)).isEqualTo(runs.get(0));
        assertThat(runs.get(2)).isEqualTo(runs.get(0));
    }

    @Test
    void simulatingAllocatesNothing() throws Exception {
        try (JobSystem jobs = new JobSystem(3)) {
            EffectRuntime r = new EffectRuntime(effects, jobs);
            CpuSimulation s = new CpuSimulation(r, null, jobs);
            EffectData data = cpu(e -> e.capacity(40).spawn(Spawn.rate(30)).init(Shape.cone(0.4f, 0.1f),
                Init.speed(Bind.random(1, 3)), Init.lifetime(Bind.random(0.5f, 1)), Init.color(Bind.param(TINT)))
                .update(Forces.gravity(), Forces.drag(0.5f), Over.size(FloatCurve.of(0, 1, 1, 0))));
            for (int i = 0; i < 20; i++) {
                effects.play(data, i, 0, 0);
            }
            ParticleCursor c = new ParticleCursor();
            long first = effects.play(data, 0, 0, 0).id();
            for (int f = 0; f < 60; f++) {
                r.update(16_667L);
                s.update();
            }
            assertAllocationFree(() -> {
                r.update(16_667L);
                s.update();
                s.particles(first, 0, c);
                while (c.next()) {
                    c.x();
                }
            }, 2_000, 500, 10);
        }
    }

    @Test
    void onlySimulatedCpuEmittersAreRead() {
        EffectData mixed = EffectData.builder()
            .emitter("gpu", e -> e.spawn(Spawn.rate(10)))
            .emitter("cpu", e -> e.target(Target.CPU).spawn(Spawn.rate(10)))
            .build();
        EffectData gpuOnly = EffectData.builder().emitter("gpu", e -> e.spawn(Spawn.rate(10))).build();
        long effect = effects.play(mixed, 0, 0, 0).id();
        long gpu = effects.play(gpuOnly, 0, 0, 0).id();
        assertThatIllegalArgumentException().isThrownBy(() -> simulation.count(effect, 1))
            .withMessageContaining("simulated");
        frame(100_000L);
        assertThat(simulation.count(effect, 1)).isOne();
        assertThatIllegalArgumentException().isThrownBy(() -> simulation.count(effect, 0))
            .withMessageContaining("not a CPU emitter");
        assertThatIllegalArgumentException().isThrownBy(() -> simulation.count(gpu, 0));
        // A slot reused, and an effect killed between the runtime and the simulation.
        effects.kill(effect);
        long again = effects.play(mixed, 0, 0, 0).id();
        runtime.update(100_000L);
        effects.kill(again);
        simulation.update();
        assertThatIllegalArgumentException().isThrownBy(() -> simulation.count(again, 1));
        long later = effects.play(mixed, 0, 0, 0).id();
        for (int i = 0; i < 4; i++) {
            effects.play(mixed, 0, 0, 0);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> simulation.count(later, 1));
        assertThat(simulation.world()).isNull();
        try (JobSystem jobs = new JobSystem(2)) {
            EffectRuntime r = new EffectRuntime(new Effects(scene), jobs);
            CpuSimulation few = new CpuSimulation(r, null, jobs);
            r.update(1L);
            few.update(); // nothing to share out
        }
    }

    @Test
    void cpuModulesNeedJavaImplementations() {
        ModuleType gust = ModuleType.builder("game.gust", ModuleType.Stage.UPDATE).build();
        EffectData data = EffectData.builder()
            .emitter("cpu", e -> e.target(Target.CPU).spawn(Spawn.rate(10)).update(Module.of("game.gust")))
            .build(ModuleRegistry.BUILT_IN.with(gust));
        effects.play(data, 0, 0, 0);
        runtime.update(1L);
        assertThatIllegalStateException().isThrownBy(simulation::update).withMessageContaining("game.gust");
    }

    @Test
    void theWorldIsTheGames() {
        ParticleWorld.Hit hit = new ParticleWorld.Hit();
        ParticleWorld flat = new ParticleWorld() {
            @Override
            public double height(double x, double z) {
                return 0;
            }

            @Override
            public boolean raycast(double x, double y, double z, double dx, double dy, double dz, double maxDistance,
                    ParticleWorld.Hit h) {
                h.set(y, 0, 1, 0, 3);
                return true;
            }
        };
        CpuSimulation withWorld = new CpuSimulation(runtime, flat);
        assertThat(withWorld.world()).isSameAs(flat);
        assertThat(flat.raycast(0, 2, 0, 0, -1, 0, 10, hit)).isTrue();
        assertThat(hit.distance()).isEqualTo(2);
        assertThat(hit.nx()).isZero();
        assertThat(hit.ny()).isOne();
        assertThat(hit.nz()).isZero();
        assertThat(hit.material()).isEqualTo(3);
        assertThat(flat.height(1, 1)).isZero();
    }
}
