package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Mat4;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Frustum;
import com.truesim.common.state.ChangeClock;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Frame costs of a large mixed scene: 1,000,000 static boxes (a 1000 x 1000 grid, 4 m apart) and 10,000 moving ones.
 * Run with {@code TRUESIM_BENCHMARK=true ./gradlew :scene-core:test --tests '*SceneBenchmark'}; results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class SceneBenchmark {

    private static final int GRID = 1000;
    private static final int MOVING = 10_000;
    private static final int FRAMES = 120;

    private long sink;

    @Test
    void mixedStaticAndDynamicScene() {
        try (JobSystem jobs = JobSystem.ofAvailableProcessors()) {
            run(null, false); // warm up both paths first
            run(jobs, false);
            run(null, true);
            run(jobs, true);
        }
    }

    private void run(JobSystem jobs, boolean print) {
        long start = System.nanoTime();
        ChangeClock clock = new ChangeClock();
        SceneGraph scene = new SceneGraph(clock, GRID * GRID + MOVING, 1 << 20);
        scene.setJobs(jobs);
        for (int x = 0; x < GRID; x++) {
            for (int z = 0; z < GRID; z++) {
                long node = scene.create();
                scene.setTranslation(node, x * 4.0, 0, z * 4.0);
                scene.setLocalBounds(node, -1, 0, -1, 1, 3, 1);
                scene.setMobility(node, Mobility.STATIC);
            }
        }
        long[] moving = new long[MOVING];
        for (int i = 0; i < MOVING; i++) {
            moving[i] = scene.create();
            scene.setLocalBounds(moving[i], -0.5, 0, -0.5, 0.5, 1, 0.5);
        }
        SpatialIndex index = new SpatialIndex(scene);
        index.setJobs(jobs);
        long created = System.nanoTime();
        scene.update();
        long propagated = System.nanoTime();
        index.update();
        long built = System.nanoTime();

        Mat4 projection = Mat4.perspective(Math.toRadians(70), 16.0 / 9, 0.1, 500);
        SpatialIndex.Visitor count = node -> {
            sink++;
            return true;
        };
        long sceneTime = 0;
        long indexTime = 0;
        long queryTime = 0;
        long staticOnly = 0;
        int visible = 0;
        for (int frame = 0; frame < FRAMES * 2; frame++) {
            boolean measured = frame >= FRAMES; // the first half warms up the JIT
            boolean move = frame % 2 == 0;
            long t0 = System.nanoTime();
            if (move) {
                for (int i = 0; i < MOVING; i++) {
                    double angle = frame * 0.01 + i;
                    scene.setTranslation(moving[i], 2000 + 1500 * Math.cos(angle), 1, 2000 + 1500 * Math.sin(angle));
                }
            }
            scene.update();
            long t1 = System.nanoTime();
            index.update();
            long t2 = System.nanoTime();
            Vec3 eye = Vec3.of(2000 + frame, 50, 2000);
            Frustum frustum = Frustum.fromMatrix(projection.mul(Mat4.lookAt(eye, eye.add(Vec3.of(1, -0.3, 0.2)),
                Vec3.of(0, 1, 0))));
            sink = 0;
            index.queryFrustum(frustum, -1, count);
            long t3 = System.nanoTime();
            if (measured) {
                if (move) {
                    sceneTime += t1 - t0;
                    indexTime += t2 - t1;
                } else {
                    staticOnly += t2 - t0;
                }
                queryTime += t3 - t2;
                visible = (int) sink;
            }
        }
        // Five views at once, e.g. a camera and four shadow cascades looking different ways.
        Frustum[] views = new Frustum[5];
        SpatialIndex.ViewResults results = new SpatialIndex.ViewResults();
        long viewsTime = 0;
        int viewNodes = 0;
        for (int frame = 0; frame < FRAMES * 2; frame++) {
            for (int v = 0; v < views.length; v++) {
                Vec3 eye = Vec3.of(2000 + frame, 50, 2000);
                double angle = v * 1.2566 + frame * 0.01;
                views[v] = Frustum.fromMatrix(projection.mul(Mat4.lookAt(eye, eye.add(Vec3.of(Math.cos(angle), -0.3,
                    Math.sin(angle))), Vec3.of(0, 1, 0))));
            }
            long t0 = System.nanoTime();
            index.queryFrustums(views, views.length, -1, results);
            if (frame >= FRAMES) {
                viewsTime += System.nanoTime() - t0;
                viewNodes = 0;
                for (int v = 0; v < views.length; v++) {
                    viewNodes += results.count(v);
                }
            }
        }
        assertThat(index.staticCount()).isEqualTo(GRID * GRID);
        assertThat(index.dynamicCount()).isEqualTo(MOVING);
        if (!print) {
            return;
        }
        int movingFrames = FRAMES / 2;
        System.out.printf(Locale.ROOT, "Scene benchmark (%s): %,d static and %,d moving nodes%n",
            jobs == null ? "one thread" : jobs.threads() + " threads", GRID * GRID, MOVING);
        System.out.printf(Locale.ROOT, "  setup: create %.0f ms, first scene update %.0f ms, index build %.0f ms%n",
            (created - start) / 1e6, (propagated - created) / 1e6, (built - propagated) / 1e6);
        System.out.printf(Locale.ROOT, "  frame with %,d moving: scene update %.2f ms, index update %.2f ms%n", MOVING,
            sceneTime / 1e6 / movingFrames, indexTime / 1e6 / movingFrames);
        System.out.printf(Locale.ROOT, "  frame with nothing moving: scene and index update %.3f ms%n",
            staticOnly / 1e6 / (FRAMES - movingFrames));
        System.out.printf(Locale.ROOT, "  frustum query: %.2f ms for %,d visible nodes%n", queryTime / 1e6 / FRAMES,
            visible);
        System.out.printf(Locale.ROOT, "  %d views at once: %.2f ms for %,d nodes found%n", views.length,
            viewsTime / 1e6 / FRAMES, viewNodes);
    }
}
