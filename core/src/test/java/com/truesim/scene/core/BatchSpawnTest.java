package com.truesim.scene.core;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Batches attached, detached and removed in one pass, and the logs' batch entries. */
class BatchSpawnTest {

    record Tag(String name) {
    }

    record Weight(double kilos) {
    }

    static final ComponentType<Tag> TAG = ComponentType.of("test.batch-tag", Tag.class);
    static final ComponentType<Weight> WEIGHT = ComponentType.of("test.batch-weight", Weight.class);

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock);

    /** What a visitor saw: single changes as "node:changes", batches as "[n]" then their nodes. */
    private static final class Seen implements NodeChange.Visitor {
        final List<String> calls = new ArrayList<>();
        final List<Long> nodes = new ArrayList<>();
        final List<Integer> changes = new ArrayList<>();

        @Override
        public void changed(long node, int bits) {
            calls.add("1");
            nodes.add(node);
            changes.add(bits);
        }

        @Override
        public void changed(ChangeBatch batch) {
            calls.add("[" + batch.size() + "]");
            for (int i = 0; i < batch.size(); i++) {
                nodes.add(batch.node(i));
                changes.add(batch.changes(i));
            }
        }
    }

    /** A root at (x, 0, 0) with {@code children} tagged children 1 m apart along z, each weighing its number. */
    private static SceneBatch tree(SceneBatch batch, double x, int children) {
        int root = batch.add();
        batch.setTranslation(root, x, 0, 0);
        batch.set(root, TAG, new Tag("root"));
        for (int i = 0; i < children; i++) {
            int child = batch.add(root);
            batch.setTranslation(child, 0, 0, i);
            batch.set(child, TAG, new Tag("child " + i));
            batch.set(child, WEIGHT, new Weight(i));
        }
        return batch;
    }

    @Test
    void anAttachedBatchIsOneEntryOfTheSceneAndOfEachStore() {
        long parent = scene.create();
        scene.setTranslation(parent, 10, 0, 0);
        scene.update();
        long since = clock.now();
        long[] handles = new long[4];
        scene.attach(tree(new SceneBatch(), 5, 3), parent, handles);
        scene.update();

        Seen nodes = new Seen();
        assertThat(scene.changesSince(since, nodes)).isTrue();
        assertThat(nodes.calls).containsExactly("[4]");
        assertThat(nodes.nodes).containsExactly(handles[0], handles[1], handles[2], handles[3]);
        assertThat(nodes.changes).allMatch(c -> NodeChange.has(c, NodeChange.CREATED));
        assertThat(scene.changeCountSince(since)).isEqualTo(4);
        Seen tags = new Seen();
        scene.components().store(TAG).changesSince(since, tags);
        assertThat(tags.calls).containsExactly("[4]");
        assertThat(tags.changes).containsOnly(ComponentStore.ADDED);
        Seen weights = new Seen();
        scene.components().store(WEIGHT).changesSince(since, weights);
        assertThat(weights.calls).containsExactly("[3]");

        assertThat(scene.parent(handles[0])).isEqualTo(parent);
        assertThat(scene.parent(handles[3])).isEqualTo(handles[0]);
        assertThat(scene.world(handles[3]).translation().x()).isEqualTo(15); // under the parent: computed at update
        assertThat(scene.world(handles[3]).translation().z()).isEqualTo(2);
        assertThat(scene.components().get(handles[2], TAG)).isEqualTo(new Tag("child 1"));
        assertThat(scene.components().get(handles[3], WEIGHT)).isEqualTo(new Weight(2));
        assertThat(scene.components().get(handles[0], WEIGHT)).isNull();
    }

    @Test
    void aVisitorWithoutBatchesSeesEachNodeAsIfLoggedAlone() {
        long since = clock.now();
        long root = scene.attach(tree(new SceneBatch(), 0, 2), NodeHandle.NONE);
        scene.update();
        List<Long> seen = new ArrayList<>();
        scene.changesSince(since, (node, changes) -> seen.add(node));
        assertThat(seen).hasSize(3).startsWith(root);
        List<Long> tagged = new ArrayList<>();
        scene.components().store(TAG).changesSince(since, (node, changes) -> tagged.add(node));
        assertThat(tagged).containsExactlyElementsOf(seen);
    }

    @Test
    void removingASubtreeIsOneEntryAndTakesItsComponents() {
        long root = scene.attach(tree(new SceneBatch(), 0, 5), NodeHandle.NONE);
        long single = scene.create();
        scene.components().set(single, TAG, new Tag("single"));
        scene.update();
        long since = clock.now();
        scene.remove(root);
        scene.remove(single);
        Seen nodes = new Seen();
        scene.changesSince(since, nodes);
        assertThat(nodes.calls).containsExactly("[6]", "1");
        assertThat(nodes.changes).containsOnly(NodeChange.REMOVED);
        assertThat(nodes.nodes.get(0)).isEqualTo(root);
        Seen tags = new Seen();
        scene.components().store(TAG).changesSince(since, tags);
        assertThat(tags.calls).containsExactly("[6]", "1");
        assertThat(tags.changes).containsOnly(ComponentStore.REMOVED);
        assertThat(scene.components().store(TAG).size()).isZero();
        assertThat(scene.components().store(WEIGHT).size()).isZero();
        assertThat(scene.size()).isZero();
    }

    @Test
    void manyNodesAreMadeAndRemovedAsOneEntryEach() {
        long parent = scene.create();
        scene.update();
        long since = clock.now();
        long[] made = new long[10];
        scene.create(parent, made, 2, 8);
        assertThat(made[0]).isZero();
        Tag[] tags = new Tag[10];
        for (int i = 2; i < 10; i++) {
            tags[i] = new Tag("n" + i);
        }
        scene.components().store(TAG).setAll(made, tags, 2, 8);
        scene.update();
        Seen nodes = new Seen();
        scene.changesSince(since, nodes);
        assertThat(nodes.calls).containsExactly("[8]");
        assertThat(nodes.changes).allMatch(c -> NodeChange.has(c, NodeChange.CREATED));
        Seen seenTags = new Seen();
        scene.components().store(TAG).changesSince(since, seenTags);
        assertThat(seenTags.calls).containsExactly("[8]");
        assertThat(scene.components().get(made[5], TAG)).isEqualTo(new Tag("n5"));

        long again = clock.now();
        scene.remove(new long[] {made[2], parent, made[3]}, 0, 3); // made[3] went with parent: skipped
        Seen removed = new Seen();
        scene.changesSince(again, removed);
        assertThat(removed.calls).containsExactly("[9]");
        assertThat(scene.size()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> scene.components().store(TAG)
            .setAll(made, tags, 2, 1)); // not nodes of the scene any more: nothing set
    }

    @Test
    void aNodeOfABatchChangedBeforeTheUpdateIsReportedOnce() {
        long since = clock.now();
        long[] handles = new long[3];
        scene.attach(tree(new SceneBatch(), 0, 2), NodeHandle.NONE, handles);
        scene.setLayers(handles[1], 4);
        scene.remove(handles[2]); // made and removed before the update: only its removal is reported
        scene.update();
        Seen nodes = new Seen();
        scene.changesSince(since, nodes);
        assertThat(nodes.calls).containsExactly("1", "[2]"); // the removal came first, the batch at the update
        assertThat(nodes.nodes).containsExactly(handles[2], handles[0], handles[1]);
        assertThat(nodes.changes.get(2) & NodeChange.LAYERS).isEqualTo(NodeChange.LAYERS);
    }

    @Test
    void detachingTakesASubtreeOutAsABatchToAttachAgain() {
        long[] handles = new long[4];
        scene.attach(tree(new SceneBatch(), 3, 3), NodeHandle.NONE, handles);
        scene.setScale(handles[2], 2, 2, 2);
        scene.setLayers(handles[3], 8);
        scene.update();
        SceneBatch out = new SceneBatch();
        int root = scene.detach(handles[0], out);
        assertThat(root).isZero();
        assertThat(scene.size()).isZero();
        assertThat(out.size()).isEqualTo(4);
        assertThat(out.get(0, TAG)).isEqualTo(new Tag("root"));

        long other = scene.create();
        scene.setTranslation(other, 0, 100, 0);
        long[] again = new long[4];
        scene.attach(out, other, again);
        scene.update();
        List<String> found = new ArrayList<>();
        for (long node : again) {
            Weight weight = scene.components().get(node, WEIGHT);
            found.add(scene.components().get(node, TAG).name() + (weight == null ? "" : " " + weight.kilos())
                + " y " + scene.world(node).translation().y() + " layers " + scene.layers(node)
                + " scale " + scene.world(node).scale().x());
        }
        assertThat(found).containsExactlyInAnyOrder("root y 100.0 layers 1 scale 1.0",
            "child 0 0.0 y 100.0 layers 1 scale 1.0", "child 1 1.0 y 100.0 layers 1 scale 2.0",
            "child 2 2.0 y 100.0 layers 8 scale 1.0");
    }

    @Test
    void aBatchBuiltOnAWorkerIsAttachedOnTheScenesThreadOnly() throws Exception {
        clock.ownedBy(Thread.currentThread());
        SceneBatch batch = CompletableFuture.supplyAsync(() -> {
            SceneBatch built = tree(new SceneBatch(), 1, 4);
            built.prepare();
            return built;
        }).get();
        assertThat(batch.isPrepared()).isTrue();
        CompletableFuture<Throwable> elsewhere = CompletableFuture.supplyAsync(() -> {
            try {
                scene.attach(batch, NodeHandle.NONE);
                return null;
            } catch (Throwable e) {
                return e;
            }
        });
        assertThat(elsewhere.get()).isInstanceOf(IllegalStateException.class);
        long root = scene.attach(batch, NodeHandle.NONE);
        assertThat(scene.size()).isEqualTo(5);
        assertThat(scene.world(root).translation().x()).isEqualTo(1); // prepared: placed without an update
        CompletableFuture<Throwable> removing = CompletableFuture.supplyAsync(() -> {
            try {
                scene.remove(root);
                return null;
            } catch (Throwable e) {
                return e;
            }
        });
        assertThat(removing.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBatchLargerThanTheLogIsKeptUntilTheUpdateAfterIt() {
        SceneGraph small = new SceneGraph(clock, 4, 8);
        long since = clock.now();
        SceneBatch batch = new SceneBatch();
        for (int i = 0; i < 20; i++) {
            batch.add();
        }
        long[] handles = new long[20];
        small.attach(batch, NodeHandle.NONE, handles);
        small.update();
        assertThat(small.changeCountSince(since)).isEqualTo(20);
        for (int round = 0; round < 3; round++) { // later changes push the batch out: a consumer that late rebuilds
            for (int i = 0; i < 20; i++) {
                small.setLayers(handles[i], round + 2);
                small.update();
            }
        }
        assertThat(small.changesSince(since, (node, changes) -> { })).isFalse();
        assertThat(small.changeCountSince(since)).isEqualTo(-1);
    }

    @Test
    void aClearedBatchIsBuiltAgainEmpty() {
        SceneBatch batch = tree(new SceneBatch(2), 0, 6);
        batch.setGroup(3);
        batch.clear();
        assertThat(batch.size()).isZero();
        assertThat(batch.group()).isZero();
        tree(batch, 7, 1);
        long[] handles = new long[2];
        scene.attach(batch, NodeHandle.NONE, handles);
        assertThat(scene.components().store(TAG).size()).isEqualTo(2);
        assertThat(scene.components().store(WEIGHT).size()).isEqualTo(1);
        assertThat(scene.components().get(handles[1], WEIGHT)).isEqualTo(new Weight(0));
    }

    @Test
    void componentsSetTwiceInABatchKeepTheLast() {
        SceneBatch batch = new SceneBatch();
        int a = batch.add();
        int b = batch.add();
        batch.set(a, TAG, new Tag("first"));
        batch.set(b, TAG, new Tag("b"));
        batch.set(a, TAG, new Tag("second"));
        assertThat(batch.get(a, TAG)).isEqualTo(new Tag("second"));
        assertThat(batch.get(b, WEIGHT)).isNull();
        long[] handles = new long[2];
        scene.attach(batch, NodeHandle.NONE, handles);
        assertThat(scene.components().get(handles[0], TAG)).isEqualTo(new Tag("second"));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setScale(a, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> scene.attach(new SceneBatch(), NodeHandle.NONE));
    }

    private long sink;

    @Test
    void streamingByReusedBatchesAllocatesNothing() {
        SceneGraph graph = new SceneGraph(clock, 256, 4096);
        SceneBatch batch = new SceneBatch(64);
        Tag tag = new Tag("cell");
        long[] made = new long[16];
        NodeChange.Visitor visitor = new NodeChange.Visitor() {
            @Override
            public void changed(long node, int changes) {
                sink += changes;
            }

            @Override
            public void changed(ChangeBatch changed) {
                sink += changed.size();
            }
        };
        ComponentStore<Tag> tags = graph.components().store(TAG);
        assertAllocationFree(() -> {
            long since = clock.now();
            batch.clear();
            int root = batch.add();
            for (int i = 0; i < 40; i++) {
                int node = batch.add(root);
                batch.setTranslation(node, i, 0, 0);
                batch.set(node, TAG, tag);
            }
            long cell = graph.attach(batch, NodeHandle.NONE);
            graph.create(cell, made, 0, made.length);
            graph.update();
            graph.changesSince(since, visitor);
            tags.changesSince(since, visitor);
            graph.remove(cell);
            sink += graph.size();
        });
    }
}
