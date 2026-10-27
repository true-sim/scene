package com.truesim.scene.world;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * What requesting a new cell costs the main thread: 4,096 requests on a lane of 16, once with the lane full (loads wait)
 * and once with loads finishing meanwhile, which contend for the lane. Measured after warm-up rounds. Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :scene-world:test --tests '*RequestBenchmark'}; results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class RequestBenchmark {

    private static final Layer<String> LAYER = Layer.of("requests", String.class, Levels.single(32));

    @Test
    void requestNewCells() throws InterruptedException {
        for (int round = 0; round < 8; round++) {
            measure(round >= 5, true);
            measure(round >= 5, false);
        }
    }

    private void measure(boolean print, boolean blocked) throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(blocked ? 1 : 0);
        SceneGraph scene = new SceneGraph(new ChangeClock());
        try (TaskScheduler tasks = new TaskScheduler(); World world = new World(scene, tasks)) {
            world.register(LAYER, new CellLoader<>() {
                @Override
                public String load(Cell cell) throws InterruptedException {
                    gate.await();
                    return "";
                }

                @Override
                public void attach(String data, CellContent content) {
                }
            });
            int cells = 4096;
            long t0 = System.nanoTime();
            for (int i = 0; i < cells; i++) {
                world.request(LAYER, 0, i % 64, 0, i / 64);
            }
            long t1 = System.nanoTime();
            gate.countDown();
            while (world.loadedCount() < cells) {
                tasks.drainCompleted();
                Thread.onSpinWait();
            }
            long t2 = System.nanoTime();
            if (print) {
                System.out.printf(Locale.ROOT, "%s: request %.2f us per cell, all loaded after %.1f ms%n",
                    blocked ? "lane full (loads wait)" : "loads running", (t1 - t0) / 1e3 / cells, (t2 - t0) / 1e6);
            }
        }
    }
}
