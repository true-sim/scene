package com.truesim.scene.animation;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Transform;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Foot placement's ground probes in the serial part of the update (decision A13): a ground need not be thread-safe. */
class GroundTest {

    private static final int ANIMATORS = 200;

    private final JobSystem jobs = new JobSystem(3);

    @AfterEach
    void close() {
        jobs.close();
    }

    /** Bumpy ground, none to stand on beyond x = 12; asserts it is asked on one thread at a time, that thread. */
    private static final class OneThreadGround implements Ground {
        private final Thread owner = Thread.currentThread();
        private final AtomicBoolean busy = new AtomicBoolean();
        int asked;

        @Override
        public double height(double x, double z) {
            if (Thread.currentThread() != owner) { // no AssertJ here: it allocates
                throw new AssertionError("asked on " + Thread.currentThread().getName());
            }
            if (!busy.compareAndSet(false, true)) {
                throw new AssertionError("asked by two threads at once");
            }
            asked++;
            busy.set(false);
            return x > 12 ? Double.NaN : 0.15 * Math.sin(3 * x) * Math.cos(2 * z);
        }
    }

    /** One run: its scene, animators and every animator's nodes. */
    private record World(SceneGraph scene, Animators animators, List<long[]> nodes, OneThreadGround ground) {

        /** Every node's world transform, animator by animator. */
        List<Transform> poses() {
            List<Transform> out = new ArrayList<>();
            for (long[] n : nodes) {
                for (long node : n) {
                    out.add(scene.world(node));
                }
            }
            return out;
        }

        void step(int times) {
            for (int i = 0; i < times; i++) {
                animators.update(1 / 60.0);
                scene.update();
            }
        }
    }

    /**
     * {@link #ANIMATORS} figures with foot placement on uneven ground (every fifth with none, every seventh with its
     * feet's weight halved), a modifier after it; {@code wrap} turns each foot placement into the rig's modifier.
     */
    private static World world(JobSystem jobs, Function<FootPlacement, PoseModifier> wrap, boolean twice) {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Animators animators = new Animators(scene, jobs);
        OneThreadGround ground = new OneThreadGround();
        Rig.Builder r = Rig.builder(IkTest.figure());
        TwoBoneIk left = r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"), r.joint("foot_l"));
        TwoBoneIk right = r.twoBoneIk(r.joint("thigh_r"), r.joint("knee_r"), r.joint("foot_r"));
        Joint pelvis = r.joint("pelvis");
        Joint head = r.joint("head");
        PoseModifier feet = wrap.apply(new FootPlacement(left, right, pelvis, ground));
        r.modifier(feet);
        PoseModifier nod = pose -> pose.moveWorld(head, 0, 0.01 * pose.weight(), 0);
        r.modifier(nod);
        PoseModifier again = twice ? wrap.apply(new FootPlacement(left, right, pelvis, (x, z) -> ground.height(z, x)))
            : null;
        if (twice) {
            r.modifier(again);
        }
        Rig rig = r.build();
        Rig plain = Rig.of(IkTest.figure());
        List<long[]> all = new ArrayList<>();
        for (int i = 0; i < ANIMATORS; i++) {
            long instance = scene.create();
            scene.setTranslation(instance, 0.37 * (i % 40), 0.05 * (i % 3), 0.61 * (i / 40));
            long[] nodes = spawn(scene, instance);
            Animator a = animators.add(i % 5 == 4 ? plain : rig, nodes);
            if (i % 5 != 4) {
                a.ik(left).target(0.37 * (i % 40) - 0.2, 0.3, 0.61 * (i / 40) + 0.1).weight(i % 2 == 0 ? 0.5 : 0);
                if (i % 7 == 3) {
                    a.weight(feet, 0.5);
                }
                if (i % 11 == 5) {
                    a.weight(feet, 0); // its feet skipped: the next modifiers in the parallel part
                }
            }
            all.add(nodes);
        }
        scene.update();
        return new World(scene, animators, all, ground);
    }

    /** The figure's nodes under {@code instance}, at rest. */
    private static long[] spawn(SceneGraph scene, long instance) {
        Skeleton figure = IkTest.figure();
        long[] nodes = new long[figure.size()];
        for (int j = 0; j < nodes.length; j++) {
            int parent = figure.parent(j);
            nodes[j] = scene.create(parent < 0 ? instance : nodes[parent]);
            scene.setTranslation(nodes[j], figure.rest(j, 0), figure.rest(j, 1), figure.rest(j, 2));
        }
        return nodes;
    }

    @Test
    void aGroundOfOneThreadServesManyAnimatorsOnJobsAsASerialRunDoes() {
        World parallel = world(jobs, feet -> feet, false);
        // The serial reference: foot placement as one modifier, its ground asked in the middle of it, as before A13
        World serial = world(null, feet -> feet::modify, false);
        parallel.step(3);
        serial.step(3);
        assertThat(parallel.poses()).isEqualTo(serial.poses());
        assertThat(parallel.ground().asked).isEqualTo(serial.ground().asked).isPositive();
        // And the feet did move: the poses differ from foot placement off.
        World off = world(null, feet -> pose -> { }, false);
        off.step(3);
        assertThat(parallel.poses()).isNotEqualTo(off.poses());
    }

    @Test
    void twoFootPlacementsOfARigWaitInTurn() {
        World parallel = world(jobs, feet -> feet, true);
        World serial = world(null, feet -> feet::modify, true);
        parallel.step(2);
        serial.step(2);
        assertThat(parallel.poses()).isEqualTo(serial.poses());
        assertThat(parallel.ground().asked).isEqualTo(serial.ground().asked);
    }

    @Test
    void aSplitUpdateOnJobsAllocatesNothing() {
        World parallel = world(jobs, feet -> feet, true);
        parallel.step(2);
        assertAllocationFree(() -> parallel.animators().update(1 / 60.0));
    }
}
