package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.random.Hash;
import com.truesim.scene.assets.AssetRef;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The music player's logic (AU8): a state machine over a {@link Score} on an {@link AudioClock}, deciding on the game
 * thread and scheduling exact output frames ahead. It never renders: each {@link #update} writes frame-stamped
 * commands (start a clip from an offset, fade, set a stem's gain, stop, pause, a stinger) that the engine's music
 * renderer plays sample-exactly, and reports bars, beats, markers and transitions to listeners as they are heard.
 *
 * <pre>{@code
 * Music music = new Music(clock, clips);
 * music.play(score, EXPLORE);              // primes the first segment, starts on its entry cue
 * music.state(COMBAT);                     // at the transition's sync point (the next bar, say), via its segment
 * music.set(INTENSITY, threat);            // stems follow, on their quantize
 * music.stinger(LEVEL_UP);
 * music.listen((event, bar, beat) -> pulseLights(beat));
 * // each frame:
 * music.update();                          // then the engine reads commands() and clears them
 * }</pre>
 *
 * <p>Timing: segments follow each other exactly, the next starting so its entry cue meets the exit cue of the one
 * before (its pre-entry before, the other's post-exit tail over it), scheduled half a second ahead. A transition
 * waits for its destination's clips to be ready ({@link MusicClips}), then takes the first sync point at or after the
 * clock's schedule point: a destination not primed waits for the next sync point after priming rather than playing
 * late, and is counted ({@link #late()}). A pause ({@link #pauseWith}) holds the music at the next beat and shifts
 * everything after it by its length. Decisions depend only on the requested states, parameters, the score, the seed
 * and the clock's frames: the same calls on the same frames give the same commands. {@link #update} allocates
 * nothing; used from the game thread.
 */
public final class Music {

    /** Command: start clip {@link #clip} as voice {@link #voice} at {@link #frame}, from {@link #offset} seconds in. */
    public static final int START = 0;
    /** Command: fade voice {@link #voice} out over {@link #seconds} from {@link #frame}, then end it. */
    public static final int STOP = 1;
    /** Command: move voice {@link #voice}'s gain to {@link #gain} over {@link #seconds} from {@link #frame}. */
    public static final int GAIN = 2;
    /** Command: hold all music at {@link #frame}, fading over {@link #seconds}. */
    public static final int PAUSE = 3;
    /** Command: resume all music at {@link #frame}, fading in over {@link #seconds}. */
    public static final int RESUME = 4;
    /** Command: a stinger: start {@link #clip} as voice {@link #voice} at {@link #frame}, ducking by {@link #gain} dB. */
    public static final int STINGER = 5;

    /** Seconds ahead the next segment is scheduled. */
    public static final double LOOKAHEAD = 0.5;
    private static final int INSTANCES = 16;

    private final AudioClock clock;
    private final MusicClips clips;
    private int seed;
    private Score score;
    private MusicState state;
    private MusicState requested;
    private Score.Transition rule;
    private boolean waited;
    private int late;
    private boolean startPending;
    /** The playlist index the state starting next (played, or requested) starts from: drawn when asked for. */
    private int startIndex;
    private boolean stopped = true;
    private float[] parameters = new float[0];
    private long nextVoice = 1;
    private int draws;
    private Sounds world;
    private boolean pausedNow;
    private long pauseFrame;
    private long transitionFrame = Long.MIN_VALUE;
    private long lastHeard = Long.MIN_VALUE;
    private String lastMarker;
    private MusicListener[] listeners = new MusicListener[0];

    // The timeline: the segment instances scheduled, oldest first, as a ring.
    private final Score.Segment[] segments = new Score.Segment[INSTANCES];
    private final long[] entries = new long[INSTANCES];
    private final long[] clipStarts = new long[INSTANCES];
    private final long[] startFrames = new long[INSTANCES];
    private final long[] firstVoices = new long[INSTANCES];
    private final long[] ends = new long[INSTANCES];
    private final int[] listIndex = new int[INSTANCES];
    private int head;
    private int count;

    // Commands written since the last clear.
    private int commandCount;
    private int[] kinds = new int[16];
    private long[] frames = new long[16];
    private long[] voices = new long[16];
    private Object[] clipRefs = new Object[16];
    private double[] offsets = new double[16];
    private float[] gains = new float[16];
    private float[] seconds = new float[16];

    /** Music on {@code clock}, asking {@code clips} whether clips are ready and how long they are. */
    public Music(AudioClock clock, MusicClips clips) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clips = Objects.requireNonNull(clips, "clips");
    }

    /** The seed shuffles and random starts draw from; 0 by default. */
    public Music seed(int seed) {
        this.seed = seed;
        return this;
    }

    /** Plays {@code score} from {@code state}: primes its first segment and starts it as soon as it is ready. */
    @AllowAllocation("a new score")
    public void play(Score score, MusicState state) {
        Objects.requireNonNull(score, "score");
        if (score.playlist(state) == null) {
            throw new IllegalArgumentException("The score has no state " + state.name());
        }
        if (!stopped) {
            stop(Quantize.IMMEDIATE, 0.5);
        }
        this.score = score;
        this.state = state;
        requested = null;
        parameters = new float[score.parameters().size()];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = score.parameters().get(i).fallback();
        }
        stopped = false;
        startPending = true;
        startIndex = firstIndex(score.playlist(state));
        prime(firstSegment(state));
    }

    /** Asks for {@code next}: the transition happens at its sync point once the destination is ready. */
    public void state(MusicState next) {
        if (score == null || stopped) {
            throw new IllegalStateException("No music plays: play() a score first");
        }
        if (score.playlist(next) == null) {
            throw new IllegalArgumentException("The score has no state " + next.name());
        }
        if (next.equals(state)) {
            requested = null; // back to the state playing before the transition was scheduled: none is
            return;
        }
        if (next.equals(requested)) {
            return;
        }
        requested = next;
        rule = score.transition(state, next);
        waited = false;
        startIndex = firstIndex(score.playlist(next));
        prime(firstSegment(next));
        if (rule.via() != null) {
            prime(score.segment(rule.via()));
        }
    }

    /** The state the music is in: a requested one once its transition is scheduled (heard at its sync point). */
    public MusicState state() {
        return state;
    }

    /** Sets {@code parameter}: stems following it change on their quantize. */
    public void set(SoundParameter parameter, float value) {
        int index = score == null ? -1 : score.parameters().indexOf(parameter);
        if (index < 0) {
            throw new IllegalArgumentException("No stem follows " + parameter);
        }
        parameters[index] = parameter.clamp(value);
        long sp = clock.schedulePoint();
        for (int n = 0; n < count; n++) {
            int i = (head + n) % INSTANCES;
            Score.Segment s = segments[i];
            if (ends[i] != Long.MAX_VALUE) {
                continue;
            }
            for (int c = 0; c < s.clips().size(); c++) {
                Score.StemCurve curve = s.curve(s.clips().get(c));
                if (curve != null && curve.parameter().equals(parameter)) {
                    long at = Math.max(sync(i, curve.quantize(), sp), startFrames[i]);
                    command(GAIN, at, firstVoices[i] + c, null, 0, curve.curve().at(parameters[index]), 0.05f);
                }
            }
        }
    }

    /** Plays {@code stinger} on its quantize over the music. */
    public void stinger(Stinger stinger) {
        Score.StingerPlay play = score == null ? null : score.stinger(stinger);
        if (play == null) {
            throw new IllegalArgumentException("The score has no stinger " + stinger.name());
        }
        long sp = clock.schedulePoint();
        int active = active(sp);
        long at = active < 0 ? sp : sync(active, play.quantize(), sp);
        command(STINGER, at, nextVoice++, play.clip(), 0, play.duck(), 0);
    }

    /** Stops the music on {@code quantize}, fading out over {@code fadeSeconds}. */
    public void stop(Quantize quantize, double fadeSeconds) {
        if (stopped) {
            return;
        }
        long sp = clock.schedulePoint();
        int active = active(sp);
        long at = active < 0 ? sp : sync(active, quantize, sp);
        stopFrom(at, (float) fadeSeconds);
        stopped = true;
        startPending = false;
        requested = null;
    }

    /** Whether music plays (or waits to start). */
    public boolean playing() {
        return !stopped;
    }

    /**
     * Pauses the music with {@code world} (opt-in): while that world is paused the music holds, from the next beat,
     * and resumes on its own clock. {@code null} for none (music follows no world by default).
     */
    public void pauseWith(Sounds world) {
        this.world = world;
    }

    /** Adds {@code listener} to hear bars, beats, markers and transitions. */
    @AllowAllocation("set up once")
    public void listen(MusicListener listener) {
        listeners = Arrays.copyOf(listeners, listeners.length + 1);
        listeners[listeners.length - 1] = Objects.requireNonNull(listener, "listener");
    }

    /** How many transitions waited for their destination to be primed. */
    public int late() {
        return late;
    }

    /** The name of the last marker heard; {@code null} before any. */
    public String lastMarker() {
        return lastMarker;
    }

    /** Fills {@code out} with where the music is as heard now. */
    public void position(MusicPosition out) {
        long heard = clock.heardFrame();
        int i = heardAt(heard);
        if (i < 0) {
            out.segment = null;
            out.bar = 0;
            out.beat = 0;
            out.fraction = 0;
            return;
        }
        Score.Segment s = segments[i];
        double beats = (heard - entries[i]) / s.tempo().framesPerBeat(clock.rate());
        double whole = Math.floor(beats);
        int bpb = s.tempo().beatsPerBar();
        out.segment = s.name();
        out.bar = (int) Math.floor(whole / bpb) + 1;
        out.beat = (int) (whole - (out.bar - 1) * (double) bpb) + 1;
        out.fraction = beats - whole;
    }

    // ---------------------------------------------------------------- each frame

    /** Decides what is due: pauses, starts, transitions, the next segments, events heard. Allocates nothing. */
    public void update() {
        long sp = clock.schedulePoint();
        long heard = clock.heardFrame();
        if (score != null && !stopped && !pause(sp)) {
            if (startPending && ready(firstSegment(state))) {
                startPending = false;
                Score.Segment first = firstSegment(state);
                long entry = sp + Math.round(first.preEntry() * first.tempo().framesPerBeat(clock.rate()));
                schedule(first, entry, 0, 0, startIndex); // its pre-entry from the schedule point
            }
            if (requested != null && !startPending) {
                transition(sp);
            }
            continuePlaylist(sp);
        }
        events(heard);
        lastHeard = heard;
    }

    /** Follows the world's pause; true while paused. */
    private boolean pause(long sp) {
        boolean paused = world != null && world.paused();
        if (paused != pausedNow) {
            pausedNow = paused;
            if (paused) {
                int active = active(sp);
                pauseFrame = active < 0 ? sp : sync(active, Quantize.NEXT_BEAT, sp);
                command(PAUSE, pauseFrame, 0, null, 0, 0, 0.1f);
            } else {
                command(RESUME, sp, 0, null, 0, 0, 0.1f);
                long shift = Math.max(0, sp - pauseFrame);
                for (int n = 0; n < count; n++) {
                    int i = (head + n) % INSTANCES;
                    entries[i] += shift;
                    clipStarts[i] += shift;
                    startFrames[i] += shift;
                    if (ends[i] != Long.MAX_VALUE) {
                        ends[i] += shift;
                    }
                }
            }
        }
        return paused;
    }

    private void transition(long sp) {
        Score.Segment destination = firstSegment(requested);
        Score.Segment via = rule.via() == null ? null : score.segment(rule.via());
        if (!ready(destination) || via != null && !ready(via)) {
            if (!waited) {
                waited = true;
                late++;
            }
            return;
        }
        int active = active(sp);
        long at = sync(active, rule.at(), sp);
        double fpb = segments[active].tempo().framesPerBeat(clock.rate());
        float fade = (float) (rule.crossfade() > 0 ? rule.crossfade() * fpb / clock.rate() : rule.fadeOut());
        float fadeIn = (float) (rule.crossfade() > 0 ? fade : rule.fadeIn());
        double position = switch (rule.startAt()) {
            case ENTRY -> 0;
            case SAME_POSITION -> (at - entries[active]) / fpb;
            case MATCHING_MARKER -> matchingMarker(active, at, destination);
            case RANDOM -> {
                int bars = (int) Math.max(1, Math.floor(destination.length() / destination.tempo().beatsPerBar()));
                yield (Integer.remainderUnsigned(Hash.combine(seed, draws++), bars))
                    * (double) destination.tempo().beatsPerBar();
            }
        };
        stopFrom(at, fade);
        long entry = at;
        if (via != null) {
            int v = schedule(via, at, 0, 0, -1);
            entry = exit(v);
            position = 0;
        }
        state = requested;
        requested = null;
        transitionFrame = at;
        schedule(destination, entry, position, fadeIn, startIndex);
    }

    /** Beats into {@code destination} that line its marker up with the source's last marker before {@code at}. */
    private double matchingMarker(int active, long at, Score.Segment destination) {
        Score.Segment source = segments[active];
        double fpb = source.tempo().framesPerBeat(clock.rate());
        String name = null;
        double passed = 0;
        for (int m = 0; m < source.markers().size(); m++) {
            Score.Marker marker = source.markers().get(m);
            double frame = entries[active] + (marker.bar().beats(source.tempo()) - source.preEntry()) * fpb;
            if (frame <= at) {
                name = marker.name();
                passed = (at - frame) / fpb;
            }
        }
        Score.Marker there = name == null ? null : destination.marker(name);
        return there == null ? 0 : there.bar().beats(destination.tempo()) - destination.preEntry() + passed;
    }

    /**
     * Schedules the playlist's next segments while the newest one's exit is within the lookahead (more than one only
     * for segments shorter than it). Ends, since every segment exits after its entry.
     */
    private void continuePlaylist(long sp) {
        while (count > 0) {
            int newest = (head + count - 1) % INSTANCES;
            if (ends[newest] != Long.MAX_VALUE) {
                return;
            }
            long exit = exit(newest);
            if (exit - sp > LOOKAHEAD * clock.rate()) {
                return;
            }
            Playlist playlist = score.playlist(state);
            int index = nextIndex(playlist, listIndex[newest], segments[newest]);
            Score.Segment next = index < 0 ? null : score.segment(playlist.segments().get(index));
            // after a gap longer than whole segments, those that would be over by now are skipped, keeping time
            while (next != null && exit + length(next) <= sp) {
                exit += length(next);
                index = nextIndex(playlist, index, next);
                next = index < 0 ? null : score.segment(playlist.segments().get(index));
            }
            if (next == null) {
                ends[newest] = exit(newest); // once through: heard until its exit, then its tail and silence
                return;
            }
            schedule(next, exit, 0, 0, index);
        }
    }

    private int firstIndex(Playlist playlist) {
        return playlist.order() == Playlist.Order.SHUFFLE
            ? Integer.remainderUnsigned(Hash.combine(seed, draws++), playlist.segments().size()) : 0;
    }

    private int nextIndex(Playlist playlist, int index, Score.Segment playing) {
        int size = playlist.segments().size();
        if (playing.loop()) {
            return index;
        }
        return switch (playlist.order()) {
            case SEQUENCE -> (index + 1) % size;
            case ONCE -> index + 1 < size ? index + 1 : -1;
            case SHUFFLE -> size == 1 ? 0
                : (index + 1 + Integer.remainderUnsigned(Hash.combine(seed, draws++), size - 1)) % size;
        };
    }

    /** The segment {@code s} starts with: its playlist's at {@link #startIndex}. */
    private Score.Segment firstSegment(MusicState s) {
        return score.segment(score.playlist(s).segments().get(startIndex));
    }

    private void prime(Score.Segment segment) {
        List<AssetRef<AudioClip>> list = segment.clips();
        for (int i = 0; i < list.size(); i++) {
            if (!clips.ready(list.get(i))) {
                clips.prime(list.get(i));
            }
        }
    }

    private boolean ready(Score.Segment segment) {
        List<AssetRef<AudioClip>> list = segment.clips();
        for (int i = 0; i < list.size(); i++) {
            if (!clips.ready(list.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Schedules {@code segment} so that {@code position} beats after its entry plays at frame {@code at}; its pre-entry
     * before (from the schedule point at the earliest) when it starts at its entry. Returns its timeline index.
     */
    private int schedule(Score.Segment segment, long at, double position, float fadeIn, int index) {
        int rate = clock.rate();
        double fpb = segment.tempo().framesPerBeat(rate);
        long entry = at - Math.round(position * fpb);
        long clipStart = entry - Math.round(segment.preEntry() * fpb);
        long start = position > 0 ? at : Math.max(clipStart, clock.schedulePoint());
        double offset = (double) (start - clipStart) / rate;
        if (count == INSTANCES) {
            head = (head + 1) % INSTANCES;
            count--;
        }
        int i = (head + count++) % INSTANCES;
        segments[i] = segment;
        entries[i] = entry;
        clipStarts[i] = clipStart;
        startFrames[i] = start;
        firstVoices[i] = nextVoice;
        ends[i] = Long.MAX_VALUE;
        listIndex[i] = index;
        for (int c = 0; c < segment.clips().size(); c++) {
            Score.StemCurve curve = segment.curve(segment.clips().get(c));
            float gain = curve == null ? 1 : curve.curve().at(parameters[score.parameters().indexOf(curve.parameter())]);
            long voice = nextVoice++;
            command(START, start, voice, segment.clips().get(c), offset, fadeIn > 0 ? 0 : gain, 0);
            if (fadeIn > 0) {
                command(GAIN, Math.max(start, at), voice, null, 0, gain, fadeIn);
            }
        }
        return i;
    }

    /** Stops every instance sounding at {@code at}: those starting later are cancelled, the others fade out. */
    private void stopFrom(long at, float fade) {
        for (int n = 0; n < count; n++) {
            int i = (head + n) % INSTANCES;
            if (ends[i] != Long.MAX_VALUE) {
                continue;
            }
            boolean future = startFrames[i] > at;
            ends[i] = future ? startFrames[i] - 1 : at; // a cancelled one ends before it starts: never heard
            for (int c = 0; c < segments[i].clips().size(); c++) {
                command(STOP, future ? startFrames[i] : at, firstVoices[i] + c, null, 0, 0, future ? 0 : fade);
            }
        }
    }

    private long length(Score.Segment s) {
        return Math.round(s.length() * s.tempo().framesPerBeat(clock.rate()));
    }

    private long exit(int i) {
        Score.Segment s = segments[i];
        return entries[i] + Math.round(s.length() * s.tempo().framesPerBeat(clock.rate()));
    }

    /** The instance playing at {@code frame}: the newest whose entry is at or before it, else the first to come. */
    private int active(long frame) {
        int found = -1;
        for (int n = 0; n < count; n++) {
            int i = (head + n) % INSTANCES;
            if (startFrames[i] > ends[i]) {
                continue; // never started
            }
            if (found < 0 || entries[i] <= frame) {
                found = i;
            }
        }
        return found;
    }

    /** The instance heard at {@code frame}: from its entry until the next one's entry or its end. */
    private int heardAt(long frame) {
        int found = -1;
        for (int n = 0; n < count; n++) {
            int i = (head + n) % INSTANCES;
            if (startFrames[i] <= ends[i] && entries[i] <= frame) {
                found = frame < ends[i] ? i : -1; // a later one that ended silences the earlier ones too
            }
        }
        return found;
    }

    /** The first frame at or after {@code t} that {@code quantize} allows in instance {@code i}; its exit at the latest. */
    private long sync(int i, Quantize quantize, long t) {
        Score.Segment s = segments[i];
        double fpb = s.tempo().framesPerBeat(clock.rate());
        long exit = exit(i);
        long at = switch (quantize.kind()) {
            case IMMEDIATE -> t;
            case NEXT_BEAT -> grid(entries[i], fpb, t);
            case NEXT_BAR -> grid(entries[i], fpb * s.tempo().beatsPerBar(), t);
            case BARS -> grid(entries[i], fpb * s.tempo().beatsPerBar() * quantize.bars(), t);
            case NEXT_MARKER -> {
                long marker = exit;
                for (int m = 0; m < s.markers().size(); m++) {
                    long frame = entries[i] + Math.round((s.markers().get(m).bar().beats(s.tempo()) - s.preEntry())
                        * fpb);
                    if (frame >= t && frame < marker) {
                        marker = frame;
                    }
                }
                yield marker;
            }
            case EXIT_CUE -> exit;
            case END -> {
                double length = clips.seconds(s.clips().get(0));
                yield length == length ? clipStarts[i] + Math.round(length * clock.rate()) : exit;
            }
        };
        if (quantize.kind() != Quantize.Kind.END) {
            at = Math.min(at, exit);
        }
        return Math.max(at, t);
    }

    private static long grid(long origin, double step, long t) {
        if (t <= origin) {
            return origin;
        }
        return origin + Math.round(Math.ceil((t - origin) / step - 1e-9) * step);
    }

    /** Reports what is heard after the last update up to {@code heard}. */
    private void events(long heard) {
        if (lastHeard == Long.MIN_VALUE || heard <= lastHeard) {
            return;
        }
        for (int n = 0; n < count; n++) {
            int i = (head + n) % INSTANCES;
            if (startFrames[i] > ends[i]) {
                continue;
            }
            long until = ends[i];
            for (int m = n + 1; m < count; m++) {
                int j = (head + m) % INSTANCES;
                if (startFrames[j] <= ends[j]) {
                    until = Math.min(until, entries[j]);
                    break;
                }
            }
            events(i, Math.max(lastHeard, entries[i] - 1), Math.min(heard, until - 1));
        }
    }

    /** Reports instance {@code i}'s beats, bars, markers and transition in ({@code from}, {@code to}]. */
    private void events(int i, long from, long to) {
        if (to <= from) {
            return;
        }
        Score.Segment s = segments[i];
        double fpb = s.tempo().framesPerBeat(clock.rate());
        int bpb = s.tempo().beatsPerBar();
        if (transitionFrame > from && transitionFrame <= to) {
            tell(MusicEvent.TRANSITION, 1, 1);
        }
        for (long k = Math.max(0, (long) Math.floor((from - entries[i]) / fpb)); ; k++) {
            long frame = entries[i] + Math.round(k * fpb);
            if (frame > to) {
                break;
            }
            if (frame > from) {
                int bar = (int) (k / bpb) + 1;
                int beat = (int) (k % bpb) + 1;
                if (beat == 1) {
                    tell(MusicEvent.BAR, bar, beat);
                }
                tell(MusicEvent.BEAT, bar, beat);
            }
        }
        for (int m = 0; m < s.markers().size(); m++) {
            Score.Marker marker = s.markers().get(m);
            double beats = marker.bar().beats(s.tempo()) - s.preEntry();
            long frame = entries[i] + Math.round(beats * fpb);
            if (frame > from && frame <= to) {
                lastMarker = marker.name();
                tell(MusicEvent.MARKER, (int) Math.floor(beats / bpb) + 1, (int) (beats % bpb) + 1);
            }
        }
    }

    private void tell(MusicEvent event, int bar, int beat) {
        for (MusicListener listener : listeners) {
            listener.heard(event, bar, beat);
        }
    }

    // ---------------------------------------------------------------- commands

    private void command(int kind, long frame, long voice, Object clip, double offset, float gain, float fade) {
        if (commandCount == kinds.length) {
            growCommands();
        }
        int c = commandCount++;
        kinds[c] = kind;
        frames[c] = frame;
        voices[c] = voice;
        clipRefs[c] = clip;
        offsets[c] = offset;
        gains[c] = gain;
        seconds[c] = fade;
    }

    @AllowAllocation("more commands in a frame than before")
    private void growCommands() {
        int n = 2 * kinds.length;
        kinds = Arrays.copyOf(kinds, n);
        frames = Arrays.copyOf(frames, n);
        voices = Arrays.copyOf(voices, n);
        clipRefs = Arrays.copyOf(clipRefs, n);
        offsets = Arrays.copyOf(offsets, n);
        gains = Arrays.copyOf(gains, n);
        seconds = Arrays.copyOf(seconds, n);
    }

    /** How many commands were written since the last {@link #clearCommands()}. */
    public int commands() {
        return commandCount;
    }

    /** Command {@code c}'s kind: {@link #START}, {@link #STOP}, {@link #GAIN}, {@link #PAUSE}, ... */
    public int kind(int c) {
        return kinds[Objects.checkIndex(c, commandCount)];
    }

    /** The output frame command {@code c} takes effect at. */
    public long frame(int c) {
        return frames[Objects.checkIndex(c, commandCount)];
    }

    /** The music voice command {@code c} acts on (0 for pause and resume). */
    public long voice(int c) {
        return voices[Objects.checkIndex(c, commandCount)];
    }

    /** The clip a start or stinger plays; {@code null} for the others. */
    @SuppressWarnings("unchecked")
    public AssetRef<AudioClip> clip(int c) {
        return (AssetRef<AudioClip>) clipRefs[Objects.checkIndex(c, commandCount)];
    }

    /** Seconds into its clip a start begins. */
    public double offset(int c) {
        return offsets[Objects.checkIndex(c, commandCount)];
    }

    /** A start's or gain's gain; a stinger's duck in decibels. */
    public float gain(int c) {
        return gains[Objects.checkIndex(c, commandCount)];
    }

    /** A fade's or ramp's length in seconds. */
    public float seconds(int c) {
        return seconds[Objects.checkIndex(c, commandCount)];
    }

    /** Forgets the commands read. */
    public void clearCommands() {
        commandCount = 0;
        Arrays.fill(clipRefs, null);
    }
}
