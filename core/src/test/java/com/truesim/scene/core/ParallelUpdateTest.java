package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** World transforms propagated on a job system: the same transforms, bounds and change log as on one thread. */
class ParallelUpdateTest {

    private static final int VEHICLES = 600; // more moved roots than propagate on the calling thread

    private final JobSystem jobs = new JobSystem(4);

    @AfterEach
    void stop() {
        jobs.close();
    }

    /** Vehicles with wheels and a driver holding something, plus a moving pivot without bounds. */
    private static final class Fixture {
        final ChangeClock clock = new ChangeClock();
        final SceneGraph scene = new SceneGraph(clock);
        final List<Long> vehicles = new ArrayList<>();
        final List<Long> all = new ArrayList<>();

        Fixture(JobSystem jobs) {
            scene.setJobs(jobs);
            for (int i = 0; i < VEHICLES; i++) {
                long vehicle = add(NodeHandle.NONE, i * 10, 0, 0);
                vehicles.add(vehicle);
                for (int w = 0; w < 4; w++) {
                    add(vehicle, w % 2 * 2 - 1, 0, w / 2 * 3 - 1.5);
                }
                long driver = add(vehicle, 0, 1, 0);
                add(driver, 0.3, 0.5, 0);
            }
            long pivot = scene.create(); // no bounds
            all.add(pivot);
        }

        private long add(long parent, double x, double y, double z) {
            long node = parent == NodeHandle.NONE ? scene.create() : scene.create(parent);
            scene.setTranslation(node, x, y, z);
            scene.setLocalBounds(node, -0.5, 0, -0.5, 0.5, 1, 0.5);
            all.add(node);
            return node;
        }

        List<String> update() {
            long since = clock.now();
            scene.update();
            List<String> log = new ArrayList<>();
            scene.changesSince(since, (node, changes) -> log.add(node + ":" + changes));
            return log;
        }

        void drive(int frame) {
            for (int i = 0; i < vehicles.size(); i++) {
                long vehicle = vehicles.get(i);
                if (!scene.contains(vehicle)) {
                    continue;
                }
                scene.setTranslation(vehicle, i * 10 + frame, 0, frame * 0.5);
                scene.setRotation(vehicle, 0, Math.sin(frame * 0.1), 0, Math.cos(frame * 0.1));
                if (i % 7 == 0) {
                    // A child moving too, under a moving parent: it is no root of its own.
                    scene.setTranslation(scene.firstChild(vehicle), 1, 0.1 * frame, 1);
                }
            }
            scene.setTranslation(all.get(all.size() - 1), frame, 0, 0);
        }
    }

    @Test
    void parallelPropagationMatchesSerial() {
        Fixture serial = new Fixture(null);
        Fixture parallel = new Fixture(jobs);
        assertThat(parallel.update()).isEqualTo(serial.update()); // the first update: every node a root
        for (int frame = 1; frame <= 3; frame++) {
            serial.drive(frame);
            parallel.drive(frame);
            if (frame == 2) { // a moved vehicle removed before the update
                serial.scene.remove(serial.vehicles.get(5));
                parallel.scene.remove(parallel.vehicles.get(5));
            }
            assertThat(parallel.update()).isEqualTo(serial.update());
            for (int i = 0; i < serial.all.size(); i++) {
                long a = serial.all.get(i);
                long b = parallel.all.get(i);
                if (!serial.scene.contains(a)) {
                    continue;
                }
                assertThat(parallel.scene.world(b)).isEqualTo(serial.scene.world(a));
                assertThat(parallel.scene.worldBounds(b)).isEqualTo(serial.scene.worldBounds(a));
            }
        }
        // A few moved nodes propagate on the calling thread.
        parallel.scene.setTranslation(parallel.vehicles.get(0), -5, 0, 0);
        serial.scene.setTranslation(serial.vehicles.get(0), -5, 0, 0);
        assertThat(parallel.update()).isEqualTo(serial.update());
    }
}
