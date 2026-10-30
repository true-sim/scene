package com.truesim.scene.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Aabb;
import com.truesim.scene.assets.mesh.Cone;
import com.truesim.scene.assets.mesh.Sphere;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.SceneGraph;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Renderables' nodes take their meshes' bounds once the meshes load, and lose them with their renderables. */
class RenderableBoundsTest {

    // Loads wait until the test opens the gate, so what is still loading does not depend on timing.
    private volatile java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(0);

    /** A triangle from the origin to {@code size} along x and z, once the gate is open. */
    private final Generator<Double, MeshData> triangles = (size, seed) -> {
        gate.await();
        float s = size.floatValue();
        return MeshData.builder(VertexFormat.POSITIONS_ONLY, 3)
            .positions(new float[] {0, 0, 0, s, 0, 0, 0, 0, s})
            .meshlet(new int[] {0, 1, 2}, new int[] {0, 1, 2}, 0, new Sphere(s / 2, 0, s / 2, s), Cone.NONE)
            .build();
    };

    private final TaskScheduler tasks = new TaskScheduler();
    private final AssetCache assets = new AssetCache(tasks);
    private final SceneGraph scene = new SceneGraph(new ChangeClock());

    @AfterEach
    void stop() {
        assets.close();
        tasks.close();
    }

    private AssetRef<MeshData> mesh(double size) {
        return AssetRef.generated(RenderAssets.MESH, triangles, size, 1);
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 1000 && !condition.getAsBoolean(); i++) {
            Thread.sleep(2);
            tasks.drainCompleted();
        }
        assertThat(condition.getAsBoolean()).as("loaded in time").isTrue();
    }

    private Aabb box(double size) {
        return new Aabb(Vec3.ZERO, Vec3.of(size, 0, size));
    }

    @Test
    void nodesTakeTheirMeshesBoundsAndLoseThemWithTheirRenderables() throws InterruptedException {
        Renderables renderables = new Renderables(scene);
        RenderableBounds bounds = new RenderableBounds(renderables, assets);
        long a = scene.create();
        renderables.set(a, mesh(1), List.of());
        bounds.update();
        assertThat(scene.hasBounds(a)).as("loading").isFalse();
        assertThat(bounds.heldCount()).isOne();
        await(() -> scene.hasBounds(a));
        assertThat(scene.localBounds(a)).isEqualTo(box(1));
        // A second node with the loaded mesh is bounded at once; the mesh is held once per renderable.
        long b = scene.create();
        renderables.set(b, mesh(1), List.of());
        bounds.update();
        assertThat(scene.localBounds(b)).isEqualTo(box(1));
        assertThat(assets.holders(mesh(1))).isEqualTo(2);
        bounds.update(); // nothing changed
        // A new mesh: the old box stays until the new one arrives.
        renderables.setMesh(a, mesh(2));
        bounds.update();
        assertThat(scene.localBounds(a)).isEqualTo(box(1));
        await(() -> scene.localBounds(a).equals(box(2)));
        // Without a renderable the node loses its bounds; the mesh, unused, may leave the cache.
        renderables.remove(b);
        bounds.update();
        assertThat(scene.hasBounds(b)).isFalse();
        assertThat(assets.holders(mesh(1))).isZero();
        assets.setBudget(0); // evicted: nothing to do for meshes nobody draws
        assertThat(assets.get(mesh(1))).isNull();
        // A removed node lets go of its mesh.
        scene.remove(a);
        scene.update();
        renderables.update();
        bounds.update();
        assertThat(bounds.heldCount()).isZero();
        assertThat(assets.holders(mesh(2))).isZero();
        bounds.close();
        bounds.close(); // twice is fine
        assertThatIllegalStateException().isThrownBy(bounds::update);
    }

    @Test
    void meshesChangingOrNodesGoingWhileLoadingAreSkipped() throws InterruptedException {
        Renderables renderables = new Renderables(scene);
        try (RenderableBounds bounds = new RenderableBounds(renderables, assets)) {
            gate = new java.util.concurrent.CountDownLatch(1); // nothing loads until the changes are made
            long c = scene.create();
            long d = scene.create();
            long e = scene.create();
            renderables.set(c, mesh(3), List.of());
            renderables.set(d, mesh(3), List.of());
            renderables.set(e, mesh(4), List.of());
            bounds.update();
            // While they load: d switches to another mesh before its first arrives, e goes with its node.
            renderables.setMesh(d, mesh(5));
            bounds.update();
            renderables.setMesh(e, mesh(6));
            bounds.update(); // e no longer waits for mesh 4
            scene.remove(e);
            gate.countDown();
            // Mesh 4 too, which nothing waits for now: its arrival finds no nodes.
            await(() -> assets.get(mesh(3)) != null && assets.get(mesh(5)) != null && assets.get(mesh(6)) != null
                && assets.state(mesh(4)) != com.truesim.scene.assets.AssetState.LOADING);
            assertThat(scene.localBounds(c)).isEqualTo(box(3));
            assertThat(scene.localBounds(d)).isEqualTo(box(5));
            assertThat(scene.contains(e)).isFalse();
            assertThat(bounds.heldCount()).isEqualTo(3);
            // A renderable whose node went before the renderables noticed holds nothing; its node took the slot of e,
            // also gone, whose mesh is let go too.
            long f = scene.create();
            renderables.set(f, mesh(3), List.of());
            scene.remove(f);
            bounds.update();
            assertThat(bounds.heldCount()).isEqualTo(2);
            assertThat(assets.holders(mesh(6))).isZero();
        }
        assertThat(assets.holders(mesh(3))).as("let go when closed").isZero();
    }

    @Test
    void fallingBehindTheRenderablesFollowsThemAsTheyAre() throws InterruptedException {
        Renderables renderables = new Renderables(scene, 2); // keeps two changes
        RenderableBounds bounds = new RenderableBounds(renderables, assets);
        long[] nodes = new long[70]; // past the first room for nodes
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = scene.create();
            renderables.set(nodes[i], mesh(7), List.of());
        }
        bounds.update();
        await(() -> scene.hasBounds(nodes[69]));
        // More changes than the log keeps: two renderables go, one comes, and every bound node is checked again.
        renderables.remove(nodes[0]);
        renderables.remove(nodes[1]);
        long late = scene.create();
        renderables.set(late, mesh(7), List.of());
        bounds.update();
        assertThat(scene.hasBounds(nodes[0])).isFalse();
        assertThat(scene.localBounds(late)).isEqualTo(box(7));
        assertThat(bounds.heldCount()).isEqualTo(69);
        bounds.close();
    }
}
