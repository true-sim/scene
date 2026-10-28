package com.truesim.scene.collision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.collision.BoxShape;
import com.truesim.common.collision.CollisionQuery;
import com.truesim.common.collision.CollisionWorld;
import com.truesim.common.collision.HeightfieldShape;
import com.truesim.common.collision.RaycastHit;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.world.Cell;
import com.truesim.scene.world.Detail;
import com.truesim.scene.world.Feature;
import com.truesim.scene.world.InterestProfile;
import com.truesim.scene.world.InterestSource;
import com.truesim.scene.world.Layer;
import com.truesim.scene.world.Levels;
import com.truesim.scene.world.Priority;
import com.truesim.scene.world.Range;
import com.truesim.scene.world.World;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A collision layer streamed into a collision world, and terrain heights from it, without any rendering. */
class CellCollidersTest {

    private static final Layer<CellColliders> COLLISION =
        Layer.of("collision", CellColliders.class, Levels.sizes(64, 256));
    private static final Feature<Void> BARRIERS = Feature.toggle("barriers");
    private static final int ASPHALT = 7;

    private final TaskScheduler tasks = new TaskScheduler();
    private final World world = new World(new SceneGraph(new ChangeClock()), tasks);
    private final CollisionWorld collisions = new CollisionWorld();
    private final CollisionQuery query = collisions.newQuery();
    private final RaycastHit hit = new RaycastHit();

    /**
     * Level 0: 64 m cells of terrain sloping up along x (height = x / 10 within the cell, from y = 1), sampled every
     * 8 m, and a barrier box at the cell's center while the barriers feature is on. Level 1: flat terrain at y = -5.
     */
    private final ColliderLoader loader = new ColliderLoader(collisions, (cell, features) -> {
        double size = cell.size();
        int samples = 9;
        double[] heights = new double[samples * samples];
        for (int z = 0; z < samples; z++) {
            for (int x = 0; x < samples; x++) {
                heights[z * samples + x] = cell.level() == 0 ? x * (size / (samples - 1)) / 10 : 0;
            }
        }
        CellColliders.Builder builder = CellColliders.builder()
            .terrain(HeightfieldShape.of(samples, samples, size / (samples - 1), size / (samples - 1), heights),
                cell.minX(), cell.level() == 0 ? 1 : -5, cell.minZ());
        if (cell.level() == 0 && features.isOn(BARRIERS)) {
            builder.add(new BoxShape(Vec3.of(1, 2, 1)),
                Transform.ofTranslation(Vec3.of(cell.minX() + 32, 3, cell.minZ() + 32)), ASPHALT, 2, -1, false);
        }
        return builder.build();
    });

    CellCollidersTest() {
        world.register(COLLISION, loader);
    }

    @AfterEach
    void stop() {
        world.close();
        tasks.close();
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            world.update();
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
        collisions.update();
    }

    private double groundBelow(double x, double z) {
        return query.raycast(Vec3.of(x, 100, z), Vec3.of(0, -1, 0), 200, -1, hit) ? hit.pointY() : Double.NaN;
    }

    @Test
    void cellsStreamTheirCollidersIntoTheCollisionWorld() throws InterruptedException {
        assertThat(loader.collisions()).isSameAs(collisions);
        world.features().enable(BARRIERS);
        InterestSource player = world.addInterest(InterestProfile.builder()
            .layer(COLLISION, Detail.full(), Range.radius(10)).build(), Priority.HIGH);
        player.moveTo(32, 0, 32); // the middle of cell (0, 0)
        await(() -> world.isReady(player));
        assertThat(collisions.size()).isEqualTo(2); // the terrain and the barrier
        assertThat(query.raycast(Vec3.of(32, 100, 32), Vec3.of(0, -1, 0), 200, -1, hit)).isTrue();
        assertThat(hit.pointY()).isCloseTo(5, within(1e-9)); // on top of the barrier
        assertThat(collisions.material(hit.collider())).isEqualTo(ASPHALT);
        assertThat(collisions.group(hit.collider())).isEqualTo(2);
        assertThat(groundBelow(10, 50)).isCloseTo(2, within(1e-9));
        TerrainHeights ground = new TerrainHeights(world, COLLISION);
        assertThat(ground.heightAt(10, 50)).isCloseTo(2, within(1e-9));
        assertThat(ground.heightAt(-10, 50)).isNaN(); // nothing loaded there

        // Switching the barriers off reloads the cell without them.
        world.features().disable(BARRIERS);
        world.update();
        await(() -> world.reloadingCount() == 0);
        assertThat(collisions.size()).isEqualTo(1);
        assertThat(groundBelow(32, 32)).isCloseTo(4.2, within(1e-9));

        // Leaving: the colliders go.
        player.close();
        world.update();
        collisions.update();
        assertThat(collisions.size()).isZero();
        assertThat(groundBelow(32, 32)).isNaN();
    }

    @Test
    void heightsFollowTheTerrainTrianglesAndTheFinestLoadedLevel() throws InterruptedException {
        world.request(COLLISION, 1, 0, 0, 0); // flat, coarse terrain at y = -5 over 256 m
        await(() -> world.loadedCount() == 1);
        TerrainHeights ground = new TerrainHeights(world, COLLISION);
        assertThat(ground.heightAt(100, 100)).isEqualTo(-5);
        world.request(COLLISION, 0, 1, 0, 0); // finer terrain for x 64..128
        await(() -> world.loadedCount() == 2);
        for (double x = 64; x <= 128; x += 3.7) {
            for (double z = 0.3; z < 64; z += 5.1) {
                assertThat(ground.heightAt(x, z)).isCloseTo(groundBelow(x, z), within(1e-9));
            }
        }
        assertThat(ground.heightAt(100, 100)).isEqualTo(-5); // outside the fine cell: the coarse one
        assertThat(ground.heightAt(300, 100)).isNaN();

        // A cell without terrain is skipped.
        CollisionWorld other = new CollisionWorld();
        Layer<CellColliders> props = Layer.of("props", CellColliders.class, Levels.single(64));
        world.register(props, new ColliderLoader(other, (cell, features) -> CellColliders.builder()
            .add(new BoxShape(Vec3.of(1, 1, 1)), Transform.IDENTITY).build()));
        world.request(props, 0, 0, 0, 0);
        await(() -> world.loadedCount() == 3);
        assertThat(new TerrainHeights(world, props).heightAt(1, 1)).isNaN();
        assertThat(other.size()).isEqualTo(1);

        // Terrain covering only part of its cell: elsewhere in the cell there is no height.
        Layer<CellColliders> patches = Layer.of("patches", CellColliders.class, Levels.single(64));
        world.register(patches, new ColliderLoader(other, (cell, features) -> CellColliders.builder()
            .terrain(HeightfieldShape.of(2, 2, 1, 1, new double[] {3, 3, 3, 3}), 0, 0, 0).build()));
        world.request(patches, 0, 0, 0, 0);
        await(() -> world.loadedCount() == 4);
        TerrainHeights patch = new TerrainHeights(world, patches);
        assertThat(patch.heightAt(0.5, 0.5)).isEqualTo(3);
        assertThat(patch.heightAt(30, 30)).isNaN();
    }

    @Test
    void terrainSamplesBothTrianglesOfACell() {
        double[] heights = {0, 1, 2, 3}; // (0,0)=0, (1,0)=1, (0,1)=2, (1,1)=3
        HeightfieldShape terrain = HeightfieldShape.of(2, 2, 10, 10, heights);
        Vec3 origin = Vec3.of(100, 1, 200);
        assertThat(TerrainHeights.sample(terrain, origin, 100, 200)).isEqualTo(1);
        assertThat(TerrainHeights.sample(terrain, origin, 110, 210)).isEqualTo(4);
        assertThat(TerrainHeights.sample(terrain, origin, 102, 203)).isCloseTo(1 + 0.2 + 0.6, within(1e-12));
        assertThat(TerrainHeights.sample(terrain, origin, 108, 207)).isCloseTo(1 + 0.8 + 1.4, within(1e-12));
        assertThat(TerrainHeights.sample(terrain, origin, 99, 205)).isNaN();
        assertThat(TerrainHeights.sample(terrain, origin, 111, 205)).isNaN();
        assertThat(TerrainHeights.sample(terrain, origin, 105, 199)).isNaN();
        assertThat(TerrainHeights.sample(terrain, origin, 105, 211)).isNaN();
    }

    @Test
    void collidersAreChecked() {
        CellColliders empty = CellColliders.builder().build();
        assertThat(empty.colliders()).isEmpty();
        assertThat(empty.terrain()).isNull();
        assertThat(empty.terrainOrigin()).isNull();
        assertThat(empty.isAttached()).isFalse();
        CellColliders.Builder builder = CellColliders.builder();
        assertThatNullPointerException().isThrownBy(() -> builder.add(null, Transform.IDENTITY));
        assertThatNullPointerException().isThrownBy(() -> builder.add(new BoxShape(Vec3.ONE), null));
        assertThatNullPointerException().isThrownBy(() -> builder.terrain(null, 0, 0, 0));
        assertThatNullPointerException().isThrownBy(() -> new ColliderLoader(null, (cell, features) -> empty));
        assertThatNullPointerException().isThrownBy(() -> new ColliderLoader(collisions, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new TerrainHeights(world,
            Layer.of("space", CellColliders.class, Levels.single(64).volumetric())));
        // Data attaches once: a reload loads it anew.
        CellColliders once = CellColliders.builder().add(new BoxShape(Vec3.ONE), Transform.IDENTITY).build();
        loader.attach(once, null);
        assertThat(once.isAttached()).isTrue();
        assertThatIllegalStateException().isThrownBy(() -> loader.attach(once, null));
        Cell cell = new Cell(COLLISION, 0, 0, 0, 0);
        assertThat(cell.size()).isEqualTo(64);
    }
}
