package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Dialogue (§2.15): lines, WebVTT cues, Rhubarb visemes, and the rules of who speaks when. */
class DialogueTest {

    static final String VTT = "﻿WEBVTT - a guard\r\n\r\nNOTE timing by hand\r\n\r\n1\r\n"
        + "00:00:00.500 --> 00:00:01.250 align:start\r\nguard.halt\r\n\r\n"
        + "00:01.500 --> 00:02.000\nguard.who\ngoes there\n";
    static final String RHUBARB = "{\"metadata\": {\"duration\": 1.0}, \"mouthCues\": ["
        + "{ \"start\": 0.00, \"end\": 0.20, \"value\": \"X\" },"
        + "{ \"start\": 0.20, \"end\": 0.50, \"value\": \"B\" },"
        + "{ \"start\": 0.50, \"end\": 0.80, \"value\": \"E\" } ]}";

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Sounds sounds = new Sounds(scene);
    private final long head = scene.create();
    private final List<String> events = new ArrayList<>();

    static AssetRef<AudioClip> clip(int n) {
        return SoundTest.clip(n);
    }

    private Dialogue dialogue() {
        return sounds.dialogue().listen((event, line, speaker, text, start, end) -> events.add(event + " " + speaker
            + " " + text + (event == DialogueEvent.CUE_STARTED ? " " + start + "-" + end : "")));
    }

    @Test
    void webVttCues() {
        List<Cue> cues = WebVtt.parse(VTT);
        assertThat(cues).containsExactly(new Cue(0.5, 1.25, "guard.halt"), new Cue(1.5, 2, "guard.who\ngoes there"));
        assertThat(WebVtt.parse("WEBVTT\n\n01:00:00.000 --> 01:00:01.000\nx")).containsExactly(new Cue(3_600, 3_601,
            "x"));
        assertThat(WebVtt.parse("WEBVTT\r00:00.000 --> 00:01.000\r\r")).containsExactly(new Cue(0, 1, ""));
        assertThatThrownBy(() -> WebVtt.parse("1\n00:00.000 --> 00:01.000")).hasMessageContaining("WEBVTT");
        assertThatThrownBy(() -> WebVtt.parse("")).hasMessageContaining("WEBVTT");
        assertThatThrownBy(() -> WebVtt.parse("WEBVTT\n1.5 --> 2.0")).hasMessageContaining("Not a WebVTT time");
        assertThatThrownBy(() -> WebVtt.parse("WEBVTT\n0:0:0:1 --> 2.0")).hasMessageContaining("Not a WebVTT time");
        assertThatThrownBy(() -> WebVtt.parse("WEBVTT\naa:01.0 --> 00:02.0")).hasMessageContaining("aa:01.0");
        assertThatThrownBy(() -> new Cue(2, 1, "x")).hasMessageContaining("forwards");
        assertThatThrownBy(() -> new Cue(-1, 1, "x")).hasMessageContaining("forwards");
    }

    @Test
    void visemesAreInterpolatedOver40Ms() {
        Visemes v = Visemes.rhubarb(RHUBARB);
        assertThat(v.size()).isEqualTo(3);
        float[] w = new float[9];
        v.weights(0.1, w);
        assertThat(w[8]).as("rest").isEqualTo(1);
        v.weights(0.21, w);
        assertThat(w[1]).as("B fading in").isCloseTo(0.25f, within(1e-5f));
        assertThat(w[8]).as("X fading out").isCloseTo(0.75f, within(1e-5f));
        v.weights(0.3, w);
        assertThat(w[1]).isEqualTo(1);
        v.weights(0.52, w);
        assertThat(w[4]).isCloseTo(0.5f, within(1e-5f));
        assertThat(w[1]).isCloseTo(0.5f, within(1e-5f));
        v.weights(2, w);
        assertThat(w[8]).as("past the end: rest").isEqualTo(1);
        v.weights(-1, w);
        assertThat(w[8]).isEqualTo(1);
        Visemes late = Visemes.rhubarb("{\"mouthCues\": [{\"start\": 0.5, \"end\": 1.0, \"value\": \"A\"}]}");
        late.weights(0.25, w);
        assertThat(w[8]).as("before the first cue").isEqualTo(1);
        late.weights(0.52, w);
        assertThat(w[0]).as("from rest").isCloseTo(0.5f, within(1e-5f));
        assertThatThrownBy(() -> Visemes.rhubarb("{}")).hasMessageContaining("No mouthCues");
        assertThatThrownBy(() -> Visemes.rhubarb("{\"start\": 0, \"end\": 1, \"value\": \"Z\"}"))
            .hasMessageContaining("Z");
    }

    @Test
    void linesResolveTheirLocaleAndKey() {
        Line halt = Line.of("game://vo/{locale}/halt.ogg").speaker("guard").subtitle("guard.halt");
        assertThat(halt.path("de")).isEqualTo("game://vo/de/halt.ogg");
        assertThat(halt.key()).isEqualTo("guard.halt");
        assertThat(Line.of("game://a.ogg").key()).isEqualTo("game://a.ogg");
        Line given = Line.of(clip(1));
        assertThat(given.key()).isEqualTo(clip(1));
        assertThat(given.path("de")).isNull();
        assertThat(given.cues(WebVtt.parse(VTT)).visemes(Visemes.rhubarb(RHUBARB)).cues()).hasSize(2);
        assertThatThrownBy(() -> new Line(null, null, "", null, List.of(), null)).hasMessageContaining("one of them");
        assertThatThrownBy(() -> new Line("a", clip(1), "", null, List.of(), null)).hasMessageContaining("one");
    }

    @Test
    void cuesAreReportedAtTheHeardTime() {
        Dialogue d = dialogue();
        LineInstance line = d.say(Line.of(clip(1)).speaker("guard").cues(WebVtt.parse(VTT))
            .visemes(Visemes.rhubarb(RHUBARB)), head, Priority.NORMAL);
        assertThat(line.alive()).isTrue();
        assertThat(d.saying(line.instance())).isTrue();
        assertThat(d.saying(12345)).isFalse();
        d.heard(line.instance(), -0.05); // the engine: not heard yet
        d.update();
        assertThat(events).isEmpty();
        d.heard(line.instance(), 0.6);
        d.envelope(line.instance(), 0.3f);
        d.update();
        assertThat(events).containsExactly("LINE_STARTED guard null", "CUE_STARTED guard guard.halt 0.5-1.25");
        assertThat(line.heard()).isEqualTo(0.6);
        assertThat(line.envelope()).isEqualTo(0.3f);
        float[] w = new float[9];
        line.visemes(w);
        assertThat(w[4]).as("E, faded in").isEqualTo(1);
        d.heard(line.instance(), 1.3); // between the cues
        d.heard(777, 1);
        d.envelope(777, 1);
        d.update();
        assertThat(events).endsWith("CUE_ENDED guard guard.halt");
        d.heard(line.instance(), 1.8);
        d.update();
        assertThat(events).endsWith("CUE_STARTED guard guard.who\ngoes there 1.5-2.0");
        sounds.ended(line.instance());
        d.update();
        assertThat(events).endsWith("CUE_ENDED guard guard.who\ngoes there", "LINE_ENDED guard null");
        assertThat(line.alive()).isFalse();
        assertThat(line.heard()).isEqualTo(-1);
        assertThat(line.envelope()).isZero();
        assertThat(d.size()).isZero();
        d.heard(line.instance(), 1);
        d.envelope(line.instance(), 1);
    }

    @Test
    void withoutAnEngineTheInstancesTimeStandsIn() {
        Dialogue d = dialogue();
        LineInstance line = d.say(Line.of(clip(1)).speaker("narrator").subtitle("intro"), Priority.NORMAL);
        float[] w = new float[9];
        line.visemes(w);
        assertThat(w[8]).as("no track: rest").isEqualTo(1);
        sounds.advance(0.5);
        assertThat(events).containsExactly("LINE_STARTED narrator intro", "CUE_STARTED narrator intro 0.0-Infinity");
        assertThat(line.heard()).isCloseTo(0.5, within(1e-6));
        sounds.kill(line.instance());
        sounds.advance(0.1);
        assertThat(events).endsWith("CUE_ENDED narrator intro", "LINE_ENDED narrator intro");
        LineInstance silent = d.say(Line.of(clip(2)).speaker("narrator"), Priority.NORMAL);
        assertThat(sounds.dialogue()).isSameAs(d);
        sounds.kill(silent.instance());
        assertThat(silent.heard()).as("its sound gone, not yet reported").isEqualTo(-1);
        sounds.advance(0.1);
        silent = d.say(Line.of(clip(2)).speaker("narrator"), Priority.NORMAL); // said before, no cooldown
        sounds.advance(0.1);
        assertThat(events).endsWith("LINE_STARTED narrator null");
        assertThat(silent.line().subtitle()).isNull();
    }

    @Test
    void withAnEngineALineIsHeardOnlyWhenItSays() {
        Dialogue d = dialogue();
        d.heardByEngine();
        LineInstance line = d.say(Line.of(clip(1)).speaker("narrator").subtitle("intro"), Priority.NORMAL);
        sounds.advance(0.5);
        assertThat(events).as("not told yet").isEmpty();
        assertThat(line.heard()).isEqualTo(-1);
        d.heard(line.instance(), 0.01);
        d.update();
        assertThat(events).hasSize(2);
    }

    @Test
    void higherInterruptsEqualQueuesLowerIsDropped() {
        Dialogue d = dialogue().queueExpiry(2);
        Line bark = Line.of(clip(1)).speaker("guard").subtitle("bark");
        LineInstance first = d.say(bark, head, Priority.NORMAL);
        sounds.advance(0.1); // heard: its cue open
        LineInstance queued = d.say(Line.of(clip(2)).speaker("guard").subtitle("next"), head, Priority.NORMAL);
        assertThat(queued.instance()).as("queued").isZero();
        assertThat(queued.alive()).isTrue();
        assertThat(queued.heard()).isEqualTo(-1);
        LineInstance low = d.say(Line.of(clip(3)).speaker("guard"), head, Priority.LOW);
        assertThat(low.alive()).isFalse();
        assertThat(events).endsWith("DROPPED guard null");
        LineInstance other = d.say(Line.of(clip(3)).speaker("captain"), 0, Priority.LOW);
        assertThat(other.instance()).as("another speaker: at once").isNotZero();
        LineInstance unheard = d.say(Line.of(clip(6)).speaker("cook"), head, Priority.LOW);
        d.say(Line.of(clip(6)).speaker("cook"), head, Priority.HIGH); // before the first was heard
        assertThat(unheard.alive()).isFalse();
        LineInstance urgent = d.say(Line.of(clip(4)).speaker("guard").subtitle("alarm"), head, Priority.HIGH);
        assertThat(events).contains("CUE_ENDED guard bark", "INTERRUPTED guard bark");
        assertThat(first.alive()).isFalse();
        assertThat(sounds.stopped(first.instance())).as("fading out").isTrue();
        assertThat(urgent.instance()).isNotZero();
        // the equal one waits for the urgent line, and expires before it ends
        sounds.advance(1.0);
        assertThat(queued.instance()).isZero();
        sounds.advance(1.5);
        assertThat(events).contains("EXPIRED guard next");
        assertThat(queued.alive()).isFalse();
        // queued behind a line that ends: starts
        LineInstance after = d.say(Line.of(clip(5)).speaker("guard"), head, Priority.HIGH);
        sounds.ended(urgent.instance());
        sounds.advance(0.1);
        assertThat(after.instance()).isNotZero();
    }

    @Test
    void barksCoolDownAndPathsResolveByLocale() {
        List<String> resolved = new ArrayList<>();
        Dialogue d = dialogue().cooldown("guard.huh", 3).locale("de").clips(path -> {
            resolved.add(path);
            return clip(7);
        });
        Line huh = Line.of("game://vo/{locale}/huh.ogg").speaker("guard").subtitle("guard.huh");
        LineInstance one = d.say(huh, Priority.NORMAL);
        assertThat(resolved).containsExactly("game://vo/de/huh.ogg");
        sounds.ended(one.instance());
        sounds.advance(1.0);
        assertThat(d.say(huh, Priority.NORMAL).alive()).as("cooling down").isFalse();
        sounds.advance(2.5);
        assertThat(d.say(huh, Priority.NORMAL).alive()).as(events.toString()).isTrue();
        assertThat(resolved).as("its sound made once").hasSize(1);
        Line other = Line.of("game://vo/{locale}/x.ogg").speaker("x");
        assertThatThrownBy(() -> new Sounds(new SceneGraph(new ChangeClock())).dialogue().say(other, Priority.LOW))
            .hasMessageContaining("clips(");
    }
}
