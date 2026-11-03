package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A sound as data: a tree of {@link SoundNode}s over clips, and how it plays: its bus, attenuation, concurrency,
 * priority, what happens when it goes virtual, how it is placed, occluded and time-scaled, Doppler, caption, how long
 * it may wait for its clips, and effects. Built once and checked as a whole ({@link InvalidAudioException} lists every
 * problem); immutable, compared by value; {@link #ref()} makes it an asset.
 *
 * <pre>{@code
 * static final Sound FOOTSTEPS = Sound.builder()
 *     .bus(STEPS).attenuation(Attenuation.near(1, 25)).priority(Priority.LOW)
 *     .concurrency(Concurrency.max(12).steal(Concurrency.Steal.FARTHEST))
 *     .root(Switch.on(SURFACE)
 *         .when(Surface.GRAVEL, Random.of(GRAVEL_1, GRAVEL_2, GRAVEL_3).noRepeat(1))
 *         .otherwise(Random.of(DIRT_1, DIRT_2)))
 *     .volume(Range.db(-2, 0)).pitch(Range.semitones(-0.5, 0.5))
 *     .build();
 * }</pre>
 */
@AllowAllocation("set up once")
public final class Sound {

    private static final Generator<Sound, Sound> INLINE = (sound, seed) -> sound;

    private final SoundNode root;
    private final Bus bus;
    private final Attenuation attenuation;
    private final Concurrency concurrency;
    private final Priority priority;
    private final Virtualization virtualization;
    private final Spatial spatial;
    private final Occlusion occlusion;
    private final TimeScaling timeScaling;
    private final float doppler;
    private final Caption caption;
    private final double maxStartDelay;
    private final double maxDuration;
    private final double releaseFade;
    private final boolean loop;
    private final boolean ignoresPause;
    private final Range volume;
    private final Range pitch;
    private final List<AudioEffect> effects;
    private final List<SoundParameter> parameters;
    private final List<SwitchGroup<?>> switches;
    private final List<AssetRef<AudioClip>> clips;
    private final int nodes;

    private Sound(Builder b, List<SoundParameter> parameters, List<SwitchGroup<?>> switches,
            List<AssetRef<AudioClip>> clips, int nodes) {
        root = b.root;
        bus = b.bus;
        attenuation = b.attenuation;
        concurrency = b.concurrency;
        priority = b.priority;
        virtualization = b.virtualization;
        spatial = b.spatial;
        occlusion = b.occlusion;
        boolean flat = b.spatial == Spatial.TWO_D || b.bus.equals(Bus.UI) || b.bus.equals(Bus.VOICE)
            || b.bus.equals(Bus.MUSIC);
        timeScaling = b.timeScaling != null ? b.timeScaling : flat ? TimeScaling.NONE : TimeScaling.PITCH;
        doppler = b.doppler == b.doppler ? b.doppler : flat ? 0 : 1;
        caption = b.caption;
        maxStartDelay = b.maxStartDelay;
        maxDuration = b.maxDuration;
        releaseFade = b.releaseFade;
        loop = b.loop;
        ignoresPause = b.ignoresPause;
        volume = b.volume;
        pitch = b.pitch;
        effects = List.copyOf(b.effects);
        this.parameters = List.copyOf(parameters);
        this.switches = List.copyOf(switches);
        this.clips = List.copyOf(clips);
        this.nodes = nodes;
    }

    /** A sound to describe: 3D on {@link Bus#SFX} with the default attenuation, at normal priority. */
    public static Builder builder() {
        return new Builder();
    }

    /** A sound playing {@code clip} once with the defaults: what {@code play(clip)} plays in a world. */
    public static Sound of(AssetRef<AudioClip> clip) {
        return builder().root(Play.clip(clip)).build();
    }

    public SoundNode root() {
        return root;
    }

    public Bus bus() {
        return bus;
    }

    public Attenuation attenuation() {
        return attenuation;
    }

    public Concurrency concurrency() {
        return concurrency;
    }

    public Priority priority() {
        return priority;
    }

    public Virtualization virtualization() {
        return virtualization;
    }

    public Spatial spatial() {
        return spatial;
    }

    public Occlusion occlusion() {
        return occlusion;
    }

    /** How it follows its world's time scale: as set, else none for 2D, UI, voice and music sounds, pitch for others. */
    public TimeScaling timeScaling() {
        return timeScaling;
    }

    /** How much Doppler shift it takes (0 to 1 or more): as set, else 0 for 2D, UI, voice and music, 1 for others. */
    public float doppler() {
        return doppler;
    }

    /** Its caption; {@code null} for none. */
    public Caption caption() {
        return caption;
    }

    /** Seconds it may wait for its clips to load before it is dropped instead of playing late. */
    public double maxStartDelay() {
        return maxStartDelay;
    }

    /** Seconds after which it is stopped; infinite by default. */
    public double maxDuration() {
        return maxDuration;
    }

    /** Seconds it fades out over when stopped without an outro. */
    public double releaseFade() {
        return releaseFade;
    }

    /** Whether its clips loop until it is stopped. */
    public boolean loop() {
        return loop;
    }

    /** Whether it keeps playing while its world is paused. */
    public boolean ignoresPause() {
        return ignoresPause;
    }

    /** Its volume, drawn once per play. */
    public Range volume() {
        return volume;
    }

    /** Its pitch, drawn once per play. */
    public Range pitch() {
        return pitch;
    }

    /** Its own effect chain, in order. */
    public List<AudioEffect> effects() {
        return effects;
    }

    /** The parameters its tree reads, in the order first met: an instance keeps a value for each. */
    public List<SoundParameter> parameters() {
        return parameters;
    }

    /** Where {@code parameter} is in {@link #parameters()}, or -1. */
    public int parameterIndex(SoundParameter parameter) {
        return parameters.indexOf(parameter);
    }

    /** The switch groups its tree reads, in the order first met: an instance keeps a value for each. */
    public List<SwitchGroup<?>> switches() {
        return switches;
    }

    /** Where {@code group} is in {@link #switches()}, or -1. */
    public int switchIndex(SwitchGroup<?> group) {
        return switches.indexOf(group);
    }

    /** Every clip its tree may play, each once. */
    public List<AssetRef<AudioClip>> clips() {
        return clips;
    }

    /** The asset of this sound: equal sounds give equal references. */
    public AssetRef<Sound> ref() {
        return AssetRef.generated(AudioAssets.SOUND, INLINE, this, 0);
    }

    /** Memory it takes, roughly, for the asset cache's budget. */
    public long bytes() {
        return 256 + 128L * nodes;
    }

    private List<Object> fields() {
        List<Object> f = new ArrayList<>(List.of(root, bus, attenuation, concurrency, priority, virtualization, spatial,
            occlusion, timeScaling, doppler, maxStartDelay, maxDuration, releaseFade, loop, ignoresPause, volume,
            pitch, effects));
        f.add(caption);
        return f;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Sound s && fields().equals(s.fields());
    }

    @Override
    public int hashCode() {
        return fields().hashCode();
    }

    @Override
    public String toString() {
        return "sound " + root;
    }

    /** Describes a sound. */
    public static final class Builder {

        private SoundNode root;
        private Bus bus = Bus.SFX;
        private Attenuation attenuation = Attenuation.DEFAULT;
        private Concurrency concurrency = Concurrency.UNLIMITED;
        private Priority priority = Priority.NORMAL;
        private Virtualization virtualization = Virtualization.KILL_IF_FINITE;
        private Spatial spatial = Spatial.THREE_D;
        private Occlusion occlusion = Occlusion.NONE;
        private TimeScaling timeScaling;
        private float doppler = Float.NaN;
        private Caption caption;
        private double maxStartDelay = 0.1;
        private double maxDuration = Double.POSITIVE_INFINITY;
        private double releaseFade = 0.05;
        private boolean loop;
        private boolean ignoresPause;
        private Range volume = Range.UNITY_DB;
        private Range pitch = Range.UNITY_SEMITONES;
        private final List<AudioEffect> effects = new ArrayList<>();

        private Builder() {
        }

        /** What it plays. */
        public Builder root(SoundNode root) {
            this.root = root;
            return this;
        }

        /** The bus it plays into. */
        public Builder bus(Bus bus) {
            this.bus = Objects.requireNonNull(bus, "bus");
            return this;
        }

        public Builder attenuation(Attenuation attenuation) {
            this.attenuation = Objects.requireNonNull(attenuation, "attenuation");
            return this;
        }

        public Builder concurrency(Concurrency concurrency) {
            this.concurrency = Objects.requireNonNull(concurrency, "concurrency");
            return this;
        }

        public Builder priority(Priority priority) {
            this.priority = Objects.requireNonNull(priority, "priority");
            return this;
        }

        /** What happens when its voice goes virtual. */
        public Builder virtualization(Virtualization virtualization) {
            this.virtualization = Objects.requireNonNull(virtualization, "virtualization");
            return this;
        }

        public Builder spatial(Spatial spatial) {
            this.spatial = Objects.requireNonNull(spatial, "spatial");
            return this;
        }

        public Builder occlusion(Occlusion occlusion) {
            this.occlusion = Objects.requireNonNull(occlusion, "occlusion");
            return this;
        }

        public Builder timeScaling(TimeScaling timeScaling) {
            this.timeScaling = Objects.requireNonNull(timeScaling, "timeScaling");
            return this;
        }

        /** How much Doppler shift it takes: 0 for none, 1 for physical. */
        public Builder doppler(double amount) {
            doppler = (float) amount;
            return this;
        }

        public Builder caption(Caption caption) {
            this.caption = caption;
            return this;
        }

        /** Seconds it may wait for its clips before it is dropped. */
        public Builder maxStartDelay(double seconds) {
            maxStartDelay = seconds;
            return this;
        }

        /** Seconds after which it stops. */
        public Builder maxDuration(double seconds) {
            maxDuration = seconds;
            return this;
        }

        /** Seconds it fades over when stopped without an outro. */
        public Builder releaseFade(double seconds) {
            releaseFade = seconds;
            return this;
        }

        /** Loops its clips until stopped. */
        public Builder loop() {
            loop = true;
            return this;
        }

        /** Keeps playing while its world is paused. */
        public Builder ignoresPause() {
            ignoresPause = true;
            return this;
        }

        /** A random volume per play. */
        public Builder volume(Range range) {
            volume = Objects.requireNonNull(range, "range");
            return this;
        }

        /** A random pitch per play. */
        public Builder pitch(Range range) {
            pitch = Objects.requireNonNull(range, "range");
            return this;
        }

        /** Adds {@code effect} to the end of its chain. */
        public Builder effect(AudioEffect effect) {
            effects.add(Objects.requireNonNull(effect, "effect"));
            return this;
        }

        /** The sound, its effects checked against the built-in ones. */
        public Sound build() {
            return build(AudioEffectTypes.builtIn());
        }

        /** The sound, its effects checked against {@code types}; throws with every problem found. */
        public Sound build(AudioEffectTypes types) {
            Check check = new Check();
            if (root == null) {
                check.problems.add("It has no root: give it one with root()");
            } else {
                check.node(root, "root");
            }
            if (!volume.decibels() || pitch.decibels()) {
                check.problems.add("Its volume is a range of decibels and its pitch one of semitones");
            }
            if (!(maxStartDelay >= 0)) {
                check.problems.add("Its max start delay is seconds, not " + maxStartDelay);
            }
            if (!(maxDuration > 0)) {
                check.problems.add("Its max duration is seconds, not " + maxDuration);
            }
            if (!(releaseFade >= 0) || Double.isInfinite(releaseFade)) {
                check.problems.add("Its release fade is seconds, not " + releaseFade);
            }
            if (doppler < 0) {
                check.problems.add("Its Doppler amount is 0 or more, not " + doppler);
            }
            if (spatial == Spatial.TWO_D && occlusion != Occlusion.NONE) {
                check.problems.add("A 2D sound is not occluded: it has no place");
            }
            for (AudioEffect effect : effects) {
                types.check(effect, "Its chain", check.problems);
            }
            InvalidAudioException.throwIf("sound", check.problems);
            return new Sound(this, check.parameters, check.switches, check.clips, check.nodes);
        }
    }

    /** Walks a tree, finding problems and the parameters, switches and clips it reads. */
    private static final class Check {

        final List<String> problems = new ArrayList<>();
        final List<SoundParameter> parameters = new ArrayList<>();
        final List<SwitchGroup<?>> switches = new ArrayList<>();
        final List<AssetRef<AudioClip>> clips = new ArrayList<>();
        final Set<AssetRef<AudioClip>> seen = new HashSet<>();
        int nodes;

        void node(SoundNode node, String where) {
            nodes++;
            for (Shaping.ParamCurve c : node.shaping().volumeCurves()) {
                parameter(c.parameter(), where);
            }
            for (Shaping.ParamCurve c : node.shaping().pitchCurves()) {
                parameter(c.parameter(), where);
            }
            switch (node) {
                case Play p -> {
                    if (!p.isInput() && seen.add(p.clip())) {
                        clips.add(p.clip());
                    }
                }
                case Random r -> {
                    children(r.children(), where + " > random");
                    if (r.weights().size() != r.children().size()) {
                        problems.add(where + ": " + r.weights().size() + " weights for " + r.children().size()
                            + " children");
                    }
                    for (float w : r.weights()) {
                        if (!(w > 0) || Float.isInfinite(w)) {
                            problems.add(where + ": a weight of " + w + " (weights are positive)");
                        }
                    }
                    if (r.noRepeat() < 0 || r.noRepeat() >= Math.max(1, r.children().size())) {
                        problems.add(where + ": no repeat of " + r.noRepeat() + " picks among "
                            + r.children().size() + " children (at most " + (r.children().size() - 1) + ")");
                    }
                }
                case Sequence s -> children(s.children(), where + " > sequence");
                case Layer l -> children(l.children(), where + " > layer");
                case Switch<?> s -> aSwitch(s, where + " > switch on " + s.group().name());
                case Blend b -> {
                    parameter(b.parameter(), where);
                    children(b.children(), where + " > blend by " + b.parameter().name());
                    for (int i = 1; i < b.points().size(); i++) {
                        if (!(b.points().get(i) > b.points().get(i - 1))) {
                            problems.add(where + ": blend points must increase: " + b.points());
                            break;
                        }
                    }
                }
                case Segments s -> {
                    if (s.intro() != null) {
                        node(s.intro(), where + " > intro");
                    }
                    node(s.loop(), where + " > loop");
                    if (s.outro() != null) {
                        node(s.outro(), where + " > outro");
                    }
                }
                case Scatter s -> {
                    node(s.sound(), where + " > scatter");
                    if (!(s.radius() > 0) || !(s.rate() >= 0) || Double.isInfinite(s.radius() + s.rate())) {
                        problems.add(where + ": a scatter within " + s.radius() + " m at " + s.rate() + " a second");
                    }
                    if (s.rateCurve() != null) {
                        parameter(s.rateCurve().parameter(), where);
                    }
                }
                case Patch p -> {
                    if (p.name().isEmpty()) {
                        problems.add(where + ": a patch without a name");
                    }
                    for (SoundParameter input : p.inputs()) {
                        parameter(input, where);
                    }
                }
            }
        }

        private void children(List<SoundNode> children, String where) {
            if (children.isEmpty()) {
                problems.add(where + ": no children");
            }
            for (int i = 0; i < children.size(); i++) {
                node(children.get(i), where + "[" + i + "]");
            }
        }

        private void aSwitch(Switch<?> s, String where) {
            if (!switches.contains(s.group())) {
                switches.add(s.group());
            }
            Set<Object> values = new HashSet<>();
            for (Switch.Case<?> c : s.cases()) {
                if (!values.add(c.value())) {
                    problems.add(where + ": two cases for " + c.value());
                }
                node(c.node(), where + "[" + c.value() + "]");
            }
            if (s.otherwise() != null) {
                node(s.otherwise(), where + "[otherwise]");
            } else {
                for (Object value : s.group().type().getEnumConstants()) {
                    if (!values.contains(value)) {
                        problems.add(where + ": no case for " + value + " and no otherwise");
                    }
                }
            }
        }

        private void parameter(SoundParameter parameter, String where) {
            for (SoundParameter p : parameters) {
                if (p.name().equals(parameter.name()) && !p.equals(parameter)) {
                    problems.add(where + ": two parameters called " + p.name() + ", " + p + " and " + parameter);
                    return;
                }
            }
            if (!parameters.contains(parameter)) {
                parameters.add(parameter);
            }
        }
    }
}
