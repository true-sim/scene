package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Device and rendering listeners, the listener graph (order, cycles, cuts, self) and reverb zones. */
class ListenersTest {

    static final Sound TV = Sound.builder().root(Play.input()).loop().build();

    private SceneGraph scene;
    private Sounds sounds;
    private Listeners listeners;

    @BeforeEach
    void setUp() {
        scene = new SceneGraph(new ChangeClock());
        sounds = new Sounds(scene);
        listeners = sounds.listeners();
    }

    private long node(double x, double y, double z) {
        long node = scene.create();
        scene.setTranslation(node, x, y, z);
        scene.update();
        return node;
    }

    /** A camera at {@code x} whose mix plays on a television at {@code tvX}. */
    private Listener camera(double x, double tvX) {
        Listener camera = listeners.render(node(x, 0, 0), ListenerMix.mono().maxDistance(10));
        sounds.play(TV, tvX, 0, 0).input(camera);
        return camera;
    }

    @Test
    void listenersSitOnNodes() {
        long head = node(1, 2, 3);
        Listener p1 = listeners.device(head);
        Listener cctv = listeners.render(node(0, 5, 0), ListenerMix.stereo().toBus(Bus.of("pip")).tap());
        assertThat(listeners.size()).isEqualTo(2);
        assertThat(listeners.rendering(p1.id())).isFalse();
        assertThat(listeners.rendering(cctv.id())).isTrue();
        assertThat(listeners.mix(p1.id())).isNull();
        assertThat(listeners.mix(cctv.id()).channels()).isEqualTo(2);
        assertThat(listeners.node(p1.id())).isEqualTo(head);
        assertThat(listeners.position(p1.id(), 1)).isEqualTo(2);
        assertThat(listeners.rotation(p1.id(), 3)).isEqualTo(1);
        scene.setTranslation(head, 9, 2, 3);
        scene.update();
        sounds.advance(0.0);
        assertThat(listeners.position(p1.id(), 0)).isEqualTo(9);
        List<Long> all = new ArrayList<>();
        listeners.forEach(all::add);
        assertThat(all).containsExactly(p1.id(), cctv.id());
        assertThat(listeners.alive(-1)).isFalse();
        assertThat(listeners.alive(1L << 32 | 50)).isFalse();
        assertThat(listeners.alive(cctv.id() + (1L << 32))).isFalse();
        int version = listeners.version();
        p1.remove();
        p1.remove();
        assertThat(p1.alive()).isFalse();
        assertThat(listeners.version()).isEqualTo(version + 1);
        assertThatThrownBy(() -> listeners.node(p1.id())).hasMessageStartingWith("No listener is ");
        all.clear();
        listeners.forEach(all::add);
        assertThat(all).containsExactly(cctv.id());
        Listener again = listeners.device(head);
        assertThat((int) again.id()).isEqualTo((int) p1.id());
        assertThat(again.alive()).isTrue();
        assertThatThrownBy(() -> listeners.device(NodeHandle.NONE)).hasMessageContaining("Not a node");
        scene.remove(head);
        scene.update();
        sounds.advance(0.0);
        assertThat(again.alive()).as("its node removed").isFalse();
        for (int i = 0; i < 6; i++) {
            listeners.device(node(i, 0, 0));
        }
        assertThat(listeners.size()).isEqualTo(7);
    }

    @Test
    void aChainRendersInOrderWithoutDelay() {
        // camera A films nothing re-emitted; its TV is near camera B, whose TV is near camera C
        Listener a = camera(0, 100);
        Listener b = camera(100, 200);
        Listener c = camera(200, 1000);
        sounds.advance(0.0);
        assertThat(listeners.hears(b.id(), a.id())).isTrue();
        assertThat(listeners.hears(c.id(), b.id())).isTrue();
        assertThat(listeners.hears(c.id(), a.id())).isFalse();
        assertThat(listeners.delayed(a.id(), b.id())).isFalse();
        assertThat(listeners.cut(a.id(), b.id())).isFalse();
        assertThat(listeners.orderCount()).isEqualTo(3);
        List<Long> order = List.of(listeners.order(0), listeners.order(1), listeners.order(2));
        assertThat(order).containsExactly(a.id(), b.id(), c.id());
        assertThat(listeners.cycles()).isZero();
    }

    @Test
    void camerasWatchingEachOthersTelevisionsMakeOneDelayedEdge() {
        Listener a = camera(0, 100);
        Listener b = camera(100, 0);
        sounds.advance(0.0);
        assertThat(listeners.hears(b.id(), a.id())).isTrue();
        assertThat(listeners.hears(a.id(), b.id())).isTrue();
        assertThat(listeners.cycles()).isEqualTo(1);
        assertThat(listeners.delayed(b.id(), a.id()) ^ listeners.delayed(a.id(), b.id())).isTrue();
        assertThat(listeners.orderCount()).isEqualTo(2);
    }

    @Test
    void twoCamerasFeedingOneMakeNoCycle() {
        Listener c = listeners.render(node(500, 0, 0), ListenerMix.mono().maxDistance(10));
        Listener a = camera(0, 500);
        Listener b = camera(100, 501);
        for (int i = 0; i < 12; i++) { // many televisions: edges grow, each counted once
            sounds.play(TV, 500 + 0.1 * i, 0, 0).input(i % 2 == 0 ? a : b);
            listeners.render(node(500, 0, 1 + 0.1 * i), ListenerMix.mono().maxDistance(10));
        }
        sounds.advance(0.0);
        assertThat(listeners.cycles()).isZero();
        assertThat(listeners.hears(c.id(), a.id())).isTrue();
        assertThat(listeners.hears(c.id(), b.id())).isTrue();
        assertThat(listeners.order(listeners.orderCount() - 1)).isNotEqualTo(a.id());
    }

    @Test
    void aListenerNeverHearsItsOwnReEmission() {
        Listener a = camera(0, 1);
        sounds.advance(0.0);
        assertThat(listeners.hears(a.id(), a.id())).isFalse();
        assertThat(listeners.cycles()).isZero();
    }

    @Test
    void chainsLongerThanTheMaximumAreCut() {
        List<Listener> chain = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chain.add(camera(100 * i, 100 * (i + 1)));
        }
        sounds.advance(0.0);
        assertThat(listeners.cut(chain.get(2).id(), chain.get(3).id())).isFalse();
        assertThat(listeners.cut(chain.get(3).id(), chain.get(4).id())).isTrue();
        assertThat(listeners.cuts()).isEqualTo(1);
        assertThat(listeners.cut(chain.get(0).id(), chain.get(5).id())).isFalse();
        assertThat(listeners.delayed(chain.get(0).id(), chain.get(5).id())).isFalse();
    }

    @Test
    void reachAndBusesDecideWhoHears() {
        Listener a = camera(0, 100);
        Listener far = listeners.render(node(150, 0, 0), ListenerMix.mono().maxDistance(10));
        Listener deaf = listeners.render(node(100, 0, 0), ListenerMix.mono().buses(Bus.AMBIENCE));
        Listener device = listeners.device(node(100, 0, 0));
        sounds.advance(0.0);
        assertThat(listeners.hears(far.id(), a.id())).isFalse();
        assertThat(listeners.hears(deaf.id(), a.id())).isFalse();
        assertThat(listeners.hears(device.id(), a.id())).isFalse();
        sounds.play(TV, 0, 0, 0); // no input: re-emits nothing
        sounds.advance(0.0);
        assertThat(listeners.orderCount()).isEqualTo(3);
        assertThatThrownBy(() -> sounds.play(TV, 0, 0, 0).input(new Listener(listeners, device.id())))
            .hasMessageStartingWith("Not a rendering listener");
        assertThatThrownBy(() -> sounds.play(TV, 0, 0, 0).input(new Listener(listeners, 99)))
            .hasMessageStartingWith("Not a rendering listener");
        long tv = sounds.fire(TV, 0, 0, 0);
        sounds.input(tv, a.id());
        assertThat(sounds.input(tv)).isEqualTo(a.id());
        sounds.input(tv, 0);
        assertThat(sounds.input(tv)).isZero();
        a.remove();
        sounds.advance(0.0); // a sound playing a removed listener's mix re-emits nothing
        assertThat(listeners.orderCount()).isEqualTo(2);
    }

    @Test
    void mixesSayWhatTheyHear() {
        ListenerMix all = ListenerMix.mono();
        assertThat(all.hears(Bus.SFX)).isTrue();
        assertThat(all.hears(Bus.child(Bus.SFX, "steps"))).isTrue();
        assertThat(all.hears(Bus.MUSIC)).isFalse();
        assertThat(all.hears(Bus.child(Bus.UI, "menu"))).isFalse();
        ListenerMix some = ListenerMix.stereo().buses(Bus.SFX).voices(8).gain(-6).maxDistance(40);
        assertThat(some.hears(Bus.child(Bus.SFX, "steps"))).isTrue();
        assertThat(some.hears(Bus.AMBIENCE)).isFalse();
        assertThat(some.voices()).isEqualTo(8);
        assertThat(some.gain()).isEqualTo(-6);
        assertThat(some.maxDistance()).isEqualTo(40);
        assertThat(some.deviceBus()).isNull();
        assertThat(some.tapped()).isFalse();
        assertThatThrownBy(() -> new ListenerMix(3, List.of(), 1, 0, 1, null, false)).hasMessageContaining("mono");
        assertThatThrownBy(() -> ListenerMix.mono().voices(0)).hasMessageContaining("needs a voice");
        assertThatThrownBy(() -> ListenerMix.mono().maxDistance(0)).hasMessage("Not a distance: 0.0");
    }

    @Test
    void reverbZonesWeighAndPickTheStrongest() {
        ReverbZones zones = sounds.reverbZones();
        AudioEffect hall = AudioEffect.named("reverb").set("size", 0.9);
        AudioEffect room = AudioEffect.named("reverb").set("size", 0.3);
        long boxNode = node(0, 0, 0);
        scene.setRotation(boxNode, 0, Math.sin(Math.PI / 4), 0, Math.cos(Math.PI / 4)); // 90 degrees about y
        scene.update();
        long box = zones.box(boxNode, 10, 3, 1, 2, hall, 0);
        long ball = zones.sphere(node(0, 0, 9), 2, 1, room, 1);
        sounds.advance(0.0);
        assertThat(zones.size()).isEqualTo(2);
        assertThat(zones.reverb(box)).isEqualTo(hall);
        assertThat(zones.weight(box, 0.5, 0, 9)).as("turned: x 1 wide, z 10 long").isEqualTo(1);
        assertThat(zones.weight(box, 9, 0, 0)).isZero();
        assertThat((double) zones.weight(box, 2, 0, 0)).isCloseTo(0.5, org.assertj.core.api.Assertions.within(1e-6));
        assertThat((double) zones.weight(ball, 0, 0, 11.5)).isCloseTo(0.5, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(zones.weight(ball, 0, 0, 9)).isEqualTo(1);
        assertThat(zones.strongest(0, 0, 9)).as("higher priority").isEqualTo(ball);
        assertThat(zones.strongest(0, 0, 0)).isEqualTo(box);
        assertThat(zones.strongest(50, 0, 0)).isZero();
        long other = zones.box(node(0, 0, 0), 1, 1, 1, 4, room, 0);
        assertThat(zones.strongest(0, 0, 0)).as("equal priority, equal weight: the first").isEqualTo(box);
        assertThat(zones.strongest(2.5, 0, 0)).as("equal priority: the heavier").isEqualTo(other);
        List<Long> all = new ArrayList<>();
        zones.forEach(all::add);
        assertThat(all).containsExactly(box, ball, other);
        int version = zones.version();
        zones.remove(other);
        zones.remove(other);
        assertThat(zones.version()).isEqualTo(version + 1);
        assertThat(zones.alive(other)).isFalse();
        assertThat(zones.alive(box)).isTrue();
        assertThat(zones.alive(-1)).isFalse();
        assertThat(zones.alive(99)).isFalse();
        assertThat(zones.alive(box + (1L << 32))).isFalse();
        all.clear();
        zones.forEach(all::add);
        assertThat(all).containsExactly(box, ball);
        assertThat(zones.strongest(0, 0, 0)).isEqualTo(box);
        long lower = zones.sphere(boxNode, 3, 0, room, -1);
        assertThat(zones.strongest(0, 0, 0)).as("a lower priority later").isEqualTo(box);
        zones.remove(lower);
        sounds.advance(0.0);
        assertThat(zones.weight(other, 0, 0, 0)).isZero();
        assertThatThrownBy(() -> zones.reverb(other)).hasMessageStartingWith("No reverb zone is");
        assertThatThrownBy(() -> zones.box(boxNode, 0, 1, 1, 0, hall, 0)).hasMessageContaining("Not a box");
        assertThatThrownBy(() -> zones.box(boxNode, 1, 0, 1, 0, hall, 0)).hasMessageContaining("Not a box");
        assertThatThrownBy(() -> zones.box(boxNode, 1, 1, 0, 0, hall, 0)).hasMessageContaining("Not a box");
        assertThatThrownBy(() -> zones.sphere(boxNode, 0, 0, hall, 0)).hasMessage("Not a radius: 0.0");
        assertThatThrownBy(() -> zones.sphere(boxNode, 1, -1, hall, 0)).hasMessage("Not a fade distance: -1.0");
        assertThatThrownBy(() -> zones.sphere(boxNode, 1, Double.POSITIVE_INFINITY, hall, 0))
            .hasMessageContaining("fade distance");
        assertThatThrownBy(() -> zones.sphere(NodeHandle.NONE, 1, 0, hall, 0)).hasMessageContaining("Not a node");
        for (int i = 0; i < 5; i++) {
            zones.sphere(boxNode, 1, 0, hall, 0);
        }
        scene.remove(boxNode);
        scene.update();
        sounds.advance(0.0);
        assertThat(zones.alive(box)).as("its node removed").isFalse();
        assertThat(zones.size()).isEqualTo(1);
    }
}
