package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Mat4;
import com.truesim.common.math.Vec3;
import com.truesim.common.math.geometry.Frustum;
import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Group trees rebuilt and views queried on a job system: the same results as on one thread. */
class ParallelIndexTest {

    private static final int GROUPS = 40;
    private static final int PER_GROUP = 50;

    private final JobSystem jobs = new JobSystem(4);

    @AfterEach
    void stop() {
        jobs.close();
    }

    /** A scene and its index, filled the same way every time. */
    private static final class Fixture {
        final SceneGraph scene = new SceneGraph(new ChangeClock());
        final SpatialIndex index = new SpatialIndex(scene);
        final List<Long> nodes = new ArrayList<>();

        Fixture(JobSystem jobs) {
            index.setJobs(jobs);
            SplittableRandom random = new SplittableRandom(7);
            for (int group = 1; group <= GROUPS; group++) {
                for (int i = 0; i < PER_GROUP; i++) {
                    long node = scene.create();
                    scene.setTranslation(node, group * 100 + random.nextDouble(100), 0, random.nextDouble(100));
                    scene.setLocalBounds(node, -1, 0, -1, 1, 2, 1);
                    scene.setMobility(node, Mobility.STATIC);
                    scene.setGroup(node, group);
                    nodes.add(node);
                }
            }
            update();
        }

        void update() {
            scene.update();
            index.update();
        }

        List<Long> query(double minX, double maxX) {
            List<Long> found = new ArrayList<>();
            index.queryAabb(minX, -1, -1, maxX, 3, 101, -1, node -> found.add(node));
            return found;
        }
    }

    private static void assertSame(Fixture serial, Fixture parallel) {
        assertThat(parallel.index.groupCount()).isEqualTo(serial.index.groupCount());
        assertThat(parallel.index.staticCount()).isEqualTo(serial.index.staticCount());
        for (double x = 0; x < (GROUPS + 2) * 100; x += 37) {
            assertThat(parallel.query(x, x + 60)).isEqualTo(serial.query(x, x + 60)); // same trees: same order
        }
        SpatialIndex.Hit a = new SpatialIndex.Hit();
        SpatialIndex.Hit b = new SpatialIndex.Hit();
        for (int group = 1; group <= GROUPS; group += 7) {
            serial.index.raycast(group * 100 - 5, 1, 50, 1, 0, 0, 200, -1, a);
            parallel.index.raycast(group * 100 - 5, 1, 50, 1, 0, 0, 200, -1, b);
            assertThat(b.node()).isEqualTo(a.node());
        }
    }

    @Test
    void groupsRebuiltInParallelMatchSerialOnes() {
        Fixture serial = new Fixture(null);
        Fixture parallel = new Fixture(jobs);
        assertSame(serial, parallel);
        // Move some nodes, empty a few groups and refill one: rebuilds, leaves and joins.
        for (Fixture f : List.of(serial, parallel)) {
            for (int i = 0; i < f.nodes.size(); i += 13) {
                f.scene.setTranslation(f.nodes.get(i), 50, 0, 50);
            }
            for (int i = 0; i < PER_GROUP; i++) {
                f.scene.remove(f.nodes.get(i)); // group 1
                f.scene.remove(f.nodes.get(5 * PER_GROUP + i)); // group 6
            }
            f.update();
            for (int i = 0; i < 10; i++) {
                long node = f.scene.create();
                f.scene.setTranslation(node, 120 + i, 0, 10);
                f.scene.setLocalBounds(node, -1, 0, -1, 1, 2, 1);
                f.scene.setMobility(node, Mobility.STATIC);
                f.scene.setGroup(node, 1);
            }
            f.update();
        }
        assertSame(serial, parallel);
        assertThat(parallel.index.groupCount()).isEqualTo(GROUPS - 1);
    }

    /** A frustum looking along +x from (x, 30, 50), down a little. */
    private static Frustum view(double x) {
        Mat4 projection = Mat4.perspective(Math.PI / 3, 16.0 / 9, 0.1, 400);
        Vec3 eye = Vec3.of(x, 30, 50);
        return Frustum.fromMatrix(projection.mul(Mat4.lookAt(eye, eye.add(Vec3.of(1, -0.3, 0)), Vec3.of(0, 1, 0))));
    }

    @Test
    void severalViewsAtOnceFindWhatSingleQueriesFind() {
        Fixture serial = new Fixture(null);
        Fixture parallel = new Fixture(jobs);
        long moving = parallel.scene.create(); // a dynamic node too
        parallel.scene.setTranslation(moving, 400, 0, 50);
        parallel.scene.setLocalBounds(moving, -1, 0, -1, 1, 2, 1);
        parallel.update();
        Frustum[] views = new Frustum[8];
        for (int v = 0; v < views.length; v++) {
            views[v] = view(v * 500);
        }
        SpatialIndex.ViewResults inParallel = new SpatialIndex.ViewResults();
        SpatialIndex.ViewResults inOrder = new SpatialIndex.ViewResults();
        parallel.index.queryFrustums(views, 6, -1, inParallel);
        parallel.index.setJobs(null);
        parallel.index.queryFrustums(views, 6, -1, inOrder);
        assertThat(inParallel.views()).isEqualTo(6);
        for (int v = 0; v < 6; v++) {
            List<Long> single = new ArrayList<>();
            parallel.index.queryFrustum(views[v], -1, node -> single.add(node));
            assertThat(single).isNotEmpty();
            assertThat(nodes(inParallel, v)).isEqualTo(single);
            assertThat(nodes(inOrder, v)).isEqualTo(single);
        }
        assertThat(nodes(inParallel, 0)).contains(moving);
        // Reused with fewer views, with one view on a job system, and checked.
        parallel.index.setJobs(jobs);
        parallel.index.queryFrustums(views, 2, -1, inParallel); // the workers' contexts are reused
        assertThat(nodes(inParallel, 1)).isEqualTo(nodes(inOrder, 1));
        parallel.index.queryFrustums(views, 1, -1, inParallel);
        assertThat(inParallel.views()).isEqualTo(1);
        assertThat(nodes(inParallel, 0)).isEqualTo(nodes(inOrder, 0));
        serial.index.queryFrustums(views, 0, -1, inOrder);
        assertThat(inOrder.views()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> serial.index.queryFrustums(views, 9, -1, inOrder));
        assertThatIllegalArgumentException().isThrownBy(() -> serial.index.queryFrustums(views, -1, -1, inOrder));
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> inParallel.count(1));
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> inParallel.count(-1));
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> inParallel.node(0, -1));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
            .isThrownBy(() -> inParallel.node(0, inParallel.count(0)));
    }

    private static List<Long> nodes(SpatialIndex.ViewResults results, int view) {
        List<Long> nodes = new ArrayList<>();
        for (int i = 0; i < results.count(view); i++) {
            nodes.add(results.node(view, i));
        }
        return nodes;
    }
}
