package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.CellGrid;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Levels with steps other than 2. */
class LevelSizesTest {

    private static final Layer<String> TERRAIN = Layer.of("terrain", String.class, Levels.sizes(10, 40, 160, 2560));

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final TaskScheduler tasks = new TaskScheduler();
    private final World world = new World(scene, tasks);

    LevelSizesTest() {
        world.register(TERRAIN, new CellLoader<>() {
            @Override
            public String load(Cell cell) {
                return "";
            }

            @Override
            public void attach(String data, CellContent content) {
            }
        });
    }

    @AfterEach
    void stop() {
        world.close();
        tasks.close();
    }

    @Test
    void sizesAreWholeMultiples() {
        Levels levels = Levels.sizes(10, 40, 160, 2560);
        assertThat(levels.count()).isEqualTo(4);
        assertThat(levels.kind()).isEqualTo(Levels.Kind.GRID);
        assertThat(levels.ratio(0)).isEqualTo(1);
        assertThat(levels.ratio(1)).isEqualTo(4);
        assertThat(levels.ratio(3)).isEqualTo(16);
        assertThat(levels.scale(3)).isEqualTo(256);
        assertThat(levels.grid(3).size()).isEqualTo(2560);
        assertThat(Levels.hlodSizes(32, 96).kind()).isEqualTo(Levels.Kind.HLOD);
        assertThat(Levels.hlodSizes(32, 96).ratio(1)).isEqualTo(3);
        assertThat(levels.volumetric().ratio(2)).isEqualTo(4);
        assertThat(Levels.grid(8, 3).ratio(2)).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> Levels.sizes(10, 15)); // less than twice
        assertThatIllegalArgumentException().isThrownBy(() -> Levels.sizes(10, 25)); // not a whole multiple
        assertThatIllegalArgumentException().isThrownBy(() -> Levels.sizes(10, 10)); // not larger
        assertThatIllegalArgumentException().isThrownBy(() -> Levels.sizes(10, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> Levels.sizes(1, 2048)); // more than 1024 times
        assertThatIllegalArgumentException().isThrownBy(Levels::sizes);
        assertThatIllegalArgumentException().isThrownBy(() -> Levels.sizes(new double[Levels.MAX_LEVELS + 1]));
        assertThatIllegalArgumentException().isThrownBy(() -> levels.ratio(4));
        assertThatIllegalArgumentException().isThrownBy(() -> levels.scale(-1));
    }

    @Test
    void unevenStepsStillCoverTheAreaOnce() {
        InterestSource camera = world.addInterest(
            InterestProfile.builder().layer(TERRAIN, Detail.bands(25, 100, 500, 3000)).build(), Priority.HIGH);
        camera.moveTo(-3, 0, 7); // negative coordinates: cells divide rounding down
        world.update();
        List<double[]> cells = new ArrayList<>();
        for (int level = 0; level < 4; level++) {
            LongIntMap wanted = camera.wanted[0][level];
            double size = TERRAIN.levels().grid(level).size();
            for (int slot = 0; slot < wanted.capacity(); slot++) {
                long key = wanted.keyAt(slot);
                if (key >= 0) {
                    cells.add(new double[] {CellGrid.keyX(key) * size, CellGrid.keyZ(key) * size, size});
                }
            }
        }
        assertThat(camera.cellCount(TERRAIN, 0)).isPositive();
        assertThat(camera.cellCount(TERRAIN, 3)).isPositive();
        for (double px = -400; px <= 400; px += 7.3) {
            for (double pz = -400; pz <= 400; pz += 7.3) {
                int covering = 0;
                for (double[] cell : cells) {
                    if (px >= cell[0] && px < cell[0] + cell[2] && pz >= cell[1] && pz < cell[1] + cell[2]) {
                        covering++;
                    }
                }
                assertThat(covering).as("cells covering %s, %s", px, pz).isEqualTo(1);
            }
        }
    }

    @Test
    void swapsAcrossNegativeCellsHoldUntilReplaced() throws InterruptedException {
        InterestSource camera = world.addInterest(
            InterestProfile.builder().layer(TERRAIN, Detail.bands(25, 100, 500, 3000)).build(), Priority.HIGH);
        camera.moveTo(-1_000, 0, -1_000);
        settle();
        camera.moveTo(-5, 0, -5);
        settle();
        assertThat(world.state(TERRAIN, 0, -1, 0, -1)).isEqualTo(CellState.LOADED);
        camera.moveTo(-1_000, 0, -1_000);
        settle();
        assertThat(world.state(TERRAIN, 0, -1, 0, -1)).isEqualTo(CellState.ABSENT);
        assertThat(world.isReady(camera)).isTrue();
    }

    private void settle() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            world.update();
            tasks.drainCompleted();
            world.update();
            Thread.sleep(2);
            assertThat(System.nanoTime()).isLessThan(deadline);
        } while (world.loadingCount() > 0);
    }
}
