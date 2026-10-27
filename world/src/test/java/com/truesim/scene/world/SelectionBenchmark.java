package com.truesim.scene.world;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Selecting cells for many interest sources, as a server with many players does: 512 players spread over 16 km, each
 * wanting terrain, props and collision, all moving every frame. Measures {@code World.update()} on one thread and on a
 * job system. Run with {@code TRUESIM_BENCHMARK=true ./gradlew :scene-world:test --tests '*SelectionBenchmark'};
 * results are printed.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class SelectionBenchmark {

    private static final Layer<String> TERRAIN = Layer.of("terrain", String.class, Levels.grid(64, 5));
    private static final Layer<String> PROPS = Layer.of("props", String.class, Levels.hlod(32, 4));
    private static final Layer<String> COLLISION = Layer.of("collision", String.class, Levels.single(64));
    private static final InterestProfile PLAYER = InterestProfile.builder()
        .layer(TERRAIN, Detail.screenError(2), Range.radius(1500))
        .layer(PROPS, Detail.bands(100, 300, 800))
        .layer(COLLISION, Detail.full(), Range.radius(200))
        .build();
    private static final int PLAYERS = 512;
    private static final int FRAMES = 200;

    @Test
    void selectForManyPlayers() throws Exception {
        try (JobSystem jobs = JobSystem.ofAvailableProcessors()) {
            run(null, false);
            run(jobs, false);
            run(null, true);
            run(jobs, true);
        }
    }

    private void run(JobSystem jobs, boolean print) throws Exception {
        try (TaskScheduler tasks = new TaskScheduler();
             World world = new World(new SceneGraph(new ChangeClock()), tasks)) {
            world.setJobs(jobs);
            for (Layer<String> layer : List.of(TERRAIN, PROPS, COLLISION)) {
                world.register(layer, new CellLoader<>() {
                    @Override
                    public String load(Cell cell) {
                        return "";
                    }

                    @Override
                    public void attach(String data, CellContent content) {
                    }
                });
            }
            SplittableRandom random = new SplittableRandom(1);
            List<InterestSource> players = new ArrayList<>();
            double[] position = new double[PLAYERS * 2];
            double[] heading = new double[PLAYERS];
            for (int i = 0; i < PLAYERS; i++) {
                players.add(world.addInterest(PLAYER, Priority.NORMAL));
                position[i * 2] = random.nextDouble(-8000, 8000);
                position[i * 2 + 1] = random.nextDouble(-8000, 8000);
                heading[i] = random.nextDouble(2 * Math.PI);
            }
            long[] frames = new long[FRAMES];
            for (int frame = -20; frame < FRAMES; frame++) { // 20 frames to settle
                for (int i = 0; i < PLAYERS; i++) {
                    position[i * 2] += Math.cos(heading[i]) * 0.5; // 30 m/s at 60 fps
                    position[i * 2 + 1] += Math.sin(heading[i]) * 0.5;
                    players.get(i).moveTo(position[i * 2], 0, position[i * 2 + 1]);
                }
                long start = System.nanoTime();
                world.update();
                long time = System.nanoTime() - start;
                tasks.drainCompleted();
                if (frame >= 0) {
                    frames[frame] = time;
                }
            }
            Arrays.sort(frames);
            if (print) {
                System.out.printf(Locale.ROOT, "Selection benchmark (%s): %d players moving, World.update() median "
                    + "%.2f ms, p99 %.2f ms%n", jobs == null ? "one thread" : jobs.threads() + " threads", PLAYERS,
                    frames[FRAMES / 2] / 1e6, frames[FRAMES * 99 / 100] / 1e6);
            }
        }
    }
}
