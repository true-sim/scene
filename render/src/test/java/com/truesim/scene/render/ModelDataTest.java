package com.truesim.scene.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.state.ChangeClock;
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
}
