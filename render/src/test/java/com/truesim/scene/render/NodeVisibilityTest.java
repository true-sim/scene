package com.truesim.scene.render;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.AnimationPointer;
import com.truesim.scene.animation.Animator;
import com.truesim.scene.animation.Animators;
import com.truesim.scene.animation.Track;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Showing and hiding nodes with what is under them, as KHR_node_visibility, by hand and by animators. */
class NodeVisibilityTest {

    private static final AssetRef<MeshData> BODY = AssetRef.of(RenderAssets.MESH, new AssetId(1, 1));
    private final SceneGraph scene = new SceneGraph(new ChangeClock());

    @Test
    void hiddenNodesTakeTheirSubtreesOffEveryLayerAndGiveThemBack() {
        NodeVisibility visibility = new NodeVisibility(scene);
        long root = scene.create();
        long arm = scene.create(root);
        long hand = scene.create(arm);
        long finger = scene.create(hand);
        long thumb = scene.create(hand);
        long leg = scene.create(root);
        long other = scene.create();
        scene.setLayers(thumb, 6);
        int shown = scene.layers(root);
        assertThat(visibility.visible(root)).as("none seen yet").isTrue();
        assertThat(visibility.shown(root)).isTrue();
        visibility.setVisible(arm, false);
        assertThat(visibility.visible(arm)).isFalse();
        assertThat(visibility.shown(finger)).isFalse();
        assertThat(visibility.visible(finger)).as("not hidden itself").isTrue();
        assertThat(new int[] {scene.layers(arm), scene.layers(hand), scene.layers(finger), scene.layers(thumb)})
            .containsOnly(0);
        assertThat(scene.layers(leg)).as("a sibling's").isEqualTo(shown);
        assertThat(scene.layers(root)).isEqualTo(shown);
        // The hand hidden too: showing the arm leaves it hidden
        visibility.setVisible(hand, false);
        visibility.setVisible(hand, false); // twice: no change
        visibility.setVisible(arm, true);
        assertThat(scene.layers(arm)).isEqualTo(shown);
        assertThat(scene.layers(finger)).isZero();
        assertThat(visibility.shown(hand)).isFalse();
        visibility.setVisible(hand, true);
        visibility.setVisible(hand, true);
        assertThat(scene.layers(finger)).isEqualTo(shown);
        assertThat(scene.layers(thumb)).as("its own layers back").isEqualTo(6);
        assertThat(visibility.shown(finger)).isTrue();
        // A leaf, a root of its own, and nodes it never saw
        visibility.setVisible(leg, false);
        assertThat(scene.layers(leg)).isZero();
        visibility.setVisible(other, false);
        visibility.setVisible(other, true);
        assertThat(scene.layers(other)).isEqualTo(shown);
        long late = scene.create();
        assertThat(visibility.visible(late)).isTrue();
        assertThat(visibility.shown(late)).isTrue();
        assertThat(visibility.visible(root)).isTrue();
        assertAllocationFree(() -> {
            visibility.setVisible(arm, false);
            visibility.setVisible(arm, true);
        });
        // A removed node's slot reused: its state is forgotten
        scene.remove(leg);
        scene.update();
        long reused = scene.create(root);
        assertThat(NodeHandle.index(reused)).isEqualTo(NodeHandle.index(leg));
        assertThat(visibility.visible(reused)).isTrue();
        assertThat(visibility.shown(reused)).isTrue();
        visibility.setVisible(reused, true);
        assertThat(visibility.visible(leg)).as("forgotten").isTrue();
        assertThat(scene.layers(reused)).isEqualTo(shown);
        assertThatIllegalArgumentException().isThrownBy(() -> visibility.setVisible(leg, false))
            .withMessageContaining("Not a node");
    }

    @Test
    void modelsSpawnedWithItAreHiddenByItAndAnimatorsShowThem() {
        Renderables renderables = new Renderables(scene);
        ModelData model = new ModelData(List.of(
            new ModelData.Node("hidden", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), false, -1),
            new ModelData.Node("child", 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, BODY, List.of(), true, -1),
            new ModelData.Node("shown", -1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, null, List.of(), true, -1)));
        NodeVisibility visibility = new NodeVisibility(scene);
        long root = scene.create();
        long[] nodes = model.spawn(scene, renderables, root, visibility);
        assertThat(scene.layers(nodes[0])).isZero();
        assertThat(scene.layers(nodes[1])).isZero();
        assertThat(scene.layers(nodes[2])).isEqualTo(scene.layers(root));
        assertThat(visibility.visible(nodes[0])).isFalse();
        // Its visibility animated: shown from 0.5 s on
        AnimationPointer shownAt = new AnimationPointer("/nodes/0/extensions/KHR_node_visibility/visible",
            AnimationPointer.Target.VISIBILITY, 0, "visible", null, 0);
        AnimationPointer light = new AnimationPointer("/extensions/KHR_lights_punctual/lights/0/intensity",
            AnimationPointer.Target.LIGHT, 0, "intensity", null, 1);
        AnimationClip clip = new AnimationClip("show", List.of(
            new Track(shownAt, Track.Interpolation.STEP, new float[] {0, 0.5f, 1}, new float[] {0, 1, 1}),
            new Track(light, Track.Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 1})));
        Animators animators = new Animators(scene);
        animators.pointerSink(visibility);
        Animator animator = animators.add(model.skeleton(), nodes);
        animator.play(clip).loop(false);
        animators.update(0.25);
        assertThat(scene.layers(nodes[1])).isZero();
        animators.update(0.5);
        assertThat(scene.layers(nodes[0])).isEqualTo(scene.layers(root));
        assertThat(scene.layers(nodes[1])).isEqualTo(scene.layers(root));
    }
}
