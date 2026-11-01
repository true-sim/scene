package com.truesim.scene.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.animation.AnimationAssets;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.Skeleton;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelDataTest {

    private static final AssetRef<MeshData> BODY = AssetRef.of(RenderAssets.MESH, new AssetId(1, 1));
    private static final AssetRef<MeshData> WHEEL = AssetRef.of(RenderAssets.MESH, new AssetId(1, 2));
    private static final AssetRef<MaterialData> PAINT = AssetRef.of(RenderAssets.MATERIAL, new AssetId(2, 1));
    private static final AssetRef<MaterialData> RUBBER = AssetRef.of(RenderAssets.MATERIAL, new AssetId(2, 2));

    private static ModelData car() {
        return new ModelData(List.of(
            ModelData.Node.of("car", -1, null, List.of()),
            new ModelData.Node("body", 0, 0, 0.5, 0, 0, 0, 0, 1, 1, 1, 1, BODY, List.of(PAINT)),
            new ModelData.Node("wheel", 0, 1, 0.3, 1.5, 0, 0.7071, 0, 0.7071, 2, 2, 2, WHEEL, List.of(RUBBER, PAINT)),
            new ModelData.Node("wheel.rear", 0, 1, 0.3, -1.5, 0, 0, 0, 1, 1, 1, 1, WHEEL, List.of(RUBBER))));
    }

    @Test
    void modelsSpawnTheirNodesUnderAParent() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        ModelData car = car();
        long garage = scene.create();
        long[] nodes = car.spawn(scene, renderables, garage);
        assertThat(nodes).hasSize(4);
        assertThat(scene.parent(nodes[0])).isEqualTo(garage);
        assertThat(scene.parent(nodes[2])).isEqualTo(nodes[0]);
        assertThat(scene.local(nodes[2]).translation().z()).isEqualTo(1.5);
        assertThat(scene.local(nodes[2]).scale().x()).isEqualTo(2);
        assertThat(renderables.has(nodes[0])).as("a group: nothing drawn").isFalse();
        assertThat(renderables.mesh(nodes[2])).isEqualTo(WHEEL);
        assertThat(renderables.materials(nodes[2])).containsExactly(RUBBER, PAINT);
        assertThat(car.references()).containsExactly(BODY, PAINT, WHEEL, RUBBER);
        assertThat(car.find("wheel")).isEqualTo(2);
        assertThat(car.find("trunk")).isEqualTo(-1);
        assertThat(car.bytes()).isPositive();
        assertThat(car).isEqualTo(car());
    }

    @Test
    void skinsNameTheirJointsAmongTheNodes() {
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        ModelData.Skin skin = new ModelData.Skin("arm", new int[] {1}, identity, 0);
        ModelData.Node body = new ModelData.Node("body", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), true, -1,
            List.of(), 0);
        ModelData.Node bone = ModelData.Node.of("bone", 0, null, List.of());
        ModelData model = new ModelData(List.of(body, bone), List.of(), List.of(skin));
        assertThat(model.skins()).containsExactly(skin);
        assertThat(bone.skin()).as("of no skin").isEqualTo(-1);
        assertThat(model.bytes()).isGreaterThan(new ModelData(List.of(body.withoutSkin(), bone)).bytes());
        // Copies in and out; equal by value.
        int[] joints = skin.joints();
        joints[0] = 5;
        assertThat(skin.joint(0)).isEqualTo(1);
        assertThat(skin.inverseBindMatrices()).containsExactly(identity);
        assertThat(skin.jointCount()).isOne();
        assertThat(skin.inverseBind(0, 15)).isEqualTo(1);
        ModelData.Skin same = new ModelData.Skin("arm", new int[] {1}, identity.clone(), 0);
        assertThat(same).isEqualTo(skin).hasSameHashCodeAs(skin).isNotEqualTo(null).isNotEqualTo("arm");
        assertThat(new ModelData.Skin("leg", new int[] {1}, identity, 0)).isNotEqualTo(skin);
        assertThat(new ModelData.Skin("arm", new int[] {0}, identity, 0)).isNotEqualTo(skin);
        assertThat(new ModelData.Skin("arm", new int[] {1}, new float[16], 0)).isNotEqualTo(skin);
        assertThat(new ModelData.Skin("arm", new int[] {1}, identity, -1)).isNotEqualTo(skin);
        assertThat(skin.toString()).contains("arm", "1 joints", "skeleton 0");
        org.assertj.core.api.Assertions.assertThatExceptionOfType(IndexOutOfBoundsException.class)
            .isThrownBy(() -> skin.inverseBind(0, 16));
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData.Skin("x", new int[0], new float[0], -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData.Skin("x", new int[1], new float[15], -1));
        // Nodes name skins of the model; skins name its nodes.
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData(List.of(body, bone)))
            .withMessageContaining("skin 0 is not one of the model's");
        ModelData.Node minus = new ModelData.Node("x", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), true, -1,
            List.of(), -2);
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData(List.of(minus)));
        for (int[] bad : new int[][] {{2}, {-1}}) {
            ModelData.Skin far = new ModelData.Skin("far", bad, identity, -1);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelData(List.of(body.withoutSkin(), bone), List.of(), List.of(far)))
                .withMessageContaining("is not a node");
        }
        for (int skeleton : new int[] {2, -2}) {
            ModelData.Skin rootless = new ModelData.Skin("r", new int[] {1}, identity, skeleton);
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new ModelData(List.of(body.withoutSkin(), bone), List.of(), List.of(rootless)))
                .withMessageContaining("skeleton");
        }
    }

    @Test
    void animationsAreNamedClipsPlayedOnItsSkeleton() {
        AssetRef<AnimationClip> walk = AssetRef.of(AnimationAssets.CLIP, new AssetId(3, 1));
        AssetRef<AnimationClip> run = AssetRef.of(AnimationAssets.CLIP, new AssetId(3, 2));
        ModelData car = car();
        ModelData model = new ModelData(car.nodes(), List.of(), List.of(), List.of(),
            List.of(new ModelData.Animation("walk", walk), new ModelData.Animation("run", run)));
        assertThat(model.animation("run")).isEqualTo(run);
        assertThatIllegalArgumentException().isThrownBy(() -> model.animation("fly"))
            .withMessageContaining("No animation fly");
        assertThat(car.animations()).isEmpty();
        assertThat(new ModelData(car.nodes(), List.of(), List.of(), List.of()).animations()).isEmpty();
        assertThatNullPointerException().isThrownBy(() -> new ModelData.Animation(null, walk));
        assertThatNullPointerException().isThrownBy(() -> new ModelData.Animation("walk", null));
        Skeleton skeleton = model.skeleton();
        assertThat(skeleton.size()).isEqualTo(4);
        assertThat(skeleton.name(2)).isEqualTo("wheel");
        assertThat(skeleton.parent(2)).isZero();
        assertThat(skeleton.rest(2, 0)).isEqualTo(1);
        assertThat(skeleton.rest(2, 4)).isEqualTo(0.7071);
        assertThat(skeleton.rest(2, 9)).isEqualTo(2);
    }

    @Test
    void camerasAreAsGltfAllowsAndNamedByNodes() {
        ModelData.Camera wide = ModelData.Camera.perspective("wide", 1.2, 0, 0.1, 0);
        ModelData.Camera map = ModelData.Camera.orthographic("map", 10, 5, 0, 100);
        assertThat(wide.orthographic()).isFalse();
        assertThat(map.orthographic()).isTrue();
        assertThat(ModelData.Camera.perspective("far", 1, 1.5, 0.1, 50).zfar()).isEqualTo(50);
        for (double[] bad : new double[][] {{0, 0, 0.1, 0}, {1, -1, 0.1, 0}, {1, 0, 0, 0}, {1, 0, 1, 0.5}}) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ModelData.Camera.perspective("bad", bad[0], bad[1], bad[2], bad[3]));
        }
        for (double[] bad : new double[][] {{0, 1, 0, 1}, {1, 0, 0, 1}, {1, 1, -1, 1}, {1, 1, 1, 1}}) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ModelData.Camera.orthographic("bad", bad[0], bad[1], bad[2], bad[3]));
        }
        ModelData.Node eye = new ModelData.Node("eye", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), true, -1,
            List.of(), -1, List.of(), 1);
        ModelData model = new ModelData(List.of(eye), List.of(), List.of(), List.of(wide, map));
        assertThat(model.nodes().get(0).camera()).isOne();
        assertThat(model.cameras()).containsExactly(wide, map);
        assertThat(eye.withoutSkin().camera()).as("kept").isOne();
        assertThat(ModelData.Node.of("plain", -1, null, List.of()).camera()).isEqualTo(-1);
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData(List.of(eye), List.of(), List.of(),
            List.of(wide))).withMessageContaining("camera 1 is not one of the model's");
        ModelData.Node minus = new ModelData.Node("x", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), true, -1,
            List.of(), -1, List.of(), -2);
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData(List.of(minus)));
    }

    @Test
    void parentsComeBeforeTheirChildren() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData(List.of(
            ModelData.Node.of("child", 1, null, List.of()), ModelData.Node.of("parent", -1, null, List.of()))))
            .withMessageContaining("Node 0's parent 1");
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData(List.of(
            ModelData.Node.of("lost", -2, null, List.of()))));
    }
    @Test
    void hiddenNodesAndTheirChildrenAreOnNoLayerAndLightsAreNamed() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Renderables renderables = new Renderables(scene);
        ModelData.Light lamp = new ModelData.Light("lamp", ModelData.LightKind.POINT, 1, 1, 1, 10, 0, 0, 0);
        ModelData model = new ModelData(List.of(
            new ModelData.Node("hidden", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), false, -1),
            new ModelData.Node("child", 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, BODY, List.of(PAINT), true, 0),
            new ModelData.Node("shown", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), true, -1)),
            List.of(lamp));
        long root = scene.create();
        long[] nodes = model.spawn(scene, renderables, root);
        assertThat(scene.layers(nodes[0])).isZero();
        assertThat(scene.layers(nodes[1])).as("under a hidden node").isZero();
        assertThat(scene.layers(nodes[2])).isEqualTo(scene.layers(root));
        assertThat(model.lights()).containsExactly(lamp);
        assertThat(model.nodes().get(1).light()).isZero();
        assertThat(car().lights()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData(List.of(
            new ModelData.Node("x", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), true, 0))))
            .withMessageContaining("light 0");
        assertThatIllegalArgumentException().isThrownBy(() -> new ModelData(List.of(
            new ModelData.Node("x", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), true, -2))));
    }
    @Test
    void linesAreCheckedAndNamedByNodes() {
        AssetRef<LineData> outline = AssetRef.of(RenderAssets.LINES, new AssetId(3, 1));
        ModelData model = new ModelData(List.of(new ModelData.Node("o", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null,
            List.of(), true, -1, List.of(outline))));
        assertThat(model.references()).containsExactly(outline);
        assertThat(car().nodes().get(0).lines()).isEmpty();
        LineData line = new LineData(LineData.Kind.LINES, new float[] {0, 0, 0, 1, 0, 0}, new float[8],
            new int[] {0, 1}, PAINT);
        assertThat(line.vertexCount()).isEqualTo(2);
        assertThat(line.bytes()).isPositive();
        new LineData(LineData.Kind.POINTS, new float[3], new float[0], new int[] {0}, PAINT);
        assertThatIllegalArgumentException().isThrownBy(() -> new LineData(LineData.Kind.LINES, new float[4],
            new float[0], new int[0], PAINT)).withMessageContaining("3 floats");
        assertThatIllegalArgumentException().isThrownBy(() -> new LineData(LineData.Kind.LINES, new float[3],
            new float[3], new int[0], PAINT)).withMessageContaining("4 floats");
        assertThatIllegalArgumentException().isThrownBy(() -> new LineData(LineData.Kind.LINES, new float[3],
            new float[0], new int[] {0}, PAINT)).withMessageContaining("two indices");
        org.assertj.core.api.Assertions.assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(
            () -> new LineData(LineData.Kind.POINTS, new float[3], new float[0], new int[] {1}, PAINT));
    }
}
