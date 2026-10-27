package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Mat4;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Frustum;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A camera flying over a 16 km x 16 km world at 300 m/s for 10 seconds, paced at 60 frames per second, streaming
 * three layers: terrain by screen-space error to an 8 km horizon, props by distance bands with HLOD proxies, and
 * collision at full detail around the camera. Loaders sleep 2-5 ms per cell to stand in for disk latency. Run with
 * {@code TRUESIM_BENCHMARK=true ./gradlew :scene-world:test --tests '*FlyThroughBenchmark'}; results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class FlyThroughBenchmark {

    private static final double WORLD = 16_000;
    private static final double SPEED = 300; // m/s
    private static final int FRAMES = 600;
    private static final long FRAME_NANOS = 16_666_667;

    record Tile(int level) {
    }

    record Props(int count) {
    }

    private static final Layer<Tile> TERRAIN = Layer.of("terrain", Tile.class, Levels.grid(64, 6));
    private static final Layer<Props> PROPS = Layer.of("props", Props.class, Levels.hlod(32, 4));
    private static final Layer<Props> COLLISION = Layer.of("collision", Props.class, Levels.single(64));

    private static final InterestProfile CAMERA = InterestProfile.builder()
        .layer(TERRAIN, Detail.screenError(2, 0.01), Range.radius(8_000)) // coarser tiles deviate by 1 % of their size
        .layer(PROPS, Detail.bands(150, 600, 1500, 3000))
        .layer(COLLISION, Detail.full(), Range.radius(200))
        .build();

    private final AtomicInteger loads = new AtomicInteger();

    /** Sleeps like a disk read, then makes the cell's content: static boxes, or one proxy for HLOD levels. */
    private final class Loader<T> implements CellLoader<T> {
        private final java.util.function.Function<Cell, T> make;
        private final int nodes;

        Loader(java.util.function.Function<Cell, T> make, int nodes) {
            this.make = make;
            this.nodes = nodes;
        }

        @Override
        public T load(Cell cell) throws InterruptedException {
            Thread.sleep(2 + ThreadLocalRandom.current().nextInt(4));
            loads.incrementAndGet();
            return make.apply(cell);
        }

        @Override
        public void attach(T data, CellContent content) {
            Cell cell = content.cell();
            int count = cell.level() == 0 ? nodes : 1; // finer cells hold props, coarser ones a merged proxy
            double size = cell.size();
            int side = (int) Math.ceil(Math.sqrt(count));
            for (int i = 0; i < count; i++) {
                long node = content.create();
                content.scene().setTranslation(node, cell.minX() + (i % side + 0.5) * size / side, 0,
                    cell.minZ() + (i / side + 0.5) * size / side);
                double half = count == 1 ? size / 2 : 1;
                content.scene().setLocalBounds(node, -half, 0, -half, half, count == 1 ? 1 : 4, half);
                content.scene().setMobility(node, Mobility.STATIC);
            }
        }
    }

    /** How many of the cells a source wants are still loading. */
    private static int pending(World world, InterestSource source) {
        int pending = 0;
        for (int i = 0; i < source.wanted.length; i++) {
            Layer<?> layer = source.profile().entries().get(i).layer();
            for (int level = 0; level < source.wanted[i].length; level++) {
                LongIntMap cells = source.wanted[i][level];
                for (int slot = 0; slot < cells.capacity(); slot++) {
                    long key = cells.keyAt(slot);
                    if (key >= 0 && world.state(layer, level, com.truesim.scene.core.CellGrid.keyX(key),
                        com.truesim.scene.core.CellGrid.keyY(key), com.truesim.scene.core.CellGrid.keyZ(key))
                        == CellState.LOADING) {
                        pending++;
                    }
                }
            }
        }
        return pending;
    }

    @Test
    void flyOverAStreamedWorld() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock(), 1 << 16, 1 << 20);
        SpatialIndex index = new SpatialIndex(scene);
        long[] frameNanos = new long[FRAMES];
        long[][] phase = new long[5][FRAMES];
        long pendingSum = 0;
        int pendingMax = 0;
        int peakLoaded = 0;
        long visibleSum = 0;
        try (TaskScheduler tasks = new TaskScheduler(); World world = new World(scene, tasks)) {
            world.register(TERRAIN, new Loader<>(cell -> new Tile(cell.level()), 1));
            world.register(PROPS, new Loader<>(cell -> new Props(16), 16));
            world.register(COLLISION, new Loader<>(cell -> new Props(1), 1));
            world.setAttachBudget(32);
            InterestSource camera = world.addInterest(CAMERA, Priority.HIGH);
            camera.setView(Math.PI / 3, 1080);
            Mat4 projection = Mat4.perspective(Math.PI / 3, 16.0 / 9, 0.5, 8_000);
            long[] visible = new long[1];
            SpatialIndex.Visitor count = node -> {
                visible[0]++;
                return true;
            };

            // Warm-up: the same flight over another strip of the world, so the measured one sees compiled code, as a
            // game does after its first minute. (Without it, the first frames of flight take tens of milliseconds.)
            camera.moveTo(1_000, 80, 2_000);
            for (int frame = 0; frame < FRAMES; frame++) {
                camera.moveTo(1_000 + frame * SPEED / 60, 80, 2_000);
                world.update();
                tasks.drainCompleted();
                scene.update();
                index.update();
                index.queryFrustum(Frustum.fromMatrix(projection), -1, count);
                Thread.sleep(2);
            }

            // Start: wait at the first position until it is loaded, as behind a loading screen.
            double x = 1_000;
            double z = WORLD / 2;
            camera.moveTo(x, 80, z);
            long loadingScreen = System.nanoTime();
            do {
                world.update();
                tasks.drainCompleted();
                Thread.sleep(1);
            } while (!world.isReady(camera) || world.waitingCount() > 0);
            loadingScreen = System.nanoTime() - loadingScreen;
            int initialLoads = loads.get();

            long next = System.nanoTime();
            for (int frame = 0; frame < FRAMES; frame++) {
                x += SPEED / 60;
                camera.moveTo(x, 80, z);
                long t0 = System.nanoTime();
                world.update();
                long p1 = System.nanoTime();
                tasks.drainCompleted();
                long p2 = System.nanoTime();
                scene.update();
                long p3 = System.nanoTime();
                index.update();
                long p4 = System.nanoTime();
                phase[0][frame] = p1 - t0;
                phase[1][frame] = p2 - p1;
                phase[2][frame] = p3 - p2;
                phase[3][frame] = p4 - p3;
                Vec3 eye = Vec3.of(x, 80, z);
                Frustum frustum = Frustum.fromMatrix(projection.mul(Mat4.lookAt(eye, eye.add(Vec3.of(1, -0.15, 0)),
                    Vec3.of(0, 1, 0))));
                visible[0] = 0;
                index.queryFrustum(frustum, -1, count);
                long p5 = System.nanoTime();
                phase[4][frame] = p5 - p4;
                frameNanos[frame] = p5 - t0;
                int pending = pending(world, camera);
                pendingSum += pending;
                pendingMax = Math.max(pendingMax, pending);
                visibleSum += visible[0];
                peakLoaded = Math.max(peakLoaded, world.loadedCount());
                next += FRAME_NANOS;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                }
            }
            long[] sorted = frameNanos.clone();
            Arrays.sort(sorted);
            System.out.printf(Locale.ROOT, "Fly-through: %.0f km world, %.0f m/s for %d frames at 60 fps, attach budget 32"
                + "%n", WORLD / 1000, SPEED, FRAMES);
            System.out.printf(Locale.ROOT, "  loading screen: %.0f ms, %d cells%n", loadingScreen / 1e6, initialLoads);
            System.out.printf(Locale.ROOT, "  main thread per frame (update, attach, scene, index, frustum query): "
                + "median %.2f ms, p99 %.2f ms, worst %.2f ms%n", sorted[FRAMES / 2] / 1e6,
                sorted[FRAMES * 99 / 100] / 1e6, sorted[FRAMES - 1] / 1e6);
            System.out.printf(Locale.ROOT, "  cells loaded in flight: %,d (%,.0f per second); resident at peak: %,d; "
                + "scene nodes at the end: %,d%n", loads.get() - initialLoads,
                (loads.get() - initialLoads) / (FRAMES / 60.0), peakLoaded, scene.size());
            System.out.printf(Locale.ROOT, "  camera's cells still loading per frame: %.0f on average, %d at most; visible "
                + "nodes per frame: %,d on average%n", pendingSum / (double) FRAMES, pendingMax, visibleSum / FRAMES);
            StringBuilder slowest = new StringBuilder("  slowest frames:");
            Integer[] order = new Integer[FRAMES];
            for (int i = 0; i < FRAMES; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Long.compare(frameNanos[b], frameNanos[a]));
            for (int i = 0; i < 5; i++) {
                int f = order[i];
                slowest.append(String.format(Locale.ROOT, "%n    #%d at x %.0f: %.1f ms (update %.1f, attach %.1f, scene %.1f, index %.1f, query %.1f)",
                    f, 1_000 + (f + 1) * SPEED / 60, frameNanos[f] / 1e6, phase[0][f] / 1e6, phase[1][f] / 1e6,
                    phase[2][f] / 1e6, phase[3][f] / 1e6, phase[4][f] / 1e6));
            }
            System.out.println(slowest);
            String[] names = {"world update", "drain and attach", "scene update", "index update", "frustum query"};
            for (int p = 0; p < phase.length; p++) {
                long[] times = phase[p].clone();
                Arrays.sort(times);
                System.out.printf(Locale.ROOT, "    %-17s median %.2f ms, p99 %.2f ms, worst %.2f ms%n", names[p],
                    times[FRAMES / 2] / 1e6, times[FRAMES * 99 / 100] / 1e6, times[FRAMES - 1] / 1e6);
            }
            assertThat(world.loadedCount()).isPositive();
        }
    }
}
