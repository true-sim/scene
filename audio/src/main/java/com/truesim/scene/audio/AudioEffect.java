package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An effect in a bus's or a sound's chain, as data: a DSP node type by its registered name and values for its fields.
 * The engine resolves the name in its registry; layouts and sounds are checked against {@link AudioEffectTypes},
 * where unknown names and fields fail with a suggestion. Immutable, equal by value.
 *
 * <pre>{@code
 * AudioEffect.named("eq.highPass").set("cutoff", 90)
 * }</pre>
 *
 * @param name   the node type's registered name
 * @param values values of its fields, by name; fields not given keep the node's defaults
 */
@AllowAllocation("set up once")
public record AudioEffect(String name, Map<String, Float> values) {

    public AudioEffect {
        Objects.requireNonNull(name, "name");
        values = Map.copyOf(values);
    }

    /** The node type registered as {@code name}, with its defaults. */
    public static AudioEffect named(String name) {
        return new AudioEffect(name, Map.of());
    }

    /** A copy with field {@code field} set to {@code value}. */
    public AudioEffect set(String field, double value) {
        Map<String, Float> map = new TreeMap<>(values);
        map.put(Objects.requireNonNull(field, "field"), (float) value);
        return new AudioEffect(name, map);
    }

    /** The value of {@code field}, or {@code fallback} when not given. */
    public float value(String field, float fallback) {
        return values.getOrDefault(field, fallback);
    }
}
