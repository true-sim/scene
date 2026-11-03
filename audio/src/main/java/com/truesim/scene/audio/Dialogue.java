package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A world's dialogue (§2.15, {@link Sounds#dialogue()}): lines said by speakers under rules. One line per speaker at
 * a time; a higher priority interrupts (the line fades over 100 ms, {@link DialogueEvent#INTERRUPTED}), an equal one
 * queues (and expires after {@link #queueExpiry}), a lower one is dropped; keys may cool down ({@link #cooldown}).
 * Lines play on the {@code VOICE} bus at the highest priority, never virtualized, at the speaker's node or in 2D.
 *
 * <pre>{@code
 * sounds.dialogue().clips(path -> paths.ref(AudioAssets.CLIP, path)).locale("de");
 * LineInstance line = sounds.dialogue().say(HALT, guardHead, Priority.HIGH);
 * sounds.dialogue().listen((event, l, speaker, text, start, end) -> subtitles.show(event, speaker, text));
 * line.visemes(weights);                                    // each frame, for the face rig
 * }</pre>
 *
 * <p>Events are reported by {@link #update} at the time a line is heard: the engine's sync tells each line where its
 * listener hears it ({@link #heard}); without it, the instance's own time stands in. Lines and their sounds are made
 * as they are said (a line is a rare event); updating allocates nothing. Scene thread only.
 */
public final class Dialogue {

    /** Seconds an interrupted line fades. */
    public static final double INTERRUPT_FADE = 0.1;

    /** Receives what happens to lines. */
    @FunctionalInterface
    public interface Listener {
        /**
         * {@code event} for {@code line} of {@code speaker}; for cues their text and times (seconds into the line),
         * else the line's subtitle key (or null) and 0.
         */
        void event(DialogueEvent event, LineInstance line, String speaker, String text, double start, double end);
    }

    private final Sounds sounds;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<Object, Double> cooldowns = new HashMap<>();
    private final Map<Object, Long> lastSaid = new HashMap<>();
    private final Map<Line, Sound> soundsOf = new HashMap<>();
    private Function<String, AssetRef<AudioClip>> clips;
    private String locale = "en";
    private double queueExpiry = 5;

    @AllowAllocation("made with its world")
    Dialogue(Sounds sounds) {
        this.sounds = sounds;
    }

    /** How a path becomes a clip: the game's path assets ({@code path -> paths.ref(AudioAssets.CLIP, path)}). */
    public Dialogue clips(Function<String, AssetRef<AudioClip>> resolver) {
        clips = Objects.requireNonNull(resolver, "resolver");
        return this;
    }

    /** The language paths are resolved in: the audio language setting. {@code "en"} by default. */
    public Dialogue locale(String locale) {
        this.locale = Objects.requireNonNull(locale, "locale");
        return this;
    }

    /** Seconds a queued line waits at most. 5 by default. */
    public Dialogue queueExpiry(double seconds) {
        queueExpiry = seconds;
        return this;
    }

    /** Lines keyed {@code key} ({@link Line#key()}) are dropped for {@code seconds} after one was said: barks. */
    @AllowAllocation("set up once")
    public Dialogue cooldown(Object key, double seconds) {
        cooldowns.put(Objects.requireNonNull(key, "key"), seconds);
        return this;
    }

    /** Reports events to {@code listener}. */
    @AllowAllocation("set up once")
    public Dialogue listen(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    /** Says {@code line} in 2D (a radio, a narrator). */
    public LineInstance say(Line line, Priority priority) {
        return say(line, 0, priority);
    }

    /** Says {@code line} at {@code node} (its speaker's head; 0 for 2D) with {@code priority}, by the rules. */
    @AllowAllocation("a line said")
    public LineInstance say(Line line, long node, Priority priority) {
        Entry entry = new Entry(line, node, priority, sounds.now());
        entry.handle = new LineInstance(this, entry);
        Long said = lastSaid.get(line.key());
        Double cooldown = cooldowns.get(line.key());
        if (said != null && cooldown != null && sounds.now() - said < cooldown * Sounds.TICKS_PER_SECOND) {
            report(DialogueEvent.DROPPED, entry, line.subtitle(), 0, 0);
            return entry.handle;
        }
        Entry current = speaking(line.speaker());
        if (current != null && priority.compareTo(current.priority) < 0) {
            report(DialogueEvent.DROPPED, entry, line.subtitle(), 0, 0);
            return entry.handle;
        }
        if (current != null && priority.compareTo(current.priority) > 0) {
            interrupt(current);
        }
        entries.add(entry);
        entry.alive = true;
        if (current == null || priority.compareTo(current.priority) > 0) {
            start(entry);
        }
        return entry.handle;
    }

    private Entry speaking(String speaker) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.instance != 0 && e.line.speaker().equals(speaker)) {
                return e;
            }
        }
        return null;
    }

    private void interrupt(Entry e) {
        sounds.stop(e.instance, INTERRUPT_FADE);
        if (e.cueOpen >= 0) {
            Cue cue = cue(e, e.cueOpen);
            report(DialogueEvent.CUE_ENDED, e, cue.text(), cue.start(), cue.end());
        }
        report(DialogueEvent.INTERRUPTED, e, e.line.subtitle(), 0, 0);
        end(e);
    }

    @AllowAllocation("a line starting: its sound made once")
    private void start(Entry e) {
        Sound sound = soundsOf.computeIfAbsent(e.line, this::sound);
        e.instance = e.node == 0 ? sounds.fire(sound) : sounds.fire(sound, e.node);
        lastSaid.put(e.line.key(), sounds.now());
    }

    @AllowAllocation("a line's sound, made once")
    private Sound sound(Line line) {
        AssetRef<AudioClip> clip = line.clip() != null ? line.clip()
            : Objects.requireNonNull(clips, "set dialogue().clips(...) to resolve paths").apply(line.path(locale));
        return Sound.builder().bus(Bus.VOICE).priority(Priority.HIGHEST).virtualization(Virtualization.KEEP_TIME)
            .root(Play.clip(clip)).build();
    }

    /** The engine's sync: {@code instance}'s listener hears it {@code seconds} in (negative: not yet). */
    public void heard(long instance, double seconds) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.instance == instance) {
                e.heard = seconds;
                e.heardKnown = true;
            }
        }
    }

    /** The engine's sync: {@code instance}'s smoothed amplitude now (for a jaw). */
    public void envelope(long instance, float level) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.instance == instance) {
                e.envelope = level;
            }
        }
    }

    /** Whether {@code instance} plays a line (the sync asks, to tell it where it is heard). */
    public boolean saying(long instance) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.instance == instance) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports what was heard since the last update (starts, cues, ends), expires queued lines and starts those whose
     * speaker is free. Called by {@link Sounds#advance} and by the engine's sync after it told where lines are heard.
     */
    public void update() {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.instance == 0) {
                if (sounds.now() - e.saidAt > queueExpiry * Sounds.TICKS_PER_SECOND) {
                    report(DialogueEvent.EXPIRED, e, e.line.subtitle(), 0, 0);
                    end(e);
                    i--;
                } else if (speaking(e.line.speaker()) == null) {
                    start(e);
                }
                continue;
            }
            boolean alive = sounds.alive(e.instance);
            double h = e.heardKnown ? e.heard : alive ? (double) sounds.time(e.instance) / Sounds.TICKS_PER_SECOND
                : Double.POSITIVE_INFINITY;
            progress(e, alive ? h : Double.POSITIVE_INFINITY);
            if (!alive) {
                report(DialogueEvent.LINE_ENDED, e, e.line.subtitle(), 0, 0);
                end(e);
                i--;
            }
        }
    }

    /** Reports the start and the cues passed by heard time {@code h}. */
    private void progress(Entry e, double h) {
        if (h < 0) {
            return;
        }
        if (!e.started) {
            e.started = true;
            report(DialogueEvent.LINE_STARTED, e, e.line.subtitle(), 0, 0);
        }
        int cues = e.line.cues().isEmpty() ? (e.line.subtitle() == null ? 0 : 1) : e.line.cues().size();
        while (e.nextCue < cues || e.cueOpen >= 0) {
            if (e.cueOpen >= 0) {
                Cue cue = cue(e, e.cueOpen);
                if (h < cue.end()) {
                    return;
                }
                report(DialogueEvent.CUE_ENDED, e, cue.text(), cue.start(), cue.end());
                e.cueOpen = -1;
            } else {
                Cue cue = cue(e, e.nextCue);
                if (h < cue.start()) {
                    return;
                }
                report(DialogueEvent.CUE_STARTED, e, cue.text(), cue.start(), cue.end());
                e.cueOpen = e.nextCue++;
            }
        }
    }

    /** Cue {@code i} of {@code e}'s line: the whole line under its subtitle when it has no cues. */
    private Cue cue(Entry e, int i) {
        return e.line.cues().isEmpty() ? e.whole : e.line.cues().get(i);
    }

    private void end(Entry e) {
        entries.remove(e);
        e.alive = false;
    }

    private void report(DialogueEvent event, Entry e, String text, double start, double end) {
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).event(event, e.handle, e.line.speaker(), text, start, end);
        }
    }

    /** Lines playing or queued. */
    public int size() {
        return entries.size();
    }

    double heardOf(Entry e) {
        return e.heardKnown ? e.heard : e.instance == 0 || !sounds.alive(e.instance) ? -1
            : (double) sounds.time(e.instance) / Sounds.TICKS_PER_SECOND;
    }

    /** A line said: queued, playing or done. */
    static final class Entry {
        final Line line;
        final long node;
        final Priority priority;
        final long saidAt;
        final Cue whole;
        LineInstance handle;
        long instance;
        boolean alive;
        boolean started;
        boolean heardKnown;
        double heard;
        float envelope;
        int nextCue;
        int cueOpen = -1;

        @AllowAllocation("a line said")
        Entry(Line line, long node, Priority priority, long saidAt) {
            this.line = line;
            this.node = node;
            this.priority = Objects.requireNonNull(priority, "priority");
            this.saidAt = saidAt;
            whole = new Cue(0, Double.POSITIVE_INFINITY, line.subtitle() == null ? "" : line.subtitle());
        }
    }
}
