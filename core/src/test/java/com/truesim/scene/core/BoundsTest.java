package com.truesim.scene.core;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
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

class BoundsTest {

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock);

    private List<String> changes(long since) {
        List<String> lines = new ArrayList<>();
        scene.changesSince(since, (node, changes) -> lines.add(NodeHandle.toString(node) + " " + changes));
        return lines;
    }

    private static void assertBox(Aabb actual, double minX, double minY, double minZ, double maxX, double maxY,
        double maxZ) {
        assertThat(actual.min().approxEquals(Vec3.of(minX, minY, minZ), 1e-9)).as("min %s", actual).isTrue();
        assertThat(actual.max().approxEquals(Vec3.of(maxX, maxY, maxZ), 1e-9)).as("max %s", actual).isTrue();
    }

    @Test
    void nodesStartWithoutBoundsOnTheDefaultLayer() {
        long node = scene.create();
        scene.update();
        assertThat(scene.hasBounds(node)).isFalse();
        assertThat(scene.localBounds(node)).isEqualTo(Aabb.EMPTY);
        assertThat(scene.worldBounds(node)).isEqualTo(Aabb.EMPTY);
        assertThat(scene.mobility(node)).isEqualTo(Mobility.DYNAMIC);
        assertThat(scene.layers(node)).isEqualTo(SceneLayer.DEFAULT.mask()).isEqualTo(1);
    }

    @Test
    void worldBoundsFollowTheTransform() {
        long parent = scene.create();
        long box = scene.create(parent);
        scene.setLocalBounds(box, -1, 0, -3, 1, 2, 3);
        scene.setTranslation(parent, 100, 0, 0);
        scene.update();
        assertBox(scene.worldBounds(box), 99, 0, -3, 101, 2, 3);
        assertThat(scene.worldBounds().get(NodeHandle.index(box), 0)).isEqualTo(99);
        // 90 degrees about y swaps x and z extents; scale 2 doubles them.
        scene.setRotation(parent, 0, Math.sqrt(0.5), 0, Math.sqrt(0.5));
        scene.setScale(box, 2, 2, 2);
        scene.update();
        assertBox(scene.worldBounds(box), 94, 0, -2, 106, 4, 2);
        // Bounds given as a value; negative scale mirrors without inverting the box.
        scene.setLocalBounds(box, Aabb.of(Vec3.of(0, 0, 0), Vec3.of(1, 1, 1)));
        scene.setScale(box, -1, 1, 1);
        scene.setRotation(parent, 0, 0, 0, 1);
        scene.update();
        assertBox(scene.worldBounds(box), 99, 0, 0, 100, 1, 1);
        assertThat(scene.localBounds(box)).isEqualTo(Aabb.of(Vec3.of(0, 0, 0), Vec3.of(1, 1, 1)));
    }

    @Test
    void rotatedBoundsEncloseEveryCornerTightly() {
        long node = scene.create();
        scene.setLocalBounds(node, -1, -2, -3, 4, 5, 6);
        Quat rotation = Quat.fromAxisAngle(Vec3.of(1, 2, 3).normalize(), 0.7);
        scene.setLocal(node, Transform.of(Vec3.of(10, -20, 30), rotation, Vec3.of(1.5, 0.5, 2)));
        scene.update();
        Transform transform = scene.world(node);
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            Vec3 point = transform.transformPoint(Vec3.of((corner & 1) == 0 ? -1 : 4, (corner & 2) == 0 ? -2 : 5,
                (corner & 4) == 0 ? -3 : 6));
            min[0] = Math.min(min[0], point.x());
            min[1] = Math.min(min[1], point.y());
            min[2] = Math.min(min[2], point.z());
            max[0] = Math.max(max[0], point.x());
            max[1] = Math.max(max[1], point.y());
            max[2] = Math.max(max[2], point.z());
        }
        assertBox(scene.worldBounds(node), min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    @Test
    void boundsChangesAreLogged() {
        long parent = scene.create();
        long box = scene.create(parent);
        long group = scene.create(parent); // no bounds
        scene.setLocalBounds(box, 0, 0, 0, 1, 1, 1);
        scene.update();
        int all = NodeChange.CREATED | NodeChange.TRANSFORM;
        assertThat(changes(-1)).containsExactly(NodeHandle.toString(parent) + " " + all, // in creation order
            NodeHandle.toString(box) + " " + (all | NodeChange.BOUNDS), NodeHandle.toString(group) + " " + all);

        long since = clock.now();
        scene.setTranslation(parent, 1, 0, 0); // moves the box's bounds, not the group's (it has none)
        scene.update();
        assertThat(changes(since)).containsExactly(NodeHandle.toString(parent) + " " + NodeChange.TRANSFORM,
            NodeHandle.toString(box) + " " + (NodeChange.TRANSFORM | NodeChange.BOUNDS),
            NodeHandle.toString(group) + " " + NodeChange.TRANSFORM);

        since = clock.now();
        scene.setLocalBounds(box, 0, 0, 0, 2, 2, 2);
        scene.clearBounds(box); // bounds only: no transform work
        scene.update();
        assertThat(changes(since)).containsExactly(NodeHandle.toString(box) + " " + NodeChange.BOUNDS);
        assertThat(scene.hasBounds(box)).isFalse();
        assertThat(scene.worldBounds(box)).isEqualTo(Aabb.EMPTY);

        since = clock.now();
        scene.setLocalBounds(group, 0, 0, 0, 1, 1, 1);
        scene.remove(group); // gone before the update: only the removal
        scene.update();
        assertThat(changes(since)).containsExactly(NodeHandle.toString(group) + " " + NodeChange.REMOVED);
    }

    @Test
    void mobilityAndLayersAreLoggedWhenTheyChange() {
        SceneLayer world = SceneLayer.of("world", 0);
        SceneLayer triggers = SceneLayer.of("triggers", 5);
        long node = scene.create();
        scene.update();
        long since = clock.now();
        scene.setMobility(node, Mobility.DYNAMIC); // already
        scene.setLayers(node, world.mask()); // already
        scene.update();
        assertThat(changes(since)).isEmpty();
        scene.setMobility(node, Mobility.STATIC);
        scene.setLayers(node, world.mask() | triggers.mask());
        scene.update();
        assertThat(changes(since)).containsExactly(
            NodeHandle.toString(node) + " " + (NodeChange.MOBILITY | NodeChange.LAYERS));
        assertThat(scene.mobility(node)).isEqualTo(Mobility.STATIC);
        assertThat(triggers.in(scene.layers(node))).isTrue();
        assertThat(triggers.in(world.mask())).isFalse();
        assertThat(triggers.mask()).isEqualTo(32);
    }

    @Test
    void invalidInputsAreRejected() {
        long node = scene.create();
        assertThatIllegalArgumentException().isThrownBy(() -> scene.setLocalBounds(node, 1, 0, 0, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.setLocalBounds(node, 0, 1, 0, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.setLocalBounds(node, 0, 0, 1, 1, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> SceneLayer.of("x", 32));
        assertThatIllegalArgumentException().isThrownBy(() -> SceneLayer.of("x", -1));
    }

    @Test
    void boundsSurviveGrowing() {
        SceneGraph small = new SceneGraph(clock, 1, 16);
        long first = small.create();
        small.setLocalBounds(first, 0, 0, 0, 2, 2, 2);
        small.update();
        for (int i = 0; i < 10; i++) {
            small.create();
        }
        small.update();
        assertBox(small.worldBounds(first), 0, 0, 0, 2, 2, 2);
    }

    private long sink;

    @Test
    void boundsLayersAndMobilityAllocateNothing() {
        SceneGraph graph = new SceneGraph(clock, 64, 4096);
        long root = graph.create();
        long node = graph.create(root);
        assertAllocationFree(() -> {
            graph.setLocalBounds(node, -1, -1, -1, 1, 1, 1);
            graph.setTranslation(root, sink & 7, 0, 0);
            graph.setLayers(node, (int) (sink & 3) + 1);
            graph.setMobility(node, (sink & 1) == 0 ? Mobility.STATIC : Mobility.DYNAMIC);
            graph.update();
            sink += graph.layers(node) + (graph.hasBounds(node) ? 1 : 0)
                + (long) graph.worldBounds().get(NodeHandle.index(node), 0);
        });
    }
}
