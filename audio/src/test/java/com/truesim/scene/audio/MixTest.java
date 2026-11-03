package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Buses, layouts, effects as records and snapshots: processing order and every problem with its suggestion. */
class MixTest {

    static final Bus FOOTSTEPS = Bus.child(Bus.SFX, "footsteps");
    static final Bus REVERB = Bus.aux("reverb");
    static final Bus ECHO = Bus.aux("echo");

    @Test
    void busesAreValuesByName() {
        assertThat(Bus.of("sfx")).isEqualTo(Bus.SFX);
        assertThat(Bus.MASTER.parent()).isNull();
        assertThat(FOOTSTEPS.parent()).isEqualTo(Bus.SFX);
        assertThat(REVERB.aux()).isTrue();
        assertThat(FOOTSTEPS.toString()).isEqualTo("footsteps");
        assertThat(Bus.VOLUME).isEqualTo(Bus.Property.VOLUME);
        assertThat(Bus.HIGH_PASS).isEqualTo(Bus.Property.HIGH_PASS);
        assertThatThrownBy(() -> Bus.of("")).hasMessage("A bus has a name");
    }

    @Test
    void layoutsOrderEveryBusAfterThoseFeedingIt() {
        BusLayout mix = BusLayout.builder()
            .aux(REVERB, b -> b.effect(AudioEffect.named("reverb").set("size", 0.6)))
            .bus(Bus.MUSIC, b -> b.volume(-4).mute())
            .bus(FOOTSTEPS, b -> b.send(REVERB, -6).sendPreFader(ECHO, -12))
            .aux(ECHO, b -> b.send(REVERB, -3))
            .bus(Bus.SFX, b -> b.effect(AudioEffect.named("compressor").set("threshold", -18).set("ratio", 3)))
            .bus(Bus.VOICE).bus(Bus.MASTER, b -> b.volume(-1))
            .duck(Bus.MUSIC, Bus.VOICE, Duck.by(-9))
            .build();
        List<String> order = mix.buses().stream().map(s -> s.bus().name()).toList();
        assertThat(order).containsExactly("music", "footsteps", "echo", "sfx", "voice", "reverb", "master")
            .isNotEmpty();
        // every bus after its feeders, the master after all
        assertThat(order.indexOf("footsteps")).isLessThan(order.indexOf("sfx")).isLessThan(order.indexOf("echo"));
        assertThat(order.indexOf("echo")).isLessThan(order.indexOf("reverb"));
        assertThat(order.getLast()).isEqualTo("master");
        BusLayout.Settings music = mix.buses().get(mix.indexOf(Bus.MUSIC));
        assertThat(music.volume()).isEqualTo(-4);
        assertThat(music.mute()).isTrue();
        assertThat(mix.buses().get(mix.indexOf(FOOTSTEPS)).sends())
            .containsExactly(new BusLayout.Send(REVERB, -6, false), new BusLayout.Send(ECHO, -12, true));
        assertThat(mix.indexOf(Bus.UI)).isEqualTo(-1);
        assertThat(mix.contains(Bus.VOICE)).isTrue();
        assertThat(mix.ducks()).containsExactly(new BusLayout.Ducking(Bus.MUSIC, Bus.VOICE, Duck.by(-9)));
        assertThat(mix.toString()).startsWith("bus layout [music");
    }

    @Test
    void theDefaultLayoutHasTheStandardBuses() {
        BusLayout d = BusLayout.DEFAULT;
        assertThat(d.buses()).extracting(s -> s.bus().name())
            .containsExactlyInAnyOrder("master", "sfx", "music", "voice", "ui", "ambience");
        assertThat(d.buses().get(d.indexOf(Bus.UI)).ignoresPause()).isTrue();
        assertThat(d.buses().getLast().bus()).isEqualTo(Bus.MASTER);
        BusLayout again = BusLayout.builder().bus(Bus.SFX).bus(Bus.MUSIC).bus(Bus.VOICE)
            .bus(Bus.UI, b -> b.ignoresPause()).bus(Bus.AMBIENCE).build();
        assertThat(again).isEqualTo(d).hasSameHashCodeAs(d).isNotEqualTo("layout")
            .isNotEqualTo(BusLayout.builder().build())
            .isNotEqualTo(BusLayout.builder().bus(Bus.SFX).bus(Bus.MUSIC).bus(Bus.VOICE)
                .bus(Bus.UI, b -> b.ignoresPause()).bus(Bus.AMBIENCE).duck(Bus.MUSIC, Bus.VOICE, Duck.by(-3)).build());
    }

    @Test
    void everyLayoutProblemIsReportedWithASuggestion() {
        InvalidAudioException e = catchThrowableOfType(InvalidAudioException.class, () -> BusLayout.builder()
            .bus(REVERB)
            .aux(Bus.of("plain"), b -> { })
            .bus(Bus.SFX).bus(Bus.SFX).bus(Bus.MUSIC)
            .bus(Bus.child(Bus.of("vehicle"), "car"))
            .bus(FOOTSTEPS, b -> b.send(Bus.aux("reverbb"), 0).send(Bus.SFX, 0).send(Bus.aux("sfx"), 0)
                .effect(AudioEffect.named("eq.lowpass")).effect(AudioEffect.named("delay").set("tiem", 1)))
            .duck(Bus.of("muzic"), Bus.SFX, Duck.by(-3)).duck(Bus.SFX, Bus.of("voise"), Duck.by(-3))
            .duck(Bus.SFX, Bus.SFX, Duck.by(-3))
            .build());
        assertThat(e.problems()).containsExactly(
            "Bus reverb is an aux bus: add it with aux()",
            "Bus plain is not an aux bus: make it with Bus.aux()",
            "Bus sfx is in the layout twice",
            "Bus car sums into vehicle, which is not in the layout",
            "Bus footsteps: no effect is called eq.lowpass (did you mean eq.lowPass?)",
            "Bus footsteps: effect delay has no field tiem (did you mean time?)",
            "Bus footsteps sends to reverbb, which is not in the layout (did you mean reverb?)",
            "Bus footsteps sends to sfx, which is not an aux bus",
            "Bus footsteps sends to sfx, which the layout has with another parent or kind",
            "A duck lowers muzic, which is not in the layout (did you mean music?)",
            "A duck listens to voise, which is not in the layout",
            "Bus sfx cannot duck itself");
    }

    @Test
    void sendsInALoopAreRefused() {
        assertThatThrownBy(() -> BusLayout.builder().aux(REVERB, b -> b.send(ECHO, 0)).aux(ECHO, b -> b.send(REVERB, 0))
                .build())
            .isInstanceOf(InvalidAudioException.class)
            .hasMessage("The bus layout has 1 problem:\n  Sends make a loop through reverb, echo");
        assertThatThrownBy(() -> BusLayout.builder().aux(REVERB, b -> b.send(REVERB, 0)).build())
            .hasMessageContaining("Sends make a loop through reverb");
    }

    @Test
    void effectsAreRecordsByNameCheckedAgainstTheTypesKnown() {
        AudioEffect eq = AudioEffect.named("eq.highPass").set("cutoff", 90).set("q", 0.7);
        assertThat(eq).isEqualTo(AudioEffect.named("eq.highPass").set("q", 0.7).set("cutoff", 90));
        assertThat(eq.value("cutoff", 0)).isEqualTo(90);
        assertThat(eq.value("gain", 3)).isEqualTo(3);
        AudioEffectTypes types = AudioEffectTypes.builtIn().add("bitCrusher", "bits", "downsample");
        assertThat(types.contains("bitCrusher")).isTrue();
        assertThat(types.contains("bitcrusher")).isFalse();
        assertThatThrownBy(() -> types.add("bitCrusher")).hasMessage("Effect bitCrusher is known already");
        BusLayout layout = BusLayout.builder().bus(Bus.UI, b -> b.effect(AudioEffect.named("bitCrusher")
            .set("bits", 6))).build(types);
        assertThat(layout.buses().get(layout.indexOf(Bus.UI)).effects()).hasSize(1);
        assertThatThrownBy(() -> BusLayout.builder().bus(Bus.UI, b -> b.effect(AudioEffect.named("bitCrusher")))
                .build())
            .hasMessageContaining("no effect is called bitCrusher");
        assertThat(Suggestions.didYouMean("zzzzzzzz", List.of("reverb"))).isEmpty();
    }

    @Test
    void snapshotsAreCheckedAlone_andAgainstALayout() {
        Snapshot pause = Snapshot.builder("pause").set(Bus.SFX, Bus.VOLUME, -60).set(Bus.MUSIC, Bus.LOW_PASS, 1_200)
            .fadeIn(0.2).fadeOut(0.4).priority(10).build();
        assertThat(pause.targets()).containsExactly(new Snapshot.Target(Bus.SFX, Bus.Property.VOLUME, -60),
            new Snapshot.Target(Bus.MUSIC, Bus.Property.LOW_PASS, 1_200));
        assertThat(pause.fadeIn()).isEqualTo(0.2);
        assertThat(pause.fadeOut()).isEqualTo(0.4);
        assertThat(pause.priority()).isEqualTo(10);
        assertThat(pause).isEqualTo(Snapshot.builder("pause").set(Bus.SFX, Bus.VOLUME, -60)
            .set(Bus.MUSIC, Bus.LOW_PASS, 1_200).fadeIn(0.2).fadeOut(0.4).priority(10).build());
        pause.check(BusLayout.DEFAULT);
        assertThatThrownBy(() -> Snapshot.builder("p").set(Bus.of("sfxx"), Bus.VOLUME, 0).build()
                .check(BusLayout.DEFAULT))
            .hasMessage("The snapshot p has 1 problem:\n  It sets VOLUME of sfxx, which is not in the layout"
                + " (did you mean sfx?)");
        InvalidAudioException e = catchThrowableOfType(InvalidAudioException.class, () -> Snapshot.builder("bad")
            .set(Bus.SFX, Bus.VOLUME, 30).set(Bus.SFX, Bus.VOLUME, -90).set(Bus.MUSIC, Bus.HIGH_PASS, 5)
            .set(Bus.MUSIC, Bus.LOW_PASS, 30_000).set(Bus.UI, Bus.VOLUME, Double.NaN).fadeIn(-1).build());
        assertThat(e.problems()).containsExactly(
            "Fades are seconds, not -1.0 and 0.1",
            "VOLUME of sfx cannot be 30.0 (-80 to 24 dB)",
            "VOLUME of sfx cannot be -90.0 (-80 to 24 dB)",
            "VOLUME of sfx is set twice",
            "HIGH_PASS of music cannot be 5.0 (10 to 24,000 Hz)",
            "LOW_PASS of music cannot be 30000.0 (10 to 24,000 Hz)",
            "VOLUME of ui cannot be NaN (-80 to 24 dB)");
        assertThatThrownBy(() -> Snapshot.builder("x").fadeOut(-1).build()).hasMessageContaining("Fades");
        assertThatThrownBy(() -> Snapshot.builder("x").fadeOut(Double.POSITIVE_INFINITY).build())
            .hasMessageContaining("Fades");
    }
}
