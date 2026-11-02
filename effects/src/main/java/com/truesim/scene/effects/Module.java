package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A module of an emitter, as data: the name of its {@link ModuleType} and its fields' values by name, as
 * {@code MaterialData} holds a material's. Scene knows no engine types: the engine finds the type by name and reads
 * the values by field name; a field left out takes its type's fallback. Made by the module classes ({@link Shape},
 * {@link Init}, {@link Forces}, {@link Over}) or by name for modules plugins add. Immutable, compared by value.
 *
 * <pre>{@code
 * Module gust = Module.of("game.windGust").with("strength", Bind.param(WIND));
 * }</pre>
 *
 * @param type its module type's name
 * @param values its fields' values by name: {@link Binding}s, {@link ColorBinding}s, {@link FloatCurve}s and
 *     {@link ColorGradient}s
 */
public record Module(String type, Map<String, Object> values) {

    @AllowAllocation("modules are made when effects are set up")
    public Module {
        Objects.requireNonNull(type, "type");
        values = Map.copyOf(values);
    }

    /** A module of type {@code type} with no values: every field takes its fallback. */
    @AllowAllocation("modules are made when effects are set up")
    public static Module of(String type) {
        return new Module(type, Map.of());
    }

    /** This module with field {@code field} bound to {@code value}. */
    public Module with(String field, Binding value) {
        return put(field, value);
    }

    /** This module with color field {@code field} bound to {@code value}. */
    public Module with(String field, ColorBinding value) {
        return put(field, value);
    }

    /** This module with field {@code field} the constant {@code value}. */
    public Module with(String field, float value) {
        return put(field, Bind.constant(value));
    }

    /** This module with curve field {@code field}. */
    public Module with(String field, FloatCurve value) {
        return put(field, value);
    }

    /** This module with gradient field {@code field}. */
    public Module with(String field, ColorGradient value) {
        return put(field, value);
    }

    @AllowAllocation("modules are made when effects are set up")
    private Module put(String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(Objects.requireNonNull(field, "field"), Objects.requireNonNull(value, "value"));
        return new Module(type, copy);
    }

    /** Field {@code field}'s value, or null if this module leaves it out. */
    public Object value(String field) {
        return values.get(field);
    }
}
