package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A kind of module, as the engine's registry knows it by name: when it runs (at birth or each frame), which targets
 * have it (the GPU kernel, the Java simulator, or both), what it needs of the device, and its fields with their kinds
 * and defaults. Effects name module types; building an effect checks its modules against them
 * ({@link ModuleRegistry}). Immutable.
 *
 * <pre>{@code
 * static final ModuleType WIND_GUST = ModuleType.builder("game.windGust", ModuleType.Stage.UPDATE)
 *     .targets(Target.GPU).field("strength", Bind.constant(1)).field("gusts", FloatCurve.constant(1)).build();
 * }</pre>
 *
 * @param name its name, as modules give it
 * @param stage when it runs
 * @param targets the targets that have it
 * @param capability what it needs of the device
 * @param fields its fields by name
 */
public record ModuleType(String name, Stage stage, Set<Target> targets, Capability capability,
        Map<String, Field> fields) {

    /** When a module runs. */
    public enum Stage {
        /** At a particle's birth: an initializer. */
        INIT,
        /** Every frame, on every particle: an updater. */
        UPDATE
    }

    /** What a field holds. */
    public enum Kind {
        /** A {@link Binding}: a float input. */
        BINDING,
        /** A {@link ColorBinding}: a color input. */
        COLOR,
        /** A {@link FloatCurve}. */
        CURVE,
        /** A {@link ColorGradient}. */
        GRADIENT,
        /** An {@link AssetRef}: a texture (a vector field's), a mesh. */
        ASSET;

        /** The kind of {@code value}. */
        static Kind of(Object value) {
            return switch (value) {
                case Binding b -> BINDING;
                case ColorBinding c -> COLOR;
                case FloatCurve c -> CURVE;
                case AssetRef<?> a -> ASSET;
                default -> GRADIENT;
            };
        }
    }

    /**
     * A field of a module type.
     *
     * @param name its name
     * @param kind what it holds
     * @param fallback its value where a module leaves it out
     */
    public record Field(String name, Kind kind, Object fallback) {
    }

    @AllowAllocation("module types are made once")
    public ModuleType {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(capability, "capability");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(name + " runs on no target");
        }
        targets = Set.copyOf(targets);
        fields = Map.copyOf(fields);
    }

    /** Sets up a module type called {@code name}, running at {@code stage}, on both targets and needing nothing. */
    @AllowAllocation("module types are made once")
    public static Builder builder(String name, Stage stage) {
        return new Builder(name, stage);
    }

    /** Whether {@code target} has it. */
    public boolean runsOn(Target target) {
        return targets.contains(target);
    }

    /** Sets up a {@link ModuleType}. */
    @AllowAllocation("module types are made once")
    public static final class Builder {
        private final String name;
        private final Stage stage;
        private Set<Target> targets = EnumSet.allOf(Target.class);
        private Capability capability = Capability.NONE;
        private final Map<String, Field> fields = new LinkedHashMap<>();

        private Builder(String name, Stage stage) {
            this.name = name;
            this.stage = stage;
        }

        /** The targets that have it: both by default. */
        public Builder targets(Target first, Target... more) {
            targets = EnumSet.of(first, more);
            return this;
        }

        /** What it needs of the device: nothing by default. */
        public Builder capability(Capability capability) {
            this.capability = capability;
            return this;
        }

        /** A float field, {@code fallback} where a module leaves it out. */
        public Builder field(String field, Binding fallback) {
            return add(field, Kind.BINDING, fallback);
        }

        /** A color field. */
        public Builder field(String field, ColorBinding fallback) {
            return add(field, Kind.COLOR, fallback);
        }

        /** A curve field. */
        public Builder field(String field, FloatCurve fallback) {
            return add(field, Kind.CURVE, fallback);
        }

        /** A gradient field. */
        public Builder field(String field, ColorGradient fallback) {
            return add(field, Kind.GRADIENT, fallback);
        }

        /** An asset field: modules must give it ({@code fallback} stands for none, e.g. {@link Forces#NO_FIELD}). */
        public Builder field(String field, AssetRef<?> fallback) {
            return add(field, Kind.ASSET, fallback);
        }

        private Builder add(String field, Kind kind, Object fallback) {
            fields.put(field, new Field(field, kind, Objects.requireNonNull(fallback, "fallback")));
            return this;
        }

        public ModuleType build() {
            return new ModuleType(name, stage, targets, capability, fields);
        }
    }
}
