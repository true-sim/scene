package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** Sounds a node plays as a component of it (application-api.md D46). */
class SoundEmitterTest {

    @Test
    void emittersPlayWhileTheyAreThere() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Sound hum = Sound.of(SoundsTest.clip(1));
        long machine = scene.create();
        scene.components().set(machine, SoundEmitter.TYPE, SoundEmitter.of(hum)); // before the store: seen too
        Sounds sounds = new Sounds(scene);
        scene.update();
        sounds.advance(1000);
        long first = sounds.emitted(machine);
        assertThat(first).isNotZero();
        assertThat(sounds.sound(first)).isSameAs(hum);
        assertThat(sounds.node(first)).isEqualTo(machine);
        // replaced: the old one stops, the new one plays
        scene.components().set(machine, SoundEmitter.TYPE, SoundEmitter.of(Sound.of(SoundsTest.clip(2))));
        sounds.advance(1000);
        long second = sounds.emitted(machine);
        assertThat(second).isNotEqualTo(first);
        assertThat(sounds.alive(first) && !sounds.stopped(first)).isFalse();
        // removed, and its node removed
        scene.components().remove(machine, SoundEmitter.TYPE);
        sounds.advance(1000);
        assertThat(sounds.stopped(second) || !sounds.alive(second)).isTrue();
        long fan = scene.create();
        scene.components().set(fan, SoundEmitter.TYPE, SoundEmitter.of(hum));
        sounds.advance(1000);
        long third = sounds.emitted(fan);
        scene.remove(fan);
        sounds.advance(1000);
        assertThat(sounds.emitted(fan)).isZero();
        assertThat(sounds.stopped(third) || !sounds.alive(third)).isTrue();
        assertThat(sounds.emitted(NodeHandle.NONE)).isZero();
        assertThat(sounds.emitted(NodeHandle.of(5000, 1))).isZero();
        assertThat(SoundEmitter.of(hum).sound()).isSameAs(hum);
        assertThat(SoundEmitter.of(hum)).hasToString("sound emitter");
        assertThatNullPointerException().isThrownBy(() -> SoundEmitter.of(null));
        assertThat(Sounds.ui().emitted(machine)).isZero();
    }

    @Test
    void aStoreFallenBehindTheLogMatchesWhatIsThere() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Sounds sounds = new Sounds(scene);
        Sound hum = Sound.of(SoundsTest.clip(1));
        long a = scene.create();
        long b = scene.create();
        scene.components().set(a, SoundEmitter.TYPE, SoundEmitter.of(hum));
        scene.components().set(b, SoundEmitter.TYPE, SoundEmitter.of(hum));
        sounds.advance(1000);
        long playing = sounds.emitted(a);
        ComponentStore<SoundEmitter> store = scene.components().store(SoundEmitter.TYPE);
        store.remove(b);
        for (int i = 0; i < ComponentStore.MAX_LOG + 10; i++) {
            store.changed(a);
        }
        long c = scene.create();
        store.set(c, SoundEmitter.of(hum));
        sounds.advance(1000);
        assertThat(sounds.emitted(a)).as("kept").isEqualTo(playing);
        assertThat(sounds.emitted(b)).isZero();
        assertThat(sounds.emitted(c)).isNotZero();
    }
}
