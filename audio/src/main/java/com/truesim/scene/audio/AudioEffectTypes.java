package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The names of the effects layouts and sounds may name, with their fields: the built-in DSP nodes, and those plugins
 * register with the engine (which adds them here, so data naming them checks). Unknown names and fields fail with
 * "did you mean". Set up once, then read.
 */
@AllowAllocation("set up once")
public final class AudioEffectTypes {

    private final Map<String, Set<String>> fields = new TreeMap<>();

    /** The engine's built-in effect nodes. */
    public static AudioEffectTypes builtIn() {
        return new AudioEffectTypes()
            .add("eq.lowPass", "cutoff", "q")
            .add("eq.highPass", "cutoff", "q")
            .add("eq.bandPass", "low", "high")
            .add("eq.peak", "frequency", "gain", "q")
            .add("compressor", "threshold", "ratio", "attack", "release", "makeup")
            .add("limiter", "ceiling", "release")
            .add("delay", "time", "feedback", "mix")
            .add("reverb", "preset", "size", "decay", "damping", "mix")
            .add("distortion", "drive", "mix");
    }

    /** Adds effect {@code name} with fields {@code fieldNames}; throws if it is known already. */
    public AudioEffectTypes add(String name, String... fieldNames) {
        if (fields.putIfAbsent(name, Set.of(fieldNames)) != null) {
            throw new IllegalStateException("Effect " + name + " is known already");
        }
        return this;
    }

    /** Whether effect {@code name} is known. */
    public boolean contains(String name) {
        return fields.containsKey(name);
    }

    /** Adds to {@code problems} what is wrong with {@code effect} (unknown name or fields), for {@code where}. */
    void check(AudioEffect effect, String where, List<String> problems) {
        Set<String> known = fields.get(effect.name());
        if (known == null) {
            problems.add(where + ": no effect is called " + effect.name()
                + Suggestions.didYouMean(effect.name(), fields.keySet()));
            return;
        }
        for (String field : new TreeMap<>(effect.values()).keySet()) {
            if (!known.contains(field)) {
                problems.add(where + ": effect " + effect.name() + " has no field " + field
                    + Suggestions.didYouMean(field, known));
            }
        }
    }
}
