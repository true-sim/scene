package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Aabb;
import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Batches built away from the scene, applied with their world data and static tree. */
class SceneBatchTest {

    private static final int ALL = -1;

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock);
    private final SpatialIndex index = new SpatialIndex(scene);

    /** A batch of a root at (x, 0, z) with {@code props} static unit boxes under it, 2 m apart along x. */
    private static SceneBatch cell(double x, double z, int props) {
        SceneBatch batch = new SceneBatch(2);
        int root = batch.add();
        batch.setTranslation(root, x, 0, z);
        for (int i = 0; i < props; i++) {
            int prop = batch.add(root);
            batch.setTranslation(prop, i * 2, 0, 0);
            batch.setLocalBounds(prop, -0.5, 0, -0.5, 0.5, 1, 0.5);
            batch.setMobility(prop, Mobility.STATIC);
        }
        return batch;
    }

    private void update() {
        scene.update();
        index.update();
    }

    private List<Long> inBox(double minX, double maxX) {
        List<Long> found = new ArrayList<>();
        index.queryAabb(minX, -1, -1000, maxX, 2, 1000, ALL, node -> found.add(node));
        return found;
    }

    @Test
    void batchesStageNodesAndPrepareTheirWorld() {
        SceneBatch batch = cell(100, 50, 3);
        assertThat(batch.size()).isEqualTo(4);
        assertThat(batch.isPrepared()).isFalse();
        batch.prepare();
        assertThat(batch.isPrepared()).isTrue();
        assertThat(batch.staticNodes()).containsExactly(1, 2, 3);
        assertThat(batch.staticTree().primitiveCount()).isEqualTo(3);
        assertThat(batch.staticBox()).containsExactly(99.5, 0, 49.5, 104.5, 1, 50.5);
        assertThat(batch.worldBounds().get(3, 0)).isEqualTo(103.5);
        batch.setLayers(0, 2); // layers do not change the prepared data
        assertThat(batch.isPrepared()).isTrue();
        batch.setScale(1, 2, 2, 2);
        assertThat(batch.isPrepared()).isFalse();
        batch.setRotation(1, 0, 0, 0, 1);
        batch.setLocal(2, Transform.of(Vec3.of(1, 2, 3), Quat.IDENTITY, Vec3.ONE));
        batch.setMobility(3, Mobility.DYNAMIC);
        batch.prepare();
        assertThat(batch.staticNodes()).containsExactly(1, 2);
        SceneBatch empty = new SceneBatch();
        int pivot = empty.add();
        empty.setMobility(pivot, Mobility.STATIC); // static, but without bounds: not in the tree
        empty.prepare();
        assertThat(empty.staticTree()).isNull();
    }

    @Test
    void batchesAreChecked() {
        SceneBatch batch = new SceneBatch();
        assertThatIllegalArgumentException().isThrownBy(() -> new SceneBatch(0));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.add(0)).withMessage("No node 0 in this batch of 0");
        assertThatIllegalArgumentException().isThrownBy(() -> batch.add(-2));
        int node = batch.add();
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setTranslation(1, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setLayers(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setLocalBounds(node, 1, 0, 0, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setLocalBounds(node, 0, 1, 0, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setLocalBounds(node, 0, 0, 1, 1, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.apply(batch, -1, new long[1]));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.apply(batch, 0, new long[0]));
    }

    @Test
    void applyingCopiesNodesWithTheirWorldAndHandsTheTreeOver() {
        SceneBatch batch = cell(100, 50, 3);
        batch.prepare();
        long[] handles = new long[batch.size()];
        scene.apply(batch, 7, handles);
        assertThat(scene.size()).isEqualTo(4);
        assertThat(scene.parent(handles[2])).isEqualTo(handles[0]);
        assertThat(scene.group(handles[2])).isEqualTo(7);
        assertThat(scene.mobility(handles[2])).isEqualTo(Mobility.STATIC);
        assertThat(scene.world(handles[3]).translation()).isEqualTo(Vec3.of(104, 0, 50)); // before any update
        assertThat(scene.worldBounds(handles[3])).isEqualTo(Aabb.of(Vec3.of(103.5, 0, 49.5), Vec3.of(104.5, 1, 50.5)));
        long since = clock.now();
        update();
        List<Integer> changes = new ArrayList<>();
        scene.changesSince(since, (node, c) -> changes.add(c));
        int created = NodeChange.CREATED | NodeChange.TRANSFORM;
        assertThat(changes).containsExactly(created, created | NodeChange.BOUNDS | NodeChange.PREPARED,
            created | NodeChange.BOUNDS | NodeChange.PREPARED, created | NodeChange.BOUNDS | NodeChange.PREPARED);
        assertThat(index.groupTree(7)).isSameAs(batch.staticTree()); // adopted, not rebuilt
        assertThat(index.staticCount()).isEqualTo(3);
        assertThat(index.groupCount()).isEqualTo(1);
        assertThat(inBox(101, 103)).containsExactly(handles[2]);
        SpatialIndex.Hit hit = new SpatialIndex.Hit();
        assertThat(index.raycast(104, 5, 50, 0, -1, 0, 10, ALL, hit)).isTrue();
        assertThat(hit.node()).isEqualTo(handles[3]);
        // Moving an adopted node later rebuilds its group as usual.
        scene.setTranslation(handles[3], 30, 0, 0);
        update();
        assertThat(index.groupTree(7)).isNotSameAs(batch.staticTree());
        assertThat(inBox(129, 131)).containsExactly(handles[3]);
        // Unprepared batches are prepared on apply; dynamic nodes go to the dynamic tree.
        SceneBatch moving = new SceneBatch();
        int car = moving.add();
        moving.setLocalBounds(car, -1, 0, -1, 1, 1, 1);
        long[] carHandle = new long[1];
        scene.apply(moving, 0, carHandle);
        update();
        assertThat(index.dynamicCount()).isEqualTo(1);
        assertThat(inBox(-1, 1)).containsExactly(carHandle[0]);
    }

    @Test
    void changesBeforeTheIndexCatchesUpFallBackToBuilding() {
        SceneBatch first = cell(0, 0, 3);
        long[] a = new long[first.size()];
        scene.apply(first, 1, a);
        scene.setTranslation(a[1], 50, 0, 0); // changed after applying: placed the usual way
        update();
        assertThat(index.groupTree(1)).isNotSameAs(first.staticTree());
        assertThat(inBox(49, 51)).containsExactly(a[1]);
        assertThat(inBox(1, 5)).containsExactlyInAnyOrder(a[2], a[3]);

        SceneBatch second = cell(200, 0, 3);
        long[] b = new long[second.size()];
        scene.apply(second, 2, b);
        scene.remove(b[2]); // gone before the index saw it
        update();
        assertThat(inBox(199, 210)).containsExactlyInAnyOrder(b[1], b[3]);

        SceneBatch third = cell(300, 0, 2);
        long[] c = new long[third.size()];
        scene.apply(third, 1, c); // group 1 is in use: its nodes join it one by one
        update();
        assertThat(index.groupTree(1)).isNotSameAs(third.staticTree());
        assertThat(inBox(299, 303)).containsExactlyInAnyOrder(c[1], c[2]);

        SceneBatch fourth = cell(400, 0, 2);
        long[] d = new long[fourth.size()];
        scene.apply(fourth, 4, d);
        scene.setGroup(d[1], 5); // moved to another group before the index saw it
        update();
        assertThat(inBox(399, 403)).containsExactlyInAnyOrder(d[1], d[2]);
    }

    @Test
    void slotsReusedInTheSameFrameAreHandledInOrder() {
        SceneBatch old = cell(0, 0, 4);
        long[] a = new long[old.size()];
        scene.apply(old, 1, a);
        update();
        scene.remove(a[0]); // the old cell leaves ...
        SceneBatch next = cell(100, 0, 4);
        long[] b = new long[next.size()];
        scene.apply(next, 2, b); // ... and the new one reuses its slots in the same frame
        assertThat(NodeHandle.index(b[0])).isIn(NodeHandle.index(a[0]), NodeHandle.index(a[1]),
            NodeHandle.index(a[2]), NodeHandle.index(a[3]), NodeHandle.index(a[4]));
        update();
        assertThat(index.groupTree(2)).isSameAs(next.staticTree());
        assertThat(inBox(-1, 10)).isEmpty();
        assertThat(inBox(99, 110)).containsExactlyInAnyOrder(b[1], b[2], b[3], b[4]);
        assertThat(index.groupCount()).isEqualTo(1);
    }

    @Test
    void indexesThatMissTheHandOverBuildTheirOwn() {
        SceneBatch batch = cell(0, 0, 3);
        long[] handles = new long[batch.size()];
        scene.apply(batch, 3, handles);
        scene.update();
        scene.update(); // the prepared tree is forgotten after an update the index did not follow
        index.update();
        assertThat(index.groupTree(3)).isNotSameAs(batch.staticTree());
        assertThat(inBox(-1, 5)).hasSize(3);
        // A second index on the same scene: whichever updates first adopts, the other builds.
        SpatialIndex other = new SpatialIndex(scene);
        other.update();
        SceneBatch early = cell(-50, 0, 2);
        scene.apply(early, 8, new long[early.size()]);
        scene.update();
        other.update(); // the second index adopts this one ...
        index.update(); // ... and the first builds it
        assertThat(other.groupTree(8)).isSameAs(early.staticTree());
        SceneBatch more = cell(50, 0, 2);
        long[] moreHandles = new long[more.size()];
        scene.apply(more, 6, moreHandles);
        scene.update();
        index.update();
        other.update();
        assertThat(index.groupTree(6)).isSameAs(more.staticTree());
        assertThat(other.groupTree(6)).isNotSameAs(more.staticTree());
        assertThat(other.staticCount()).isEqualTo(7);
        assertThat(index.groupTree(99)).isNull();
    }
}
