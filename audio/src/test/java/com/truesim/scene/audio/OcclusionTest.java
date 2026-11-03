package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.collision.BoxShape;
import com.truesim.common.collision.CollisionWorld;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** What occludes (§2.11): transmissions per band, materials, and rays through physics. */
class OcclusionTest {

    static final AudioMaterial[] TABLE = {null, AudioMaterial.CONCRETE, AudioMaterial.GLASS};

    /** A wall across x = {@code x}, 0.2 m thick, of material {@code material}. */
    static int wall(CollisionWorld world, double x, int material) {
        int c = world.addStatic(new BoxShape(new Vec3(0.1, 5, 5)), Transform.ofTranslation(new Vec3(x, 0, 0)));
        world.setMaterial(c, material);
        return c;
    }

    static CollisionAudioWorld world(CollisionWorld world) {
        world.update(); // the broad phase sees what was added
        return new CollisionAudioWorld(world, m -> m >= 0 && m < TABLE.length ? TABLE[m] : null, -1);
    }

    @Test
    void transmissionsMultiplyPerBand() {
        Transmission t = new Transmission();
        assertThat(t.mid()).isEqualTo(1);
        t.cross(AudioMaterial.WOOD).cross(0.5f, 0.5f, 0.5f);
        assertThat(t.low()).isEqualTo(0.25f);
        assertThat(t.mid()).isEqualTo(0.125f);
        assertThat(t.high()).isCloseTo(0.05f, within(1e-7f));
        t.open();
        assertThat(t.high()).isEqualTo(1);
        assertThat(AudioMaterial.OPEN.mid()).isEqualTo(1);
        assertThat(AudioMaterial.CURTAIN.high()).isLessThan(AudioMaterial.CURTAIN.low());
        assertThat(AudioMaterial.BRICK.mid()).isLessThan(AudioMaterial.GLASS.mid());
        assertThatThrownBy(() -> new AudioMaterial(2, 0, 0)).hasMessageContaining("0 to 1");
        assertThatThrownBy(() -> new AudioMaterial(0, -1, 0)).hasMessageContaining("0 to 1");
        assertThatThrownBy(() -> new AudioMaterial(0, 0, Float.NaN)).hasMessageContaining("0 to 1");
        assertThatThrownBy(() -> new AudioMaterial(-1, 0, 0)).hasMessageContaining("0 to 1");
        assertThatThrownBy(() -> new AudioMaterial(0, 2, 0)).hasMessageContaining("0 to 1");
        assertThatThrownBy(() -> new AudioMaterial(0, 0, -1)).hasMessageContaining("0 to 1");
        assertThatThrownBy(() -> new AudioMaterial(0, 0, 2)).hasMessageContaining("0 to 1");
    }

    @Test
    void raysCrossEachBodyOnce() {
        CollisionWorld physics = new CollisionWorld();
        wall(physics, 5, 1);
        wall(physics, 8, 2);
        wall(physics, 20, 7); // no material for 7: open
        CollisionAudioWorld audio = world(physics);
        assertThat(audio.world()).isSameAs(physics);
        Transmission t = new Transmission();
        assertThat(audio.trace(0, 0, 0, 6, 0, 0, t)).isTrue();
        assertThat(t.mid()).as("concrete once").isEqualTo(AudioMaterial.CONCRETE.mid());
        assertThat(audio.trace(0, 0, 0, 10, 0, 0, t.open())).isTrue();
        assertThat(t.mid()).isCloseTo(AudioMaterial.CONCRETE.mid() * AudioMaterial.GLASS.mid(), within(1e-7f));
        assertThat(audio.trace(0, 0, 0, 25, 0, 0, t.open())).isTrue();
        assertThat(t.high()).as("the open wall lets all through")
            .isCloseTo(AudioMaterial.CONCRETE.high() * AudioMaterial.GLASS.high(), within(1e-8f));
        assertThat(audio.trace(0, 0, 0, 3, 0, 0, t.open())).as("short of the wall").isFalse();
        assertThat(t.mid()).isEqualTo(1);
        assertThat(audio.trace(1, 1, 1, 1, 1, 1.0001, t)).as("too short to trace").isFalse();
        assertThat(audio.trace(5, 0, 0, 6, 0, 0, t)).as("from inside a wall: it steps out").isFalse();
        assertThat(audio.trace(6, 0, 0, 0, 0, 0, t.open())).as("the other way").isTrue();
        assertThat(t.mid()).isEqualTo(AudioMaterial.CONCRETE.mid());
    }

    /** A slab across x from {@code from} to {@code to}, of material {@code material}. */
    static void slab(CollisionWorld world, double from, double to, int material) {
        int c = world.addStatic(new BoxShape(new Vec3((to - from) / 2, 5, 5)),
            Transform.ofTranslation(new Vec3((from + to) / 2, 0, 0)));
        world.setMaterial(c, material);
    }

    /** A glass pane across x = {@code x}: a mesh of two triangles, material 2. */
    static void pane(CollisionWorld world, double x) {
        world.addStatic(com.truesim.common.collision.TriangleMeshShape.of(java.util.List.of(
            new Vec3(x, -5, -5), new Vec3(x, 5, -5), new Vec3(x, 5, 5), new Vec3(x, -5, 5)),
            new int[] {0, 1, 2, 0, 2, 3}, new int[] {2, 2}), Transform.IDENTITY);
    }

    @Test
    void stepsOutOfABodyCountTheBodiesBehindIt() {
        float both = AudioMaterial.CONCRETE.mid() * AudioMaterial.GLASS.mid();
        // a curtain from 4.9 to 5.1; the steps out of it (10, 20, 40, 80 mm on) land inside the brick at 5.12
        CollisionWorld behind = new CollisionWorld();
        wall(behind, 5, 1);
        slab(behind, 5.12, 5.32, 2);
        CollisionAudioWorld audio = world(behind);
        Transmission t = new Transmission();
        assertThat(audio.trace(5, 0, 0, 6, 0, 0, t)).as("from inside the curtain").isTrue();
        assertThat(t.mid()).isEqualTo(AudioMaterial.GLASS.mid());
        assertThat(audio.trace(0, 0, 0, 6, 0, 0, t.open())).as("through both").isTrue();
        assertThat(t.mid()).isCloseTo(both, within(1e-7f));
        assertThat(audio.trace(0, 0, 0, 5.2, 0, 0, t.open())).as("ending inside the brick").isTrue();
        assertThat(t.mid()).isCloseTo(both, within(1e-7f));
        // a thin board closer than a step: the steps would pass over it; and a foil thinner than the step past a face
        CollisionWorld board = new CollisionWorld();
        wall(board, 5, 1);
        slab(board, 5.13, 5.17, 2);
        slab(board, 3, 3.0005, 2);
        Transmission b = new Transmission();
        assertThat(world(board).trace(0, 0, 0, 6, 0, 0, b)).isTrue();
        assertThat(b.mid()).isCloseTo(both * AudioMaterial.GLASS.mid(), within(1e-7f));
        // touching: the brick starts where the curtain ends
        CollisionWorld touching = new CollisionWorld();
        wall(touching, 5, 1);
        slab(touching, 5.1, 5.3, 2);
        Transmission c = new Transmission();
        assertThat(world(touching).trace(0, 0, 0, 6, 0, 0, c)).isTrue();
        assertThat(c.mid()).isCloseTo(both, within(1e-7f));
        // a mesh's side half a millimetre behind a body: within a step of its end
        CollisionWorld near = new CollisionWorld();
        wall(near, 5, 1);
        pane(near, 5.1005);
        Transmission n = new Transmission();
        assertThat(world(near).trace(0, 0.3, 0.2, 6, 0.3, 0.2, n)).isTrue();
        assertThat(n.mid()).isCloseTo(both, within(1e-7f));
        // a mesh's side just behind a body
        CollisionWorld pane = new CollisionWorld();
        wall(pane, 5, 1);
        pane(pane, 5.15);
        Transmission p = new Transmission();
        assertThat(world(pane).trace(0, 0.3, 0.2, 6, 0.3, 0.2, p)).isTrue();
        assertThat(p.mid()).isCloseTo(both, within(1e-7f));
    }

    @Test
    void aMeshsTrianglesHaveTheirOwnMaterials() {
        CollisionWorld physics = new CollisionWorld();
        // a glass pane at x = 4: two triangles, material 2; the collider's own material is concrete
        int pane = physics.addStatic(com.truesim.common.collision.TriangleMeshShape.of(java.util.List.of(
            new Vec3(4, -5, -5), new Vec3(4, 5, -5), new Vec3(4, 5, 5), new Vec3(4, -5, 5)),
            new int[] {0, 1, 2, 0, 2, 3}, new int[] {2, 2}), Transform.IDENTITY);
        physics.setMaterial(pane, 1);
        Transmission t = new Transmission();
        assertThat(world(physics).trace(0, 0.3, 0.2, 9, 0.3, 0.2, t)).isTrue();
        assertThat(t.mid()).as("one side crossed, glass").isEqualTo(AudioMaterial.GLASS.mid());
    }

    @Test
    void crossingsAreCountedUpToEight() {
        CollisionWorld physics = new CollisionWorld();
        for (int i = 0; i < 20; i++) { // more walls than crossings counted, and than casts allowed
            wall(physics, 2 + i, 2);
        }
        Transmission t = new Transmission();
        assertThat(world(physics).trace(0, 0, 0, 30, 0, 0, t)).isTrue();
        assertThat(t.mid()).isCloseTo((float) Math.pow(AudioMaterial.GLASS.mid(), 8), within(1e-9f));
        CollisionWorld deep = new CollisionWorld();
        deep.addStatic(new BoxShape(new Vec3(1e6, 1e6, 1e6)), Transform.IDENTITY);
        assertThat(world(deep).trace(0, 0, 0, 1e5, 0, 0, t.open())).as("inside all the way")
            .isFalse();
    }

    @Test
    void eachThreadHasItsOwnQuery() throws Exception {
        CollisionWorld physics = new CollisionWorld();
        wall(physics, 5, 1);
        CollisionAudioWorld audio = world(physics);
        float there = CompletableFuture.supplyAsync(() -> {
            Transmission t = new Transmission();
            audio.trace(0, 0, 0, 10, 0, 0, t);
            return t.mid();
        }).get();
        Transmission here = new Transmission();
        audio.trace(0, 0, 0, 10, 0, 0, here);
        assertThat(there).isEqualTo(here.mid());
    }
}
