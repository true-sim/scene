package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Hysteresis, swaps without holes, and readiness. */
class ResidencyTest {

    private static final Layer<String> TERRAIN = Layer.of("terrain", String.class, Levels.grid(10, 3));
    private static final Layer<String> COLLISION = Layer.of("collision", String.class, Levels.single(10));
    private static final Layer<String> SPACE = Layer.of("space", String.class, Levels.grid(10, 2).volumetric());

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final TaskScheduler tasks = new TaskScheduler();
    private final World world = new World(scene, tasks);
    private final AtomicInteger loads = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    /** Cells that wait for {@link #release} before loading. */
    private volatile Predicate<Cell> blocked = cell -> false;
    /** Cells that fail to load. */
    private volatile Predicate<Cell> failing = cell -> false;

    ResidencyTest() {
        CellLoader<String> loader = new CellLoader<>() {
            @Override
            public String load(Cell cell) throws Exception {
                loads.incrementAndGet();
                if (blocked.test(cell)) {
                    release.await();
                }
                if (failing.test(cell)) {
                    throw new java.io.IOException("broken cell");
                }
                return "";
            }

            @Override
            public void attach(String data, CellContent content) {
            }
        };
        world.register(TERRAIN, loader);
        world.register(COLLISION, loader);
        world.register(SPACE, loader);
    }

    @AfterEach
    void stop() {
        release.countDown();
        world.close();
        tasks.close();
    }

    /** Frames until no unblocked load is outstanding. */
    private void settle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int idle = 0;
        while (idle < 20) {
            int before = world.loadedCount() + world.failedCount();
            Thread.sleep(2);
            tasks.drainCompleted();
            world.update();
            idle = world.loadedCount() + world.failedCount() == before ? idle + 1 : 0;
            assertThat(System.nanoTime()).as("settles").isLessThan(deadline);
        }
    }

    private static InterestProfile bands(Layer<?> layer, double... distances) {
        return InterestProfile.builder().layer(layer, Detail.bands(distances)).build();
    }

    @Test
    void sourcesWobblingAtABandEdgeDoNotThrash() throws InterruptedException {
        InterestSource camera = world.addInterest(bands(TERRAIN, 15, 40, 100), Priority.HIGH);
        camera.moveTo(5, 0, 5); // a level-1 cell is exactly 15 away: the edge of the first band
        world.update();
        settle();
        int before = loads.get();
        for (int i = 0; i < 20; i++) {
            camera.moveTo(5 + (i % 2 == 0 ? 0.01 : -0.01), 0, 5);
            world.update();
        }
        settle();
        assertThat(loads.get() - before).isLessThanOrEqualTo(4); // at most one switch, not one per wobble
    }

    @Test
    void fullDetailKeepsCellsJustBeyondItsRange() throws InterruptedException {
        InterestSource player = world.addInterest(
            InterestProfile.builder().layer(COLLISION, Detail.full(), Range.radius(10)).build(), Priority.NORMAL);
        player.moveTo(5, 0, 5);
        world.update();
        settle();
        assertThat(world.state(COLLISION, 0, 1, 0, 0)).isEqualTo(CellState.LOADED); // 5 away
        player.moveTo(-6, 0, 5); // cell 1 is now 16 away: beyond 10 * 1.15 = 11.5, released
        world.update();
        assertThat(world.state(COLLISION, 0, 1, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.state(COLLISION, 0, -1, 0, 0)).isNotEqualTo(CellState.ABSENT);
        player.moveTo(-10.5, 0, 5); // cell 0 is 10.5 away: beyond the range, kept by the hysteresis
        world.update();
        assertThat(world.state(COLLISION, 0, 0, 0, 0)).isEqualTo(CellState.LOADED);
        player.moveTo(-12, 0, 5); // 12 away: beyond 11.5, released
        world.update();
        assertThat(world.state(COLLISION, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
    }

    @Test
    void coarseCellsStayUntilTheirFinerReplacementsLoad() throws InterruptedException {
        InterestSource camera = world.addInterest(bands(TERRAIN, 15, 40, 100), Priority.HIGH);
        camera.moveTo(95, 0, 5); // far from the origin: cell 0 of level 2 is coarse here
        world.update();
        settle();
        assertThat(world.state(TERRAIN, 2, 0, 0, 0)).isEqualTo(CellState.LOADED);
        blocked = cell -> cell.level() < 2 && cell.x() >= 0 && cell.x() < 4; // its finer replacements wait
        camera.moveTo(5, 0, 5);
        world.update();
        assertThat(world.isReady(camera)).isFalse();
        assertThat(world.state(TERRAIN, 2, 0, 0, 0)).as("held while its replacements load")
            .isEqualTo(CellState.LOADED);
        release.countDown();
        settle();
        assertThat(world.state(TERRAIN, 2, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.state(TERRAIN, 0, 0, 0, 0)).isEqualTo(CellState.LOADED);
        assertThat(world.isReady(camera)).isTrue();
    }

    @Test
    void fineCellsStayUntilTheirCoarseReplacementLoads() throws InterruptedException {
        InterestSource camera = world.addInterest(bands(TERRAIN, 15, 40, 100), Priority.HIGH);
        camera.moveTo(5, 0, 5);
        world.update();
        settle();
        assertThat(world.state(TERRAIN, 0, 0, 0, 0)).isEqualTo(CellState.LOADED);
        blocked = cell -> cell.level() == 2 && cell.x() == 0 && cell.z() == 0;
        camera.moveTo(95, 0, 5);
        world.update();
        assertThat(world.state(TERRAIN, 0, 0, 0, 0)).as("held").isEqualTo(CellState.LOADED);
        release.countDown();
        settle();
        assertThat(world.state(TERRAIN, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.state(TERRAIN, 2, 0, 0, 0)).isEqualTo(CellState.LOADED);
    }

    @Test
    void cellsLeftBehindGoAtOnceAndHeldCellsCanReturn() throws InterruptedException {
        InterestSource camera = world.addInterest(bands(TERRAIN, 15, 40, 100), Priority.HIGH);
        camera.moveTo(5, 0, 5);
        world.update();
        settle();
        camera.moveTo(5_000, 0, 5); // nothing new overlaps the old cells: released without waiting
        world.update();
        world.update();
        assertThat(world.state(TERRAIN, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        settle();
        // A held cell wanted again keeps its single request.
        blocked = cell -> cell.x() < 100 && cell.level() < 2;
        camera.moveTo(5_005, 0, 5);
        world.update();
        camera.moveTo(5_000, 0, 5);
        world.update();
        release.countDown();
        settle();
        camera.close();
        world.update();
        assertThat(world.loadedCount() + world.loadingCount()).isZero(); // every request was released once
    }

    @Test
    void aHeldCellWantedAgainKeepsItsSingleRequest() throws InterruptedException {
        InterestSource camera = world.addInterest(bands(TERRAIN, 15, 40, 100), Priority.HIGH);
        camera.moveTo(95, 0, 5);
        world.update();
        settle();
        blocked = cell -> cell.level() < 2; // finer cells never arrive while blocked
        camera.moveTo(5, 0, 5); // coarse cell 0 is held, waiting for its replacements
        assertThat(world.isReady(camera)).isFalse(); // moved, not yet updated
        world.update();
        camera.moveTo(95, 0, 5); // back: the held coarse cell is wanted again
        world.update();
        assertThat(world.state(TERRAIN, 2, 0, 0, 0)).isEqualTo(CellState.LOADED);
        release.countDown();
        settle();
        camera.close();
        assertThat(world.loadedCount() + world.loadingCount()).isZero(); // every request was released exactly once
    }

    @Test
    void failedReplacementsCountAsReplaced() throws InterruptedException {
        InterestSource camera = world.addInterest(bands(TERRAIN, 15, 40, 100), Priority.HIGH);
        camera.moveTo(95, 0, 5);
        world.update();
        settle();
        failing = cell -> cell.level() < 2;
        camera.moveTo(5, 0, 5);
        world.update();
        settle();
        assertThat(world.state(TERRAIN, 2, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.failedCount()).isPositive();
        assertThat(world.isReady(camera)).isTrue(); // failed cells do not keep a loading screen up
    }

    @Test
    void volumetricSwapsHoldToo() throws InterruptedException {
        InterestSource ship = world.addInterest(bands(SPACE, 12, 40), Priority.NORMAL);
        ship.moveTo(5, 5, 5);
        world.update();
        settle();
        ship.moveTo(5, 35, 5); // the cells around the old position coarsen
        world.update();
        settle();
        assertThat(world.state(SPACE, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.state(SPACE, 1, 0, 0, 0)).isEqualTo(CellState.LOADED);
    }

    @Test
    void hysteresisIsConfigurable() {
        world.setHysteresis(0);
        world.setHysteresis(1);
        assertThatIllegalArgumentException().isThrownBy(() -> world.setHysteresis(-0.1));
        assertThatIllegalArgumentException().isThrownBy(() -> world.setHysteresis(1.5));
        assertThatIllegalArgumentException().isThrownBy(() -> world.setHysteresis(Double.NaN));
    }
}
