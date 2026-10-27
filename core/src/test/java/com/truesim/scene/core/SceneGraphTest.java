package com.truesim.scene.core;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.ChangeCursor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SceneGraphTest {

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock);

    /** The changes after {@code since}, as "node changes" lines. */
    private List<String> changes(long since) {
        List<String> lines = new ArrayList<>();
        assertThat(scene.changesSince(since, (node, changes) -> lines.add(NodeHandle.toString(node) + " " + changes)))
            .isTrue();
        return lines;
    }

    private static Vec3 translation(Transform transform) {
        return transform.translation();
    }

    // ---------------------------------------------------------------- nodes and hierarchy

    @Test
    void nodesFormAHierarchy() {
        long root = scene.create();
        long a = scene.create(root);
        long b = scene.create(root);
        long c = scene.create(a);
        assertThat(scene.size()).isEqualTo(4);
        assertThat(scene.slotCount()).isEqualTo(4);
        assertThat(scene.contains(root)).isTrue();
        assertThat(scene.parent(root)).isEqualTo(NodeHandle.NONE);
        assertThat(scene.parent(c)).isEqualTo(a);
        assertThat(scene.firstChild(root)).isEqualTo(b); // newest first
        assertThat(scene.nextSibling(b)).isEqualTo(a);
        assertThat(scene.nextSibling(a)).isEqualTo(NodeHandle.NONE);
        assertThat(scene.firstChild(c)).isEqualTo(NodeHandle.NONE);
        List<Long> visited = new ArrayList<>();
        scene.forEach(visited::add);
        assertThat(visited).hasSize(4).startsWith(root);
        assertThat(visited.indexOf(a)).isLessThan(visited.indexOf(c)); // parents before children
    }

    @Test
    void removingTakesTheSubtreeAndStalesHandles() {
        long root = scene.create();
        long a = scene.create(root);
        long b = scene.create(root);
        long c = scene.create(a);
        scene.update();
        scene.remove(a);
        assertThat(scene.size()).isEqualTo(2);
        assertThat(scene.contains(a)).isFalse();
        assertThat(scene.contains(c)).isFalse();
        assertThat(scene.firstChild(root)).isEqualTo(b);
        assertThat(scene.nextSibling(b)).isEqualTo(NodeHandle.NONE);
        // Slots are reused with a new generation: old handles stay stale.
        long reused = scene.create();
        assertThat(NodeHandle.index(reused)).isIn(NodeHandle.index(a), NodeHandle.index(c));
        assertThat(NodeHandle.generation(reused)).isEqualTo(2);
        assertThat(scene.contains(a) || scene.contains(c)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> scene.parent(a))
            .withMessage("Not a node of this scene: " + NodeHandle.toString(a));
        // Removing the first and the last child of a parent.
        long d = scene.create(root);
        long e = scene.create(root);
        scene.remove(e);
        assertThat(scene.firstChild(root)).isEqualTo(d);
        scene.remove(b);
        assertThat(scene.nextSibling(d)).isEqualTo(NodeHandle.NONE);
        scene.remove(root);
        assertThat(scene.size()).isEqualTo(1);
    }

    @Test
    void handlesAreChecked() {
        assertThat(scene.contains(NodeHandle.NONE)).isFalse();
        assertThat(scene.contains(NodeHandle.of(3, 1))).isFalse();
        assertThat(scene.contains(NodeHandle.of(Integer.MAX_VALUE, 1))).isFalse();
        assertThat(scene.contains(-1L)).isFalse(); // negative slot
        long node = scene.create();
        assertThat(scene.contains(NodeHandle.of(NodeHandle.index(node), 2))).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> scene.create(NodeHandle.of(9, 1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new SceneGraph(clock, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new SceneGraph(clock, 1, 0));
    }

    @Test
    void reparentingKeepsTheLocalTransform() {
        long a = scene.create();
        long b = scene.create();
        long child = scene.create(a);
        scene.setTranslation(a, 10, 0, 0);
        scene.setTranslation(b, 0, 20, 0);
        scene.setTranslation(child, 1, 1, 1);
        scene.update();
        assertThat(translation(scene.world(child))).isEqualTo(Vec3.of(11, 1, 1));
        scene.setParent(child, b);
        scene.update();
        assertThat(scene.parent(child)).isEqualTo(b);
        assertThat(scene.firstChild(a)).isEqualTo(NodeHandle.NONE);
        assertThat(translation(scene.world(child))).isEqualTo(Vec3.of(1, 21, 1));
        scene.setParent(child, NodeHandle.NONE);
        scene.update();
        assertThat(translation(scene.world(child))).isEqualTo(Vec3.of(1, 1, 1));
        scene.setParent(child, NodeHandle.NONE); // already: nothing to do
        long grandchild = scene.create(child);
        assertThatIllegalArgumentException().isThrownBy(() -> scene.setParent(child, grandchild))
            .withMessageEndingWith("or below it");
        assertThatIllegalArgumentException().isThrownBy(() -> scene.setParent(child, child));
    }

    // ---------------------------------------------------------------- transforms

    @Test
    void worldTransformsComposeDownTheHierarchy() {
        long car = scene.create();
        long wheel = scene.create(car);
        scene.setTranslation(car, 1_000_000.25, 0, -40);
        scene.setRotation(car, 0, Math.sqrt(0.5), 0, Math.sqrt(0.5)); // 90 degrees about y
        scene.setScale(car, 2, 2, 2);
        scene.setTranslation(wheel, 1, 0, 0);
        scene.update();
        Transform world = scene.world(wheel);
        assertThat(world.translation().approxEquals(Vec3.of(1_000_000.25, 0, -42), 1e-9)).isTrue();
        assertThat(world.scale()).isEqualTo(Vec3.of(2, 2, 2));
        assertThat(scene.local(wheel)).isEqualTo(Transform.ofTranslation(Vec3.of(1, 0, 0)));
        assertThat(scene.worldTransforms().get(NodeHandle.index(car)).translation().x()).isEqualTo(1_000_000.25);
        assertThat(scene.localTransforms().get(NodeHandle.index(car)).scale()).isEqualTo(Vec3.of(2, 2, 2));
        // A whole transform at once.
        Transform placed = Transform.of(Vec3.of(5, 6, 7), Quat.IDENTITY, Vec3.of(3, 3, 3));
        scene.setLocal(car, placed);
        scene.update();
        assertThat(scene.world(car)).isEqualTo(placed);
        assertThat(translation(scene.world(wheel))).isEqualTo(Vec3.of(8, 6, 7));
    }

    @Test
    void childrenMovedBeforeTheirParentsAreUpdatedOnce() {
        long parent = scene.create();
        long child = scene.create(parent);
        scene.update();
        long since = clock.now();
        scene.setTranslation(child, 0, 1, 0); // the child is listed first ...
        scene.setTranslation(parent, 1, 0, 0); // ... and covered by its parent's update
        scene.update();
        assertThat(translation(scene.world(child))).isEqualTo(Vec3.of(1, 1, 0));
        assertThat(changes(since)).containsExactly(NodeHandle.toString(parent) + " " + NodeChange.TRANSFORM,
            NodeHandle.toString(child) + " " + NodeChange.TRANSFORM); // parents first
    }

    @Test
    void scenesGrow() {
        SceneGraph small = new SceneGraph(clock, 1, 4);
        long first = small.create();
        small.setTranslation(first, 3, 0, 0);
        small.update();
        for (int i = 0; i < 100; i++) {
            small.create(first);
        }
        small.update();
        assertThat(small.size()).isEqualTo(101);
        assertThat(translation(small.world(first))).isEqualTo(Vec3.of(3, 0, 0)); // kept while growing
        assertThat(translation(small.world(small.firstChild(first)))).isEqualTo(Vec3.of(3, 0, 0));
    }

    // ---------------------------------------------------------------- changes

    @Test
    void changesAreLoggedPerUpdate() {
        ChangeCursor cursor = clock.cursor();
        long since = cursor.mark();
        long a = scene.create();
        long b = scene.create(a);
        scene.update();
        int created = NodeChange.CREATED | NodeChange.TRANSFORM;
        assertThat(changes(since)).containsExactly(NodeHandle.toString(a) + " " + created,
            NodeHandle.toString(b) + " " + created);
        assertThat(scene.version(a)).isEqualTo(scene.version(b)).isPositive();

        since = clock.now();
        scene.setTranslation(a, 1, 0, 0); // moves b too
        scene.update();
        assertThat(changes(since)).containsExactly(NodeHandle.toString(a) + " " + NodeChange.TRANSFORM,
            NodeHandle.toString(b) + " " + NodeChange.TRANSFORM);

        since = clock.now();
        long c = scene.create();
        scene.setParent(b, c);
        scene.update();
        assertThat(changes(since)).containsExactly(NodeHandle.toString(c) + " " + created,
            NodeHandle.toString(b) + " " + (NodeChange.PARENT | NodeChange.TRANSFORM));

        since = clock.now();
        scene.remove(c);
        scene.update(); // nothing left to update
        assertThat(changes(since)).containsExactly(NodeHandle.toString(c) + " " + NodeChange.REMOVED,
            NodeHandle.toString(b) + " " + NodeChange.REMOVED);

        since = clock.now();
        long gone = scene.create();
        scene.remove(gone); // created and removed before an update: only the removal is reported
        long again = scene.create();
        scene.update();
        assertThat(changes(since)).containsExactly(NodeHandle.toString(gone) + " " + NodeChange.REMOVED,
            NodeHandle.toString(again) + " " + created);
        since = clock.now();
        long brief = scene.create();
        scene.setTranslation(brief, 1, 1, 1);
        scene.remove(brief); // removed before the update, slot not reused: nothing to update or report but the removal
        scene.update();
        assertThat(changes(since)).containsExactly(NodeHandle.toString(brief) + " " + NodeChange.REMOVED);
        List<Long> remaining = new ArrayList<>();
        scene.forEach(remaining::add); // skips the free slots
        assertThat(remaining).containsExactlyInAnyOrder(a, again);
        assertThat(NodeChange.has(created, NodeChange.TRANSFORM)).isTrue();
        assertThat(NodeChange.has(created, NodeChange.REMOVED | NodeChange.PARENT)).isFalse();
    }

    @Test
    void consumersTooFarBehindRebuild() {
        SceneGraph small = new SceneGraph(clock, 4, 2);
        long since = clock.now();
        long a = small.create();
        small.update();
        long b = small.create();
        long c = small.create();
        small.update(); // three changes in a log of two: the first is gone
        assertThat(small.changesSince(since, (node, changes) -> { })).isFalse();
        List<Long> rebuilt = new ArrayList<>();
        small.forEach(rebuilt::add);
        assertThat(rebuilt).containsExactlyInAnyOrder(a, b, c);
        // A consumer that kept up still gets everything.
        List<Long> recent = new ArrayList<>();
        assertThat(small.changesSince(small.version(a), (node, changes) -> recent.add(node))).isTrue();
        assertThat(recent).containsExactly(b, c);
    }

    @Test
    void scenesAreDeterministic() {
        assertThat(run()).isEqualTo(run());
    }

    private static List<String> run() {
        ChangeClock clock = new ChangeClock();
        SceneGraph scene = new SceneGraph(clock, 2, 1024);
        List<Long> nodes = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long parent = nodes.isEmpty() || i % 7 == 0 ? NodeHandle.NONE : nodes.get((i * 31) % nodes.size());
            long node = scene.create(parent);
            scene.setTranslation(node, i, i * 0.5, -i);
            nodes.add(node);
            if (i % 11 == 10) {
                scene.remove(nodes.remove((i * 17) % nodes.size()));
                nodes.removeIf(n -> !scene.contains(n));
            }
        }
        scene.update();
        List<String> out = new ArrayList<>();
        scene.changesSince(-1, (node, changes) -> out.add(node + ":" + changes));
        for (long node : nodes) {
            out.add(node + "=" + scene.world(node));
        }
        return out;
    }

    // ---------------------------------------------------------------- allocation

    private long sink;

    @Test
    void framesAllocateNothing() {
        SceneGraph graph = new SceneGraph(clock, 256, 4096);
        long root = graph.create();
        NodeChange.Visitor visitor = (node, changes) -> sink += changes;
        ChangeCursor cursor = clock.cursor();
        assertAllocationFree(() -> {
            long since = cursor.mark();
            long node = graph.create(root);
            graph.setTranslation(node, 1, 2, 3);
            graph.setRotation(root, 0, 0, 0, 1);
            graph.setScale(node, 1, 1, 1);
            graph.update();
            graph.changesSince(since, visitor);
            graph.remove(node);
            sink += graph.size();
        });
    }
}
