package com.truesim.scene.audio;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.truesim.common.math.random.Hash;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Music on a fake clock: transitions on the computed frames, pre-entry, priming, stems, stingers, pause, events. */
class MusicTest {

    /** 120 bpm at 48 kHz: 24,000 frames a beat, 96,000 a bar. */
    static final int BEAT = 24_000;
    static final int BAR = 4 * BEAT;

    static final MusicState EXPLORE = MusicState.of("explore");
    static final MusicState COMBAT = MusicState.of("combat");
    static final MusicState BOSS = MusicState.of("boss");
    static final MusicState CALM = MusicState.of("calm");
    static final SoundParameter INTENSITY = SoundParameter.of("intensity", 0, 0, 1);

    static AssetRef<AudioClip> clip(int n) {
        return AssetRef.of(AudioAssets.CLIP, new AssetId(9, n));
    }

    static final AssetRef<AudioClip> A = clip(1);
    static final AssetRef<AudioClip> B = clip(2);
    static final AssetRef<AudioClip> HIT = clip(3);
    static final AssetRef<AudioClip> DRUMS = clip(4);
    static final AssetRef<AudioClip> BRASS = clip(5);
    static final AssetRef<AudioClip> STING = clip(6);
    static final AssetRef<AudioClip> C = clip(7);

    /** A clock the test moves: the schedule point is the rendered frame plus 1,000, heard is rendered minus 1,000. */
    static final class FakeClock implements AudioClock {
        long rendered;

        @Override
        public int rate() {
            return 48_000;
        }

        @Override
        public long renderedFrame() {
            return rendered;
        }

        @Override
        public long heardFrame() {
            return rendered - 1_000;
        }

        @Override
        public long schedulePoint() {
            return rendered + 1_000;
        }
    }

    /** Clips ready unless listed as not, recording what was primed. */
    static final class FakeClips implements MusicClips {
        final Set<AssetRef<AudioClip>> notReady = new HashSet<>();
        final List<AssetRef<AudioClip>> primed = new ArrayList<>();
        double seconds = Double.NaN;

        @Override
        public boolean ready(AssetRef<AudioClip> clip) {
            return !notReady.contains(clip);
        }

        @Override
        public void prime(AssetRef<AudioClip> clip) {
            primed.add(clip);
        }

        @Override
        public double seconds(AssetRef<AudioClip> clip) {
            return seconds;
        }
    }

    private final FakeClock clock = new FakeClock();
    private final FakeClips clips = new FakeClips();
    private Music music;
    private final List<String> heard = new ArrayList<>();

    static Score score() {
        return Score.builder().tempo(Tempo.bpm(120).meter(4, 4))
            .segment("a", s -> s.clip(A).exit(Bar.of(9)).loop().marker("drop", Bar.of(5)).marker("late", Bar.of(7)))
            .segment("b", s -> s.clip(B).entry(Bar.of(1, 3)).exit(Bar.of(5, 3)).marker("drop", Bar.of(3)))
            .segment("c", s -> s.clip(C).exit(Bar.of(3)))
            .segment("hit", s -> s.clip(HIT).exit(Bar.of(2)))
            .segment("stems", s -> s.stems(DRUMS, BRASS).stem(BRASS, INTENSITY, Curve.points(0.5, 0, 1, 1),
                Quantize.NEXT_BEAT).exit(Bar.of(5)).loop())
            .state(EXPLORE, Playlist.of("a"))
            .state(COMBAT, Playlist.of("b", "c"))
            .state(BOSS, Playlist.once("stems"))
            .state(CALM, Playlist.shuffle("b", "c", "hit"))
            .transition(EXPLORE, COMBAT, t -> t.at(Quantize.NEXT_BAR).fadeOut(0.5))
            .transition(COMBAT, EXPLORE, t -> t.at(Quantize.bars(2)).crossfade(Beats.of(2)).startAt(Start.SAME_POSITION))
            .transition(MusicState.ANY, BOSS, t -> t.at(Quantize.NEXT_BEAT).via("hit").fadeIn(0.25))
            .transition(EXPLORE, CALM, t -> t.at(Quantize.NEXT_MARKER).startAt(Start.MATCHING_MARKER))
            .stinger(Stinger.of("level-up"), STING, Quantize.NEXT_BEAT).duck(-6)
            .build();
    }

    @BeforeEach
    void setUp() {
        music = new Music(clock, clips);
        music.listen((event, bar, beat) -> heard.add(event + " " + bar + "." + beat));
    }

    /** Moves the clock to {@code rendered} and updates. */
    private void at(long rendered) {
        clock.rendered = rendered;
        music.update();
    }

    /** The commands so far, as text, then cleared. */
    private List<String> commands() {
        List<String> out = new ArrayList<>();
        for (int c = 0; c < music.commands(); c++) {
            String name = switch (music.kind(c)) {
                case Music.START -> "start";
                case Music.STOP -> "stop";
                case Music.GAIN -> "gain";
                case Music.PAUSE -> "pause";
                case Music.RESUME -> "resume";
                default -> "stinger";
            };
            String clip = music.clip(c) == null ? "" : " clip" + music.clip(c).id().low();
            out.add(name + " v" + music.voice(c) + clip + " @" + music.frame(c)
                + (music.offset(c) != 0 ? " +" + Math.round(music.offset(c) * 48_000) : "")
                + " g" + music.gain(c) + " s" + music.seconds(c));
        }
        music.clearCommands();
        return out;
    }

    @Test
    void aTransitionLandsOnTheNextBarWithItsPreEntryBefore() {
        music.play(score(), EXPLORE);
        at(0);
        assertThat(commands()).containsExactly("start v1 clip1 @1000 g1.0 s0.0");
        at(BAR + 20_000); // schedule point 117,000: the next bar from the entry at 1,000 is 193,000
        music.state(COMBAT);
        assertThat(music.state()).isEqualTo(EXPLORE);
        at(BAR + 30_000);
        // b's two beats of pre-entry start at 193,000 - 48,000, while a fades out from the bar line
        assertThat(commands()).containsExactly("stop v1 @193000 g0.0 s0.5", "start v2 clip2 @145000 g1.0 s0.0");
        assertThat(music.state()).isEqualTo(COMBAT);
        music.state(COMBAT);
        at(BAR + 40_000);
        assertThat(commands()).isEmpty();
    }

    @Test
    void preEntryThatNoLongerFitsStartsLateIntoTheClip() {
        music.play(score(), EXPLORE);
        at(0);
        at(BAR + 90_000); // schedule point 187,000: b's pickup would start at 145,000
        music.state(COMBAT);
        at(BAR + 90_000);
        assertThat(commands()).containsSubsequence("stop v1 @193000 g0.0 s0.5",
            "start v2 clip2 @187000 +42000 g1.0 s0.0");
    }

    @Test
    void aDestinationNotPrimedWaitsForTheNextSyncPointAfterPriming() {
        clips.notReady.add(B);
        music.play(score(), EXPLORE);
        at(0);
        commands();
        music.state(COMBAT);
        assertThat(clips.primed).contains(B);
        at(BAR + 30_000);
        at(BAR + 60_000);
        assertThat(commands()).isEmpty();
        assertThat(music.late()).isEqualTo(1);
        clips.notReady.clear();
        at(2 * BAR + 10_000); // schedule point 203,000: past 193,000, so the bar after
        assertThat(commands()).containsExactly("stop v1 @289000 g0.0 s0.5", "start v2 clip2 @241000 g1.0 s0.0");
    }

    @Test
    void aSegmentNotReadyStartsWhenItIs() {
        clips.notReady.add(A);
        music.play(score(), EXPLORE);
        at(0);
        assertThat(commands()).isEmpty();
        assertThat(music.playing()).isTrue();
        clips.notReady.clear();
        at(5_000);
        assertThat(commands()).containsExactly("start v1 clip1 @6000 g1.0 s0.0");
    }

    @Test
    void segmentsFollowExactlyAsTheirPlaylistsSay() {
        music.play(score(), EXPLORE);
        at(0);
        long exit = 1_000 + 8L * BAR;
        at(exit - 30_000); // half a second of lookahead not yet reached
        assertThat(commands()).containsExactly("start v1 clip1 @1000 g1.0 s0.0");
        at(exit - 25_000);
        assertThat(commands()).as("a loops: again from its exit").containsExactly("start v2 clip1 @" + exit
            + " g1.0 s0.0");
        MusicPosition p = new MusicPosition();
        music.position(p);
        assertThat(p.bar()).as("the next one is still to come").isEqualTo(8);
        at(exit - 25_000);
        assertThat(commands()).isEmpty();

        Music once = new Music(clock, clips);
        clock.rendered = 0;
        once.play(score(), BOSS);
        once.update();
        assertThat(once.commands()).isEqualTo(2);
        clock.rendered = 1_000 + 4L * BAR;
        once.update();
        assertThat(once.commands()).as("stems loop").isEqualTo(4);
    }

    @Test
    void aSequenceGoesRoundAndAShuffleNeverRepeats() {
        Score score = score();
        music.play(score, COMBAT);
        at(0);
        List<Long> clipsPlayed = new ArrayList<>();
        for (int step = 0; step < 30; step++) {
            for (int c = 0; c < music.commands(); c++) {
                clipsPlayed.add(music.clip(c).id().low());
            }
            music.clearCommands();
            at(clock.rendered + BAR);
        }
        assertThat(clipsPlayed).startsWith(2L, 7L, 2L, 7L);

        Music shuffled = new Music(clock, clips).seed(7);
        clock.rendered = 0;
        shuffled.play(score, CALM);
        List<Long> order = new ArrayList<>();
        for (int step = 0; step < 80; step++) {
            shuffled.update();
            for (int c = 0; c < shuffled.commands(); c++) {
                order.add(shuffled.clip(c).id().low());
            }
            shuffled.clearCommands();
            clock.rendered += BAR;
        }
        assertThat(order).hasSizeGreaterThan(20);
        for (int i = 1; i < order.size(); i++) {
            assertThat(order.get(i)).isNotEqualTo(order.get(i - 1));
        }
        assertThat(new HashSet<>(order)).hasSize(3);
    }

    @Test
    void theSameCallsGiveTheSameCommands() {
        assertThat(script(new Music(clock, clips).seed(3))).isEqualTo(script(new Music(clock, clips).seed(3)))
            .isNotEmpty();
    }

    private List<String> script(Music m) {
        music = m;
        clock.rendered = 0;
        music.play(score(), CALM);
        at(0);
        at(3 * BAR);
        music.state(COMBAT);
        at(3 * BAR + 100);
        music.state(BOSS);
        at(5 * BAR);
        music.set(INTENSITY, 0.9f);
        at(9 * BAR);
        return commands();
    }

    @Test
    void viaASegmentWithAFadeIn() {
        music.play(score(), EXPLORE);
        at(0);
        commands();
        at(10_000); // schedule point 11,000: the next beat is 25,000
        music.state(BOSS);
        at(10_000);
        assertThat(commands()).containsExactly("stop v1 @25000 g0.0 s0.5", "start v2 clip3 @25000 g1.0 s0.0",
            "start v3 clip4 @121000 g0.0 s0.0", "gain v3 @121000 g1.0 s0.25", "start v4 clip5 @121000 g0.0 s0.0",
            "gain v4 @121000 g0.0 s0.25");
    }

    @Test
    void aCrossfadeAtTheSamePosition() {
        music.play(score(), COMBAT);
        at(0);
        commands(); // b's entry at 1,000 + 48,000 (its pickup from the schedule point)
        at(BAR);
        music.state(EXPLORE);
        at(BAR);
        // two-bar grid from b's entry at 49,000: 241,000; a cross-fade of two beats is one second
        assertThat(commands()).containsExactly("stop v1 @241000 g0.0 s1.0", "start v2 clip1 @241000 +192000 g0.0 s0.0",
            "gain v2 @241000 g1.0 s1.0");
    }

    @Test
    void matchingMarkersLineUp() {
        int seed = 0;
        while (Integer.remainderUnsigned(Hash.combine(seed, 0), 3) != 0) {
            seed++; // a seed whose shuffle starts calm on b, which has a marker "drop" too
        }
        music = new Music(clock, clips).seed(seed);
        music.play(score(), EXPLORE);
        at(0);
        commands();
        at(BAR); // a's marker "drop" at bar 5: 1,000 + 4 bars
        music.state(CALM);
        at(BAR);
        List<String> out = commands();
        assertThat(out.get(0)).isEqualTo("stop v1 @385000 g0.0 s0.5");
        // b's "drop" (bar 3, after a pickup of two beats: 6 beats past its entry) plays at 385,000
        assertThat(out.get(1)).isEqualTo("start v2 clip2 @385000 +192000 g1.0 s0.0");
        assertThat(music.state()).isEqualTo(CALM);
        // with the seed starting calm on hit, which has no such marker: at its entry
        Music other = new Music(clock, clips).seed(seed + 1);
        while (Integer.remainderUnsigned(Hash.combine(seed + 1, 0), 3) != 2) {
            seed++;
            other = new Music(clock, clips).seed(seed + 1);
        }
        clock.rendered = 0;
        other.play(score(), EXPLORE);
        other.update();
        other.clearCommands();
        clock.rendered = BAR;
        other.state(CALM);
        other.update();
        assertThat(other.clip(1)).isEqualTo(HIT);
        assertThat(other.offset(1)).isZero();
    }

    @Test
    void randomStartsAndTheEndOfAClip() {
        Score score = Score.builder()
            .segment("long", s -> s.clip(A).exit(Bar.of(9)))
            .segment("other", s -> s.clip(B).exit(Bar.of(9)))
            .state(EXPLORE, Playlist.of("long"))
            .state(COMBAT, Playlist.of("other"))
            .transition(EXPLORE, COMBAT, t -> t.at(Quantize.END).startAt(Start.RANDOM))
            .transition(COMBAT, EXPLORE, t -> t.at(Quantize.EXIT_CUE).startAt(Start.MATCHING_MARKER))
            .build();
        music.play(score, EXPLORE);
        at(0);
        commands();
        music.state(COMBAT);
        at(10);
        List<String> out = commands();
        assertThat(out.get(0)).as("the exit cue while the length is not known").startsWith("stop v1 @" + (1_000
            + 8 * BAR));
        clips.seconds = 2;
        music.state(EXPLORE);
        at(20);
        assertThat(commands().get(0)).as("no marker to match: the exit").startsWith("stop v2 @");
        music.state(COMBAT);
        at(30);
        assertThat(commands().get(0)).as("the clip's end, 2 s in").contains("@");
    }

    @Test
    void stemsFollowTheirParameterOnTheBeat() {
        music.play(score(), BOSS);
        at(0);
        assertThat(commands()).containsExactly("start v1 clip4 @1000 g1.0 s0.0", "start v2 clip5 @1000 g0.0 s0.0");
        at(30_000); // schedule point 31,000: the next beat is 49,000
        music.set(INTENSITY, 0.75f);
        assertThat(commands()).containsExactly("gain v2 @49000 g0.5 s0.05");
        assertThatThrownBy(() -> music.set(SoundParameter.of("other", 0, 0, 1), 1))
            .hasMessageStartingWith("No stem follows");
        assertThatThrownBy(() -> new Music(clock, clips).set(INTENSITY, 1)).hasMessageStartingWith("No stem follows");
    }

    @Test
    void stingersPlayOnTheirQuantizeDucking() {
        assertThatThrownBy(() -> music.stinger(Stinger.of("level-up"))).hasMessageContaining("no stinger");
        clips.notReady.add(STING);
        music.play(score(), EXPLORE);
        assertThat(clips.primed).as("stingers primed with the score").contains(STING);
        clips.notReady.clear();
        music.stinger(Stinger.of("level-up"));
        assertThat(commands()).as("nothing scheduled yet: at the schedule point").containsExactly(
            "stinger v1 clip6 @1000 g-6.0 s0.0");
        at(0);
        commands();
        at(30_000);
        music.stinger(Stinger.of("level-up"));
        assertThat(commands()).containsExactly("stinger v3 clip6 @49000 g-6.0 s0.0");
        assertThatThrownBy(() -> music.stinger(Stinger.of("other"))).hasMessageContaining("no stinger other");
    }

    @Test
    void musicPausesWithItsWorldOnTheBeat() {
        Sounds world = new Sounds(new SceneGraph(new ChangeClock()));
        music.pauseWith(world);
        music.play(score(), EXPLORE);
        at(0);
        commands();
        world.pause(true);
        at(30_000);
        assertThat(commands()).containsExactly("pause v0 @49000 g0.0 s0.1");
        at(200_000);
        assertThat(commands()).isEmpty();
        world.pause(false);
        at(300_000);
        assertThat(commands()).containsExactly("resume v0 @301000 g0.0 s0.1");
        // everything after the pause moved by its length: a's exit from 769,000 to 1,021,000
        at(1_021_000 - 30_000);
        assertThat(commands()).isEmpty();
        at(1_021_000 - 23_000);
        assertThat(commands()).containsExactly("start v2 clip1 @1021000 g1.0 s0.0");
        music.pauseWith(null);
    }

    @Test
    void stoppingFadesOrCancelsWhatWouldStart() {
        music.play(score(), EXPLORE);
        at(0);
        at(8L * BAR - 20_000); // the next a is scheduled at its exit, 769,000
        commands();
        music.stop(Quantize.IMMEDIATE, 2);
        assertThat(commands()).containsExactly("stop v1 @749000 g0.0 s2.0", "stop v2 @769000 g0.0 s0.0");
        assertThat(music.playing()).isFalse();
        MusicPosition p = new MusicPosition();
        music.position(p);
        assertThat(p.segment()).isEqualTo("a");
        music.stop(Quantize.IMMEDIATE, 2);
        at(9L * BAR);
        assertThat(commands()).isEmpty();
        assertThatThrownBy(() -> music.state(COMBAT)).hasMessageContaining("No music plays");
        assertThatThrownBy(() -> new Music(clock, clips).state(COMBAT)).hasMessageContaining("No music plays");
        music.play(score(), COMBAT); // again
        at(9L * BAR);
        assertThat(commands()).hasSize(1);
        music.play(score(), EXPLORE); // over what plays: it fades out at once
        assertThat(commands().getFirst()).startsWith("stop v3");
        Music idle = new Music(clock, clips);
        idle.play(score(), EXPLORE);
        idle.stop(Quantize.NEXT_BAR, 1); // nothing scheduled yet
        assertThat(idle.commands()).isZero();
    }

    @Test
    void beatsBarsMarkersAndTransitionsAreHeardWhenTheyAre() {
        music.play(score(), EXPLORE);
        at(0);
        at(2_000); // heard 1,000: the entry
        assertThat(heard).containsExactly("BAR 1.1", "BEAT 1.1");
        heard.clear();
        at(2_000 + 4L * BAR);
        assertThat(heard).hasSize(16 + 4 + 1).contains("BAR 5.1", "MARKER 5.1", "BEAT 4.4");
        assertThat(music.lastMarker()).isEqualTo("drop");
        MusicPosition p = new MusicPosition();
        music.position(p);
        assertThat(p.segment()).isEqualTo("a");
        assertThat(p.bar()).isEqualTo(5);
        assertThat(p.beat()).isEqualTo(1);
        assertThat(p.fraction()).isZero();
        heard.clear();
        music.state(COMBAT);
        at(5L * BAR);
        at(6L * BAR); // heard past the sync at 481,000
        assertThat(heard).contains("TRANSITION 1.1");
        at(6L * BAR); // nothing new heard
        Music silent = new Music(clock, clips);
        silent.position(p);
        assertThat(p.segment()).isNull();
        assertThat(p.bar()).isZero();
    }

    @Test
    void stateRequestsAreChecked() {
        assertThatThrownBy(() -> music.play(score(), MusicState.of("x"))).hasMessage("The score has no state x");
        music.play(score(), EXPLORE);
        assertThatThrownBy(() -> music.state(MusicState.of("x"))).hasMessage("The score has no state x");
        music.state(COMBAT);
        music.state(COMBAT);
        assertThat(music.state()).isEqualTo(EXPLORE);
        at(0);
        assertThat(music.state()).as("once its transition is scheduled").isEqualTo(COMBAT);
    }

    @Test
    void updatingAllocatesNothing() {
        music = new Music(clock, clips);
        music.listen((event, bar, beat) -> { });
        music.play(score(), EXPLORE);
        music.update();
        long[] frame = {0};
        assertAllocationFree(() -> {
            frame[0] += 256;
            clock.rendered = frame[0];
            music.update();
            music.clearCommands();
        });
    }

    @Test
    void scoresAreChecked() {
        InvalidAudioException e = catchThrowableOfType(InvalidAudioException.class, () -> Score.builder()
            .segment("a", s -> s.clip(A).exit(Bar.of(2)))
            .segment("a", s -> s.clip(A).exit(Bar.of(2)))
            .segment("empty", s -> s.exit(Bar.of(2)))
            .segment("open", s -> s.clip(A))
            .segment("back", s -> s.clip(A).entry(Bar.of(3)).exit(Bar.of(2)))
            .segment("stray", s -> s.clip(A).exit(Bar.of(2)).stem(B, INTENSITY, Curve.points(0, 1), Quantize.NEXT_BAR))
            .state(MusicState.ANY, Playlist.of("a"))
            .state(EXPLORE, Playlist.of())
            .state(COMBAT, Playlist.of("aa"))
            .transition(BOSS, EXPLORE, t -> t.via("hti"))
            .transition(EXPLORE, BOSS, t -> { })
            .build());
        assertThat(e.problems()).containsExactly(
            "Segment a is in the score twice",
            "Segment empty has no clip",
            "Segment open has no exit cue",
            "Segment back exits at Bar[bar=2, beat=1], not after its entry at Bar[bar=3, beat=1]",
            "Segment stray has a curve for a stem it does not play",
            "ANY is not a state to play",
            "State explore plays no segments",
            "State combat plays aa, which it lacks (did you mean a?)",
            "The transition from boss to explore starts from a state the score lacks",
            "The transition from boss to explore goes via hti, which it lacks",
            "The transition from explore to boss goes to a state the score lacks");
        assertThatThrownBy(() -> Score.builder().build()).hasMessageContaining("It has no states");
        assertThatThrownBy(() -> Score.builder().duck(-3)).hasMessage("duck() follows a stinger()");
        assertThatThrownBy(() -> Score.builder().transition(EXPLORE, COMBAT, t -> t.fadeOut(-1)))
            .hasMessageContaining("Fades are seconds");
        assertThatThrownBy(() -> Score.builder().transition(EXPLORE, COMBAT, t -> t.fadeIn(Double.NaN)))
            .hasMessageContaining("Fades are seconds");
        assertThatThrownBy(() -> Score.builder().transition(EXPLORE, COMBAT, t -> t.fadeIn(Double.POSITIVE_INFINITY)))
            .hasMessageContaining("Fades are seconds");
    }

    @Test
    void theScoreAnswersItsLookups() {
        Score score = score();
        assertThat(score.tempo()).isEqualTo(Tempo.bpm(120));
        assertThat(score.states()).containsExactlyInAnyOrder(EXPLORE, COMBAT, BOSS, CALM);
        assertThat(score.segment("x")).isNull();
        assertThat(score.playlist(MusicState.of("x"))).isNull();
        assertThat(score.transition(EXPLORE, COMBAT).fadeOut()).isEqualTo(0.5);
        assertThat(score.transition(COMBAT, BOSS).via()).isEqualTo("hit");
        assertThat(score.transition(BOSS, CALM)).isEqualTo(Score.DEFAULT_TRANSITION);
        Score withAny = Score.builder().segment("a", s -> s.clip(A).exit(Bar.of(2))).state(EXPLORE, Playlist.of("a"))
            .state(COMBAT, Playlist.of("a")).transition(EXPLORE, MusicState.ANY, t -> t.at(Quantize.EXIT_CUE)).build();
        assertThat(withAny.transition(EXPLORE, COMBAT).at()).isEqualTo(Quantize.EXIT_CUE);
        assertThat(withAny.transition(COMBAT, EXPLORE)).isEqualTo(Score.DEFAULT_TRANSITION);
        assertThat(score.stinger(Stinger.of("x"))).isNull();
        assertThat(score.stinger(Stinger.of("level-up")).duck()).isEqualTo(-6);
        assertThat(score.parameters()).containsExactly(INTENSITY);
        Score.Segment b = score.segment("b");
        assertThat(b.preEntry()).isEqualTo(2);
        assertThat(b.length()).isEqualTo(16);
        assertThat(b.marker("drop").bar()).isEqualTo(Bar.of(3));
        assertThat(b.marker("x")).isNull();
        assertThat(b.curve(B)).isNull();
        assertThat(score.segment("stems").curve(BRASS).quantize()).isEqualTo(Quantize.NEXT_BEAT);
        assertThat(Score.builder().segment("t", s -> s.clip(A).tempo(Tempo.bpm(60)).exit(Bar.of(2)))
            .state(EXPLORE, Playlist.of("t")).build().segment("t").tempo().bpm()).isEqualTo(60);
    }

    static final SoundParameter LOAD = SoundParameter.of("load", 1, 0, 1);
    static final MusicState ONCE = MusicState.of("once");
    static final MusicState SOLO = MusicState.of("solo");
    static final MusicState THRICE = MusicState.of("thrice");

    static Score edges() {
        return Score.builder()
            .segment("duo", s -> s.stems(DRUMS, BRASS).stem(DRUMS, LOAD, Curve.points(0, 0, 1, 1), Quantize.IMMEDIATE)
                .stem(BRASS, INTENSITY, Curve.points(0, 0, 1, 1), Quantize.IMMEDIATE).exit(Bar.of(3)).loop())
            .segment("again", s -> s.clip(BRASS).stem(BRASS, INTENSITY, Curve.points(0, 1), Quantize.NEXT_BAR)
                .exit(Bar.of(2)))
            .segment("c", s -> s.clip(C).exit(Bar.of(2)))
            .segment("hit", s -> s.clip(HIT).exit(Bar.of(2)))
            .state(EXPLORE, Playlist.of("duo"))
            .state(COMBAT, Playlist.of("c", "c"))
            .state(ONCE, Playlist.once("c"))
            .state(SOLO, Playlist.shuffle("c"))
            .state(THRICE, Playlist.once("c", "hit", "c"))
            .transition(MusicState.ANY, ONCE, t -> t.at(Quantize.IMMEDIATE).via("hit"))
            .stinger(Stinger.of("s"), STING, Quantize.IMMEDIATE)
            .build();
    }

    @Test
    void edgesOfTheStateMachine() {
        new Music(clock, clips).update(); // nothing to play
        Score score = edges();
        assertThat(score.parameters()).containsExactly(LOAD, INTENSITY);
        music.play(score, EXPLORE);
        at(0);
        commands();
        music.set(INTENSITY, 0.5f);
        assertThat(commands()).containsExactly("gain v2 @1000 g0.5 s0.05");
        // a long gap: the loop keeps time, skipping what would be over by now
        at(100L * BAR);
        List<String> caught = commands();
        assertThat(caught).hasSize(2);
        assertThat(caught.get(0)).isEqualTo("start v3 clip4 @9601000 g1.0 s0.0");
        // once through, then silence
        music.state(COMBAT);
        music.state(EXPLORE); // back before it was scheduled: no transition
        at(100L * BAR);
        assertThat(music.state()).isEqualTo(EXPLORE);
        commands();
        clips.notReady.add(HIT);
        music.state(ONCE);
        at(101L * BAR);
        assertThat(commands()).as("its transition segment is not ready").noneMatch(c -> c.contains("clip3"));
        assertThat(music.state()).isEqualTo(EXPLORE);
        clips.notReady.clear();
        at(101L * BAR);
        assertThat(commands()).anyMatch(c -> c.contains("clip3")).anyMatch(c -> c.contains("clip7"));
        assertThat(music.state()).isEqualTo(ONCE);
        at(110L * BAR);
        at(111L * BAR);
        assertThat(commands()).as("once through").isEmpty();
        clips.notReady.add(DRUMS);
        music.play(score, EXPLORE); // over what plays, not ready yet: what was open fades, nothing starts
        at(111L * BAR);
        assertThat(commands()).isNotEmpty().allMatch(c -> c.startsWith("stop"));
        clips.notReady.clear();
        music.stop(Quantize.IMMEDIATE, 0);
        MusicPosition p = new MusicPosition();
        at(111L * BAR);
        music.position(p);
        assertThat(p.segment()).as("heard after it stopped").isNull();
        music.play(score, SOLO);
        for (int i = 0; i < 20; i++) {
            music.stinger(Stinger.of("s")); // more commands than the first arrays hold
        }
        assertThat(commands()).hasSize(20);
        at(112L * BAR);
        at(120L * BAR);
        assertThat(commands()).allMatch(c -> c.contains("clip7"));
        Music thrice = new Music(clock, clips);
        clock.rendered = 0;
        thrice.play(score, THRICE);
        thrice.update();
        clock.rendered = 2L * BAR; // past the first c and the hit: the last c
        thrice.update();
        assertThat(thrice.commands()).isEqualTo(2);
        assertThat(thrice.clip(1)).isEqualTo(C);
        clock.rendered = 40L * BAR; // past them all at once: silence
        thrice.update();
        assertThat(thrice.commands()).isEqualTo(2);
        Music skipped = new Music(clock, clips);
        clock.rendered = 0;
        skipped.play(score, THRICE);
        skipped.update();
        clock.rendered = 40L * BAR; // past all three in one gap
        skipped.update();
        assertThat(skipped.commands()).isEqualTo(1);
    }

    @Test
    void requestsAndPausesBeforeTheStart() {
        clips.notReady.add(A);
        Sounds world = new Sounds(new SceneGraph(new ChangeClock()));
        world.pause(true);
        music.pauseWith(world);
        music.play(score(), EXPLORE);
        music.state(COMBAT);
        at(0);
        assertThat(commands()).containsExactly("pause v0 @1000 g0.0 s0.1");
        world.pause(false);
        at(10);
        assertThat(commands()).containsExactly("resume v0 @1010 g0.0 s0.1");
        assertThat(music.state()).as("waits for the start").isEqualTo(EXPLORE);
        clips.notReady.clear();
        at(20);
        assertThat(music.state()).isEqualTo(COMBAT);
        commands();
        world.pause(true);
        at(BAR);
        world.pause(false);
        at(2L * BAR); // the stopped a is shifted too
        assertThat(commands()).hasSize(2);
    }

    @Test
    void theNextMarkerOnceOneHasPassed() {
        music.play(score(), EXPLORE);
        at(0);
        commands();
        at(5L * BAR); // past "drop" at 385,000: the next is "late" at 577,000
        music.state(CALM);
        at(5L * BAR);
        assertThat(commands().get(0)).isEqualTo("stop v1 @577000 g0.0 s0.5");
    }

    @Test
    void musicalValues() {
        assertThat(Tempo.bpm(90).meter(3, 4).framesPerBeat(48_000)).isEqualTo(32_000);
        assertThatThrownBy(() -> Tempo.bpm(0)).hasMessageContaining("Not a tempo");
        assertThatThrownBy(() -> Tempo.bpm(Double.POSITIVE_INFINITY)).hasMessageContaining("Not a tempo");
        assertThatThrownBy(() -> Tempo.bpm(60).meter(0, 4)).hasMessageContaining("Not a meter");
        assertThatThrownBy(() -> Tempo.bpm(60).meter(4, 0)).hasMessageContaining("Not a meter");
        assertThat(Bar.of(3, 2).beats(Tempo.bpm(60))).isEqualTo(9);
        assertThatThrownBy(() -> Bar.of(0)).hasMessageContaining("count from 1");
        assertThatThrownBy(() -> Bar.of(1, 0)).hasMessageContaining("count from 1");
        assertThat(Beats.of(2).count()).isEqualTo(2);
        assertThatThrownBy(() -> Beats.of(-1)).hasMessageContaining("Not a number of beats");
        assertThatThrownBy(() -> Beats.of(Double.POSITIVE_INFINITY)).hasMessageContaining("Not a number of beats");
        assertThat(Quantize.bars(4).bars()).isEqualTo(4);
        assertThatThrownBy(() -> Quantize.bars(0)).hasMessageContaining("Groups of bars");
        assertThatThrownBy(() -> new Quantize(Quantize.Kind.NEXT_BAR, 2)).hasMessageContaining("Groups of bars");
        assertThat(MusicState.ANY.name()).isEqualTo("*");
        assertThat(Stinger.of("x").name()).isEqualTo("x");
        assertThat(MusicClips.READY.ready(A)).isTrue();
        MusicClips.READY.prime(A);
        assertThat(MusicClips.READY.seconds(A)).isNaN();
        assertThat(new Score.Marker("m", Bar.of(1)).name()).isEqualTo("m");
    }
}
