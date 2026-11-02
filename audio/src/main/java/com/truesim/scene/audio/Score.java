package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adaptive music as data: a tempo; segments (a clip or stems in sync, entry and exit cues, markers, looping), the
 * playlist of each state, transitions between states (when, fades, an optional transition segment, where the
 * destination starts) and stingers. Built once and checked as a whole ({@link InvalidAudioException}); immutable.
 * {@link Music} plays it.
 *
 * <pre>{@code
 * Score score = Score.builder()
 *     .tempo(Tempo.bpm(112).meter(4, 4))
 *     .segment("explore-a", s -> s.clip(EXPLORE_A).entry(Bar.of(1)).exit(Bar.of(33)))
 *     .segment("combat", s -> s.stems(DRUMS, BASS, BRASS)
 *         .stem(BRASS, INTENSITY, Curve.points(0.5, 0, 1, 1), Quantize.NEXT_BEAT).exit(Bar.of(17)).loop())
 *     .segment("combat-intro", s -> s.clip(HIT).exit(Bar.of(2)))
 *     .state(EXPLORE, Playlist.shuffle("explore-a", "explore-b"))
 *     .state(COMBAT, Playlist.of("combat"))
 *     .transition(EXPLORE, COMBAT, t -> t.at(Quantize.NEXT_BAR).fadeOut(0.5).via("combat-intro"))
 *     .transition(COMBAT, EXPLORE, t -> t.at(Quantize.bars(4)).crossfade(Beats.of(4)))
 *     .stinger(LEVEL_UP, LEVEL_UP_CLIP, Quantize.NEXT_BEAT).duck(-6)
 *     .build();
 * }</pre>
 */
@AllowAllocation("set up once")
public final class Score {

    /**
     * A stem's volume following a parameter.
     *
     * @param clip      the stem
     * @param parameter the parameter read
     * @param curve     from its value to the stem's gain
     * @param quantize  when a change is applied
     */
    public record StemCurve(AssetRef<AudioClip> clip, SoundParameter parameter, Curve curve, Quantize quantize) {
        public StemCurve {
            Objects.requireNonNull(clip, "clip");
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(curve, "curve");
            Objects.requireNonNull(quantize, "quantize");
        }
    }

    /**
     * A named place in a segment.
     *
     * @param name its name
     * @param bar  where it is
     */
    public record Marker(String name, Bar bar) {
        public Marker {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(bar, "bar");
        }
    }

    /**
     * A segment.
     *
     * @param name    its name
     * @param clips   its clip, or its stems played in sync
     * @param curves  stems' volumes by parameter
     * @param tempo   its tempo
     * @param entry   its entry cue
     * @param exit    its exit cue
     * @param loop    whether it repeats while its state's playlist is on it
     * @param markers named places, in order
     */
    public record Segment(String name, List<AssetRef<AudioClip>> clips, List<StemCurve> curves, Tempo tempo, Bar entry,
            Bar exit, boolean loop, List<Marker> markers) {

        public Segment {
            clips = List.copyOf(clips);
            curves = List.copyOf(curves);
            markers = List.copyOf(markers);
        }

        /** Its marker called {@code name}, or {@code null}. */
        public Marker marker(String name) {
            for (int i = 0; i < markers.size(); i++) {
                if (markers.get(i).name().equals(name)) {
                    return markers.get(i);
                }
            }
            return null;
        }

        /** Beats from its clip's start to its entry: its pre-entry. */
        public double preEntry() {
            return entry.beats(tempo);
        }

        /** Beats from its entry to its exit. */
        public double length() {
            return exit.beats(tempo) - entry.beats(tempo);
        }

        /** Stem {@code clip}'s curve, or {@code null}. */
        public StemCurve curve(AssetRef<AudioClip> clip) {
            for (int i = 0; i < curves.size(); i++) {
                if (curves.get(i).clip().equals(clip)) {
                    return curves.get(i);
                }
            }
            return null;
        }
    }

    /**
     * How the music goes from one state to another.
     *
     * @param at       when: the sync point
     * @param fadeOut  seconds the source fades out over from the sync point
     * @param fadeIn   seconds the destination fades in over
     * @param crossfade beats the source fades out and the destination in over, overlapping, instead of the fades;
     *                  0 for none
     * @param via      a transition segment played between them; {@code null} for none
     * @param startAt  where the destination starts
     */
    public record Transition(Quantize at, double fadeOut, double fadeIn, double crossfade, String via, Start startAt) {
        public Transition {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(startAt, "startAt");
        }
    }

    /**
     * A stinger's clip, when it plays and how far it ducks the music.
     *
     * @param clip     its clip
     * @param quantize when it plays
     * @param duck     decibels the music is lowered while it plays; 0 for none
     */
    public record StingerPlay(AssetRef<AudioClip> clip, Quantize quantize, float duck) {
        public StingerPlay {
            Objects.requireNonNull(clip, "clip");
            Objects.requireNonNull(quantize, "quantize");
        }
    }

    /** The transition used where the score gives none: on the next bar, a one-beat cross-fade, at the entry. */
    public static final Transition DEFAULT_TRANSITION = new Transition(Quantize.NEXT_BAR, 0, 0, 1, null,
        Start.ENTRY);

    private final Tempo tempo;
    private final Map<String, Segment> segments;
    private final Map<MusicState, Playlist> states;
    private final Map<MusicState, Map<MusicState, Transition>> transitions;
    private final Map<Stinger, StingerPlay> stingers;
    private final List<SoundParameter> parameters;

    private Score(Builder b, List<SoundParameter> parameters) {
        tempo = b.tempo;
        segments = Map.copyOf(b.segments);
        states = Map.copyOf(b.states);
        Map<MusicState, Map<MusicState, Transition>> t = new LinkedHashMap<>();
        b.transitions.forEach((from, to) -> t.put(from, Map.copyOf(to)));
        transitions = Map.copyOf(t);
        stingers = Map.copyOf(b.stingers);
        this.parameters = List.copyOf(parameters);
    }

    /** A score to describe. */
    public static Builder builder() {
        return new Builder();
    }

    public Tempo tempo() {
        return tempo;
    }

    /** Segment {@code name}; {@code null} if it has none such. */
    public Segment segment(String name) {
        return segments.get(name);
    }

    /** The playlist of {@code state}; {@code null} if it has none such. */
    public Playlist playlist(MusicState state) {
        return states.get(state);
    }

    /** The states it has. */
    public java.util.Set<MusicState> states() {
        return states.keySet();
    }

    /**
     * The transition from {@code from} to {@code to}: the score's for the pair, else from any state to {@code to},
     * else from {@code from} to any, else {@link #DEFAULT_TRANSITION}.
     */
    public Transition transition(MusicState from, MusicState to) {
        Transition t = find(from, to);
        if (t == null) {
            t = find(MusicState.ANY, to);
        }
        if (t == null) {
            t = find(from, MusicState.ANY);
        }
        return t != null ? t : DEFAULT_TRANSITION;
    }

    private Transition find(MusicState from, MusicState to) {
        Map<MusicState, Transition> map = transitions.get(from);
        return map == null ? null : map.get(to);
    }

    /** What {@code stinger} plays; {@code null} if it has none such. */
    public StingerPlay stinger(Stinger stinger) {
        return stingers.get(stinger);
    }

    /** The parameters its stems follow. */
    public List<SoundParameter> parameters() {
        return parameters;
    }

    /** Describes a score. */
    public static final class Builder {

        private Tempo tempo = Tempo.bpm(120);
        private final Map<String, Segment> segments = new LinkedHashMap<>();
        private final Map<MusicState, Playlist> states = new LinkedHashMap<>();
        private final Map<MusicState, Map<MusicState, Transition>> transitions = new LinkedHashMap<>();
        private final Map<Stinger, StingerPlay> stingers = new LinkedHashMap<>();
        private final List<String> problems = new ArrayList<>();
        private Stinger lastStinger;

        private Builder() {
        }

        /** The tempo of segments that give none. */
        public Builder tempo(Tempo tempo) {
            this.tempo = Objects.requireNonNull(tempo, "tempo");
            return this;
        }

        /** Adds segment {@code name}, as {@code setup} describes it. */
        public Builder segment(String name, Consumer<SegmentBuilder> setup) {
            SegmentBuilder s = new SegmentBuilder(name);
            setup.accept(s);
            if (segments.putIfAbsent(name, s.build(tempo, problems)) != null) {
                problems.add("Segment " + name + " is in the score twice");
            }
            return this;
        }

        /** What {@code state} plays. */
        public Builder state(MusicState state, Playlist playlist) {
            if (state.equals(MusicState.ANY)) {
                problems.add("ANY is not a state to play");
            }
            states.put(state, playlist);
            return this;
        }

        /** How the music goes from {@code from} (or {@link MusicState#ANY}) to {@code to}. */
        public Builder transition(MusicState from, MusicState to, Consumer<TransitionBuilder> setup) {
            TransitionBuilder t = new TransitionBuilder();
            setup.accept(t);
            transitions.computeIfAbsent(from, f -> new LinkedHashMap<>()).put(to, t.build());
            return this;
        }

        /** What {@code stinger} plays, on {@code quantize}. */
        public Builder stinger(Stinger stinger, AssetRef<AudioClip> clip, Quantize quantize) {
            stingers.put(stinger, new StingerPlay(clip, quantize, 0));
            lastStinger = stinger;
            return this;
        }

        /** Ducks the music by {@code db} while the stinger last given plays. */
        public Builder duck(double db) {
            if (lastStinger == null) {
                throw new IllegalStateException("duck() follows a stinger()");
            }
            StingerPlay s = stingers.get(lastStinger);
            stingers.put(lastStinger, new StingerPlay(s.clip(), s.quantize(), (float) db));
            return this;
        }

        /** The score; throws with every problem found. */
        public Score build() {
            List<String> found = new ArrayList<>(problems);
            List<SoundParameter> parameters = new ArrayList<>();
            for (Segment s : segments.values()) {
                for (StemCurve c : s.curves()) {
                    if (!parameters.contains(c.parameter())) {
                        parameters.add(c.parameter());
                    }
                }
            }
            if (states.isEmpty()) {
                found.add("It has no states: give it one with state()");
            }
            states.forEach((state, playlist) -> {
                if (playlist.segments().isEmpty()) {
                    found.add("State " + state.name() + " plays no segments");
                }
                for (String name : playlist.segments()) {
                    known(name, "State " + state.name() + " plays", found);
                }
            });
            transitions.forEach((from, map) -> map.forEach((to, t) -> {
                String where = "The transition from " + from.name() + " to " + to.name();
                if (!from.equals(MusicState.ANY) && !states.containsKey(from)) {
                    found.add(where + " starts from a state the score lacks");
                }
                if (!to.equals(MusicState.ANY) && !states.containsKey(to)) {
                    found.add(where + " goes to a state the score lacks");
                }
                if (t.via() != null) {
                    known(t.via(), where + " goes via", found);
                }
            }));
            InvalidAudioException.throwIf("score", found);
            return new Score(this, parameters);
        }

        private void known(String name, String what, List<String> found) {
            if (!segments.containsKey(name)) {
                found.add(what + " " + name + ", which it lacks" + Suggestions.didYouMean(name, segments.keySet()));
            }
        }
    }

    /** Describes a segment. */
    public static final class SegmentBuilder {

        private final String name;
        private final List<AssetRef<AudioClip>> clips = new ArrayList<>();
        private final List<StemCurve> curves = new ArrayList<>();
        private Tempo tempo;
        private Bar entry = Bar.of(1);
        private Bar exit;
        private boolean loop;
        private final List<Marker> markers = new ArrayList<>();

        private SegmentBuilder(String name) {
            this.name = name;
        }

        /** Plays {@code clip}. */
        public SegmentBuilder clip(AssetRef<AudioClip> clip) {
            clips.add(Objects.requireNonNull(clip, "clip"));
            return this;
        }

        /** Plays {@code stems} in sync. */
        @SafeVarargs
        public final SegmentBuilder stems(AssetRef<AudioClip>... stems) {
            for (AssetRef<AudioClip> stem : stems) {
                clip(stem);
            }
            return this;
        }

        /** Stem {@code clip}'s volume follows {@code curve} at {@code parameter}'s value, changes on {@code quantize}. */
        public SegmentBuilder stem(AssetRef<AudioClip> clip, SoundParameter parameter, Curve curve, Quantize quantize) {
            curves.add(new StemCurve(clip, parameter, curve, quantize));
            return this;
        }

        /** Its own tempo. */
        public SegmentBuilder tempo(Tempo tempo) {
            this.tempo = Objects.requireNonNull(tempo, "tempo");
            return this;
        }

        /** Its entry cue: what comes before is pre-entry, heard before the sync point. */
        public SegmentBuilder entry(Bar bar) {
            entry = Objects.requireNonNull(bar, "bar");
            return this;
        }

        /** Its exit cue: what comes after is post-exit, heard over what follows. */
        public SegmentBuilder exit(Bar bar) {
            exit = Objects.requireNonNull(bar, "bar");
            return this;
        }

        /** Repeats while its state's playlist is on it. */
        public SegmentBuilder loop() {
            loop = true;
            return this;
        }

        /** Names the place {@code bar}. */
        public SegmentBuilder marker(String marker, Bar bar) {
            markers.add(new Marker(marker, bar));
            return this;
        }

        private Segment build(Tempo scoreTempo, List<String> problems) {
            Tempo t = tempo != null ? tempo : scoreTempo;
            String where = "Segment " + name;
            if (clips.isEmpty()) {
                problems.add(where + " has no clip");
            }
            if (exit == null) {
                problems.add(where + " has no exit cue");
                exit = Bar.of(entry.bar() + 1, entry.beat());
            } else if (!(exit.beats(t) > entry.beats(t))) {
                problems.add(where + " exits at " + exit + ", not after its entry at " + entry);
            }
            for (StemCurve c : curves) {
                if (!clips.contains(c.clip())) {
                    problems.add(where + " has a curve for a stem it does not play");
                }
            }
            return new Segment(name, clips, curves, t, entry, exit, loop, markers);
        }
    }

    /** Describes a transition. */
    public static final class TransitionBuilder {

        private Quantize at = Quantize.NEXT_BAR;
        private double fadeOut = 0.5;
        private double fadeIn;
        private Beats crossfade;
        private String via;
        private Start startAt = Start.ENTRY;

        private TransitionBuilder() {
        }

        /** Its sync point. */
        public TransitionBuilder at(Quantize quantize) {
            at = Objects.requireNonNull(quantize, "quantize");
            return this;
        }

        /** The source fades out over {@code seconds} from the sync point. */
        public TransitionBuilder fadeOut(double seconds) {
            fadeOut = seconds;
            return this;
        }

        /** The destination fades in over {@code seconds}. */
        public TransitionBuilder fadeIn(double seconds) {
            fadeIn = seconds;
            return this;
        }

        /** The source fades out and the destination in over {@code beats}, overlapping. */
        public TransitionBuilder crossfade(Beats beats) {
            crossfade = Objects.requireNonNull(beats, "beats");
            return this;
        }

        /** A transition segment played between them. */
        public TransitionBuilder via(String segment) {
            via = Objects.requireNonNull(segment, "segment");
            return this;
        }

        /** Where the destination starts. */
        public TransitionBuilder startAt(Start start) {
            startAt = Objects.requireNonNull(start, "start");
            return this;
        }

        private Transition build() {
            if (!(fadeOut >= 0 && fadeIn >= 0) || Double.isInfinite(fadeOut + fadeIn)) {
                throw new IllegalArgumentException("Fades are seconds, not " + fadeOut + " and " + fadeIn);
            }
            return new Transition(at, fadeOut, fadeIn, crossfade == null ? 0 : crossfade.count(), via, startAt);
        }
    }
}
