package com.truesim.scene.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.ChangeClock;
import com.truesim.common.state.ChangeCursor;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.ComponentType;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RenderableTest {

    static final AssetRef<MeshData> ROCK = AssetRef.of(RenderAssets.MESH, AssetId.parse("11111111111111111111111111111111"));
    static final AssetRef<MeshData> TREE = AssetRef.of(RenderAssets.MESH, AssetId.parse("22222222222222222222222222222222"));
    static final AssetRef<MaterialData> STONE =
        AssetRef.of(RenderAssets.MATERIAL, AssetId.parse("33333333333333333333333333333333"));
    static final AssetRef<MaterialData> MOSS =
        AssetRef.of(RenderAssets.MATERIAL, AssetId.parse("44444444444444444444444444444444"));
    static final int ADDED = ComponentStore.ADDED;
    static final int CHANGED = ComponentStore.CHANGED;
    static final int REMOVED = ComponentStore.REMOVED;

    private final ChangeClock clock = new ChangeClock();
    private final SceneGraph scene = new SceneGraph(clock);
    private final ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);

    /** The renderable changes after {@code since}, as "node changes" lines. */
    private List<String> changes(long since) {
        List<String> lines = new ArrayList<>();
        assertThat(renderables.changesSince(since, (node, changes) -> lines.add(line(node, changes)))).isTrue();
        return lines;
    }

    private static String line(long node, int changes) {
        return NodeHandle.toString(node) + " " + changes;
    }

    @Test
    void theTypesAreSavedUnderStableNames() {
        assertThat(Renderable.TYPE.name()).isEqualTo("truesim.renderable");
        assertThat(Renderable.TYPE.saved()).isTrue();
        assertThat(Renderable.TYPE.hasFields()).isTrue();
        assertThat(ComponentType.byName("truesim.renderable")).isSameAs(Renderable.TYPE);
        assertThat(MorphWeights.TYPE.name()).isEqualTo("truesim.morph-weights");
        assertThat(MorphWeights.TYPE.saved()).isTrue();
        assertThat(ComponentType.byName("truesim.morph-weights")).isSameAs(MorphWeights.TYPE);
        int fields = Renderable.MESH | Renderable.MATERIALS | Renderable.SHADOWS | Renderable.SKIN;
        assertThat(fields & ~ComponentStore.FIELDS).as("field bits only").isZero();
        assertThat(Integer.bitCount(fields)).isEqualTo(4);
    }

    @Test
    void nodesGetMeshesAndMaterials() {
        ChangeCursor cursor = clock.cursor();
        long rock = scene.create();
        long bare = scene.create();
        renderables.set(rock, Renderable.of(ROCK, List.of(STONE, MOSS)));
        renderables.set(bare, Renderable.of(TREE, List.of()));
        assertThat(renderables.size()).isEqualTo(2);
        assertThat(renderables.has(rock)).isTrue();
        Renderable r = renderables.get(rock);
        assertThat(r.mesh()).isEqualTo(ROCK);
        assertThat(r.materials()).containsExactly(STONE, MOSS);
        assertThat(r.castsShadows()).isTrue();
        assertThat(r.skin()).isNull();
        // Submesh s takes material s, those past the end the last one; none: the default.
        assertThat(r.material(0)).isEqualTo(STONE);
        assertThat(r.material(1)).isEqualTo(MOSS);
        assertThat(r.material(5)).isEqualTo(MOSS);
        assertThat(renderables.get(bare).material(0)).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> r.material(-1));
        assertThat(changes(cursor.mark())).containsExactly(line(rock, ADDED), line(bare, ADDED));
        // Changes are logged once each with the fields that changed, and only when something changes.
        renderables.set(rock, renderables.get(rock).withMesh(TREE));
        renderables.set(rock, renderables.get(rock).withMesh(TREE));
        renderables.set(rock, renderables.get(rock).withMaterials(List.of(MOSS)));
        renderables.set(rock, renderables.get(rock).withMaterials(List.of(MOSS)));
        renderables.set(rock, renderables.get(rock).withCastsShadows(false));
        renderables.set(rock, renderables.get(rock).withCastsShadows(false));
        renderables.set(bare, Renderable.of(TREE, List.of()));        // the same: nothing
        renderables.set(bare, Renderable.of(ROCK, List.of()));        // the mesh
        renderables.set(bare, Renderable.of(ROCK, List.of(STONE)));   // the materials
        renderables.set(bare, Renderable.of(TREE, List.of(MOSS)));    // both
        assertThat(changes(cursor.mark())).containsExactly(line(rock, CHANGED | Renderable.MESH),
            line(rock, CHANGED | Renderable.MATERIALS), line(rock, CHANGED | Renderable.SHADOWS),
            line(bare, CHANGED | Renderable.MESH), line(bare, CHANGED | Renderable.MATERIALS),
            line(bare, CHANGED | Renderable.MESH | Renderable.MATERIALS));
        assertThat(renderables.get(rock).castsShadows()).isFalse();
        // The material list is a copy.
        List<AssetRef<MaterialData>> list = new ArrayList<>(List.of(STONE));
        renderables.set(rock, renderables.get(rock).withMaterials(list));
        list.add(MOSS);
        assertThat(renderables.get(rock).materials()).containsExactly(STONE);
        assertThat(renderables.remove(rock)).isTrue();
        assertThat(renderables.remove(rock)).as("nothing left to remove").isFalse();
        assertThat(renderables.has(rock)).isFalse();
        assertThat(renderables.size()).isEqualTo(1);
        assertThat(changes(cursor.mark()))
            .containsExactly(line(rock, CHANGED | Renderable.MATERIALS), line(rock, REMOVED));
    }

    @Test
    void valuesAreEqualByTheirFields() {
        Renderable rock = Renderable.of(ROCK, List.of(STONE));
        assertThat(rock).isEqualTo(Renderable.of(ROCK, new ArrayList<>(List.of(STONE))))
            .hasSameHashCodeAs(Renderable.of(ROCK, List.of(STONE))).isNotEqualTo(rock.withMesh(TREE))
            .isNotEqualTo(rock.withCastsShadows(false)).isNotEqualTo("rock");
        assertThat(rock.withCastsShadows(false).withCastsShadows(true)).isEqualTo(rock);
        assertThat(rock.withCastsShadows(false))
            .hasSameHashCodeAs(Renderable.of(ROCK, List.of(STONE)).withCastsShadows(false));
        assertThat(rock.toString()).contains("Renderable");
        assertThat(rock.withCastsShadows(false).toString()).contains("no shadows");
        SkinBinding skin = new SkinBinding(new long[] {1}, new float[16]);
        assertThat(rock.withSkin(skin)).hasSameHashCodeAs(rock.withSkin(new SkinBinding(new long[] {1}, new float[16])))
            .isNotEqualTo(rock);
        assertThat(rock.withSkin(skin).toString()).contains("skinned");
    }

    @Test
    void skinsDeformRenderables() {
        ChangeCursor cursor = clock.cursor();
        long body = scene.create();
        long bone = scene.create(body);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        SkinBinding skin = new SkinBinding(new long[] {bone}, identity);
        renderables.set(body, Renderable.of(ROCK, List.of()));
        cursor.mark();
        assertThat(renderables.get(body).skin()).isNull();
        renderables.set(body, renderables.get(body).withSkin(skin));
        renderables.set(body, renderables.get(body).withSkin(new SkinBinding(new long[] {bone}, identity.clone())));
        renderables.set(body, renderables.get(body).withMesh(TREE)); // the skin stays
        assertThat(renderables.get(body).skin()).isEqualTo(skin);
        renderables.set(body, renderables.get(body).withSkin(null));
        assertThat(changes(cursor.mark())).containsExactly(line(body, CHANGED | Renderable.SKIN),
            line(body, CHANGED | Renderable.MESH), line(body, CHANGED | Renderable.SKIN));
        // Bindings: copied, equal by value.
        long[] joints = {bone, body};
        float[] matrices = new float[32];
        matrices[17] = 3;
        SkinBinding two = new SkinBinding(joints, matrices);
        joints[0] = 0;
        matrices[17] = 0;
        assertThat(two.jointCount()).isEqualTo(2);
        assertThat(two.joint(0)).isEqualTo(bone);
        assertThat(two.inverseBind(1, 1)).isEqualTo(3);
        assertThatThrownBy(() -> two.inverseBind(0, 16)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(skin).isEqualTo(new SkinBinding(new long[] {bone}, identity))
            .hasSameHashCodeAs(new SkinBinding(new long[] {bone}, identity)).isNotEqualTo(two).isNotEqualTo("skin")
            .isNotEqualTo(new SkinBinding(new long[] {body}, identity))
            .isNotEqualTo(new SkinBinding(new long[] {bone}, new float[16]));
        assertThatIllegalArgumentException().isThrownBy(() -> new SkinBinding(new long[0], new float[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> new SkinBinding(new long[1], new float[15]));
    }

    @Test
    void morphWeightsAreAComponentWrittenInPlace() {
        long face = scene.create();
        ComponentStore<MorphWeights> morphs = scene.components().store(MorphWeights.TYPE);
        assertThat(morphs.has(face)).isFalse();
        MorphWeights.write(scene, face, new double[] {9, 0.25, 0.5, 9}, 1, 2);
        MorphWeights weights = morphs.get(face);
        assertThat(weights.count()).isEqualTo(2);
        assertThat(weights.weight(1)).isEqualTo(0.5f);
        assertThat(weights.weight(2)).as("past those set").isZero();
        long version = morphs.version();
        MorphWeights.write(scene, face, new double[] {1}, 0, 1); // fewer: kept in the same room, not logged
        assertThat(morphs.get(face)).isSameAs(weights);
        assertThat(morphs.version()).isEqualTo(version);
        assertThat(weights.count()).isOne();
        assertThat(weights.weight(1)).isZero();
        weights.set(new double[] {1, 2, 3}, 0, 3); // more: more room
        assertThat(weights.weight(2)).isEqualTo(3);
        assertThat(new MorphWeights().count()).isZero();
        assertThatThrownBy(() -> weights.weight(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> weights.set(new double[1], 0, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        scene.remove(face);
        assertThat(morphs.has(face)).as("gone with the node").isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> MorphWeights.write(scene, face, new double[1], 0, 1));
        long other = scene.create();
        clock.ownedBy(new Thread(() -> { }));
        assertThatIllegalStateException().isThrownBy(() -> MorphWeights.write(scene, other, new double[1], 0, 1))
            .withMessageContaining("Morph weights change on");
        clock.ownedBy(Thread.currentThread());
        MorphWeights.write(scene, other, new double[1], 0, 1); // the owner may
        assertThat(morphs.has(other)).isTrue();
    }

    @Test
    void misuseIsChecked() {
        long node = scene.create();
        assertThatNullPointerException().isThrownBy(() -> Renderable.of(null, List.of()));
        assertThatNullPointerException().isThrownBy(() -> Renderable.of(ROCK, null));
        assertThatNullPointerException().isThrownBy(() -> Renderable.of(ROCK, List.of()).withMesh(null));
        scene.remove(node);
        assertThatIllegalArgumentException().isThrownBy(() -> renderables.set(node, Renderable.of(ROCK, List.of())))
            .withMessageContaining("Not a node of the scene");
    }

    @Test
    void onlyTheOwnerChangesRenderables() {
        long node = scene.create();
        renderables.set(node, Renderable.of(ROCK, List.of()));
        Thread other = new Thread(() -> { });
        clock.ownedBy(other);
        assertThatIllegalStateException().isThrownBy(() -> renderables.set(node, Renderable.of(TREE, List.of())));
        assertThatIllegalStateException().isThrownBy(() -> renderables.remove(node));
        assertThat(renderables.get(node).mesh()).as("refused changes change nothing").isEqualTo(ROCK);
        clock.ownedBy(Thread.currentThread());
        renderables.set(node, Renderable.of(TREE, List.of())); // the owner may
    }

    @Test
    void removedNodesLoseTheirRenderablesAtOnce() {
        ChangeCursor cursor = clock.cursor();
        long a = scene.create();
        long b = scene.create();
        renderables.set(a, Renderable.of(ROCK, List.of()));
        renderables.set(b, Renderable.of(ROCK, List.of()));
        cursor.mark();
        scene.remove(a);
        assertThat(renderables.has(a)).isFalse();
        assertThat(renderables.has(b)).isTrue();
        assertThat(changes(cursor.mark())).containsExactly(line(a, REMOVED));
        // A new node in the removed one's slot: the old renderable went first.
        scene.remove(b);
        long c = scene.create();
        assertThat(NodeHandle.index(c)).isEqualTo(NodeHandle.index(b));
        assertThat(renderables.has(c)).isFalse();
        renderables.set(c, Renderable.of(TREE, List.of()));
        assertThat(changes(cursor.mark())).containsExactly(line(b, REMOVED), line(c, ADDED));
        assertThat(renderables.size()).isEqualTo(1);
    }

    @Test
    void updatesAndReadsAllocateNothing() {
        long node = scene.create();
        Renderable stone = Renderable.of(ROCK, List.of(STONE));
        // Values swapped between are kept: setting them allocates nothing.
        Renderable[] looks = {stone, stone.withMaterials(List.of(MOSS)), stone.withMaterials(List.of(STONE, MOSS)),
            stone.withCastsShadows(false), stone.withMesh(TREE)};
        renderables.set(node, stone);
        long[] since = {clock.now()};
        int[] seen = {0};
        int[] turn = {0};
        ComponentStore.Visitor count = (n, changes) -> seen[0]++;
        double[] weights = {0.5, 0.25};
        MorphWeights.write(scene, node, weights, 0, 2);
        ComponentStore<MorphWeights> morphs = scene.components().store(MorphWeights.TYPE);
        assertAllocationFree(() -> {
            scene.setTranslation(node, seen[0], 0, 0);
            scene.update();
            renderables.set(node, looks[turn[0]++ % looks.length]);
            renderables.changesSince(since[0], count);
            since[0] = clock.now();
            renderables.get(node).material(3);
            weights[0] = seen[0];
            MorphWeights.write(scene, node, weights, 0, 2);
            morphs.get(node).weight(1);
        });
    }

    @Test
    void settersChangeOneFieldAndLogJustItsBit() {
        ChangeCursor cursor = clock.cursor();
        long rock = scene.create();
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        SkinBinding skin = new SkinBinding(new long[] {rock}, identity);
        renderables.set(rock, Renderable.of(ROCK, List.of(STONE)));
        Renderable first = renderables.get(rock);
        cursor.mark();
        Renderable.setCastsShadows(scene, rock, false);
        Renderable.setMaterials(scene, rock, new ArrayList<>(List.of(MOSS))); // copied, as the value keeps it
        Renderable.setMesh(scene, rock, TREE);
        Renderable.setSkin(scene, rock, skin);
        Renderable.setSkin(scene, rock, new SkinBinding(new long[] {rock}, identity.clone())); // equal: nothing
        Renderable.setMaterials(scene, rock, List.of(MOSS));                                    // the same: nothing
        assertThat(changes(cursor.mark())).containsExactly(line(rock, CHANGED | Renderable.SHADOWS),
            line(rock, CHANGED | Renderable.MATERIALS), line(rock, CHANGED | Renderable.MESH),
            line(rock, CHANGED | Renderable.SKIN));
        Renderable now = renderables.get(rock);
        assertThat(now.mesh()).isEqualTo(TREE);
        assertThat(now.materials()).containsExactly(MOSS);
        assertThat(now.castsShadows()).isFalse();
        assertThat(now.skin()).isEqualTo(skin);
        assertThat(first.castsShadows()).as("values stay as they were made").isTrue();
        // Back to what it was: the value it had, reused.
        Renderable.setSkin(scene, rock, null);
        Renderable.setMesh(scene, rock, ROCK);
        Renderable.setMaterials(scene, rock, List.of(STONE));
        Renderable.setCastsShadows(scene, rock, true);
        assertThat(renderables.get(rock)).isSameAs(first);
        Renderable.Recent recent = scene.components().get(rock, Renderable.RECENT_TYPE);
        assertThat(recent.size()).isEqualTo(5);
        assertThat(Renderable.RECENT_TYPE.saved()).as("runtime state").isFalse();
        // More looks than are kept: the least recently used go.
        for (int i = 0; i < 2 * Renderable.RECENT; i++) {
            Renderable.setMaterials(scene, rock, List.of(i % 2 == 0 ? STONE : MOSS, i % 3 == 0 ? STONE : MOSS,
                AssetRef.of(RenderAssets.MATERIAL, AssetId.parse(String.format("%032x", 100 + i)))));
        }
        assertThat(recent.size()).isEqualTo(Renderable.RECENT);
        // Misuse.
        long bare = scene.create();
        assertThatIllegalArgumentException().isThrownBy(() -> Renderable.setCastsShadows(scene, bare, false))
            .withMessageContaining("No renderable");
        assertThatNullPointerException().isThrownBy(() -> Renderable.setMesh(scene, rock, null));
        assertThatNullPointerException().isThrownBy(() -> Renderable.setMaterials(scene, rock, null));
        clock.ownedBy(new Thread(() -> { }));
        assertThatIllegalStateException().isThrownBy(() -> Renderable.setCastsShadows(scene, rock, false));
    }

    @Test
    void togglingShadowsAndSwappingMaterialsEveryFrameAllocatesNothing() {
        long rock = scene.create();
        renderables.set(rock, Renderable.of(ROCK, List.of(STONE)));
        List<AssetRef<MaterialData>> stone = List.of(STONE);
        List<AssetRef<MaterialData>> mossy = List.of(MOSS, STONE);
        long[] since = {clock.now()};
        int[] frame = {0};
        int[] shadows = {0};
        int[] materials = {0};
        int[] other = {0};
        ComponentStore.Visitor count = (node, changes) -> {
            if (changes == (CHANGED | Renderable.SHADOWS)) {
                shadows[0]++;
            } else if (changes == (CHANGED | Renderable.MATERIALS)) {
                materials[0]++;
            } else {
                other[0]++;
            }
        };
        Runnable oneFrame = () -> {
            boolean even = frame[0]++ % 2 == 0;
            Renderable.setCastsShadows(scene, rock, !even);
            Renderable.setMaterials(scene, rock, even ? mossy : stone);
            scene.update();
            renderables.changesSince(since[0], count);
            since[0] = clock.now();
        };
        // Warmed past the change log's growth (two entries a frame, up to ComponentStore.MAX_LOG); then rounds of
        // 10,000 frames.
        assertAllocationFree(oneFrame, ComponentStore.MAX_LOG, 10_000, 5);
        assertThat(shadows[0]).as("a shadow change every frame").isEqualTo(frame[0]);
        assertThat(materials[0]).as("a material change every frame").isEqualTo(frame[0]);
        assertThat(other[0]).isZero();
        assertThat(scene.components().get(rock, Renderable.RECENT_TYPE).size()).isEqualTo(4);
    }
}
