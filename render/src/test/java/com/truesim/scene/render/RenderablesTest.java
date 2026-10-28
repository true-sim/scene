package com.truesim.scene.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.ChangeCursor;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderablesTest {

    static final AssetRef<MeshData> ROCK = AssetRef.of(RenderAssets.MESH, AssetId.parse("11111111111111111111111111111111"));
    static final AssetRef<MeshData> TREE = AssetRef.of(RenderAssets.MESH, AssetId.parse("22222222222222222222222222222222"));
    static final AssetRef<MaterialData> STONE =
        AssetRef.of(RenderAssets.MATERIAL, AssetId.parse("33333333333333333333333333333333"));
    static final AssetRef<MaterialData> MOSS =
        AssetRef.of(RenderAssets.MATERIAL, AssetId.parse("44444444444444444444444444444444"));

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock);
    private final Renderables renderables = new Renderables(scene);

    /** The renderable changes after {@code since}, as "node changes" lines. */
    private List<String> changes(Renderables of, long since) {
        List<String> lines = new ArrayList<>();
        assertThat(of.changesSince(since, (node, changes) -> lines.add(NodeHandle.toString(node) + " " + changes)))
            .isTrue();
        return lines;
    }

    private static String line(long node, int changes) {
        return NodeHandle.toString(node) + " " + changes;
    }

    @Test
    void nodesGetMeshesAndMaterials() {
        ChangeCursor cursor = clock.cursor();
        long rock = scene.create();
        long bare = scene.create();
        renderables.set(rock, ROCK, List.of(STONE, MOSS));
        renderables.set(bare, TREE, List.of());
        assertThat(renderables.scene()).isSameAs(scene);
        assertThat(renderables.size()).isEqualTo(2);
        assertThat(renderables.has(rock)).isTrue();
        assertThat(renderables.has(NodeHandle.NONE)).isFalse();
        assertThat(renderables.has(NodeHandle.of(5000, 1))).isFalse();
        assertThat(renderables.mesh(rock)).isEqualTo(ROCK);
        assertThat(renderables.materials(rock)).containsExactly(STONE, MOSS);
        assertThat(renderables.castsShadows(rock)).isTrue();
        // Submesh s takes material s, those past the end the last one; none: the default.
        assertThat(renderables.material(rock, 0)).isEqualTo(STONE);
        assertThat(renderables.material(rock, 1)).isEqualTo(MOSS);
        assertThat(renderables.material(rock, 5)).isEqualTo(MOSS);
        assertThat(renderables.material(bare, 0)).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> renderables.material(rock, -1));
        assertThat(changes(renderables, cursor.mark()))
            .containsExactly(line(rock, Renderables.ADDED), line(bare, Renderables.ADDED));
        // Changes are logged once each, and only when something changes.
        renderables.setMesh(rock, TREE);
        renderables.setMesh(rock, TREE);
        renderables.setMaterials(rock, List.of(MOSS));
        renderables.setMaterials(rock, List.of(MOSS));
        renderables.setCastsShadows(rock, false);
        renderables.setCastsShadows(rock, false);
        renderables.set(bare, TREE, List.of());                // the same: nothing
        renderables.set(bare, ROCK, List.of());                // the mesh
        renderables.set(bare, ROCK, List.of(STONE));           // the materials
        renderables.set(bare, TREE, List.of(MOSS));            // both
        assertThat(changes(renderables, cursor.mark())).containsExactly(line(rock, Renderables.MESH),
            line(rock, Renderables.MATERIALS), line(rock, Renderables.SHADOWS), line(bare, Renderables.MESH),
            line(bare, Renderables.MATERIALS), line(bare, Renderables.MESH | Renderables.MATERIALS));
        assertThat(renderables.castsShadows(rock)).isFalse();
        // The material list is a copy.
        List<AssetRef<MaterialData>> list = new ArrayList<>(List.of(STONE));
        renderables.setMaterials(rock, list);
        list.add(MOSS);
        assertThat(renderables.materials(rock)).containsExactly(STONE);
        renderables.remove(rock);
        renderables.remove(rock); // nothing left to remove
        assertThat(renderables.has(rock)).isFalse();
        assertThat(renderables.size()).isEqualTo(1);
        assertThat(changes(renderables, cursor.mark()))
            .containsExactly(line(rock, Renderables.MATERIALS), line(rock, Renderables.REMOVED));
        List<Long> visited = new ArrayList<>();
        renderables.forEach(visited::add);
        assertThat(visited).containsExactly(bare);
    }

    @Test
    void misuseIsChecked() {
        long node = scene.create();
        assertThatIllegalArgumentException().isThrownBy(() -> renderables.mesh(node)).withMessageContaining("No renderable");
        assertThatIllegalArgumentException().isThrownBy(() -> renderables.setMesh(node, ROCK));
        assertThatNullPointerException().isThrownBy(() -> renderables.set(node, null, List.of()));
        renderables.set(node, ROCK, List.of());
        assertThatNullPointerException().isThrownBy(() -> renderables.setMesh(node, null));
        assertThatNullPointerException().isThrownBy(() -> renderables.set(node, ROCK, null));
        scene.remove(node);
        assertThatIllegalArgumentException().isThrownBy(() -> renderables.set(node, ROCK, List.of()))
            .withMessageContaining("Not a node of the scene");
        assertThatIllegalArgumentException().isThrownBy(() -> new Renderables(scene, 0));
        assertThatNullPointerException().isThrownBy(() -> new Renderables(null));
    }

    @Test
    void removedNodesLoseTheirRenderables() {
        ChangeCursor cursor = clock.cursor();
        long a = scene.create();
        long b = scene.create();
        renderables.set(a, ROCK, List.of());
        renderables.set(b, ROCK, List.of());
        scene.update();
        renderables.update();
        cursor.mark();
        scene.remove(a);
        scene.update();
        renderables.update();
        assertThat(renderables.has(a)).isFalse();
        assertThat(renderables.has(b)).isTrue();
        assertThat(changes(renderables, cursor.mark())).containsExactly(line(a, Renderables.REMOVED));
        // A new node in the removed one's slot, set before update(): the old renderable goes first.
        scene.remove(b);
        long c = scene.create();
        assertThat(NodeHandle.index(c)).isEqualTo(NodeHandle.index(b));
        renderables.set(c, TREE, List.of());
        assertThat(changes(renderables, cursor.mark()))
            .containsExactly(line(b, Renderables.REMOVED), line(c, Renderables.ADDED));
        scene.update();
        renderables.update();
        assertThat(renderables.has(c)).isTrue();
        assertThat(renderables.size()).isEqualTo(1);
    }

    @Test
    void fallingBehindTheSceneChecksEveryRenderable() {
        SceneGraph small = new SceneGraph(clock, 16, 4);
        Renderables onSmall = new Renderables(small);
        long kept = small.create();
        long gone = small.create();
        onSmall.set(kept, ROCK, List.of());
        onSmall.set(gone, ROCK, List.of());
        small.remove(gone);
        for (int i = 0; i < 10; i++) {
            small.create(); // more changes than the scene keeps
        }
        small.update();
        onSmall.update();
        assertThat(onSmall.has(gone)).isFalse();
        assertThat(onSmall.has(kept)).isTrue();
    }

    @Test
    void consumersThatFallBehindRebuild() {
        Renderables short_ = new Renderables(scene, 2);
        long since = clock.now();
        for (int i = 0; i < 3; i++) {
            short_.set(scene.create(), ROCK, List.of());
        }
        assertThat(short_.changesSince(since, (node, changes) -> { })).isFalse();
        assertThat(changes(short_, clock.now())).isEmpty();
    }

    @Test
    void manyNodesGrowTheStore() {
        for (int i = 0; i < 200; i++) {
            renderables.set(scene.create(), ROCK, List.of(STONE));
        }
        assertThat(renderables.size()).isEqualTo(200);
    }

    @Test
    void updatesAndReadsAllocateNothing() {
        long node = scene.create();
        renderables.set(node, ROCK, List.of(STONE));
        long[] since = {clock.now()};
        int[] seen = {0};
        Renderables.Visitor count = (n, changes) -> seen[0]++;
        assertAllocationFree(() -> {
            scene.setTranslation(node, seen[0], 0, 0);
            scene.update();
            renderables.update();
            renderables.setCastsShadows(node, !renderables.castsShadows(node));
            renderables.changesSince(since[0], count);
            since[0] = clock.now();
            renderables.material(node, 3);
        });
    }
}
