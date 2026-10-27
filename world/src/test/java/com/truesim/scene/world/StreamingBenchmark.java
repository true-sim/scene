package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Streaming costs: 4,096 cells (a 64 x 64 grid of 32 m cells) of 64 static props each, 262,144 nodes in all. The
 * loader generates its data in memory, so this measures the world, scene and index, not a disk. Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :scene-world:test --tests '*StreamingBenchmark'}; results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class StreamingBenchmark {

    private static final int GRID = 64;
    private static final int PROPS = 64;

    /** A cell's props: positions within the cell. */
    record PropCell(double[] positions) {
    }

    private static final Layer<PropCell> LAYER = Layer.of("props", PropCell.class, Levels.single(32));

    /** Generates props in the background; attaches them as static boxes. */
    private static final class Loader implements CellLoader<PropCell> {
        @Override
        public PropCell load(Cell cell) {
            double[] positions = new double[PROPS * 3];
            for (int i = 0; i < PROPS; i++) {
                positions[i * 3] = cell.minX() + (i % 8) * 4 + 2;
                positions[i * 3 + 1] = 0;
                positions[i * 3 + 2] = cell.minZ() + (i / 8) * 4 + 2;
            }
            return new PropCell(positions);
        }

        @Override
        public void attach(PropCell data, CellContent content) {
            SceneGraph scene = content.scene();
            double[] p = data.positions();
            for (int i = 0; i < PROPS; i++) {
                long node = content.create();
                scene.setTranslation(node, p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
                scene.setLocalBounds(node, -1, 0, -1, 1, 2, 1);
                scene.setMobility(node, Mobility.STATIC);
            }
        }
    }

    private long sink;

    @Test
    void streamAGridInAndOut() throws InterruptedException {
        // Warm up the JIT on a smaller run first, then measure a fresh world.
        run(16, false);
        run(GRID, true);
    }

    private void run(int grid, boolean print) throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock(), grid * grid * PROPS, 1 << 20);
        SpatialIndex index = new SpatialIndex(scene);
        try (TaskScheduler tasks = new TaskScheduler(); World world = new World(scene, tasks)) {
            world.register(LAYER, new Loader());
            int cells = grid * grid;

            // 1. Request every cell, then run frames until all are attached.
            long start = System.nanoTime();
            for (int x = 0; x < grid; x++) {
                for (int z = 0; z < grid; z++) {
                    world.request(LAYER, 0, x, 0, z);
                }
            }
            long requested = System.nanoTime();
            long attachTime = 0;
            long updateTime = 0;
            long indexTime = 0;
            long worstFrame = 0;
            int frames = 0;
            while (world.loadedCount() < cells) {
                long t0 = System.nanoTime();
                tasks.drainCompleted();
                long t1 = System.nanoTime();
                scene.update();
                long tScene = System.nanoTime();
                index.update();
                long t2 = System.nanoTime();
                attachTime += t1 - t0;
                updateTime += tScene - t1;
                indexTime += t2 - tScene;
                worstFrame = Math.max(worstFrame, t2 - t0);
                frames++;
                Thread.sleep(1);
            }
            long loaded = System.nanoTime();

            // 2. Steady state: more requests and releases of cells already loaded.
            int operations = 1_000_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < operations; i++) {
                int x = i % grid;
                int z = (i / grid) % grid;
                world.request(LAYER, 0, x, 0, z);
                world.release(LAYER, 0, x, 0, z);
            }
            long steady = System.nanoTime() - t0;

            // 3. Release everything: cells detach, nodes leave the scene and the index.
            long t3 = System.nanoTime();
            for (int x = 0; x < grid; x++) {
                for (int z = 0; z < grid; z++) {
                    world.release(LAYER, 0, x, 0, z);
                }
            }
            long released = System.nanoTime();
            scene.update();
            index.update();
            long cleaned = System.nanoTime();
            assertThat(scene.size()).isZero();
            sink += frames;

            if (print) {
                System.out.printf(Locale.ROOT, "Streaming benchmark: %,d cells of %d props, %,d nodes%n", cells, PROPS,
                    cells * PROPS);
                System.out.printf(Locale.ROOT, "  request all: %.1f ms (%.2f us per cell)%n", (requested - start) / 1e6,
                    (requested - start) / 1e3 / cells);
                System.out.printf(Locale.ROOT, "  all loaded and attached after %.0f ms, over %d frames (%,.0f cells/s)%n",
                    (loaded - start) / 1e6, frames, cells / ((loaded - start) / 1e9));
                System.out.printf(Locale.ROOT, "  main thread: attach %.1f us per cell; per frame scene update %.1f ms, "
                    + "index update %.1f ms; worst frame %.1f ms%n", attachTime / 1e3 / cells,
                    updateTime / 1e6 / frames, indexTime / 1e6 / frames, worstFrame / 1e6);
                System.out.printf(Locale.ROOT, "  request and release of a loaded cell: %.0f ns%n",
                    steady / (double) operations);
                System.out.printf(Locale.ROOT, "  release all: %.1f ms (%.1f us per cell), then scene and index update "
                    + "%.1f ms%n", (released - t3) / 1e6, (released - t3) / 1e3 / cells, (cleaned - released) / 1e6);
            }
        }
    }
}
