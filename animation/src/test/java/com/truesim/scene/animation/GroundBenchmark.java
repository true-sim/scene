package com.truesim.scene.animation;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Update costs of 1,000 animators with foot placement on uneven ground, a clip playing on a skeleton of 61 joints: the
 * ground probes in the serial part (decision A13), the rest of the sampling on the jobs. Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :scene-animation:test --tests '*GroundBenchmark'}; results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class GroundBenchmark {

    private static final int ANIMATORS = 1000;
    private static final int EXTRA = 50; // joints hanging from the head, as fingers and face do
    private static final int FRAMES = 600;

    @Test
    void footPlacementOnManyAnimators() {
        try (JobSystem jobs = JobSystem.ofAvailableProcessors()) {
            for (int round = 0; round < 3; round++) {
                run(null, round == 2);
                run(jobs, round == 2);
            }
        }
    }

    /** {@link IkTest#figure()} with a chain of {@link #EXTRA} joints under its head. */
    static Skeleton skeleton() {
        Skeleton figure = IkTest.figure();
        int n = figure.size() + EXTRA;
        String[] names = new String[n];
        int[] parents = new int[n];
        double[] rest = new double[Skeleton.TRANSFORM * n];
        for (int j = 0; j < n; j++) {
            if (j < figure.size()) {
                names[j] = figure.name(j);
                parents[j] = figure.parent(j);
                for (int i = 0; i < Skeleton.TRANSFORM; i++) {
                    rest[Skeleton.TRANSFORM * j + i] = figure.rest(j, i);
                }
            } else {
                names[j] = "extra" + j;
                parents[j] = j == figure.size() ? figure.size() - 1 : j - 1;
                rest[Skeleton.TRANSFORM * j + 1] = 0.01;
                rest[Skeleton.TRANSFORM * j + 6] = 1;
                for (int i = 7; i < 10; i++) {
                    rest[Skeleton.TRANSFORM * j + i] = 1;
                }
            }
        }
        return new Skeleton(names, parents, rest);
    }

    /** Every joint turning a little about x over a second, looping. */
    static AnimationClip sway(Skeleton skeleton) {
        List<Track> tracks = new ArrayList<>();
        float s = (float) Math.sin(0.05);
        float c = (float) Math.cos(0.05);
        for (int j = 0; j < skeleton.size(); j++) {
            tracks.add(new Track(skeleton.name(j), j, Track.Property.ROTATION, Track.Interpolation.LINEAR,
                new float[] {0, 0.5f, 1}, new float[] {0, 0, 0, 1, s, 0, 0, c, 0, 0, 0, 1}, 0));
        }
        return new AnimationClip("sway", tracks);
    }

    private void run(JobSystem jobs, boolean print) {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Animators animators = new Animators(scene, jobs);
        Skeleton skeleton = skeleton();
        Rig.Builder r = Rig.builder(skeleton);
        TwoBoneIk left = r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"), r.joint("foot_l"));
        TwoBoneIk right = r.twoBoneIk(r.joint("thigh_r"), r.joint("knee_r"), r.joint("foot_r"));
        Thread updating = Thread.currentThread();
        long[] asked = new long[1];
        r.modifier(new FootPlacement(left, right, r.joint("pelvis"), (x, z) -> {
            if (Thread.currentThread() != updating) {
                throw new AssertionError("ground asked off the updating thread");
            }
            asked[0]++;
            return 0.1 * Math.sin(x) * Math.cos(z);
        }));
        Clip clip = r.clip(sway(skeleton));
        Rig.Layer layer = r.layer();
        Rig rig = r.build();
        for (int i = 0; i < ANIMATORS; i++) {
            long instance = scene.create();
            scene.setTranslation(instance, i % 32, 0, i / 32);
            long[] nodes = new long[skeleton.size()];
            for (int j = 0; j < nodes.length; j++) {
                int parent = skeleton.parent(j);
                nodes[j] = scene.create(parent < 0 ? instance : nodes[parent]);
            }
            animators.add(rig, nodes).layer(layer).play(clip);
        }
        scene.update();
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long bytes = threads.getCurrentThreadAllocatedBytes();
        long nanos = 0;
        for (int f = 0; f < FRAMES; f++) {
            long start = System.nanoTime();
            animators.update(1 / 60.0);
            nanos += System.nanoTime() - start;
            scene.update();
        }
        bytes = threads.getCurrentThreadAllocatedBytes() - bytes;
        assertThat(asked[0]).isEqualTo(2L * ANIMATORS * FRAMES);
        if (print) {
            System.out.printf(Locale.ROOT, "%s: %.3f ms per animators update, %d B allocated on"
                + " the updating thread per frame%n", jobs == null ? "serial" : "jobs",
                nanos / 1e6 / FRAMES, bytes / FRAMES);
        }
    }
}
