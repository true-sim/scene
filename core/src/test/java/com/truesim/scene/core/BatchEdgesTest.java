package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Batches at their edges: many between updates, slots reused, stores changed in bulk, batches checked and grown. */
class BatchEdgesTest {

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock, 4, 8);

    @Test
    void manyBatchesAndReusedSlotsBetweenTwoUpdatesAreEachLoggedOnce() {
        long since = clock.now();
        long[] made = new long[300];
        for (int b = 0; b < 12; b++) { // more batches, and more nodes in them, than the lists start with
            scene.create(NodeHandle.NONE, made, 25 * b, 25);
        }
        long parent = made[0];
        long[] one = new long[1];
        for (int i = 0; i < 600; i++) { // the same slot made and removed again and again: listed once
            scene.create(parent, one, 0, 1);
            scene.remove(one[0]);
        }
        scene.create(parent, one, 0, 0); // nothing
        scene.update();
        List<Integer> sizes = new ArrayList<>();
        List<Long> created = new ArrayList<>();
        scene.changesSince(since, new NodeChange.Visitor() {
            @Override
            public void changed(long node, int changes) {
                sizes.add(1);
            }

            @Override
            public void changed(ChangeBatch batch) {
                sizes.add(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    if (NodeChange.has(batch.changes(i), NodeChange.CREATED)) {
                        created.add(batch.node(i));
                    }
                }
            }
        });
        assertThat(created).hasSize(300).doesNotContain(one[0]);
        assertThat(scene.size()).isEqualTo(300);
        assertThat(scene.changeCountSince(since)).isEqualTo(900); // 600 removals logged one by one, and the batches
    }

    @Test
    void storesSetAndRemoveManyAtOnceLoggingOnlyWhatChanged() {
        ComponentStore<ComponentsTest.Sprite> sprites = scene.components().store(ComponentsTest.SPRITE);
        long[] nodes = new long[3];
        scene.create(NodeHandle.NONE, nodes, 0, 3);
        ComponentsTest.Sprite[] first = {new ComponentsTest.Sprite("a", 1), new ComponentsTest.Sprite("b", 1),
            new ComponentsTest.Sprite("c", 1)};
        sprites.setAll(nodes, first, 0, 3);
        long since = clock.now();
        sprites.setAll(nodes, first.clone(), 0, 3); // equal values of a type with fields: nothing logged
        assertThat(sprites.changeCountSince(since)).isZero();
        ComponentsTest.Sprite[] tinted = first.clone();
        tinted[1] = new ComponentsTest.Sprite("b", 2);
        sprites.setAll(nodes, tinted, 0, 3);
        List<String> logged = new ArrayList<>();
        sprites.changesSince(since, (node, changes) -> logged.add(node + ":" + changes));
        assertThat(logged).containsExactly(nodes[1] + ":" + (ComponentStore.CHANGED | ComponentsTest.Sprite.TINT));
        assertThatIllegalArgumentException().isThrownBy(() -> sprites.setAll(new long[] {nodes[0], 12345L},
            first, 0, 2));
        assertThat(sprites.get(nodes[0])).isEqualTo(first[0]); // checked first: nothing changed

        // A store with fewer components than nodes removed looks through its own; one with more, through the nodes
        long kept = scene.create();
        scene.components().set(kept, LightSource.TYPE, LightSource.point(1, 1, 1, 1));
        long bare = scene.create(); // in a subtree removed, without a sprite
        scene.setParent(bare, nodes[2]);
        long removing = clock.now();
        scene.remove(new long[] {nodes[0], nodes[2]}, 0, 2);
        assertThat(scene.components().store(LightSource.TYPE).size()).isEqualTo(1);
        assertThat(sprites.size()).isEqualTo(1);
        assertThat(sprites.changeCountSince(removing)).isEqualTo(2);
        scene.remove(new long[] {nodes[0]}, 0, 1); // removed already: nothing logged
        assertThat(scene.changeCountSince(removing)).isEqualTo(3);
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(
            () -> scene.remove(new long[1], 0, 2));
    }

    @Test
    void aBatchChecksWhatItIsGivenAndGrowsItsComponents() {
        SceneBatch batch = new SceneBatch(2);
        int root = batch.add();
        int child = batch.add(root);
        assertThat(batch.parent(child)).isEqualTo(root);
        assertThat(batch.parent(root)).isEqualTo(-1);
        assertThatIllegalArgumentException().isThrownBy(() -> batch.parent(2));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setTranslation(root, Double.NaN, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setRotation(root, 0, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(
            () -> batch.setRotation(root, Double.POSITIVE_INFINITY, 0, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setScale(root, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setScale(root, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setScale(root, 1, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setScale(root, 1, 1, Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(
            () -> batch.setLocalBounds(root, 0, 0, 0, Double.NaN, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> batch.setGroup(-1));
        batch.set(root, BatchSpawnTest.TAG, new BatchSpawnTest.Tag("first")); // the type's list made small
        for (int i = 0; i < 20; i++) { // past the room the component lists start with
            batch.add(root);
        }
        for (int node = 21; node >= 0; node--) { // backwards: each set looks for the node first
            batch.set(node, BatchSpawnTest.TAG, new BatchSpawnTest.Tag("n" + node));
        }
        for (int t = 0; t < 6; t++) { // more types than the batch starts with room for
            @SuppressWarnings("unchecked")
            ComponentType<Integer> type = (ComponentType<Integer>) ComponentsTest.MANY[t];
            batch.set(child, type, t);
        }
        assertThat(batch.get(root, BatchSpawnTest.WEIGHT)).isNull();
        @SuppressWarnings("unchecked")
        ComponentType<Integer> fifth = (ComponentType<Integer>) ComponentsTest.MANY[5];
        assertThat(batch.get(child, fifth)).isEqualTo(5);
        assertThat(batch.get(root, fifth)).isNull(); // the type there, the node not
        batch.setMobility(child, Mobility.STATIC); // static, no bounds: not in the tree
        batch.setMobility(2, Mobility.STATIC);
        batch.setLocalBounds(2, 0, 0, 0, 1, 1, 1);
        batch.prepare();
        assertThat(batch.staticNodes()).containsExactly(2);
        long[] handles = new long[batch.size()];
        scene.attach(batch, NodeHandle.NONE, handles);
        assertThat(scene.components().get(handles[21], BatchSpawnTest.TAG)).isEqualTo(new BatchSpawnTest.Tag("n21"));
        assertThat(scene.mobility(handles[child])).isEqualTo(Mobility.STATIC);
        scene.attach(new SceneBatch(), NodeHandle.NONE, new long[0]); // nothing
        assertThatIllegalArgumentException().isThrownBy(() -> scene.attach(batch, NodeHandle.NONE, new long[1]));

        // Detached, every node's components come along, into lists that grow
        SceneBatch out = new SceneBatch(2);
        scene.detach(handles[root], out);
        assertThat(out.size()).isEqualTo(22);
        assertThat(out.get(0, BatchSpawnTest.TAG)).isEqualTo(new BatchSpawnTest.Tag("n0"));
        int detachedChild = -1;
        for (int node = 0; node < out.size(); node++) {
            if (out.get(node, fifth) != null) {
                detachedChild = node;
            }
        }
        assertThat(detachedChild).isPositive();

        // A cleared batch keeps its types: one not set again attaches nothing of it
        batch.clear();
        int alone = batch.add();
        batch.set(alone, BatchSpawnTest.TAG, new BatchSpawnTest.Tag("alone"));
        long single = scene.attach(batch, NodeHandle.NONE);
        assertThat(scene.components().get(single, fifth)).isNull();
    }

    @Test
    void changesComeInBatchesTheirVisitorsReadWithinBounds() {
        SceneGraph tiny = new SceneGraph(clock, 1, 1); // the smallest log there is: it grows as it must
        long since = clock.now();
        long[] made = new long[3];
        tiny.create(NodeHandle.NONE, made, 0, 3);
        tiny.update();
        List<ChangeBatch> seen = new ArrayList<>();
        tiny.changesSince(since, new NodeChange.Visitor() {
            @Override
            public void changed(long node, int changes) {
            }

            @Override
            public void changed(ChangeBatch batch) {
                seen.add(batch);
                assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> batch.node(3));
                assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> batch.changes(-1));
            }
        });
        assertThat(seen).hasSize(1);
        long[] all = {made[0], made[1], made[2]};
        long removing = clock.now();
        tiny.remove(all, 0, 3);
        assertThat(tiny.changeCountSince(removing)).isEqualTo(3);
        long[] again = new long[2];
        long gone = clock.now();
        tiny.create(NodeHandle.NONE, again, 0, 2);
        tiny.remove(again, 0, 2); // made and removed before the update: only their removal logged
        tiny.update();
        assertThat(tiny.changeCountSince(gone)).isEqualTo(2);
    }
}
