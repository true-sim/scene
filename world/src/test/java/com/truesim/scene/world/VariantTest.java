package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Cells that depend on features reload when those change, and swap in without a gap. */
class VariantTest {

    enum Chicane { TIGHT, WIDE, BROKEN }

    private static final Feature<Chicane> CHICANE = Feature.of("chicane", Chicane.class, Chicane.TIGHT);
    private static final Feature<Void> NIGHT_LIGHTS = Feature.toggle("night-lights");
    private static final Feature<Double> FLOODLIGHTS = Feature.live("floodlights", Double.class, 1.0);
    private static final Variant GRAND_PRIX = Variant.builder("grand-prix").enable(CHICANE, Chicane.WIDE).build();

    private static final Layer<String> TRACK = Layer.of("track", String.class, Levels.single(100));

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final TaskScheduler tasks = new TaskScheduler();
    private final World world = new World(scene, tasks);
    private final List<String> attached = new CopyOnWriteArrayList<>();
    private final List<String> detached = new CopyOnWriteArrayList<>();
    private final List<String> discarded = new CopyOnWriteArrayList<>();
    private final Map<Integer, AtomicInteger> loads = new ConcurrentHashMap<>();
    private volatile CountDownLatch gate;

    /**
     * Cell 0 is the chicane: its data is the chicane's parameter, or "plain" while it is off, and it fails while
     * broken. Cell 1 has night lights while they are on. Cell 2 reads no features, only the live floodlights.
     */
    VariantTest() {
        world.register(TRACK, new CellLoader<>() {
            @Override
            public String load(Cell cell, FeatureView features) throws InterruptedException {
                loads.computeIfAbsent(cell.x(), x -> new AtomicInteger()).incrementAndGet();
                CountDownLatch wait = gate;
                if (wait != null) {
                    assertThat(wait.await(10, TimeUnit.SECONDS)).isTrue();
                }
                return switch (cell.x()) {
                    case 0 -> {
                        if (features.value(CHICANE) == Chicane.BROKEN) {
                            throw new IllegalStateException("No such chicane");
                        }
                        yield features.isOn(CHICANE) ? "0:" + features.value(CHICANE) : "0:plain";
                    }
                    case 1 -> features.isOn(NIGHT_LIGHTS) ? "1:lit" : "1:dark";
                    default -> cell.x() + ":" + features.value(FLOODLIGHTS);
                };
            }

            @Override
            public void attach(String data, CellContent content) {
                attached.add(data);
                content.create();
            }

            @Override
            public void detach(String data, CellContent content) {
                detached.add(data);
                content.removeAll();
            }

            @Override
            public void discard(String data) {
                discarded.add(data);
            }
        });
    }

    @AfterEach
    void stop() {
        world.close();
        tasks.close();
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    /** Runs frames until {@code condition} holds. */
    private void frames(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            world.update();
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    private int loads(int cell) {
        return loads.getOrDefault(cell, new AtomicInteger()).get();
    }

    private void loadAll(int cells) throws InterruptedException {
        for (int x = 0; x < cells; x++) {
            world.request(TRACK, 0, x, 0, 0);
        }
        await(() -> world.loadedCount() + world.failedCount() == cells);
    }

    @Test
    void changingAFeatureReloadsExactlyTheCellsThatReadIt() throws InterruptedException {
        world.features().select(GRAND_PRIX);
        world.update();
        loadAll(3);
        assertThat(attached).containsExactlyInAnyOrder("0:WIDE", "1:dark", "2:1.0");
        scene.update();
        attached.clear();

        world.features().set(CHICANE, Chicane.TIGHT);
        world.update();
        assertThat(world.reloadingCount()).isEqualTo(1);
        assertThat(world.content(TRACK, 0, 0, 0, 0).nodeCount()).isEqualTo(1); // the old content stays meanwhile
        await(() -> world.reloadingCount() == 0);
        assertThat(detached).containsExactly("0:WIDE");
        assertThat(attached).containsExactly("0:TIGHT"); // swapped in the same frame
        assertThat(world.data(TRACK, 0, 0, 0, 0)).isEqualTo("0:TIGHT");
        assertThat(scene.size()).isEqualTo(3);
        assertThat(loads(1)).isEqualTo(1);
        assertThat(loads(2)).isEqualTo(1);

        world.features().enable(NIGHT_LIGHTS);
        world.features().set(FLOODLIGHTS, 0.2); // live: no reload
        world.update();
        await(() -> world.reloadingCount() == 0);
        assertThat(world.data(TRACK, 0, 1, 0, 0)).isEqualTo("1:lit");
        assertThat(loads(0)).isEqualTo(2);
        assertThat(loads(2)).isEqualTo(1);

        world.features().select(GRAND_PRIX); // the night lights go off again, the chicane goes back to wide
        world.update();
        await(() -> world.reloadingCount() == 0);
        assertThat(world.data(TRACK, 0, 0, 0, 0)).isEqualTo("0:WIDE");
        assertThat(world.data(TRACK, 0, 1, 0, 0)).isEqualTo("1:dark");
        world.update(); // nothing pending
        assertThat(world.reloadingCount()).isZero();
    }

    @Test
    void liveFeaturesReachListeners() throws InterruptedException {
        List<Double> heard = new CopyOnWriteArrayList<>();
        world.features().listen(FLOODLIGHTS, (feature, on, value) -> heard.add(value));
        loadAll(3);
        world.features().set(FLOODLIGHTS, 0.6);
        assertThat(heard).isEmpty(); // applied on the next update
        world.update();
        assertThat(heard).containsExactly(0.6);
        assertThat(world.reloadingCount()).isZero();
    }

    @Test
    void loadsThatReadAFeatureChangedMeanwhileLoadAgain() throws InterruptedException {
        gate = new CountDownLatch(1);
        world.request(TRACK, 0, 0, 0, 0);
        await(() -> loads(0) == 1); // running with the chicane off
        world.features().enable(CHICANE, Chicane.WIDE);
        world.update(); // the running load is checked when it is delivered
        gate.countDown();
        gate = null;
        await(() -> world.loadedCount() == 1);
        assertThat(discarded).containsExactly("0:plain");
        assertThat(attached).containsExactly("0:WIDE");
        assertThat(loads(0)).isEqualTo(2);
    }

    @Test
    void failedLoadsThatReadAChangedFeatureLoadAgain() throws InterruptedException {
        world.features().enable(CHICANE, Chicane.BROKEN);
        world.update();
        loadAll(1);
        assertThat(world.state(TRACK, 0, 0, 0, 0)).isEqualTo(CellState.FAILED);
        world.features().set(CHICANE, Chicane.TIGHT); // fixed: the failed cell tries again
        world.update();
        assertThat(world.state(TRACK, 0, 0, 0, 0)).isEqualTo(CellState.LOADING);
        assertThat(world.reloadingCount()).isZero(); // loading again, but it had no content
        assertThat(world.failedCount()).isZero();
        await(() -> world.loadedCount() == 1);
        assertThat(world.data(TRACK, 0, 0, 0, 0)).isEqualTo("0:TIGHT");

        // A load failing while a feature it read changes loads again, too.
        gate = new CountDownLatch(1);
        world.features().set(CHICANE, Chicane.BROKEN);
        world.update();
        await(() -> loads(0) == 3);
        world.features().set(CHICANE, Chicane.WIDE);
        world.update();
        gate.countDown();
        gate = null;
        await(() -> world.reloadingCount() == 0);
        assertThat(world.data(TRACK, 0, 0, 0, 0)).isEqualTo("0:WIDE");
        assertThat(detached).containsExactly("0:TIGHT");
    }

    @Test
    void aReloadThatFailsKeepsTheContent() throws InterruptedException {
        world.features().enable(CHICANE);
        world.update();
        loadAll(1);
        world.features().set(CHICANE, Chicane.BROKEN);
        world.update();
        await(() -> world.reloadingCount() == 0);
        assertThat(world.state(TRACK, 0, 0, 0, 0)).isEqualTo(CellState.LOADED);
        assertThat(world.data(TRACK, 0, 0, 0, 0)).isEqualTo("0:TIGHT");
        assertThat(detached).isEmpty();
        // It still depends on the chicane.
        world.features().set(CHICANE, Chicane.WIDE);
        world.update();
        await(() -> world.reloadingCount() == 0);
        assertThat(world.data(TRACK, 0, 0, 0, 0)).isEqualTo("0:WIDE");
    }

    @Test
    void reloadsWaitForTheAttachBudget() throws InterruptedException {
        world.setAttachBudget(1);
        world.update();
        world.request(TRACK, 0, 1, 0, 0);
        await(() -> world.loadedCount() == 1);
        world.request(TRACK, 0, 0, 0, 0); // this frame's budget is spent: it waits
        await(() -> world.waitingCount() == 1);
        // Waiting data that read a changed feature is discarded, and the cell loads again.
        world.features().enable(CHICANE);
        world.update();
        assertThat(discarded).containsExactly("0:plain");
        await(() -> world.loadedCount() == 2);
        assertThat(world.data(TRACK, 0, 0, 0, 0)).isEqualTo("0:TIGHT");

        // Two swaps in one frame: one waits for the next frame.
        discarded.clear();
        world.update();
        world.features().set(CHICANE, Chicane.WIDE);
        world.features().enable(NIGHT_LIGHTS);
        world.update();
        await(() -> world.waitingCount() == 1);
        assertThat(world.reloadingCount()).isEqualTo(1);
        // A change while a swap waits: the waiting data is discarded and it loads again, like the swapped cell.
        world.features().set(CHICANE, Chicane.TIGHT);
        world.features().disable(NIGHT_LIGHTS);
        world.update();
        assertThat(discarded).hasSize(1).containsAnyOf("0:WIDE", "1:lit");
        frames(() -> world.reloadingCount() == 0);
        assertThat(world.data(TRACK, 0, 0, 0, 0)).isEqualTo("0:TIGHT");
        assertThat(world.data(TRACK, 0, 1, 0, 0)).isEqualTo("1:dark");

        // Released while its swap waits: the waiting data is discarded with the cell.
        discarded.clear();
        world.update();
        world.features().set(CHICANE, Chicane.WIDE);
        world.features().enable(NIGHT_LIGHTS);
        world.update();
        await(() -> world.waitingCount() == 1);
        int waiting = "0:TIGHT".equals(world.data(TRACK, 0, 0, 0, 0)) ? 0 : 1;
        world.release(TRACK, 0, waiting, 0, 0);
        assertThat(discarded).containsExactly(waiting == 0 ? "0:WIDE" : "1:lit");
        assertThat(world.waitingCount()).isZero();
        assertThat(world.reloadingCount()).isZero();
    }

    @Test
    void cellsReleasedWhileReloadingDropTheReload() throws InterruptedException {
        world.features().enable(CHICANE);
        world.update();
        loadAll(1);
        gate = new CountDownLatch(1);
        world.features().set(CHICANE, Chicane.WIDE);
        world.update();
        await(() -> loads(0) == 2);
        assertThat(world.reloadingCount()).isEqualTo(1);
        world.release(TRACK, 0, 0, 0, 0);
        assertThat(detached).containsExactly("0:TIGHT");
        assertThat(world.reloadingCount()).isZero();
        gate.countDown();
        gate = null;
        await(() -> discarded.size() == 1); // the running reload finishes, unwanted
        assertThat(discarded).containsExactly("0:WIDE");
        assertThat(scene.size()).isZero();
        world.features().set(CHICANE, Chicane.TIGHT); // nothing left to reload
        world.update();
        assertThat(loads(0)).isEqualTo(2);
    }
}
