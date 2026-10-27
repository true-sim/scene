package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Interest sources selected on a job system: the same cells as selected on one thread. */
class ParallelSelectionTest {

    private static final Layer<String> TERRAIN = Layer.of("terrain", String.class, Levels.grid(32, 4));
    private static final Layer<String> PROPS = Layer.of("props", String.class, Levels.hlod(16, 3));
    private static final Layer<String> COLLISION = Layer.of("collision", String.class, Levels.single(64));
    private static final InterestProfile PLAYER = InterestProfile.builder()
        .layer(TERRAIN, Detail.screenError(2), Range.radius(600))
        .layer(PROPS, Detail.bands(40, 120, 300))
        .layer(COLLISION, Detail.full(), Range.radius(100))
        .build();
    private static final int SOURCES = 24;

    private final JobSystem jobs = new JobSystem(4);
    private final List<AutoCloseable> closing = new ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        for (int i = closing.size() - 1; i >= 0; i--) {
            closing.get(i).close();
        }
        jobs.close();
    }

    /** A world with {@code SOURCES} players. */
    private final class Fixture {
        final World world;
        final List<InterestSource> players = new ArrayList<>();

        Fixture(JobSystem jobSystem) {
            TaskScheduler tasks = new TaskScheduler();
            world = new World(new SceneGraph(new ChangeClock()), tasks);
            closing.add(tasks);
            closing.add(world);
            world.setJobs(jobSystem);
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
            for (int i = 0; i < SOURCES; i++) {
                players.add(world.addInterest(PLAYER, Priority.NORMAL));
            }
        }

        void move(long seed) {
            SplittableRandom random = new SplittableRandom(seed);
            for (InterestSource player : players) {
                player.moveTo(random.nextDouble(-2000, 2000), 0, random.nextDouble(-2000, 2000));
            }
            world.update();
        }
    }

    private static void assertSame(Fixture serial, Fixture parallel) {
        for (int p = 0; p < SOURCES; p++) {
            InterestSource a = serial.players.get(p);
            InterestSource b = parallel.players.get(p);
            for (int i = 0; i < a.wanted.length; i++) {
                for (int level = 0; level < a.wanted[i].length; level++) {
                    assertThat(keys(b.wanted[i][level])).isEqualTo(keys(a.wanted[i][level]));
                    assertThat(keys(b.retained[i][level])).isEqualTo(keys(a.retained[i][level]));
                }
            }
        }
        assertThat(parallel.world.loadingCount() + parallel.world.loadedCount())
            .isEqualTo(serial.world.loadingCount() + serial.world.loadedCount());
    }

    private static List<Long> keys(LongIntMap map) {
        List<Long> keys = new ArrayList<>();
        for (int slot = 0; slot < map.capacity(); slot++) {
            if (map.keyAt(slot) >= 0) {
                keys.add(map.keyAt(slot));
            }
        }
        return keys;
    }

    @Test
    void sourcesSelectedInParallelWantTheSameCells() {
        Fixture serial = new Fixture(null);
        Fixture parallel = new Fixture(jobs);
        for (long seed = 1; seed <= 4; seed++) {
            serial.move(seed);
            parallel.move(seed);
            assertSame(serial, parallel);
        }
        // One source moving alone selects on the calling thread.
        serial.players.get(3).moveTo(10, 0, 10);
        parallel.players.get(3).moveTo(10, 0, 10);
        serial.world.update();
        parallel.world.update();
        assertSame(serial, parallel);
    }
}
