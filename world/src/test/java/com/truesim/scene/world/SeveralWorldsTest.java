package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Several worlds in one process, e.g. a server hosting many places or instances: each a world on a scene of its own,
 * with its own clock, cells, sources, features and spatial index. They share only what is declared once (layers,
 * profiles, features, loaders without state) and, if they like, a task scheduler.
 */
class SeveralWorldsTest {

    /** A cell's data: where its prop stands. */
    record Prop(double x, double z) {
    }

    private static final Layer<Prop> PROPS = Layer.of("props", Prop.class, Levels.single(10));
    private static final Feature<Void> NIGHT = Feature.toggle("night");
    private static final InterestProfile PLAYER = InterestProfile.builder()
        .layer(PROPS, Detail.full(), Range.radius(12)).build();

    /** One prop per cell at its corner, a unit box: no state of its own, so one loader serves every world. */
    private static final class PropLoader implements CellLoader<Prop> {
        final AtomicInteger loads = new AtomicInteger();

        @Override
        public Prop load(Cell cell) {
            loads.incrementAndGet();
            return new Prop(cell.minX(), cell.minZ());
        }

        @Override
        public void attach(Prop prop, CellContent content) {
            long node = content.create();
            content.scene().setTranslation(node, prop.x(), 0, prop.z());
            content.scene().setLocalBounds(node, 0, 0, 0, 1, 1, 1);
        }
    }

    /** A world and what goes with it. */
    private static final class Place implements AutoCloseable {
        final ChangeClock clock = new ChangeClock();
        final SceneGraph scene = new SceneGraph(clock);
        final SpatialIndex index = new SpatialIndex(scene);
        final World world;
        final InterestSource player;

        Place(TaskScheduler tasks, PropLoader loader, double x) {
            world = new World(scene, tasks);
            world.register(PROPS, loader);
            player = world.addInterest(PLAYER, Priority.NORMAL);
            player.moveTo(x, 0, 5);
        }

        /** A frame: what the world wants, what finished loading, the scene and its index. */
        void frame(TaskScheduler tasks) {
            world.update();
            tasks.drainCompleted();
            scene.update();
            index.update();
        }

        int propsNear(double x) {
            return index.queryAabb(x - 15, -1, -15, x + 15, 2, 25, -1, node -> true);
        }

        @Override
        public void close() {
            world.close();
            index.close();
        }
    }

    /** Frames until every place has loaded what its player wants. */
    private static void settle(TaskScheduler tasks, Place... places) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            boolean ready = true;
            for (Place place : places) {
                place.frame(tasks);
                ready &= place.world.isReady(place.player) && place.world.loadingCount() == 0;
            }
            if (ready) {
                return;
            }
            assertThat(System.nanoTime()).as("loads finish").isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    @Test
    void worldsSideBySideKeepTheirCellsClocksIdsAndFeaturesApart() throws InterruptedException {
        PropLoader loader = new PropLoader();
        try (TaskScheduler tasks = new TaskScheduler();
             Place town = new Place(tasks, loader, 5);
             Place dungeon = new Place(tasks, loader, 1005)) {
            settle(tasks, town, dungeon);
            // Cells: each world holds the 9 cells around its own player, none of the other's.
            assertThat(town.world.loadedCount()).isEqualTo(9);
            assertThat(dungeon.world.loadedCount()).isEqualTo(9);
            assertThat(town.world.state(PROPS, 0, 0, 0, 0)).isEqualTo(CellState.LOADED);
            assertThat(dungeon.world.state(PROPS, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
            assertThat(dungeon.world.state(PROPS, 0, 100, 0, 0)).isEqualTo(CellState.LOADED);
            assertThat(town.world.state(PROPS, 0, 100, 0, 0)).isEqualTo(CellState.ABSENT);
            assertThat(loader.loads).hasValue(18);
            // Scenes and indexes: each finds its own props only.
            assertThat(town.scene.size()).isEqualTo(9);
            assertThat(town.propsNear(5)).isEqualTo(9);
            assertThat(town.propsNear(1005)).isZero();
            assertThat(dungeon.propsNear(1005)).isEqualTo(9);
            assertThat(dungeon.propsNear(5)).isZero();
            // Ids: each scene numbers its nodes from the start, so equal handles name different nodes in each world.
            List<Long> townNodes = new ArrayList<>();
            List<Long> dungeonNodes = new ArrayList<>();
            town.scene.forEach(townNodes::add);
            dungeon.scene.forEach(dungeonNodes::add);
            assertThat(townNodes).containsExactlyInAnyOrderElementsOf(dungeonNodes);
            long townFirst = townNodes.getFirst();
            assertThat(town.scene.world(townFirst).translation().x())
                .isNotEqualTo(dungeon.scene.world(townFirst).translation().x());
            // Clocks and change logs: changes in one world stamp and log there only.
            long townTime = town.clock.now();
            long dungeonSince = dungeon.clock.now();
            town.scene.setTranslation(townFirst, -5, 0, -5);
            town.scene.update();
            assertThat(town.clock.now()).isGreaterThan(townTime);
            assertThat(dungeon.clock.now()).isEqualTo(dungeonSince);
            List<Long> dungeonChanges = new ArrayList<>();
            assertThat(dungeon.scene.changesSince(dungeonSince, (node, changes) -> dungeonChanges.add(node))).isTrue();
            assertThat(dungeonChanges).isEmpty();
            // Features: each world has its own states.
            town.world.features().enable(NIGHT);
            assertThat(town.world.features().isOn(NIGHT)).isTrue();
            assertThat(dungeon.world.features().isOn(NIGHT)).isFalse();
            // Streaming: the dungeon's player leaves; only the dungeon's cells go.
            dungeon.player.close();
            settle(tasks, town, dungeon);
            assertThat(dungeon.world.loadedCount()).isZero();
            assertThat(dungeon.scene.size()).isZero();
            assertThat(town.world.loadedCount()).isEqualTo(9);
            // Owners: each world is owned by the thread its clock names, the others unaffected.
            Thread other = new Thread(() -> { });
            dungeon.clock.ownedBy(other);
            assertThatIllegalStateException().isThrownBy(dungeon.world::update);
            town.frame(tasks); // the town still runs here
            dungeon.clock.ownedBy(null);
        }
    }

    @Test
    void worldsRunOnThreadsOfTheirOwnInParallel() throws InterruptedException {
        // A server hosting places on threads of their own: each place, its scheduler and its clock owned by its thread.
        PropLoader loader = new PropLoader();
        int places = 4;
        int[] loaded = new int[places];
        int[] props = new int[places];
        Throwable[] failures = new Throwable[places];
        Thread[] threads = new Thread[places];
        for (int p = 0; p < places; p++) {
            int i = p;
            threads[p] = new Thread(() -> {
                try (TaskScheduler tasks = new TaskScheduler(); Place place = new Place(tasks, loader, 1000.0 * i + 5)) {
                    place.clock.ownedBy(Thread.currentThread());
                    settle(tasks, place);
                    loaded[i] = place.world.loadedCount();
                    props[i] = place.propsNear(1000.0 * i + 5);
                } catch (Throwable e) {
                    failures[i] = e;
                }
            }, "place-" + p);
            threads[p].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(failures).containsOnlyNulls();
        assertThat(loaded).containsOnly(9);
        assertThat(props).containsOnly(9);
        assertThat(loader.loads).hasValue(9 * places);
    }
}
