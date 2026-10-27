package com.truesim.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.SceneBatch;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.SpatialIndex;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BuildingBlocksTest {

    @Test
    void levelsDoubleTheirCells() {
        Levels grid = Levels.grid(64, 6);
        assertThat(grid.kind()).isEqualTo(Levels.Kind.GRID);
        assertThat(grid.count()).isEqualTo(6);
        assertThat(grid.grid(0).size()).isEqualTo(64);
        assertThat(grid.grid(5).size()).isEqualTo(2048);
        assertThat(Levels.hlod(32, 4).kind()).isEqualTo(Levels.Kind.HLOD);
        Levels single = Levels.single(128);
        assertThat(single.kind()).isEqualTo(Levels.Kind.SINGLE);
        assertThat(single.count()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> Levels.grid(1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> Levels.grid(1, Levels.MAX_LEVELS + 1));
        assertThatIllegalArgumentException().isThrownBy(() -> grid.grid(6)).withMessage("No level 6 in 6 levels");
        assertThatIllegalArgumentException().isThrownBy(() -> grid.grid(-1));
    }

    @Test
    void layersAreTypedKeys() {
        Layer<String> layer = Layer.of("names", String.class, Levels.single(10));
        assertThat(layer.name()).isEqualTo("names");
        assertThat(layer.type()).isEqualTo(String.class);
        assertThat(layer.lane()).isSameAs(Layer.LOADING);
        assertThat(layer.levels().count()).isEqualTo(1);
        assertThat(layer).hasToString("layer names").isNotEqualTo(Layer.of("names", String.class, Levels.single(10)));
        Cell cell = new Cell(layer, 0, -1, 2, 3);
        assertThat(cell.size()).isEqualTo(10);
        assertThat(cell.minX()).isEqualTo(-10);
        assertThat(cell.minY()).isEqualTo(20);
        assertThat(cell.minZ()).isEqualTo(30);
        assertThat(cell.grid()).isSameAs(layer.levels().grid(0));
    }

    @Test
    void contentRemovesWhatItAdded() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        CellContent content = new CellContent(scene, 3);
        long root = content.create();
        content.create(root);
        long outside = scene.create();
        long adopted = content.adopt(scene.create());
        for (int i = 0; i < 20; i++) { // past the initial capacity
            content.create();
        }
        assertThat(content.nodeCount()).isEqualTo(23);
        assertThat(content.node(0)).isEqualTo(root);
        assertThat(content.node(2)).isEqualTo(adopted);
        assertThat(content.group()).isEqualTo(3);
        assertThat(scene.group(adopted)).isEqualTo(3);
        assertThat(scene.group(outside)).isZero();
        assertThatThrownBy(() -> content.node(23)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> content.node(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        scene.remove(content.node(content.nodeCount() - 1)); // removed by the game already: skipped
        content.removeAll(); // the child goes with its parent first
        assertThat(content.nodeCount()).isZero();
        assertThat(scene.size()).isEqualTo(1);
        assertThat(scene.contains(outside)).isTrue();
    }

    @Test
    void removalsWrapAroundTheTable() {
        // Keys whose home is the last slot of a 16-slot table spill into slots 0, 1, ...; removing them must wrap.
        List<Long> last = new ArrayList<>();
        List<Long> first = new ArrayList<>();
        for (long key = 0; last.size() < 3 || first.size() < 2; key++) {
            int home = LongIntMap.slot(key, 15);
            if (home == 15 && last.size() < 3) {
                last.add(key);
            } else if (home == 0 && first.size() < 2) {
                first.add(key);
            }
        }
        LongIntMap map = new LongIntMap();
        map.put(last.get(0), 0); // slot 15
        map.put(last.get(1), 1); // wraps to 0
        map.put(first.get(0), 2); // home 0, pushed to 1
        map.put(last.get(2), 3); // wraps to 2
        map.put(first.get(1), 4); // home 0, pushed to 3
        map.remove(last.get(0)); // hole at 15: entries from 0 on shift back across the wrap
        assertThat(map.get(last.get(1))).isEqualTo(1);
        assertThat(map.get(first.get(0))).isEqualTo(2);
        assertThat(map.get(last.get(2))).isEqualTo(3);
        assertThat(map.get(first.get(1))).isEqualTo(4);
        map.remove(first.get(0));
        map.remove(last.get(1));
        assertThat(map.get(last.get(2))).isEqualTo(3);
        assertThat(map.get(first.get(1))).isEqualTo(4);
        assertThat(map.size()).isEqualTo(2);
        map.remove(12345L); // absent
        assertThat(map.size()).isEqualTo(2);
    }

    @Test
    void wrappedEntriesWhoseHomeIsAfterTheHoleStay() {
        long at14 = -1;
        List<Long> at15 = new ArrayList<>();
        for (long key = 0; at14 < 0 || at15.size() < 2; key++) {
            int home = LongIntMap.slot(key, 15);
            if (home == 14 && at14 < 0) {
                at14 = key;
            } else if (home == 15 && at15.size() < 2) {
                at15.add(key);
            }
        }
        LongIntMap map = new LongIntMap();
        map.put(at14, 0); // slot 14
        map.put(at15.get(0), 1); // slot 15
        map.put(at15.get(1), 2); // wraps to 0
        map.remove(at14); // hole at 14: both home-15 entries stay where they are
        assertThat(map.get(at15.get(0))).isEqualTo(1);
        assertThat(map.get(at15.get(1))).isEqualTo(2);
        assertThat(map.get(at14)).isEqualTo(-1);
    }

    @Test
    void contentAppliesPreparedBatches() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        SpatialIndex index = new SpatialIndex(scene);
        CellContent content = new CellContent(scene, 4);
        content.create();
        SceneBatch small = new SceneBatch();
        small.add();
        content.apply(small); // fits as it is
        SceneBatch batch = new SceneBatch();
        for (int i = 0; i < 20; i++) { // past the content's initial capacity
            int node = batch.add();
            batch.setTranslation(node, i, 0, 0);
            batch.setLocalBounds(node, 0, 0, 0, 1, 1, 1);
            batch.setMobility(node, Mobility.STATIC);
        }
        batch.prepare();
        content.apply(batch);
        assertThat(content.nodeCount()).isEqualTo(22);
        assertThat(scene.group(content.node(6))).isEqualTo(4);
        scene.update();
        index.update();
        assertThat(index.staticCount()).isEqualTo(20);
        content.removeAll();
        scene.update();
        index.update();
        assertThat(scene.size()).isZero();
        assertThat(index.staticCount()).isZero();
    }

    @Test
    void longIntMapMatchesAHashMap() {
        LongIntMap map = new LongIntMap();
        Map<Long, Integer> expected = new HashMap<>();
        Random random = new Random(42);
        for (int step = 0; step < 200_000; step++) {
            long key = random.nextInt(5_000) * 0x10001L; // collisions and long probe runs
            switch (random.nextInt(3)) {
                case 0, 1 -> {
                    map.put(key, step);
                    expected.put(key, step);
                }
                default -> {
                    map.remove(key);
                    expected.remove(key);
                }
            }
            if (step % 1_000 == 0) {
                for (int k = 0; k < 5_000; k++) {
                    long probe = k * 0x10001L;
                    assertThat(map.get(probe)).isEqualTo(expected.getOrDefault(probe, -1));
                }
                assertThat(map.size()).isEqualTo(expected.size());
            }
        }
    }
}
