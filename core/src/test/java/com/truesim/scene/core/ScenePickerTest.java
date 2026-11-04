package com.truesim.scene.core;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.Mat4;
import com.truesim.common.math.geometry.Frustum;
import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenePickerTest {

    /** Two triangles making a square in z = 0, from -0.25 to 0.25: half the width of the nodes' boxes. */
    private static PickMesh square() {
        return new PickMesh(new float[] {-0.25f, -0.25f, 0, 0.25f, -0.25f, 0, 0.25f, 0.25f, 0, -0.25f, 0.25f, 0},
            new int[] {0, 1, 2, 0, 2, 3});
    }

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final SpatialIndex index = new SpatialIndex(scene);
    private final ScenePicker picker = new ScenePicker(scene, index);
    private final Components components = scene.components();

    /** A node at {@code z} on the axis, a unit box around it, pickable as {@code pickable} (null: none). */
    private long node(double z, Pickable pickable) {
        long node = scene.create();
        scene.setTranslation(node, 0, 0, z);
        scene.setLocalBounds(node, -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);
        if (pickable != null) {
            components.set(node, Pickable.TYPE, pickable);
        }
        return node;
    }

    private void update() {
        scene.update();
        index.update();
        picker.beginFrame();
    }

    /** Picks down the -z axis from z = 100. */
    private int pick(PickHits hits) {
        return picker.pick(0, 0, 100, 0, 0, -1, 1000, Pickable.DEFAULT_LAYERS, hits);
    }

    @Test
    void picksPickablesNearestFirstForPassThrough() {
        long far = node(0, Pickable.bounds());
        long near = node(10, Pickable.bounds());
        long plain = node(20, null);
        long off = node(30, Pickable.bounds().enabled(false));
        long elsewhere = node(40, Pickable.bounds().layers(2));
        update();
        PickHits hits = new PickHits(4);
        assertThat(pick(hits)).isEqualTo(2);
        assertThat(hits.node(0)).isEqualTo(near);
        assertThat(hits.node(1)).isEqualTo(far);
        assertThat(hits.distance(0)).isCloseTo(89.5, within(1e-9));
        assertThat(hits.part(0)).as("a box has no parts").isEqualTo(-1);
        double[] point = new double[3];
        hits.point(1, point);
        assertThat(point[2]).isCloseTo(0.5, within(1e-9));
        assertThat(hits.indexOf(far)).isOne();
        assertThat(hits.indexOf(plain)).isEqualTo(-1);
        assertThat(hits.ray(5)).isEqualTo(-1);
        assertThat(hits.reach()).isEqualTo(1000);
        assertThat(picker.narrowTests()).as("only pickables on the layers are tested").isBetween(2L, 3L);
        // other pick layers
        assertThat(picker.pick(0, 0, 100, 0, 0, -1, 1000, 2, hits)).isOne();
        assertThat(hits.node(0)).isEqualTo(elsewhere);
        // switched on again
        components.get(off, Pickable.TYPE).enabled(true);
        picker.beginFrame();
        assertThat(pick(hits)).isEqualTo(3);
        assertThat(hits.node(0)).isEqualTo(off);
        // only as deep as there is room
        PickHits one = new PickHits(1);
        assertThat(pick(one)).isOne();
        assertThat(one.node(0)).isEqualTo(off);
        // hidden nodes (on no scene layer) and other scene layers are not picked
        scene.setLayers(off, 0);
        update();
        assertThat(pick(hits)).isEqualTo(2);
        picker.sceneLayers(2);
        assertThat(picker.sceneLayers()).isEqualTo(2);
        assertThat(pick(hits)).isZero();
        assertThat(picker.scene()).isSameAs(scene);
        assertThat(picker.index()).isSameAs(index);
    }

    @Test
    void aFramesPicksOfTheSameRayAreAnsweredOnce() {
        node(0, Pickable.bounds());
        node(10, Pickable.bounds());
        node(20, Pickable.bounds());
        update();
        PickHits a = new PickHits(2);
        PickHits b = new PickHits(2);
        PickHits deeper = new PickHits(3);
        PickHits shallow = new PickHits(1);
        pick(a);
        long raycasts = picker.raycasts();
        pick(b);
        assertThat(picker.cacheHits()).isOne();
        assertThat(picker.raycasts()).isEqualTo(raycasts);
        assertThat(b.count()).isEqualTo(2);
        pick(shallow);
        assertThat(shallow.count()).as("the nearest of the kept").isOne();
        assertThat(picker.cacheHits()).isEqualTo(2);
        pick(deeper);
        assertThat(deeper.count()).as("the kept pick stopped at 2: picked again").isEqualTo(3);
        assertThat(picker.cacheHits()).isEqualTo(2);
        // a ray that found fewer than it had room for is complete
        PickHits room = new PickHits(8);
        picker.pick(0, 0, 100, 0, 0, -1, 85, Pickable.DEFAULT_LAYERS, room);
        picker.pick(0, 0, 100, 0, 0, -1, 85, Pickable.DEFAULT_LAYERS, deeper);
        assertThat(deeper.count()).isOne();
        assertThat(picker.cacheHits()).isEqualTo(3);
        // another ray, other layers, another frame
        picker.pick(0, 0, 100, 0, 0, -1, 1000, 4, a);
        assertThat(a.count()).isZero();
        picker.pick(0.1, 0, 100, 0, 0, -1, 1000, Pickable.DEFAULT_LAYERS, a);
        picker.beginFrame();
        pick(b);
        assertThat(picker.cacheHits()).isEqualTo(3);
        // more rays than are kept: picked each time
        for (int i = 0; i < ScenePicker.CACHED_RAYS + 2; i++) {
            picker.pick(0, 0.01 * i, 100, 0, 0, -1, 1000, Pickable.DEFAULT_LAYERS, a);
        }
        long hits = picker.cacheHits();
        picker.pick(0, 0.01 * (ScenePicker.CACHED_RAYS + 1), 100, 0, 0, -1, 1000, Pickable.DEFAULT_LAYERS, a);
        assertThat(picker.cacheHits()).isEqualTo(hits);
    }

    @Test
    void meshesArePickedByTheirTrianglesInTheNodesSpace() {
        PickMesh mesh = square();
        long node = node(0, Pickable.mesh(mesh));
        scene.setRotation(node, 0, Math.sin(Math.PI / 4), 0, Math.cos(Math.PI / 4)); // the square faces +x now
        scene.setScale(node, 1, 4, 1);
        update();
        PickHits hits = new PickHits(2);
        assertThat(picker.pick(10, 0.8, 0, -1, 0, 0, 100, 1, hits)).as("within the scaled square").isOne();
        assertThat(hits.distance(0)).isCloseTo(10, within(1e-9));
        assertThat(hits.part(0)).isIn(0, 1);
        assertThat(mesh.built()).isTrue();
        assertThat(mesh.part()).isIn(0, 1);
        assertThat(picker.pick(10, 0.3, 0.45, -1, 0, 0, 100, 1, hits)).as("the box, not the triangles").isZero();
        assertThat(mesh.part()).isEqualTo(-1);
        // a mesh moved in place refits
        float[] p = mesh.positions();
        for (int i = 0; i < p.length; i += 3) {
            p[i + 2] = 0.4f;
        }
        mesh.refit();
        picker.beginFrame();
        assertThat(picker.pick(10, 0.8, 0, -1, 0, 0, 100, 1, hits)).isOne();
        assertThat(hits.distance(0)).as("moved towards the ray's origin").isCloseTo(9.6, within(1e-6));
        assertThat(mesh.triangleCount()).isEqualTo(2);
    }

    @Test
    void meshesWithoutGeometryAreResolvedOrHitByTheirBoxes() {
        long resolved = node(0, Pickable.mesh());
        long boxed = node(10, Pickable.mesh());
        update();
        PickHits hits = new PickHits(4);
        assertThat(pick(hits)).as("no resolver: their boxes").isEqualTo(2);
        PickMesh mesh = square();
        picker.resolver(node -> node == resolved ? mesh : null);
        assertThat(picker.resolver()).isNotNull();
        picker.beginFrame();
        assertThat(pick(hits)).isEqualTo(2);
        assertThat(hits.node(1)).isEqualTo(resolved);
        assertThat(hits.distance(1)).as("its square, at z 0").isCloseTo(100, within(1e-9));
        assertThat(hits.distance(0)).as("the box").isCloseTo(89.5, within(1e-9));
        assertThat(components.get(boxed, Pickable.TYPE).geometry()).isNull();
        components.get(boxed, Pickable.TYPE).geometry(mesh);
        picker.beginFrame();
        pick(hits);
        assertThat(hits.distance(0)).isCloseTo(90, within(1e-9));
    }

    @Test
    void customTestsDecide() {
        long sphere = node(0, Pickable.custom((node, box, ox, oy, oz, dx, dy, dz, max) -> box + 0.25));
        long never = node(10, Pickable.custom((node, box, ox, oy, oz, dx, dy, dz, max) -> Double.POSITIVE_INFINITY));
        update();
        PickHits hits = new PickHits(2);
        assertThat(pick(hits)).isOne();
        assertThat(hits.node(0)).isEqualTo(sphere);
        assertThat(hits.distance(0)).isCloseTo(99.75, within(1e-9));
        assertThat(components.get(never, Pickable.TYPE).test()).isNotNull();
        assertThat(picker.pick(0, 0, 100, 0, 0, -1, 99.6, 1, hits)).as("its box within reach, its shape not").isZero();
    }

    @Test
    void marqueesFindTheEnabledPickablesInTheirFrustum() {
        long a = node(-5, Pickable.bounds());
        long b = node(-8, Pickable.bounds());
        node(-9, null);
        node(-10, Pickable.bounds().enabled(false));
        node(-11, Pickable.bounds().layers(2));
        update();
        Frustum frustum = Frustum.fromMatrix(Mat4.perspective(1, 1, 0.1, 100));
        List<Long> found = new ArrayList<>();
        assertThat(picker.pickFrustum(frustum, 1, node -> found.add(node))).isPositive();
        assertThat(found).containsExactlyInAnyOrder(a, b);
        found.clear();
        picker.pickFrustum(frustum, 1, node -> !found.add(node));
        assertThat(found).hasSize(1);
    }

    @Test
    void picksAllocateNothing() {
        PickMesh mesh = square();
        node(0, Pickable.mesh(mesh));
        node(10, Pickable.bounds());
        node(20, null);
        update();
        PickHits hits = new PickHits(4);
        assertAllocationFree(() -> {
            picker.beginFrame();
            pick(hits);
            pick(hits);
        });
    }

    @Test
    void piecesAreChecked() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PickHits(0));
        PickHits hits = new PickHits(1);
        assertThat(hits.capacity()).isOne();
        assertThat(hits.add(1, 2, 3)).isTrue();
        assertThat(hits.add(4, 5, 6)).as("full").isFalse();
        hits.clear();
        assertThat(hits.count()).isZero();
        assertThatThrownBy(() -> hits.node(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new PickMesh(new float[2], new int[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> new PickMesh(new float[3], new int[2]));
        assertThatIllegalArgumentException().isThrownBy(() -> new PickMesh(new float[3], new int[] {0, 0, 1}));
        assertThatIllegalArgumentException().isThrownBy(() -> new PickMesh(new float[3], new int[] {0, 0, -1}));
        PickMesh empty = new PickMesh(new float[0], new int[0]);
        assertThat(empty.raycast(0, 0, 0, 1, 0, 0, 10)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(empty.built()).isFalse();
        assertThat(((PickGeometry) (node, ox, oy, oz, dx, dy, dz, max) -> 1).part()).isEqualTo(-1);
        assertThat(Pickable.of(Pickable.Shape.MESH).shape()).isEqualTo(Pickable.Shape.MESH);
        assertThatIllegalArgumentException().isThrownBy(() -> Pickable.of(Pickable.Shape.CUSTOM));
        assertThatNullPointerException().isThrownBy(() -> Pickable.of(null));
        assertThatNullPointerException().isThrownBy(() -> Pickable.mesh(null));
        assertThatNullPointerException().isThrownBy(() -> Pickable.custom(null));
        Pickable p = Pickable.bounds().layers(6).enabled(false);
        assertThat(p.layers()).isEqualTo(6);
        assertThat(p.version()).isEqualTo(2);
        assertThat(p).hasToString("pickable by bounds, off, layers 6");
        assertThat(Pickable.mesh()).hasToString("pickable by mesh, layers 1");
        assertThat(Pickable.Shape.named("MESH")).isEqualTo(Pickable.Shape.MESH);
        assertThat(Pickable.Shape.named("laser")).isNull();
        assertThat(Selected.YES).hasToString("selected");
        assertThat(Selected.TYPE.saved()).isTrue();
        assertThat(Hovered.YES).hasToString("hovered");
        assertThat(Hovered.TYPE.saved()).isFalse();
    }
}
