package com.truesim.scene.effects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.collision.BoxShape;
import com.truesim.common.collision.CollisionQuery;
import com.truesim.common.collision.CollisionWorld;
import com.truesim.common.collision.RaycastHit;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Collision in the Java simulator (E10, step 12): bounces by restitution off colliders on nodes, rest on the game's
 * terrain, a CPU emitter hitting a common-collision box through the world, responses, chains, and the colliders.
 */
class CollisionTest {

    private static final long TICK = 1_000_000 / 240;
    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);
    private final EffectRuntime runtime = new EffectRuntime(effects);
    private final ParticleCursor cursor = new ParticleCursor();

    /** One CPU particle at the effect, with {@code setup}'s modules. */
    private static EffectData one(Consumer<EmitterData.Builder> setup) {
        return EffectData.builder().emitter("e", e -> setup.accept(e.target(Target.CPU).spawn(Spawn.burst(0, 1))
            .init(Init.lifetime(100), Init.size(Bind.constant(0.1f))))).build();
    }

    private void frames(CpuSimulation simulation, int n) {
        for (int f = 0; f < n; f++) {
            runtime.update(TICK);
            simulation.update();
        }
    }

    private double[] particle(CpuSimulation simulation, long effect) {
        simulation.particles(effect, 0, cursor);
        assertThat(cursor.next()).isTrue();
        return new double[] {cursor.x(), cursor.y(), cursor.z(), cursor.vx(), cursor.vy(), cursor.vz()};
    }

    private long node(double x, double y, double z) {
        long node = scene.create();
        scene.setTranslation(node, x, y, z);
        scene.update();
        return node;
    }

    /** The highest a particle dropped from 2 m onto a plane rises after its first bounce. */
    private double apexAfterBounce(float restitution) {
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long effect = effects.play(one(e -> e.update(Forces.gravity())
            .collide(Collide.colliders(1).bounce(restitution).radius(0.05f))), 0, 2.05, 0).id();
        boolean bounced = false;
        double apex = 0;
        for (int f = 0; f < 1000; f++) {
            frames(simulation, 1);
            double[] p = particle(simulation, effect);
            bounced |= p[4] > 0;
            if (bounced) {
                apex = Math.max(apex, p[1] - 0.05);
            }
            assertThat(p[1]).as("never through the floor").isGreaterThanOrEqualTo(0.05 - 1e-4);
        }
        effects.kill(effect);
        return apex;
    }

    @Test
    void bouncesRiseByTheirRestitutionSquared() {
        effects.colliders().plane(node(0, 0, 0), 1);
        assertThat(apexAfterBounce(0.5f)).isCloseTo(0.5, within(0.03));
        assertThat(apexAfterBounce(0.8f)).isCloseTo(1.28, within(0.04));
    }

    @Test
    void collidersOfEveryShapeKeepParticlesOutside() {
        long sphere = node(0, 0, 0);
        long box = node(10, 0, 0);
        long capsule = node(20, 0, 0);
        scene.setRotation(box, 0, Math.sin(0.3), 0, Math.cos(0.3));
        scene.update();
        EffectColliders colliders = effects.colliders();
        colliders.sphere(sphere, 1, 2);
        colliders.box(box, 1, 0.5f, 1, 2);
        colliders.capsule(capsule, 0.5f, 1, 2);
        colliders.sphere(node(30, 0, 0), 5, 4); // another layer: not hit
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long[] dropped = new long[4];
        double[] xs = {0, 10.2, 20.1, 30};
        for (int k = 0; k < 4; k++) {
            dropped[k] = effects.play(one(e -> e.update(Forces.gravity())
                .collide(Collide.colliders(2).slide().friction(1))), xs[k], 3, 0).id();
        }
        frames(simulation, 400);
        assertThat(particle(simulation, dropped[0])[1]).as("on the sphere").isCloseTo(1.05, within(0.02));
        assertThat(particle(simulation, dropped[1])[1]).as("on the box").isCloseTo(0.55, within(0.02));
        assertThat(particle(simulation, dropped[2])[1]).as("on the capsule's end").isCloseTo(1.55, within(0.02));
        assertThat(particle(simulation, dropped[3])[1]).as("another layer: falling on").isLessThan(-5);
        assertThat(colliders.count()).isEqualTo(4);
        assertThat(colliders.kind(1)).isEqualTo(EffectColliders.Kind.BOX);
        assertThat(colliders.node(1)).isEqualTo(box);
        assertThat(colliders.layers(1)).isEqualTo(2);
        assertThat(colliders.size(2, 1)).isEqualTo(1);
        assertThat(colliders.rotation(1, 1)).isCloseTo(Math.sin(0.3), within(1e-9));
        assertThat(colliders.position(2, 0)).isEqualTo(20);
        colliders.remove(capsule);
        assertThat(colliders.count()).isEqualTo(3);
        scene.remove(box);
        scene.update();
        runtime.update(TICK);
        assertThat(colliders.count()).as("its node gone").isEqualTo(2);
    }

    @Test
    void particlesRestOnTheGamesTerrain() {
        ParticleWorld slope = world(x -> 0.2 * x, null);
        CpuSimulation simulation = new CpuSimulation(runtime, slope);
        long snapped = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.height().snap())), 3, 4, 0)
            .id();
        long stuck = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.height().stick().radius(0.2f))),
            -2, 4, 1).id();
        long sliding = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.height().slide())), 0, 2, 0)
            .id();
        frames(simulation, 600);
        double[] s = particle(simulation, snapped);
        assertThat(s[1]).as("on the ground at x = 3").isCloseTo(0.6 + 0.05, within(1e-4));
        assertThat(s[3]).isZero();
        assertThat(particle(simulation, stuck)[1]).isCloseTo(-0.4 + 0.2, within(1e-4));
        double[] slid = particle(simulation, sliding);
        assertThat(slid[0]).as("down the slope").isLessThan(-1);
        assertThat(slid[1]).as("on it").isCloseTo(0.2 * slid[0] + 0.05, within(0.02));
    }

    @Test
    void aCpuEmitterHitsACommonCollisionBox() {
        CollisionWorld physics = new CollisionWorld();
        physics.addStatic(new BoxShape(Vec3.of(2, 0.5, 2)), Transform.ofTranslation(Vec3.of(0, 0, 0)));
        physics.update();
        CollisionQuery query = physics.newQuery();
        RaycastHit hit = new RaycastHit();
        ParticleWorld world = world(x -> Double.NaN, (ox, oy, oz, dx, dy, dz, max, h) -> {
            if (!query.raycast(ox, oy, oz, dx, dy, dz, max, -1, hit)) {
                return false;
            }
            h.set(hit.distance(), (float) hit.normalX(), (float) hit.normalY(), (float) hit.normalZ(), 9);
            return true;
        });
        CpuSimulation simulation = new CpuSimulation(runtime, world);
        long effect = effects.play(one(e -> e.init(Init.velocity(Bind.constant(0), Bind.constant(-6),
            Bind.constant(0))).collide(Collide.world().kill())), 0.5, 3, 0).id();
        long bouncing = effects.play(one(e -> e.update(Forces.gravity())
            .collide(Collide.world().bounce(0.3f))), -1, 2, 1).id();
        long missing = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.world())), 5, 2, 0).id();
        frames(simulation, 240);
        assertThat(simulation.count(effect, 0)).as("killed on the box").isZero();
        double[] b = particle(simulation, bouncing);
        assertThat(b[1]).as("on the box's top").isBetween(0.5, 0.6);
        assertThat(particle(simulation, missing)[1]).as("beside it, falling on").isLessThan(-2);
        // Without a world, world and height collision do nothing
        CpuSimulation without = new CpuSimulation(runtime, null);
        long free = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.world().orElse(Collide.height()))),
            0, 1, 0).id();
        frames(without, 240);
        assertThat(particle(without, free)[1]).isLessThan(-1);
    }

    @Test
    void localSpaceParticlesCollideInTheWorld() {
        effects.colliders().plane(node(0, 0, 0), EffectColliders.ALL_LAYERS);
        long holder = node(0, 2, 0);
        scene.setRotation(holder, Math.sin(0.5), 0, 0, Math.cos(0.5));
        scene.update();
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long effect = effects.play(EffectData.builder().emitter("e", e -> e.target(Target.CPU).space(Space.LOCAL)
            .spawn(Spawn.burst(0, 1)).init(Init.lifetime(100)).update(Forces.gravity())
            .collide(Collide.colliders(1).bounce(0))).build(), holder).id();
        frames(simulation, 400);
        assertThat(particle(simulation, effect)[1]).as("resting on the floor, in the world").isCloseTo(0.05,
            within(0.01));
    }

    @Test
    void chainsUseTheirFirstMethodTheTargetHas() {
        effects.colliders().plane(node(0, 0, 0), 1);
        CpuSimulation simulation = new CpuSimulation(runtime, world(x -> -5, null));
        // Depth is the GPU's: the CPU takes the colliders after it; the height after them is a fallback, unused
        long effect = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.depth().orElse(
            Collide.colliders(1)).orElse(Collide.height()).stick())), 0, 1, 0).id();
        frames(simulation, 300);
        assertThat(particle(simulation, effect)[1]).as("on the plane, not the ground below").isCloseTo(0.05,
            within(1e-3));
        assertThat(simulation.count(effect, 0)).isOne();
        assertThatThrownBy(() -> one(e -> e.collide(Collide.depth()))).isInstanceOf(InvalidEffectException.class)
            .hasMessageContaining("collide.depth does not run on CPU");
        assertThatThrownBy(() -> one(e -> e.collide(Collide.depth().orElse(Collide.rays()))))
            .isInstanceOf(InvalidEffectException.class).hasMessageContaining("does not run on CPU");
        EffectData gpu = EffectData.builder().emitter("e", e -> e.spawn(Spawn.rate(1))
            .collide(Collide.rays().orElse(Collide.depth(0.3f)).bounce(0.4f).friction(0.1f))).build();
        Module fallback = gpu.emitters().getFirst().update().get(1);
        assertThat(fallback.value("fallback")).isEqualTo(Bind.constant(1));
        assertThat(fallback.value("restitution")).isEqualTo(Bind.constant(0.4f));
        assertThat(gpu.emitters().getFirst().capability()).isEqualTo(Capability.RAY_QUERY);
        assertThat(Collide.fallback(Module.of(Collide.HEIGHT).with("fallback", Bind.param(ValuesTest.INTENSITY))))
            .isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> Collide.colliders(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> Collide.colliders(1 << 24));
    }

    @Test
    void stuckParticlesStayAndKilledOnesGo() {
        effects.colliders().plane(node(0, 0, 0), 1);
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        long stuck = effects.play(EffectData.builder().emitter("e", e -> e.target(Target.CPU)
            .spawn(Spawn.burst(0, 1)).init(Init.lifetime(100)).update(Forces.gravity(), Over.size(FloatCurve.of(0, 1,
                1, 2))).collide(Collide.colliders(1).stick())).build(), 0, 1, 0).id();
        long killed = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.colliders(1).kill())), 2, 1, 0)
            .id();
        frames(simulation, 300);
        double[] p = particle(simulation, stuck);
        frames(simulation, 100);
        double[] later = particle(simulation, stuck);
        assertThat(later[1]).isEqualTo(p[1]);
        assertThat(later[4]).isZero();
        assertThat(simulation.count(killed, 0)).isZero();
    }

    @Test
    void collidersAreCheckedWhenAdded() {
        EffectColliders colliders = effects.colliders();
        long n = node(0, 0, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> colliders.sphere(n, 1, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> colliders.sphere(n, 1, 1 << 24));
        assertThatIllegalArgumentException().isThrownBy(() -> colliders.sphere(n, -1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> colliders.box(n, 1, Float.NaN, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> colliders.capsule(n, 1, -1, 1));
        scene.remove(n);
        scene.update();
        assertThatIllegalArgumentException().isThrownBy(() -> colliders.plane(n, 1));
        long m = node(0, 0, 0);
        for (int i = 0; i < 9; i++) {
            colliders.sphere(node(i, 0, 0), i, 1); // growing
        }
        assertThat(colliders.count()).isEqualTo(9);
        assertThat(colliders.size(8, 0)).isEqualTo(8);
        // one per node, a component of it: another replaces it, removing takes it away
        colliders.capsule(m, 1, 2, 3);
        colliders.box(m, 1, 2, 3, 3);
        assertThat(colliders.count()).isEqualTo(10);
        assertThat(scene.components().get(m, EffectCollider.TYPE).kind()).isEqualTo(EffectColliders.Kind.BOX);
        assertThat(scene.components().get(m, EffectCollider.TYPE).size(2)).isEqualTo(3);
        assertThat(scene.components().get(m, EffectCollider.TYPE)).hasToString("BOX collider in layers 3");
        colliders.remove(m);
        colliders.remove(m);
        assertThat(colliders.count()).isEqualTo(9);
        scene.components().set(m, EffectCollider.TYPE, EffectCollider.plane(1));
        effects.advance(0.01);
        assertThat(colliders.count()).as("set through the components").isEqualTo(10);
    }

    @Test
    void boxesPushParticlesOutThroughTheirNearestFace() {
        effects.colliders().box(node(0, 0, 0), 1, 1, 1, 1);
        effects.colliders().sphere(node(10, 0, 0), 1, 1);
        effects.colliders().plane(node(0, -10, 0), 1);
        CpuSimulation simulation = new CpuSimulation(runtime, null);
        double[][] starts = {{-1.5, 0, 0.2, 3, 0, 0}, {1.5, 0, -0.2, -3, 0, 0}, {0.2, 0, -1.5, 0, 0, 3},
            {-0.2, 0, 1.5, 0, 0, -3}, {0.1, -1.5, 0.1, 0, 3, 0}, {5, 0, 0, 0, 0, 0}};
        long[] ids = new long[starts.length];
        for (int k = 0; k < starts.length; k++) {
            double[] s = starts[k];
            ids[k] = effects.play(one(e -> e.init(Init.velocity(Bind.constant((float) s[3]),
                Bind.constant((float) s[4]), Bind.constant((float) s[5])))
                .collide(Collide.colliders(1).bounce(0).radius(0.1f))), s[0], s[1], s[2]).id();
        }
        // At the sphere's very center: pushed out along +Y; moving out of it: not slowed
        long centered = effects.play(one(e -> e.collide(Collide.colliders(1).slide())), 10, 0, 0).id();
        long leaving = effects.play(one(e -> e.init(Init.velocity(Bind.constant(0), Bind.constant(2),
            Bind.constant(0))).collide(Collide.colliders(1).slide())), 10, 0.5, 0).id();
        // Deep below the plane: no hit
        long below = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.colliders(1))), 0, -14, 0).id();
        frames(simulation, 60);
        for (int k = 0; k < 5; k++) {
            double[] p = particle(simulation, ids[k]);
            double outside = Math.max(Math.abs(p[0]), Math.max(Math.abs(p[1]), Math.abs(p[2])));
            assertThat(outside).as("particle " + k + " outside the box").isGreaterThanOrEqualTo(1.1 - 1e-4);
        }
        assertThat(particle(simulation, ids[5])[0]).as("beside it").isEqualTo(5);
        assertThat(particle(simulation, centered)[1]).isCloseTo(1.05, within(1e-4));
        assertThat(particle(simulation, leaving)[4]).as("moving out: kept").isEqualTo(2);
        assertThat(particle(simulation, below)[1]).isLessThan(-14);
    }

    @Test
    void edgeCasesOfTheWorld() {
        // Heights only right where the particle falls: no slope around it, a flat normal
        CpuSimulation simulation = new CpuSimulation(runtime, new ParticleWorld() {
            @Override
            public double height(double x, double z) {
                return x == 3 && z == 0 ? 0 : Double.NaN;
            }

            @Override
            public boolean raycast(double x, double y, double z, double dx, double dy, double dz, double max, Hit h) {
                return true;
            }
        });
        long snapped = effects.play(one(e -> e.update(Forces.gravity(), Over.color(ColorGradient.constant(1, 1, 1, 1)))
            .collide(Collide.height().snap())), 3, 1, 0).id();
        long resting = effects.play(one(e -> e.collide(Collide.world())), 0, 1, 0).id(); // not moving: no ray
        CpuSimulation without = new CpuSimulation(runtime, null);
        long free = effects.play(one(e -> e.update(Forces.gravity()).collide(Collide.height())), 0, 1, 0).id();
        for (int f = 0; f < 240; f++) {
            runtime.update(TICK);
            simulation.update();
            without.update();
        }
        assertThat(particle(simulation, snapped)[1]).isCloseTo(0.05, within(1e-4));
        assertThat(particle(simulation, resting)[1]).isEqualTo(1);
        assertThat(particle(without, free)[1]).isLessThan(-2);
    }

    @Test
    void chainsAreCheckedWhereverTheyAre() {
        assertThatThrownBy(() -> one(e -> e.update(Forces.gravity()).collide(Collide.depth()))
            .emitters()).hasMessageContaining("does not run on CPU");
        assertThatThrownBy(() -> one(e -> e.collide(Collide.depth()).update(Forces.gravity()))
            .emitters()).hasMessageContaining("does not run on CPU");
        assertThatThrownBy(() -> one(e -> e.collide(new Collide.Collision(java.util.List.of(Module.of(Collide.DEPTH),
            Module.of("collide.unknown").with("fallback", 1f))))).emitters()).hasMessageContaining("collide.unknown");
        assertThat(Collide.fallback(Module.of(Collide.HEIGHT).with("fallback", 0f))).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> effects.colliders().box(node(0, 0, 0), 1, 1, -1, 1));
    }

    /** A world of {@code heights} and {@code rays} (null: no hits). */
    private static ParticleWorld world(java.util.function.DoubleUnaryOperator heights, Rays rays) {
        return new ParticleWorld() {
            @Override
            public double height(double x, double z) {
                return heights.applyAsDouble(x);
            }

            @Override
            public boolean raycast(double x, double y, double z, double dx, double dy, double dz, double maxDistance,
                    Hit hit) {
                return rays != null && rays.cast(x, y, z, dx, dy, dz, maxDistance, hit);
            }
        };
    }

    @FunctionalInterface
    private interface Rays {
        boolean cast(double x, double y, double z, double dx, double dy, double dz, double max, ParticleWorld.Hit hit);
    }
}
