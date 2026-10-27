package com.truesim.scene.world;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.jobs.LaneSpec;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldTest {

    /** A cell's data: how many props it has. */
    record Props(int count) {
    }

    private static final Layer<Props> PROPS = Layer.of("props", Props.class, Levels.hlod(32, 3));

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final TaskScheduler tasks = new TaskScheduler();
    private final World world = new World(scene, tasks);

    /** Loads two props per cell, placed at the cell's corner; records what happened. */
    private class PropLoader implements CellLoader<Props> {
        final List<String> events = new CopyOnWriteArrayList<>();
        final AtomicInteger loads = new AtomicInteger();

        @Override
        public Props load(Cell cell) {
            loads.incrementAndGet();
            return new Props(2);
        }

        @Override
        public void attach(Props props, CellContent content) {
            Cell cell = content.cell();
            long group = content.create();
            content.scene().setTranslation(group, cell.minX(), cell.minY(), cell.minZ());
            for (int i = 0; i < props.count(); i++) {
                content.create(group);
            }
            events.add("attach " + cell.level() + ":" + cell.x() + "," + cell.y() + "," + cell.z());
        }

        @Override
        public void discard(Props props) {
            events.add("discard " + props.count());
        }
    }

    @AfterEach
    void stop() {
        world.close();
        tasks.close();
    }

    /** Drains the scheduler until no cell is loading, as frames would. */
    private void awaitLoads() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            tasks.drainCompleted();
            if (world.loadingCount() == 0 && tasks.pending() == 0) {
                return;
            }
            assertThat(System.nanoTime()).as("loads finish").isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    @Test
    void cellsLoadInTheBackgroundAndAttachAtTheFrameBoundary() throws InterruptedException {
        PropLoader loader = new PropLoader();
        world.register(PROPS, loader);
        world.request(PROPS, 1, 2, 0, -3);
        assertThat(world.state(PROPS, 1, 2, 0, -3)).isEqualTo(CellState.LOADING);
        assertThat(world.loadingCount()).isEqualTo(1);
        assertThat(world.data(PROPS, 1, 2, 0, -3)).isNull();
        assertThat(world.content(PROPS, 1, 2, 0, -3)).isNull();
        awaitLoads();
        assertThat(world.state(PROPS, 1, 2, 0, -3)).isEqualTo(CellState.LOADED);
        assertThat(world.loadedCount()).isEqualTo(1);
        assertThat(world.data(PROPS, 1, 2, 0, -3)).isEqualTo(new Props(2));
        CellContent content = world.content(PROPS, 1, 2, 0, -3);
        assertThat(content.nodeCount()).isEqualTo(3);
        assertThat(content.cell()).isEqualTo(new Cell(PROPS, 1, 2, 0, -3));
        scene.update();
        assertThat(scene.world(content.node(0)).translation()).isEqualTo(Vec3.of(128, 0, -192)); // level 1: 64 m cells
        assertThat(scene.size()).isEqualTo(3);
        assertThat(loader.events).containsExactly("attach 1:2,0,-3");
        assertThat(world.scene()).isSameAs(scene);
    }

    @Test
    void requestsAreCountedAndTheLastReleaseUnloads() throws InterruptedException {
        PropLoader loader = new PropLoader();
        world.register(PROPS, loader);
        world.request(PROPS, 0, 1, 1, 1);
        world.request(PROPS, 0, 1, 1, 1); // a second user: no second load
        awaitLoads();
        assertThat(loader.loads).hasValue(1);
        world.release(PROPS, 0, 1, 1, 1);
        assertThat(world.state(PROPS, 0, 1, 1, 1)).isEqualTo(CellState.LOADED);
        world.release(PROPS, 0, 1, 1, 1);
        assertThat(world.state(PROPS, 0, 1, 1, 1)).isEqualTo(CellState.ABSENT);
        assertThat(world.loadedCount()).isZero();
        assertThat(scene.size()).isZero(); // the group and its props went with the cell
        assertThatIllegalStateException().isThrownBy(() -> world.release(PROPS, 0, 1, 1, 1))
            .withMessageStartingWith("Released more often than requested");
        // Requested again: loads again, in a reused entry.
        world.request(PROPS, 0, 1, 1, 1);
        awaitLoads();
        assertThat(loader.loads).hasValue(2);
        assertThat(world.loadedCount()).isEqualTo(1);
    }

    @Test
    void failedLoadsStayFailedUntilReleasedThenRetry() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        world.register(PROPS, new CellLoader<>() {
            @Override
            public Props load(Cell cell) throws Exception {
                if (attempts.incrementAndGet() == 1) {
                    throw new java.io.IOException("disk on fire");
                }
                return new Props(0);
            }

            @Override
            public void attach(Props data, CellContent content) {
                content.create();
            }
        });
        world.request(PROPS, 0, 0, 0, 0);
        awaitLoads();
        assertThat(world.state(PROPS, 0, 0, 0, 0)).isEqualTo(CellState.FAILED);
        assertThat(world.failedCount()).isEqualTo(1);
        world.request(PROPS, 0, 0, 0, 0); // still failed: no retry while requested
        awaitLoads();
        assertThat(attempts).hasValue(1);
        world.release(PROPS, 0, 0, 0, 0);
        world.release(PROPS, 0, 0, 0, 0);
        assertThat(world.failedCount()).isZero();
        world.request(PROPS, 0, 0, 0, 0);
        awaitLoads();
        assertThat(world.state(PROPS, 0, 0, 0, 0)).isEqualTo(CellState.LOADED);
        assertThat(scene.size()).isEqualTo(1); // detach by default removes the content
        world.release(PROPS, 0, 0, 0, 0);
        assertThat(scene.size()).isZero();
    }

    @Test
    void releasingWhileLoadingCancelsOrDiscards() throws InterruptedException {
        Layer<Props> serial = Layer.of("serial props", Props.class, Levels.single(10), LaneSpec.serial("props-io"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        PropLoader loader = new PropLoader() {
            @Override
            public Props load(Cell cell) {
                if (cell.x() == 0) {
                    started.countDown();
                    try {
                        proceed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.load(cell);
            }
        };
        world.register(serial, loader);
        world.request(serial, 0, 0, 0, 0); // runs and blocks
        world.request(serial, 0, 1, 0, 0); // queued behind it on the serial lane
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        world.release(serial, 0, 1, 0, 0); // queued: cancelled, never loads
        world.release(serial, 0, 0, 0, 0); // running: its result is discarded when it arrives
        assertThat(world.loadingCount()).isZero();
        world.request(serial, 0, 2, 0, 0); // reuses a freed entry while the old load still runs
        proceed.countDown();
        awaitLoads();
        assertThat(loader.events).containsExactlyInAnyOrder("discard 2", "attach 0:2,0,0");
        assertThat(loader.loads).hasValue(2); // cells 0 and 2, not the cancelled cell 1
        assertThat(world.state(serial, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.state(serial, 0, 2, 0, 0)).isEqualTo(CellState.LOADED);
        assertThat(serial.lane().name()).isEqualTo("props-io");
    }

    @Test
    void releasedLoadsThatFailOrUseTheDefaultDiscardAreDropped() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);
        world.register(PROPS, new CellLoader<>() {
            @Override
            public Props load(Cell cell) throws Exception {
                started.countDown();
                proceed.await();
                if (cell.x() == 1) {
                    throw new java.io.IOException("gone");
                }
                return new Props(1);
            }

            @Override
            public void attach(Props data, CellContent content) {
                content.create();
            }
        });
        world.request(PROPS, 0, 0, 0, 0);
        world.request(PROPS, 0, 1, 0, 0);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        world.release(PROPS, 0, 0, 0, 0); // will succeed: discarded by the default discard
        world.release(PROPS, 0, 1, 0, 0); // will fail: nothing to discard
        proceed.countDown();
        awaitLoads();
        assertThat(world.failedCount()).isZero();
        assertThat(scene.size()).isZero();
    }

    @Test
    void misuseIsReported() {
        assertThatIllegalArgumentException().isThrownBy(() -> world.request(PROPS, 0, 0, 0, 0))
            .withMessage("layer props is not registered");
        world.register(PROPS, new PropLoader());
        assertThatIllegalStateException().isThrownBy(() -> world.register(PROPS, new PropLoader()))
            .withMessage("layer props is already registered");
        assertThatIllegalArgumentException().isThrownBy(() -> world.request(PROPS, 3, 0, 0, 0))
            .withMessage("layer props has no level 3");
        assertThatIllegalArgumentException().isThrownBy(() -> world.state(PROPS, -1, 0, 0, 0));
        assertThat(world.state(PROPS, 0, 9, 9, 9)).isEqualTo(CellState.ABSENT);
        assertThat(world.data(PROPS, 0, 9, 9, 9)).isNull();
        assertThat(world.content(PROPS, 0, 9, 9, 9)).isNull();
    }

    @Test
    void closingUnloadsEverything() throws InterruptedException {
        PropLoader loader = new PropLoader();
        world.register(PROPS, loader);
        for (int i = 0; i < 40; i++) { // more cells than the initial entry arrays hold
            world.request(PROPS, 0, i, 0, 0);
        }
        awaitLoads();
        assertThat(world.loadedCount()).isEqualTo(40);
        assertThat(scene.size()).isEqualTo(120);
        world.request(PROPS, 0, 100, 0, 0); // still loading when closed
        world.close();
        world.close(); // twice is fine
        assertThat(scene.size()).isZero();
        assertThat(world.loadedCount()).isZero();
        assertThat(world.loadingCount()).isZero();
        assertThatIllegalStateException().isThrownBy(() -> world.request(PROPS, 0, 0, 0, 0))
            .withMessage("The world is closed");
        assertThatThrownBy(() -> world.register(Layer.of("x", Props.class, Levels.single(1)), new PropLoader()))
            .isInstanceOf(IllegalStateException.class);
        tasks.drainCompleted(); // late deliveries of the cancelled load are ignored
    }

    @Test
    void knownCellsAreRequestedAndReleasedWithoutAllocating() throws InterruptedException {
        world.register(PROPS, new PropLoader());
        world.request(PROPS, 0, 5, 5, 5);
        awaitLoads();
        assertAllocationFree(() -> {
            world.request(PROPS, 0, 5, 5, 5);
            world.state(PROPS, 0, 5, 5, 5);
            world.data(PROPS, 0, 5, 5, 5);
            world.release(PROPS, 0, 5, 5, 5);
        });
        assertThat(tasks.pending()).isZero();
    }
}
