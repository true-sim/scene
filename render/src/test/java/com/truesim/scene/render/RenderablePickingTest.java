package com.truesim.scene.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.Cone;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.Sphere;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.PickHits;
import com.truesim.scene.core.PickMesh;
import com.truesim.scene.core.Pickable;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ScenePicker;
import com.truesim.scene.core.SpatialIndex;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RenderablePickingTest {

    private static final AssetRef<MeshData> QUAD = AssetRef.of(RenderAssets.MESH, new AssetId(7, 1));
    private static final AssetRef<MeshData> SKINNED = AssetRef.of(RenderAssets.MESH, new AssetId(7, 2));
    private static final AssetRef<MeshData> MISSING = AssetRef.of(RenderAssets.MESH, new AssetId(7, 3));
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /** A unit square in z = 0, its corners weighing joint 0 alone when {@code skinned}. */
    private static MeshData quad(boolean skinned) {
        return quad(skinned, false);
    }

    /** {@link #quad(boolean)}, with a morph target lifting every corner 1 along +Z when {@code morph}. */
    private static MeshData quad(boolean skinned, boolean morph) {
        float[] positions = {-0.5f, -0.5f, 0, 0.5f, -0.5f, 0, 0.5f, 0.5f, 0, -0.5f, 0.5f, 0};
        MeshData.Builder b = MeshData.builder(skinned ? VertexFormat.of(VertexAttribute.JOINTS,
            VertexAttribute.WEIGHTS) : VertexFormat.POSITIONS_ONLY, 4).positions(positions);
        if (skinned) {
            float[] weights = new float[16];
            for (int v = 0; v < 4; v++) {
                weights[4 * v] = 1;
            }
            b.joints(new int[16]).weights(weights).jointPalette(new int[] {0});
        }
        if (morph) {
            b.morphTarget("lift", new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1}, null, null);
        }
        return b.meshlet(new int[] {0, 1, 2, 3}, new int[] {0, 1, 2, 0, 2, 3}, 0, new Sphere(0, 0, 0, 1), Cone.NONE)
            .build();
    }

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Renderables renderables = new Renderables(scene);
    private final SpatialIndex index = new SpatialIndex(scene);
    private final Map<AssetRef<MeshData>, MeshData> loaded = new HashMap<>();
    private final RenderablePicking picking = new RenderablePicking(renderables, loaded::get);
    private final ScenePicker picker = new ScenePicker(scene, index).resolver(picking);

    private long node(AssetRef<MeshData> mesh) {
        long node = scene.create();
        scene.setLocalBounds(node, -5, -5, -1, 5, 5, 1);
        renderables.set(node, mesh, List.of());
        scene.components().set(node, Pickable.TYPE, Pickable.mesh());
        return node;
    }

    private void update() {
        scene.update();
        index.update();
        picker.beginFrame();
    }

    @Test
    void staticMeshesShareTheirAssetsPickMesh() {
        MeshData quad = quad(false);
        loaded.put(QUAD, quad);
        long a = node(QUAD);
        long b = node(QUAD);
        long missing = node(MISSING);
        long plain = scene.create();
        assertThat(picking.geometry(a)).isSameAs(picking.geometry(b)).isSameAs(picking.pickMeshes().of(quad));
        assertThat(picking.geometry(missing)).as("not loaded: its box").isNull();
        assertThat(picking.geometry(plain)).as("draws nothing").isNull();
        assertThat(picking.pickMeshes().count()).isOne();
        PickMesh mesh = PickMeshes.build(quad);
        assertThat(mesh.triangleCount()).isEqualTo(2);
        assertThat(mesh.raycast(0.4, 0.4, 1, 0, 0, -1, 10)).isEqualTo(1);
        picking.pickMeshes().forget(quad);
        assertThat(picking.pickMeshes().count()).isZero();
    }

    @Test
    void skinnedMeshesArePickedWhereTheirJointsPoseThemNow() {
        loaded.put(SKINNED, quad(true));
        long body = node(SKINNED);
        long bone = scene.create();
        scene.setTranslation(bone, 2, 0, 0);
        renderables.setSkin(body, new SkinBinding(new long[] {bone}, IDENTITY));
        update();
        PickHits hits = new PickHits(2);
        assertThat(picker.pick(2.2, 0, 10, 0, 0, -1, 100, 1, hits)).as("where the bone took it").isOne();
        assertThat(hits.distance(0)).isCloseTo(10, within(1e-6));
        assertThat(picker.pick(0, 0, 10, 0, 0, -1, 100, 1, hits)).as("not where it rests").isZero();
        long poses = picking.poses();
        // the bone moves, turns and scales: posed again on the next pick that reaches it
        scene.setTranslation(bone, -2, 1, 0);
        scene.setRotation(bone, 0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4));
        scene.setScale(bone, 2, 2, 2);
        update();
        assertThat(picker.pick(-2, 1.9, 10, 0, 0, -1, 100, 1, hits)).isOne();
        assertThat(picking.poses()).isEqualTo(poses + 1);
        picker.beginFrame();
        picker.pick(-2, 1.9, 10, 0, 0, -1, 100, 1, hits);
        assertThat(picking.poses()).as("nothing moved: not posed again").isEqualTo(poses + 1);
        // the body moves: its vertices stay with the bone in the world, so they move back in its space
        scene.setTranslation(body, 1, 0, 0);
        update();
        assertThat(picker.pick(-2, 1.9, 10, 0, 0, -1, 100, 1, hits)).isOne();
        // a bone removed: its vertices stay at rest
        scene.remove(bone);
        update();
        assertThat(picker.pick(1.2, 0, 10, 0, 0, -1, 100, 1, hits)).isOne();
        picker.beginFrame();
        assertAllocationFree(() -> {
            picker.beginFrame();
            picker.pick(1.2, 0, 10, 0, 0, -1, 100, 1, hits);
        });
    }

    @Test
    void skinnedCopiesFollowTheirNodesMeshAndSkin() {
        loaded.put(SKINNED, quad(true));
        loaded.put(QUAD, quad(false));
        long body = node(SKINNED);
        long bone = scene.create();
        renderables.setSkin(body, new SkinBinding(new long[] {bone}, IDENTITY));
        update();
        PickMesh first = (PickMesh) picking.geometry(body);
        assertThat(picking.geometry(body)).as("kept").isSameAs(first);
        renderables.setSkin(body, new SkinBinding(new long[] {bone, bone}, new float[32]));
        assertThat(picking.geometry(body)).as("another skin").isNotSameAs(first);
        PickMesh second = (PickMesh) picking.geometry(body);
        loaded.put(SKINNED, quad(true));
        assertThat(picking.geometry(body)).as("the mesh reloaded").isNotSameAs(second);
        // the node's slot reused by another node
        scene.remove(body);
        long other = node(SKINNED);
        assertThat(NodeHandle.index(other)).isEqualTo(NodeHandle.index(body));
        renderables.setSkin(other, new SkinBinding(new long[] {bone}, IDENTITY));
        assertThat(picking.geometry(other)).isNotSameAs(second);
        // a skin on a mesh of no joints: drawn as it is, so picked so
        long rigid = node(QUAD);
        renderables.setSkin(rigid, new SkinBinding(new long[] {bone}, IDENTITY));
        assertThat(picking.geometry(rigid)).isSameAs(picking.pickMeshes().of(loaded.get(QUAD)));
    }

    @Test
    void onlyLeafMeshletsArePicked() {
        float[] positions = {-0.5f, -0.5f, 0, 0.5f, -0.5f, 0, 0.5f, 0.5f, 0, -0.5f, 0.5f, 5};
        MeshData mesh = MeshData.builder(VertexFormat.POSITIONS_ONLY, 4).positions(positions)
            .meshlet(new int[] {0, 1, 2}, new int[] {0, 1, 2}, 0, new Sphere(0, 0, 0, 1), Cone.NONE)
            .lod(new Sphere(0, 0, 0, 1), 0, new Sphere(0, 0, 0, 1), 1)
            .meshlet(new int[] {0, 2, 3}, new int[] {0, 1, 2}, 0, new Sphere(0, 0, 0, 1), Cone.NONE)
            .lod(new Sphere(0, 0, 0, 1), 1, new Sphere(0, 0, 0, 1), Double.POSITIVE_INFINITY)
            .build();
        assertThat(PickMeshes.build(mesh).triangleCount()).as("the coarser meshlet left out").isOne();
    }

    @Test
    void morphTargetsMoveTheVerticesBeforeSkinning() {
        AssetRef<MeshData> lifted = AssetRef.of(RenderAssets.MESH, new AssetId(7, 4));
        AssetRef<MeshData> skinnedLifted = AssetRef.of(RenderAssets.MESH, new AssetId(7, 5));
        loaded.put(lifted, quad(false, true));
        loaded.put(skinnedLifted, quad(true, true));
        long face = node(lifted);
        renderables.setMorphWeights(face, new double[] {0}, 0, 1);
        update();
        PickHits hits = new PickHits(1);
        picker.pick(0.2, 0.2, 10, 0, 0, -1, 100, 1, hits);
        assertThat(hits.distance(0)).as("no weights: at rest, the shared mesh").isCloseTo(10, within(1e-6));
        assertThat(picking.geometry(face)).isSameAs(picking.pickMeshes().of(loaded.get(lifted)));
        renderables.setMorphWeights(face, new double[] {0.5}, 0, 1);
        picker.beginFrame();
        picker.pick(0.2, 0.2, 10, 0, 0, -1, 100, 1, hits);
        assertThat(hits.distance(0)).as("lifted half way").isCloseTo(9.5, within(1e-3));
        long poses = picking.poses();
        picker.beginFrame();
        picker.pick(0.2, 0.2, 10, 0, 0, -1, 100, 1, hits);
        assertThat(picking.poses()).as("the same weights: not posed again").isEqualTo(poses);
        renderables.setMorphWeights(face, new double[] {1}, 0, 1);
        picker.beginFrame();
        picker.pick(0.2, 0.2, 10, 0, 0, -1, 100, 1, hits);
        assertThat(hits.distance(0)).isCloseTo(9, within(1e-3));
        // morphed, then skinned: lifted, then carried by the bone
        long body = node(skinnedLifted);
        long bone = scene.create();
        scene.setTranslation(bone, 2, 0, 0);
        renderables.setSkin(body, new SkinBinding(new long[] {bone}, IDENTITY));
        renderables.setMorphWeights(body, new double[] {1}, 0, 1);
        update();
        picker.pick(2.2, 0.2, 10, 0, 0, -1, 100, 1, hits);
        assertThat(hits.node(0)).isEqualTo(body);
        assertThat(hits.distance(0)).isCloseTo(9, within(1e-3));
    }

    @Test
    void affineMatricesInvertAndMultiply() {
        double[] m = {2, 0, 0, 1, 0, 0, -3, 2, 0, 1, 0, 3};
        double[] inverse = new double[12];
        RenderablePicking.invert(m, inverse);
        double[] product = new double[12];
        RenderablePicking.multiply(m, inverse, product, 0);
        double[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
        for (int i = 0; i < 12; i++) {
            assertThat(product[i]).isCloseTo(identity[i], within(1e-12));
        }
    }
}
