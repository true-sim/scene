package com.truesim.scene.world;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.CellGrid;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InterestTest {

    private static final Layer<String> COLLISION = Layer.of("collision", String.class, Levels.single(10));
    private static final Layer<String> TERRAIN = Layer.of("terrain", String.class, Levels.grid(10, 3));
    private static final Layer<String> SPACE = Layer.of("space", String.class, Levels.grid(10, 2).volumetric());

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final TaskScheduler tasks = new TaskScheduler();
    private final World world = new World(scene, tasks);

    /** Loads nothing worth keeping: these tests look at which cells are requested. */
    private static final CellLoader<String> EMPTY = new CellLoader<>() {
        @Override
        public String load(Cell cell) {
            return "";
        }

        @Override
        public void attach(String data, CellContent content) {
        }
    };

    InterestTest() {
        world.register(COLLISION, EMPTY);
        world.register(TERRAIN, EMPTY);
        world.register(SPACE, EMPTY);
    }

    @AfterEach
    void stop() {
        world.close();
        tasks.close();
    }

    /** The cells a source holds of a layer, as "level:x,y,z". */
    private static List<String> cells(InterestSource source, int layer) {
        List<String> cells = new ArrayList<>();
        LongIntMap[] levels = source.wanted[layer];
        for (int level = 0; level < levels.length; level++) {
            for (int slot = 0; slot < levels[level].capacity(); slot++) {
                long key = levels[level].keyAt(slot);
                if (key >= 0) {
                    cells.add(level + ":" + CellGrid.keyX(key) + "," + CellGrid.keyY(key) + "," + CellGrid.keyZ(key));
                }
            }
        }
        return cells;
    }

    @Test
    void fullDetailFollowsTheSourceWithinItsRange() {
        InterestProfile profile = InterestProfile.builder().layer(COLLISION, Detail.full(), Range.radius(12)).build();
        InterestSource player = world.addInterest(profile, Priority.NORMAL);
        assertThat(world.interestCount()).isEqualTo(1);
        player.moveTo(5, 100, 5); // flat layer: height does not matter
        world.update();
        // Cells within 12 of (5, 5): x and z from -1 to 1 (the corners are about 7 away).
        assertThat(cells(player, 0)).hasSize(9).contains("0:-1,0,-1", "0:0,0,0", "0:1,0,1");
        assertThat(world.state(COLLISION, 0, 0, 0, 0)).isNotEqualTo(CellState.ABSENT);
        assertThat(player.cellCount(COLLISION, 0)).isEqualTo(9);
        assertThat(player.cellCount(TERRAIN, 0)).isZero(); // not in its profile
        player.moveTo(5, 100, 5); // not moved: nothing to do
        world.update();
        player.moveTo(5, 0, 5); // height only: still recomputed
        world.update();
        player.moveTo(5, 0, 6); // depth only
        world.update();
        player.moveTo(105, 0, 5);
        world.update();
        assertThat(world.state(COLLISION, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.state(COLLISION, 0, 10, 0, 0)).isNotEqualTo(CellState.ABSENT);
        assertThat(player.x()).isEqualTo(105);
        assertThat(player.y()).isZero();
        assertThat(player.z()).isEqualTo(5);
        assertThat(player.priority()).isEqualTo(Priority.NORMAL);
        assertThat(player.profile()).isSameAs(profile);
    }

    @Test
    void sourcesShareCells() {
        InterestProfile profile = InterestProfile.builder().layer(COLLISION, Detail.full(), Range.radius(4)).build();
        InterestSource a = world.addInterest(profile, Priority.HIGH);
        InterestSource b = world.addInterest(profile, Priority.LOW);
        a.moveTo(5, 0, 5);
        b.moveTo(6, 0, 5);
        world.update();
        assertThat(world.loadingCount() + world.loadedCount()).isEqualTo(1); // one cell, loaded once
        a.close();
        a.close(); // twice is fine
        assertThat(a.isClosed()).isTrue();
        assertThat(world.state(COLLISION, 0, 0, 0, 0)).isNotEqualTo(CellState.ABSENT); // b still needs it
        b.close();
        assertThat(world.state(COLLISION, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.interestCount()).isZero();
    }

    @Test
    void bandsCoverTheAreaOnceFinestNearest() {
        InterestProfile profile = InterestProfile.builder().layer(TERRAIN, Detail.bands(15, 40, 100)).build();
        InterestSource camera = world.addInterest(profile, Priority.HIGH);
        camera.moveTo(3, 0, 7);
        world.update();
        List<String> cells = cells(camera, 0);
        assertThat(cells).anyMatch(c -> c.startsWith("0:")).anyMatch(c -> c.startsWith("1:"))
            .anyMatch(c -> c.startsWith("2:"));
        assertThat(cells).contains("0:0,0,0"); // the source's own cell at the finest level
        // Every point near the source lies in exactly one chosen cell, whatever its level.
        for (double px = -60; px <= 60; px += 1.7) {
            for (double pz = -60; pz <= 60; pz += 1.7) {
                int covering = 0;
                for (String cell : cells) {
                    int level = cell.charAt(0) - '0';
                    String[] xyz = cell.substring(2).split(",");
                    double size = 10 << level;
                    double minX = Integer.parseInt(xyz[0]) * size;
                    double minZ = Integer.parseInt(xyz[2]) * size;
                    if (px >= minX && px < minX + size && pz >= minZ && pz < minZ + size) {
                        covering++;
                    }
                }
                assertThat(covering).as("cells covering %s, %s", px, pz).isEqualTo(1);
            }
        }
        // Moving swaps cells between levels.
        camera.moveTo(203, 0, 7);
        world.update();
        assertThat(world.state(TERRAIN, 0, 0, 0, 0)).isEqualTo(CellState.ABSENT);
        assertThat(world.state(TERRAIN, 0, 20, 0, 0)).isNotEqualTo(CellState.ABSENT);
    }

    @Test
    void volumetricLevelsUseHeightToo() {
        InterestProfile profile = InterestProfile.builder().layer(SPACE, Detail.bands(5, 30)).build();
        InterestSource ship = world.addInterest(profile, Priority.NORMAL);
        ship.moveTo(5, 5, 5);
        world.update();
        List<String> cells = cells(ship, 0);
        // The source's own level-1 cell splits into 8 finer ones; around it, level-1 cells above and below.
        assertThat(cells).contains("0:0,0,0", "0:1,1,1", "1:0,-1,0", "1:0,1,0");
        assertThat(SPACE.levels().isVolumetric()).isTrue();
        assertThat(TERRAIN.levels().isVolumetric()).isFalse();
    }

    @Test
    void profilesAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> InterestProfile.builder().layer(COLLISION, Detail.full()))
            .withMessage("Full detail of layer collision needs a range");
        assertThatIllegalArgumentException().isThrownBy(() -> InterestProfile.builder()
            .layer(TERRAIN, Detail.bands(10), Range.radius(5))).withMessage("Bands of layer terrain carry their own range");
        assertThatIllegalArgumentException().isThrownBy(() -> InterestProfile.builder()
            .layer(TERRAIN, Detail.bands(1, 2, 3, 4))).withMessageContaining("needs 4 levels");
        assertThatIllegalArgumentException().isThrownBy(() -> InterestProfile.builder()
            .layer(TERRAIN, Detail.bands(1)).layer(TERRAIN, Detail.bands(2)))
            .withMessage("layer terrain is already in this profile");
        assertThatIllegalArgumentException().isThrownBy(Detail::bands);
        assertThatIllegalArgumentException().isThrownBy(() -> Detail.bands(0));
        assertThatIllegalArgumentException().isThrownBy(() -> Detail.bands(5, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> Range.radius(0));
        assertThatIllegalArgumentException().isThrownBy(() -> Range.radius(Double.POSITIVE_INFINITY));
        assertThat(Detail.full()).hasToString("full");
        assertThat(Detail.bands(1, 2)).hasToString("bands [1.0, 2.0]");
        InterestProfile unknown = InterestProfile.builder()
            .layer(Layer.of("unregistered", String.class, Levels.single(1)), Detail.full(), Range.radius(1)).build();
        assertThatIllegalArgumentException().isThrownBy(() -> world.addInterest(unknown, Priority.LOW));
        assertThat(unknown.entries()).hasSize(1);
    }

    @Test
    void closingTheWorldClosesItsSources() {
        InterestProfile profile = InterestProfile.builder().layer(COLLISION, Detail.full(), Range.radius(30)).build();
        InterestSource source = world.addInterest(profile, Priority.NORMAL);
        world.update();
        assertThat(world.loadingCount() + world.loadedCount()).isPositive();
        world.close();
        assertThat(source.isClosed()).isTrue();
        assertThat(world.loadingCount() + world.loadedCount()).isZero();
    }

    @Test
    void movingWithinTheSameCellsAllocatesNothing() {
        InterestProfile profile = InterestProfile.builder()
            .layer(TERRAIN, Detail.bands(15, 40, 100))
            .layer(COLLISION, Detail.full(), Range.radius(20))
            .build();
        InterestSource camera = world.addInterest(profile, Priority.HIGH);
        camera.moveTo(3, 0, 7); // clear of band boundaries (see the hysteresis note in #7)
        world.update();
        double[] wobble = {0};
        assertAllocationFree(() -> {
            wobble[0] = wobble[0] == 0 ? 0.01 : 0; // tiny moves: recomputed, same cells
            camera.moveTo(3 + wobble[0], 0, 7);
            world.update();
        });
    }
}
