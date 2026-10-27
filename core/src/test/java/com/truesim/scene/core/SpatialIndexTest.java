package com.truesim.scene.core;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.math.Mat4;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Frustum;
import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpatialIndexTest {

    private static final SceneLayer WORLD = SceneLayer.of("world", 0);
    private static final SceneLayer PICKABLE = SceneLayer.of("pickable", 1);
    private static final int ALL = -1;

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock);
    private final SpatialIndex index = new SpatialIndex(scene);

    /** A unit box at (x, y, z). */
    private long box(double x, double y, double z, Mobility mobility) {
        long node = scene.create();
        scene.setLocalBounds(node, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        scene.setTranslation(node, x, y, z);
        scene.setMobility(node, mobility);
        return node;
    }

    private void update() {
        scene.update();
        index.update();
    }

    private List<Long> inBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int mask) {
        List<Long> found = new ArrayList<>();
        int count = index.queryAabb(minX, minY, minZ, maxX, maxY, maxZ, mask, node -> found.add(node));
        assertThat(count).isEqualTo(found.size());
        return found;
    }

    @Test
    void staticAndDynamicNodesAreFoundTogether() {
        long wall = box(0, 0, 0, Mobility.STATIC);
        long car = box(3, 0, 0, Mobility.DYNAMIC);
        long pivot = scene.create(); // no bounds: not indexed
        update();
        assertThat(index.staticCount()).isEqualTo(1);
        assertThat(index.dynamicCount()).isEqualTo(1);
        assertThat(inBox(-10, -10, -10, 10, 10, 10, ALL)).containsExactlyInAnyOrder(wall, car).doesNotContain(pivot);
        assertThat(inBox(2, -1, -1, 4, 1, 1, ALL)).containsExactly(car);
        List<Long> near = new ArrayList<>();
        assertThat(index.querySphere(0, 0, 0, 1, ALL, node -> near.add(node))).isEqualTo(1);
        assertThat(near).containsExactly(wall);
    }

    @Test
    void movingNodesAreFollowed() {
        long wall = box(0, 0, 0, Mobility.STATIC);
        long car = box(3, 0, 0, Mobility.DYNAMIC);
        update();
        scene.setTranslation(car, 50, 0, 0); // refit
        scene.setTranslation(wall, -50, 0, 0); // a static node moving: rebuilt
        update();
        assertThat(inBox(-1, -1, -1, 4, 1, 1, ALL)).isEmpty();
        assertThat(inBox(49, -1, -1, 51, 1, 1, ALL)).containsExactly(car);
        assertThat(inBox(-51, -1, -1, -49, 1, 1, ALL)).containsExactly(wall);
        // Changing mobility moves a node between the indexes.
        scene.setMobility(car, Mobility.STATIC);
        scene.setMobility(wall, Mobility.DYNAMIC);
        update();
        assertThat(index.staticCount()).isEqualTo(1);
        assertThat(index.dynamicCount()).isEqualTo(1);
        assertThat(inBox(-100, -100, -100, 100, 100, 100, ALL)).containsExactlyInAnyOrder(wall, car);
        // Losing bounds or being removed takes a node out.
        scene.clearBounds(car);
        scene.remove(wall);
        update();
        assertThat(index.staticCount()).isZero();
        assertThat(index.dynamicCount()).isZero();
        assertThat(inBox(-100, -100, -100, 100, 100, 100, ALL)).isEmpty();
        // A node without bounds removed, and a node moved then removed before the index caught up.
        long pivot = scene.create();
        long brief = box(7, 7, 7, Mobility.DYNAMIC);
        update();
        scene.setTranslation(brief, 8, 8, 8);
        scene.update();
        scene.remove(brief);
        scene.remove(pivot);
        update();
        assertThat(index.dynamicCount()).isZero();
        // A reused slot is a different node.
        long next = box(0, 0, 0, Mobility.DYNAMIC);
        update();
        assertThat(inBox(-1, -1, -1, 1, 1, 1, ALL)).containsExactly(next);
    }

    @Test
    void layersFilterAndVisitorsStop() {
        long a = box(0, 0, 0, Mobility.STATIC);
        long b = box(0, 0, 0, Mobility.DYNAMIC);
        scene.setLayers(b, WORLD.mask() | PICKABLE.mask());
        update();
        assertThat(inBox(-1, -1, -1, 1, 1, 1, PICKABLE.mask())).containsExactly(b);
        assertThat(inBox(-1, -1, -1, 1, 1, 1, WORLD.mask())).containsExactlyInAnyOrder(a, b);
        List<Long> first = new ArrayList<>();
        assertThat(index.queryAabb(-1, -1, -1, 1, 1, 1, ALL, node -> !first.add(node))).isEqualTo(1);
        assertThat(first).hasSize(1); // stopped in the static index: the dynamic one is skipped
        List<Long> sphere = new ArrayList<>();
        index.querySphere(0, 0, 0, 1, ALL, node -> !sphere.add(node));
        assertThat(sphere).hasSize(1);
    }

    @Test
    void frustumsFindWhatIsInView() {
        long ahead = box(0, 0, -10, Mobility.STATIC);
        long behind = box(0, 0, 10, Mobility.DYNAMIC);
        long aheadMoving = box(1, 0, -20, Mobility.DYNAMIC);
        update();
        Mat4 view = Mat4.lookAt(Vec3.ZERO, Vec3.of(0, 0, -1), Vec3.of(0, 1, 0));
        Frustum frustum = Frustum.fromMatrix(Mat4.perspective(Math.toRadians(60), 1, 0.1, 100).mul(view));
        List<Long> visible = new ArrayList<>();
        index.queryFrustum(frustum, ALL, node -> visible.add(node));
        assertThat(visible).containsExactlyInAnyOrder(ahead, aheadMoving).doesNotContain(behind);
        List<Long> one = new ArrayList<>();
        index.queryFrustum(frustum, ALL, node -> !one.add(node));
        assertThat(one).hasSize(1);
    }

    @Test
    void raycastsFindTheNearestBox() {
        long far = box(0, 0, -10, Mobility.STATIC);
        long near = box(0, 0, -5, Mobility.DYNAMIC);
        long nearer = box(0, 0, -3, Mobility.STATIC);
        scene.setLayers(nearer, PICKABLE.mask());
        update();
        SpatialIndex.Hit hit = new SpatialIndex.Hit();
        assertThat(index.raycast(0, 0, 0, 0, 0, -1, 100, ALL, hit)).isTrue();
        assertThat(hit.node()).isEqualTo(nearer);
        assertThat(hit.distance()).isEqualTo(2.5);
        assertThat(index.raycast(0, 0, 0, 0, 0, -1, 100, WORLD.mask(), hit)).isTrue();
        assertThat(hit.node()).isEqualTo(near); // dynamic, nearer than the static one
        assertThat(hit.distance()).isEqualTo(4.5);
        scene.setLayers(near, PICKABLE.mask());
        update();
        assertThat(index.raycast(0, 0, 0, 0, 0, -1, 100, WORLD.mask(), hit)).isTrue();
        assertThat(hit.node()).isEqualTo(far);
        assertThat(index.raycast(0, 0, 0, 0, 0, -1, 5, WORLD.mask(), hit)).isFalse(); // too short
        assertThat(hit.node()).isEqualTo(NodeHandle.NONE);
        assertThat(hit.distance()).isInfinite();
        assertThat(index.raycast(0, 0, 0, 0, 0, 1, 100, ALL, hit)).isFalse(); // the other way
    }

    @Test
    void emptyIndexesAnswerNothing() {
        update();
        assertThat(inBox(-1, -1, -1, 1, 1, 1, ALL)).isEmpty();
        assertThat(index.querySphere(0, 0, 0, 1, ALL, node -> true)).isZero();
        assertThat(index.raycast(0, 0, 0, 1, 0, 0, 10, ALL, new SpatialIndex.Hit())).isFalse();
        Frustum frustum = Frustum.fromMatrix(Mat4.perspective(1, 1, 0.1, 10));
        assertThat(index.queryFrustum(frustum, ALL, node -> true)).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> index.setRebuildThreshold(0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> index.setRebuildThreshold(Double.NaN));
    }

    @Test
    void manyDynamicNodesGrowAndRebuild() {
        index.setRebuildThreshold(1); // rebuild whenever refitting made it any worse
        List<Long> nodes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            nodes.add(box(i * 2, 0, 0, Mobility.DYNAMIC));
        }
        update();
        assertThat(index.dynamicCount()).isEqualTo(100);
        for (int i = 0; i < 100; i++) {
            scene.setTranslation(nodes.get(i), 0, i * 2, 0); // scatter them elsewhere
        }
        update();
        assertThat(inBox(-1, 9, -1, 1, 11, 1, ALL)).containsExactly(nodes.get(5));
        assertThat(inBox(9, -1, -1, 11, 1, 1, ALL)).isEmpty();
    }

    @Test
    void indexesFallingBehindTheSceneRebuild() {
        SceneGraph small = new SceneGraph(clock, 8, 2);
        SpatialIndex lagging = new SpatialIndex(small);
        long[] nodes = new long[4];
        for (int i = 0; i < 4; i++) {
            nodes[i] = small.create();
            small.setLocalBounds(nodes[i], 0, 0, 0, 1, 1, 1);
            small.setMobility(nodes[i], i % 2 == 0 ? Mobility.STATIC : Mobility.DYNAMIC);
        }
        small.update(); // four changes in a log of two
        lagging.update();
        assertThat(lagging.staticCount()).isEqualTo(2);
        assertThat(lagging.dynamicCount()).isEqualTo(2);
        small.remove(nodes[0]);
        small.remove(nodes[1]);
        small.remove(nodes[2]);
        small.update();
        lagging.update(); // behind again: rebuilt without the removed nodes
        assertThat(lagging.staticCount()).isZero();
        assertThat(lagging.dynamicCount()).isEqualTo(1);
        List<Long> found = new ArrayList<>();
        lagging.queryAabb(-1, -1, -1, 2, 2, 2, ALL, node -> found.add(node));
        assertThat(found).containsExactly(nodes[3]);
    }

    private long sink;

    @Test
    void framesAllocateNothing() {
        List<Long> moving = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            box(i, 0, 0, Mobility.STATIC);
            moving.add(box(i, 5, 0, Mobility.DYNAMIC));
        }
        update();
        long[] dynamic = moving.stream().mapToLong(Long::longValue).toArray();
        SpatialIndex.Hit hit = new SpatialIndex.Hit();
        Frustum frustum = Frustum.fromMatrix(Mat4.perspective(1, 1, 0.1, 100));
        SpatialIndex.Visitor counter = node -> {
            sink++;
            return true;
        };
        assertAllocationFree(() -> {
            for (int i = 0; i < dynamic.length; i += 7) {
                scene.setTranslation(dynamic[i], i, 5 + (sink & 3), 0);
            }
            scene.update();
            index.update();
            index.queryAabb(0, 0, 0, 20, 20, 20, ALL, counter);
            index.querySphere(10, 5, 0, 3, ALL, counter);
            index.queryFrustum(frustum, ALL, counter);
            index.raycast(0, 5, -10, 0, 0, 1, 100, ALL, hit);
            sink += hit.node();
        });
    }
}
