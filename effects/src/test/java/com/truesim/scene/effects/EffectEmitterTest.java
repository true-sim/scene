package com.truesim.scene.effects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** Effects a node plays as a component of it (application-api.md D46). */
class EffectEmitterTest {

    private static EffectData flame() {
        return EffectData.builder().emitter("e", e -> e.target(Target.CPU).spawn(Spawn.rate(10))
            .init(Init.lifetime(1))).build();
    }

    @Test
    void emittersPlayWhileTheyAreThereAndMatchWhatIsThereWhenFallenBehind() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Effects effects = new Effects(scene);
        EffectData flame = flame();
        long torch = scene.create();
        scene.components().set(torch, EffectEmitter.TYPE, EffectEmitter.of(flame));
        scene.update();
        effects.advance(1000L);
        long first = effects.emitted(torch);
        assertThat(first).isNotZero();
        scene.components().set(torch, EffectEmitter.TYPE, EffectEmitter.of(flame()));
        effects.advance(1000L);
        assertThat(effects.emitted(torch)).isNotEqualTo(first).isNotZero();
        scene.components().remove(torch, EffectEmitter.TYPE);
        effects.advance(1000L);
        assertThat(effects.emitted(torch)).isZero();
        long a = scene.create();
        long b = scene.create();
        ComponentStore<EffectEmitter> store = scene.components().store(EffectEmitter.TYPE);
        store.set(a, EffectEmitter.of(flame));
        store.set(b, EffectEmitter.of(flame));
        effects.advance(1000L);
        long playing = effects.emitted(a);
        store.remove(b);
        for (int i = 0; i < ComponentStore.MAX_LOG + 10; i++) {
            store.changed(a);
        }
        long c = scene.create();
        store.set(c, EffectEmitter.of(flame));
        effects.advance(1000L);
        assertThat(effects.emitted(a)).isEqualTo(playing);
        assertThat(effects.emitted(b)).isZero();
        assertThat(effects.emitted(c)).isNotZero();
        assertThat(effects.emitted(NodeHandle.NONE)).isZero();
        assertThat(effects.emitted(NodeHandle.of(5000, 1))).isZero();
        assertThat(EffectEmitter.of(flame).effect()).isSameAs(flame);
        assertThat(EffectEmitter.of(flame)).hasToString("effect emitter");
        assertThatNullPointerException().isThrownBy(() -> EffectEmitter.of(null));
    }
}
