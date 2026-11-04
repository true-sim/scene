package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** Listeners and reverb zones are components of their nodes (application-api.md D46). */
class AudioComponentsTest {

    private static final AudioEffect HALL = AudioEffect.named("reverb");

    @Test
    void listenersAreComponentsSetEitherWay() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Sounds sounds = new Sounds(scene);
        Listeners listeners = sounds.listeners();
        long head = scene.create();
        long tv = scene.create();
        scene.setTranslation(tv, 1, 2, 3);
        scene.setRotation(tv, 0, 1, 0, 0);
        scene.update();
        Listener p1 = listeners.device(head);
        assertThat(p1.id()).as("its node").isEqualTo(head);
        assertThat(scene.components().get(head, AudioListener.TYPE).rendering()).isFalse();
        scene.components().set(tv, AudioListener.TYPE, AudioListener.render(ListenerMix.mono()));
        sounds.advance(0.01);
        assertThat(listeners.size()).isEqualTo(2);
        assertThat(listeners.rendering(tv)).isTrue();
        assertThat(listeners.mix(tv)).isEqualTo(ListenerMix.mono());
        assertThat(listeners.mix(head)).isNull();
        assertThat(listeners.node(tv)).isEqualTo(tv);
        assertThat(listeners.position(tv, 2)).isEqualTo(3);
        assertThat(listeners.rotation(tv, 1)).isEqualTo(1);
        AudioListener value = scene.components().get(tv, AudioListener.TYPE);
        assertThat(value.position(0)).isEqualTo(1);
        assertThat(value.rotation(3)).isZero();
        assertThat(value).hasToString("rendering listener");
        assertThat(AudioListener.device()).hasToString("device listener");
        assertThatThrownBy(() -> value.position(3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(listeners.hears(tv, NodeHandle.of(99, 1))).as("not a listener the graph knows").isFalse();
        // gone with their nodes; removing twice does nothing
        scene.remove(tv);
        assertThat(listeners.alive(tv)).isFalse();
        listeners.remove(tv);
        p1.remove();
        assertThat(p1.alive()).isFalse();
        assertThat(listeners.size()).isZero();
        assertThatIllegalArgumentException().isThrownBy(() -> listeners.node(head)).withMessageContaining("No listener");
        assertThatNullPointerException().isThrownBy(() -> AudioListener.render(null));
        assertThat(AudioListener.TYPE.name()).isEqualTo("truesim.audio.listener");
    }

    @Test
    void reverbZonesAreComponentsSetEitherWay() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Sounds sounds = new Sounds(scene);
        ReverbZones zones = sounds.reverbZones();
        long hall = scene.create();
        long cave = scene.create();
        scene.setTranslation(cave, 100, 0, 0);
        scene.update();
        assertThat(zones.box(hall, 10, 5, 10, 2, HALL, 0)).isEqualTo(hall);
        scene.components().set(cave, ReverbZone.TYPE, ReverbZone.sphere(5, 1, HALL, 3));
        sounds.advance(0.01);
        assertThat(zones.size()).isEqualTo(2);
        assertThat(zones.strongest(100, 0, 0)).isEqualTo(cave);
        assertThat(zones.weight(cave, 105.5, 0, 0)).isEqualTo(0.5f);
        assertThat(zones.reverb(cave)).isSameAs(HALL);
        // overlapping: the higher priority, then the greater weight
        long inner = scene.create(cave);
        long quiet = scene.create(cave);
        zones.sphere(inner, 2, 10, HALL, 3);
        zones.sphere(quiet, 50, 1, HALL, 1);
        scene.update();
        sounds.advance(0.01);
        assertThat(zones.strongest(103, 0, 0)).as("deeper in the first of equal priority").isEqualTo(cave);
        assertThat(zones.strongest(105.5, 0, 0)).as("the inner one's fade reaches further").isEqualTo(inner);
        scene.remove(inner);
        scene.remove(quiet);
        ReverbZone box = scene.components().get(hall, ReverbZone.TYPE);
        assertThat(box.sphere()).isFalse();
        assertThat(box.extent(0)).isEqualTo(10);
        assertThat(box.extent(1)).isEqualTo(5);
        assertThat(box.extent(2)).isEqualTo(10);
        assertThat(box.fade()).isEqualTo(2);
        assertThat(box.reverb()).isSameAs(HALL);
        assertThat(box.priority()).isZero();
        assertThat(box).hasToString("box reverb zone, priority 0");
        ReverbZone sphere = scene.components().get(cave, ReverbZone.TYPE);
        assertThat(sphere.sphere()).isTrue();
        assertThat(sphere).hasToString("sphere reverb zone, priority 3");
        assertThatThrownBy(() -> box.extent(3)).isInstanceOf(IndexOutOfBoundsException.class);
        for (double fade : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ReverbZone.box(1, 1, 1, fade, HALL, 0));
        }
        for (double[] bad : new double[][] {{0, 1, 1}, {1, 0, 1}, {1, 1, 0}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ReverbZone.box(bad[0], bad[1], bad[2], 0, HALL, 0));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> ReverbZone.sphere(0, 0, HALL, 0));
        assertThatNullPointerException().isThrownBy(() -> ReverbZone.sphere(1, 0, null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> zones.box(NodeHandle.of(77, 1), 1, 1, 1, 0, HALL, 0))
            .withMessageContaining("Not a node of the scene");
        assertThatIllegalArgumentException().isThrownBy(() -> zones.sphere(NodeHandle.of(77, 1), 1, 0, HALL, 0));
        assertThat(zones.weight(NodeHandle.of(77, 1), 0, 0, 0)).isZero();
        scene.remove(cave);
        assertThat(zones.alive(cave)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> zones.reverb(cave));
        assertThat(ReverbZone.TYPE.name()).isEqualTo("truesim.audio.reverb-zone");
    }

    @Test
    void theUiStoreHasNone() {
        Sounds ui = Sounds.ui();
        Listeners listeners = ui.listeners();
        ReverbZones zones = ui.reverbZones();
        assertThat(listeners.size()).isZero();
        assertThat(listeners.version()).isZero();
        assertThat(listeners.alive(1)).isFalse();
        listeners.remove(1);
        assertThatIllegalArgumentException().isThrownBy(() -> listeners.rendering(1));
        assertThatIllegalStateException().isThrownBy(() -> listeners.device(1));
        assertThat(zones.size()).isZero();
        assertThat(zones.version()).isZero();
        assertThat(zones.alive(1)).isFalse();
        assertThat(zones.weight(1, 0, 0, 0)).isZero();
        zones.remove(1);
        assertThatIllegalArgumentException().isThrownBy(() -> zones.reverb(1));
        assertThatIllegalStateException().isThrownBy(() -> zones.box(1, 1, 1, 1, 0, HALL, 0));
        assertThatIllegalStateException().isThrownBy(() -> zones.sphere(1, 1, 0, HALL, 0));
        ui.advance(0.01);
    }
}
