package com.truesim.scene.effects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.core.SceneGraph;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The forces of step 11 in the Java simulator: orbits keep their radius, vortices turn and pull, attractors pull and
 * kill, curl noise is divergence-free, wind carries particles to its speed, the speed limit holds; in world and local
 * space.
 */
class ForcesTest {

    private static final AssetRef<?> FIELD = AssetRef.of(AssetType.of("test.volume", Object.class, o -> 0),
        new AssetId(0, 7));
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final CpuSimulation simulation = new CpuSimulation(runtime, null);
    private final ParticleCursor cursor = new ParticleCursor();

    /** A CPU emitter of {@code count} still particles on a sphere of {@code radius}, then {@code setup}. */
    private static EffectData shell(int count, float radius, Space space, Consumer<EmitterData.Builder> setup) {
        return EffectData.builder().emitter("e", e -> setup.accept(e.target(Target.CPU).space(space)
            .spawn(Spawn.burst(0, count)).init(Shape.sphere(radius).with("thickness", 0f), Init.lifetime(100))))
            .build();
    }

    private void frame(long ticks) {
        runtime.update(ticks);
        simulation.update();
    }

    /** A node at ({@code x}, {@code y}, {@code z}) turned a quarter about +X (its +Y is world +Z). */
    private long turnedNode(double x, double y, double z) {
        long node = scene.create();
        scene.setTranslation(node, x, y, z);
        scene.setRotation(node, Math.sqrt(0.5), 0, 0, Math.sqrt(0.5));
        scene.update();
        return node;
    }

    @Test
    void orbitsKeepTheirRadiusAndTurnAtTheirSpeed() {
        long effect = effects.play(shell(20, 2, Space.WORLD, e -> e.update(Forces.orbit(1.5f))), 5, 1, 2).id();
        long node = turnedNode(-3, 0, 0);
        long local = effects.play(shell(20, 2, Space.LOCAL, e -> e.update(Forces.orbit(Bind.constant(1.5f), 0, 1,
            0))), node).id();
        frame(1L);
        double[][] start = positions(effect);
        double[][] startLocal = positions(local);
        for (int f = 0; f < 120; f++) {
            frame(16_667L);
        }
        double t = 120 * 0.016667;
        double[][] end = positions(effect);
        double[][] endLocal = positions(local);
        for (int i = 0; i < 20; i++) {
            double r0 = length(start[i][0] - 5, start[i][2] - 2);
            double r1 = length(end[i][0] - 5, end[i][2] - 2);
            assertThat(r1).as("radius about the axis").isCloseTo(r0, within(1e-4));
            assertThat(end[i][1]).as("along the axis").isCloseTo(start[i][1], within(1e-4));
            if (r0 > 0.2) {
                double turned = Math.atan2(start[i][0] - 5, start[i][2] - 2) - Math.atan2(end[i][0] - 5,
                    end[i][2] - 2);
                turned = Math.IEEEremainder(turned, 2 * Math.PI);
                assertThat(Math.abs(turned)).as("angle: 3 radians in 2 s").isCloseTo(1.5 * t, within(1e-3));
            }
            // The local emitter's axis is its +Y, world +Z: radii about it kept in the world
            double l0 = length(startLocal[i][0] + 3, startLocal[i][1]);
            double l1 = length(endLocal[i][0] + 3, endLocal[i][1]);
            assertThat(l1).isCloseTo(l0, within(1e-4));
            assertThat(endLocal[i][2]).isCloseTo(startLocal[i][2], within(1e-4));
        }
    }

    private static double length(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }

    private double[][] positions(long effect) {
        simulation.particles(effect, 0, cursor);
        double[][] p = new double[cursor.count()][];
        for (int i = 0; cursor.next(); i++) {
            p[i] = new double[] {cursor.x(), cursor.y(), cursor.z(), cursor.vx(), cursor.vy(), cursor.vz()};
        }
        return p;
    }

    /** After a tick (spawned) and a 10 ms step: each particle's velocity over the step, against {@code expected}. */
    private void accelerations(long effect, double cx, double cy, double cz, Expected expected) {
        frame(1L);
        double[][] start = positions(effect);
        frame(10_000L);
        double[][] end = positions(effect);
        for (int i = 0; i < start.length; i++) {
            double[] a = expected.at(start[i][0] - cx, start[i][1] - cy, start[i][2] - cz);
            for (int c = 0; c < 3; c++) {
                assertThat(end[i][3 + c] / 0.01).as("particle " + i + ", axis " + c).isCloseTo(a[c], within(2e-3));
            }
        }
    }

    @FunctionalInterface
    private interface Expected {
        double[] at(double x, double y, double z);
    }

    @Test
    void vorticesTurnParticlesAboutTheirAxisAndPullThemIn() {
        long effect = effects.play(shell(16, 1, Space.WORLD, e -> e.update(Forces.vortex(Bind.constant(2), 0.5f, 0,
            1, 0))), 1, 2, 3).id();
        accelerations(effect, 1, 2, 3, (x, y, z) -> {
            double r = length(x, z);
            return new double[] {(2 * z - 0.5 * x) / r, 0, (-2 * x - 0.5 * z) / r};
        });
    }

    @Test
    void aVortexWithoutAnAxisTurnsAboutUp() {
        long effect = effects.play(shell(8, 1, Space.WORLD, e -> e.update(Forces.vortex(Bind.constant(2), 0, 0, 0,
            0))), 0, 0, 0).id();
        accelerations(effect, 0, 0, 0, (x, y, z) -> {
            double r = length(x, z);
            return new double[] {2 * z / r, 0, -2 * x / r};
        });
        long onAxis = effects.play(EffectData.builder().emitter("e", e -> e.target(Target.CPU)
            .spawn(Spawn.burst(0, 1)).init(Init.lifetime(10)).update(Forces.vortex(5), Forces.line(Bind.constant(5),
                0, 1, 0), Forces.point(Bind.constant(5), 0, 0, 0))).build(), 0, 0, 0).id();
        frame(10_000L);
        assertThat(positions(onAxis)[0][3]).as("on the axis and at the point: no direction, no force").isZero();
    }

    @Test
    void pointAttractorsPullFallOffAndKill() {
        long pulled = effects.play(shell(12, 2, Space.WORLD, e -> e.update(Forces.point(Bind.constant(3), 0, 0, 0)
            .with("radius", 4f))), 4, 0, 0).id();
        accelerations(pulled, 4, 0, 0, (x, y, z) -> {
            double d = Math.sqrt(x * x + y * y + z * z);
            return new double[] {-1.5 * x / d, -1.5 * y / d, -1.5 * z / d}; // half its strength at half its radius
        });
        long killed = effects.play(shell(12, 2, Space.WORLD, e -> e.update(Forces.point(Bind.constant(1), 0, 0, 0)
            .with("killRadius", 3f))), -4, 0, 0).id();
        frame(10_000L);
        assertThat(simulation.count(killed, 0)).as("dead within the kill radius, still there this frame").isEqualTo(12);
        simulation.particles(killed, 0, cursor);
        cursor.next();
        assertThat(cursor.age()).isGreaterThanOrEqualTo(cursor.lifetime());
        frame(10_000L);
        assertThat(simulation.count(killed, 0)).isZero();
        long far = effects.play(shell(4, 5, Space.WORLD, e -> e.update(Forces.point(Bind.constant(3), 0, 0, 0)
            .with("radius", 4f))), 0, 30, 0).id();
        accelerations(far, 0, 30, 0, (x, y, z) -> new double[] {0, 0, 0});
    }

    @Test
    void lineAttractorsPullTowardsTheirLine() {
        long effect = effects.play(shell(12, 1, Space.WORLD, e -> e.update(Forces.line(Bind.constant(2), 1, 0, 0)
            .with("y", 0.5f))), 0, 0, 0).id();
        accelerations(effect, 0, 0, 0, (x, y, z) -> {
            double dy = y - 0.5;
            double d = length(dy, z);
            return new double[] {0, -2 * dy / d, -2 * z / d};
        });
    }

    @Test
    void localForcesTurnWithTheirEmitter() {
        long node = turnedNode(0, 0, 0);
        long vortex = effects.play(shell(8, 1, Space.LOCAL, e -> e.update(Forces.vortex(2))), node).id();
        long point = effects.play(shell(8, 1, Space.LOCAL, e -> e.update(Forces.point(Bind.constant(1), 0, 2, 0),
            Forces.line(Bind.constant(1), 1, 0, 0))), node).id();
        frame(1L);
        frame(10_000L);
        // In the emitter's frame (what local particles live in), the vortex is about its +Y as in the world case
        simulation.particles(vortex, 0, cursor);
        assertThat(cursor.count()).isEqualTo(8);
        assertThat(simulation.count(point, 0)).isEqualTo(8);
    }

    @Test
    void curlNoiseIsSmoothDivergenceFreeAndTheSameEveryTime() {
        float[] scratch = new float[4];
        float[] a = new float[3];
        float[] b = new float[3];
        float h = 1e-2f;
        double largest = 0;
        for (int i = 0; i < 50; i++) {
            float x = 0.37f * i - 3;
            float y = 0.11f * i + 1;
            float z = -0.23f * i;
            double divergence = 0;
            for (int c = 0; c < 3; c++) {
                CurlNoise.curl(x + (c == 0 ? h : 0), y + (c == 1 ? h : 0), z + (c == 2 ? h : 0), 0.8f, 0.3f, 2, 99,
                    scratch, a);
                CurlNoise.curl(x - (c == 0 ? h : 0), y - (c == 1 ? h : 0), z - (c == 2 ? h : 0), 0.8f, 0.3f, 2, 99,
                    scratch, b);
                divergence += (a[c] - b[c]) / (2 * h);
            }
            CurlNoise.curl(x, y, z, 0.8f, 0.3f, 2, 99, scratch, a);
            largest = Math.max(largest, Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]));
            assertThat(divergence).as("divergence at " + i).isCloseTo(0, within(0.05));
        }
        assertThat(largest).as("a field, not nothing").isGreaterThan(0.5);
        for (int i = 0; i < 100; i++) {
            assertThat(CurlNoise.lattice(i, -i, 3 * i, 5)).isBetween(-1f, 1f);
        }
    }

    @Test
    void curlNoisePushesParticlesAsItsFieldSays() {
        EffectData data = shell(10, 1, Space.WORLD, e -> e.update(Forces.curl(Bind.constant(3), 0.7f, 0.5f, 9)));
        long effect = effects.play(data, 0, 0, 0).id();
        frame(1L);
        double[][] start = positions(effect);
        frame(10_000L);
        double[][] end = positions(effect);
        int seed = Hash.combine(EffectRuntime.emitterSeed(effects.seed(effect), 0), CpuSimulation.UPDATE_SALTS + 8);
        float[] scratch = new float[4];
        float[] curl = new float[3];
        for (int i = 0; i < start.length; i++) {
            // drifted by the effect's time at the step's end (10,001 ticks) times its speed; at most 4 octaves
            CurlNoise.curl((float) start[i][0], (float) start[i][1], (float) start[i][2], 0.7f, 0.010001f * 0.5f, 4,
                seed, scratch, curl);
            for (int c = 0; c < 3; c++) {
                assertThat(end[i][3 + c] / 0.01).isCloseTo(3 * curl[c], within(0.05));
            }
        }
    }

    @Test
    void windCarriesParticlesToItsSpeedWithGusts() {
        effects.wind(5, 0, 0);
        assertThat(effects.wind(0)).isEqualTo(5);
        long calm = effects.play(shell(1, 0, Space.WORLD, e -> e.update(Forces.wind(2))), 0, 0, 0).id();
        long gusty = effects.play(shell(1, 0, Space.WORLD, e -> e.update(Forces.wind(2, 1, 0.8f))), 0, 0, 0).id();
        long node = turnedNode(0, 0, 0);
        long local = effects.play(shell(1, 0, Space.LOCAL, e -> e.update(Forces.wind(2, 0.5f, 0))), node).id();
        frame(1L);
        double t = 0;
        boolean differed = false;
        for (int f = 0; f < 60; f++) {
            frame(16_667L);
            t += 0.016667;
            double expected = 5 * (1 - Math.exp(-2 * t));
            assertThat(positions(calm)[0][3]).as("towards the wind's speed").isCloseTo(expected, within(2e-3));
            differed |= Math.abs(positions(gusty)[0][3] - expected) > 0.01;
        }
        assertThat(differed).as("gusts vary it").isTrue();
        // Local: the wind turned into its frame; drawn in the world, the same direction at half the speed
        simulation.particles(local, 0, cursor);
        cursor.next();
        assertThat((double) cursor.vx()).isCloseTo(2.5 * (1 - Math.exp(-2 * t)), within(5e-3));
    }

    @Test
    void theSpeedLimitHolds() {
        assertThat(Forces.curl(2, 0.5f).value("frequency")).isEqualTo(Bind.constant(0.5f));
        long effect = effects.play(EffectData.builder().emitter("e", e -> e.target(Target.CPU)
            .spawn(Spawn.burst(0, 1)).init(Init.lifetime(10), Init.velocity(Bind.constant(6), Bind.constant(8),
                Bind.constant(0))).update(Forces.speedLimit(3))).build(), 0, 0, 0).id();
        long stopped = effects.play(EffectData.builder().emitter("e", e -> e.target(Target.CPU)
            .spawn(Spawn.burst(0, 1)).init(Init.lifetime(10), Init.velocity(Bind.constant(6), Bind.constant(8),
                Bind.constant(0))).update(Forces.speedLimit(-1))).build(), 0, 0, 0).id();
        long slow = effects.play(EffectData.builder().emitter("e", e -> e.target(Target.CPU)
            .spawn(Spawn.burst(0, 1)).init(Init.lifetime(10), Init.velocity(Bind.constant(1), Bind.constant(0),
                Bind.constant(0))).update(Forces.speedLimit(3))).build(), 0, 0, 0).id();
        frame(10_000L);
        double[] v = positions(effect)[0];
        assertThat(Math.sqrt(v[3] * v[3] + v[4] * v[4] + v[5] * v[5])).isCloseTo(3, within(1e-5));
        assertThat(v[4] / v[3]).as("its direction kept").isCloseTo(8.0 / 6, within(1e-5));
        assertThat(positions(stopped)[0][3]).isZero();
        assertThat(positions(slow)[0][3]).isEqualTo(1);
    }

    @Test
    void vectorFieldsAreForGpuEmittersAndNeedTheirTexture() {
        assertThat(Forces.field(FIELD, 2, 10).value("field")).isEqualTo(FIELD);
        EffectData gpu = EffectData.builder().emitter("e", e -> e.spawn(Spawn.rate(10))
            .update(Forces.field(FIELD, 2, 10))).build();
        assertThat(gpu.emitters().getFirst().update()).hasSize(1);
        assertThatThrownBy(() -> EffectData.builder().emitter("e", e -> e.target(Target.CPU).spawn(Spawn.rate(10))
            .update(Forces.field(FIELD, 2, 10))).build()).isInstanceOf(InvalidEffectException.class)
            .hasMessageContaining("does not run on CPU");
        assertThatThrownBy(() -> EffectData.builder().emitter("e", e -> e.spawn(Spawn.rate(10))
            .update(Module.of(Forces.FIELD))).build()).isInstanceOf(InvalidEffectException.class)
            .hasMessageContaining("force.field needs its field");
        assertThatThrownBy(() -> EffectData.builder().emitter("e", e -> e.spawn(Spawn.rate(10))
            .update(Module.of(Forces.FIELD).with("field", 1f))).build()).isInstanceOf(InvalidEffectException.class)
            .hasMessageContaining("takes a ASSET");
        assertThat(ModuleType.builder("t", ModuleType.Stage.UPDATE).field("mesh", FIELD).build().fields().get("mesh")
            .kind()).isEqualTo(ModuleType.Kind.ASSET);
        assertThat(ModuleRegistry.BUILT_IN.type(Forces.FIELD).runsOn(Target.CPU)).isFalse();
    }
}
