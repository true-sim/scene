package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** A patch names the parameters it reads, so instances set them and fire its triggers. */
class PatchTest {

    static final SoundParameter RPM = SoundParameter.of("rpm", 800, 0, 9000);
    static final SoundParameter SHIFT = SoundParameter.trigger("shift");

    @Test
    void aPatchsInputsAreItsSoundsParameters() {
        Sound sound = Sound.builder().root(Patch.named("v8", RPM, SHIFT).delay(0.1)).loop().build();
        assertThat(sound.parameters()).containsExactly(RPM, SHIFT);
        assertThat(((Patch) sound.root()).inputs()).containsExactly(RPM, SHIFT);
        assertThat(((Patch) sound.root()).shaping().delay()).isEqualTo(0.1);
        SoundInstance engine = new Sounds(new SceneGraph(new ChangeClock())).play(sound);
        engine.set(RPM, 3000);
        assertThat(engine.get(RPM)).isEqualTo(3000);
    }

    @Test
    void aTriggerCountsAndWraps() {
        Sounds sounds = new Sounds(new SceneGraph(new ChangeClock()));
        SoundInstance engine = sounds.play(Sound.builder().root(Patch.named("v8", RPM, SHIFT)).build());
        engine.trigger(SHIFT).trigger(SHIFT);
        assertThat(engine.get(SHIFT)).isEqualTo(2);
        assertThat(sounds.parameterRamp(engine.id())).isZero();
        engine.set(SHIFT, SoundParameter.TRIGGER_WRAP - 1);
        engine.trigger(SHIFT);
        assertThat(engine.get(SHIFT)).isZero();
        engine.kill();
        engine.trigger(SHIFT); // inert once ended
        assertThat(engine.get(SHIFT)).isNaN();
    }

    @Test
    void aTriggerIsAParameterFromZero() {
        assertThat(SHIFT).isEqualTo(SoundParameter.of("shift", 0, 0, SoundParameter.TRIGGER_WRAP));
        Sounds sounds = new Sounds(new SceneGraph(new ChangeClock()));
        SoundInstance other = sounds.play(Sound.builder().root(Patch.named("wind")).build());
        other.trigger(SHIFT); // a sound that reads no such trigger ignores it
        assertThat(other.get(SHIFT)).isNaN();
    }
}
