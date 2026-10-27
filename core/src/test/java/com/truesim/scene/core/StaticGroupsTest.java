package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.math.Mat4;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Frustum;
import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Static nodes indexed per group, under a top-level tree of groups. */
class StaticGroupsTest {

    private static final int ALL = -1;

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock);
    private final SpatialIndex index = new SpatialIndex(scene);

    /** A static unit box at (x, 0, z) in {@code group}. */
    private long box(double x, double z, int group) {
        long node = scene.create();
        scene.setLocalBounds(node, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        scene.setTranslation(node, x, 0, z);
        scene.setMobility(node, Mobility.STATIC);
        scene.setGroup(node, group);
        return node;
    }

    private void update() {
        scene.update();
        index.update();
    }

    private List<Long> inBox(double minX, double maxX) {
        List<Long> found = new ArrayList<>();
        index.queryAabb(minX, -1, -100, maxX, 1, 100, ALL, node -> found.add(node));
        return found;
    }

    @Test
    void groupsAreLoggedOnNodes() {
        long node = scene.create();
        assertThat(scene.group(node)).isZero();
        scene.update();
        long since = clock.now();
        scene.setGroup(node, 0); // unchanged: nothing logged
        scene.update();
        List<Integer> changes = new ArrayList<>();
        scene.changesSince(since, (n, c) -> changes.add(c));
        assertThat(changes).isEmpty();
        scene.setGroup(node, 7);
        scene.update();
        scene.changesSince(since, (n, c) -> changes.add(c));
        assertThat(changes).containsExactly(NodeChange.GROUP);
        assertThat(scene.group(node)).isEqualTo(7);
        assertThatIllegalArgumentException().isThrownBy(() -> scene.setGroup(node, -1));
    }

    @Test
    void eachGroupHasItsOwnTree() {
        assertThat(index.groupCount()).isZero();
        long a = box(0, 0, 0);
        long b = box(10, 0, 2); // group 1 stays empty: a gap in the groups
        long c = box(20, 0, 2);
        update();
        assertThat(index.groupCount()).isEqualTo(2);
        assertThat(index.staticCount()).isEqualTo(3);
        assertThat(inBox(-1, 21)).containsExactlyInAnyOrder(a, b, c);
        assertThat(inBox(9, 11)).containsExactly(b);
        // Moving a node to another group.
        scene.setGroup(b, 5);
        update();
        assertThat(index.groupCount()).isEqualTo(3);
        assertThat(inBox(9, 11)).containsExactly(b);
        // Moving a node within its group: only that group rebuilds, the top level refits.
        scene.setTranslation(c, 50, 0, 0);
        update();
        assertThat(inBox(49, 51)).containsExactly(c);
        assertThat(inBox(19, 21)).isEmpty();
        // Emptying a group takes it off the top level.
        scene.remove(c);
        update();
        assertThat(index.groupCount()).isEqualTo(2);
        scene.remove(a);
        scene.remove(b);
        update();
        assertThat(index.groupCount()).isZero();
        assertThat(inBox(-100, 100)).isEmpty();
    }

    @Test
    void queriesCrossGroupsAndStopAcrossThem() {
        List<Long> nodes = new ArrayList<>();
        for (int group = 0; group < 4; group++) {
            for (int i = 0; i < 5; i++) {
                nodes.add(box(group * 100 + i * 2, 0, group));
            }
        }
        update();
        assertThat(inBox(-1, 400)).hasSize(20);
        List<Long> first = new ArrayList<>();
        assertThat(index.queryAabb(-1, -1, -1, 400, 1, 1, ALL, node -> !first.add(node))).isEqualTo(1);
        List<Long> sphere = new ArrayList<>();
        index.querySphere(204, 0, 0, 3, ALL, node -> sphere.add(node));
        assertThat(sphere).containsExactlyInAnyOrder(nodes.get(11), nodes.get(12), nodes.get(13));
        List<Long> stopped = new ArrayList<>();
        index.querySphere(204, 0, 0, 300, ALL, node -> !stopped.add(node));
        assertThat(stopped).hasSize(1);
        Mat4 view = Mat4.lookAt(Vec3.of(150, 50, 0), Vec3.of(150, 0, 0), Vec3.of(0, 0, -1));
        Frustum frustum = Frustum.fromMatrix(Mat4.perspective(Math.toRadians(90), 1, 1, 200).mul(view));
        List<Long> visible = new ArrayList<>();
        index.queryFrustum(frustum, ALL, node -> visible.add(node));
        assertThat(visible).containsExactlyInAnyOrderElementsOf(nodes.subList(5, 11)); // x 100 to 200
        List<Long> one = new ArrayList<>();
        index.queryFrustum(frustum, ALL, node -> !one.add(node));
        assertThat(one).hasSize(1);
    }

    @Test
    void raycastsFindTheNearestAcrossGroups() {
        long far = box(0, -30, 1);
        long near = box(0, -10, 2);
        long between = box(0, -20, 3);
        long dynamic = scene.create();
        scene.setLocalBounds(dynamic, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        scene.setTranslation(dynamic, 0, 0, -40);
        update();
        SpatialIndex.Hit hit = new SpatialIndex.Hit();
        assertThat(index.raycast(0, 0, 0, 0, 0, -1, 100, ALL, hit)).isTrue();
        assertThat(hit.node()).isEqualTo(near);
        assertThat(hit.distance()).isEqualTo(9.5);
        scene.remove(near);
        update();
        assertThat(index.raycast(0, 0, 0, 0, 0, -1, 100, ALL, hit)).isTrue();
        assertThat(hit.node()).isEqualTo(between);
        assertThat(index.raycast(0, 0, 0, 0, 0, 1, 100, ALL, hit)).isFalse();
        assertThat(far).isNotEqualTo(between);
    }

    @Test
    void lagBehindTheLogRebuildsAllGroups() {
        SceneGraph small = new SceneGraph(clock, 8, 2);
        SpatialIndex lagging = new SpatialIndex(small);
        long[] nodes = new long[3];
        for (int i = 0; i < 3; i++) {
            nodes[i] = small.create();
            small.setLocalBounds(nodes[i], 0, 0, 0, 1, 1, 1);
            small.setMobility(nodes[i], Mobility.STATIC);
            small.setGroup(nodes[i], i);
        }
        small.update();
        lagging.update();
        assertThat(lagging.groupCount()).isEqualTo(3);
        small.remove(nodes[1]); // group 1 empties ...
        small.update();
        lagging.update();
        small.remove(nodes[0]);
        small.remove(nodes[2]);
        long kept = small.create();
        small.setLocalBounds(kept, 0, 0, 0, 1, 1, 1);
        small.setMobility(kept, Mobility.STATIC);
        small.setGroup(kept, 2);
        small.update(); // ... and the index falls behind the log: rebuilt, skipping the empty group
        lagging.update();
        assertThat(lagging.groupCount()).isEqualTo(1);
        assertThat(lagging.staticCount()).isEqualTo(1);
    }
}
