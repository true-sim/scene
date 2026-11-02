package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The module types effects may name, by name: the built-in ones ({@link #BUILT_IN}) and those plugins add
 * ({@link #with}). Building an effect checks its modules against a registry: unknown names and fields, fields of the
 * wrong kind, modules at the wrong stage or on a target that lacks them. Immutable.
 *
 * <pre>{@code
 * ModuleRegistry modules = ModuleRegistry.BUILT_IN.with(WIND_GUST);
 * EffectData leaves = EffectData.builder(AMBIENT).emitter("leaves", e -> e.update(Module.of("game.windGust")))
 *     .build(modules);
 * }</pre>
 */
public final class ModuleRegistry {

    /**
     * The built-in modules: the shapes, initializers, forces and attributes over life both targets have, and the
     * vector field, which only GPU emitters have.
     */
    public static final ModuleRegistry BUILT_IN = new ModuleRegistry(Map.of()).with(
        init(Shape.POINT).build(),
        init(Shape.SPHERE).field("radius", Bind.constant(1)).field("thickness", Bind.constant(1)).build(),
        init(Shape.BOX).field("x", Bind.constant(1)).field("y", Bind.constant(1)).field("z", Bind.constant(1))
            .build(),
        init(Shape.CONE).field("angle", Bind.constant(0.5f)).field("radius", Bind.constant(0)).build(),
        init(Shape.DISC).field("radius", Bind.constant(1)).build(),
        init(Init.LIFETIME).field("lifetime", Bind.constant(EmitterData.DEFAULT_LIFETIME)).build(),
        init(Init.SPEED).field("speed", Bind.constant(1)).build(),
        init(Init.VELOCITY).field("x", Bind.constant(0)).field("y", Bind.constant(0)).field("z", Bind.constant(0))
            .build(),
        init(Init.SIZE).field("size", Bind.constant(0.1f)).build(),
        init(Init.COLOR).field("color", Bind.color(1, 1, 1, 1)).build(),
        update(Forces.GRAVITY).field("scale", Bind.constant(1)).build(),
        update(Forces.DRAG).field("coefficient", Bind.constant(1)).build(),
        update(Forces.WIND).field("coupling", Bind.constant(1)).field("scale", Bind.constant(1))
            .field("gusts", Bind.constant(0)).build(),
        update(Forces.CURL).field("strength", Bind.constant(1)).field("frequency", Bind.constant(1))
            .field("speed", Bind.constant(0)).field("octaves", Bind.constant(1)).build(),
        axis(update(Forces.VORTEX)).field("strength", Bind.constant(1)).field("pull", Bind.constant(0)).build(),
        update(Forces.POINT).field("strength", Bind.constant(1)).field("radius", Bind.constant(0))
            .field("killRadius", Bind.constant(0)).field("x", Bind.constant(0)).field("y", Bind.constant(0))
            .field("z", Bind.constant(0)).build(),
        axis(update(Forces.LINE)).field("strength", Bind.constant(1)).field("radius", Bind.constant(0))
            .field("x", Bind.constant(0)).field("y", Bind.constant(0)).field("z", Bind.constant(0)).build(),
        axis(update(Forces.ORBIT)).field("speed", Bind.constant(1)).build(),
        update(Forces.FIELD).targets(Target.GPU).field("field", Forces.NO_FIELD).field("strength", Bind.constant(1))
            .field("sizeX", Bind.constant(1)).field("sizeY", Bind.constant(1)).field("sizeZ", Bind.constant(1))
            .field("tile", Bind.constant(0)).build(),
        update(Forces.SPEED_LIMIT).field("speed", Bind.constant(1)).build(),
        update(Over.SIZE).field("curve", FloatCurve.constant(1)).build(),
        update(Over.COLOR).field("gradient", ColorGradient.constant(1, 1, 1, 1)).build());

    private final Map<String, ModuleType> types;

    @AllowAllocation("registries are made once")
    private ModuleRegistry(Map<String, ModuleType> types) {
        this.types = Collections.unmodifiableMap(types);
    }

    private static ModuleType.Builder init(String name) {
        return ModuleType.builder(name, ModuleType.Stage.INIT);
    }

    private static ModuleType.Builder update(String name) {
        return ModuleType.builder(name, ModuleType.Stage.UPDATE);
    }

    /** An axis in the emitter's frame: +Y unless given. */
    private static ModuleType.Builder axis(ModuleType.Builder type) {
        return type.field("axisX", Bind.constant(0)).field("axisY", Bind.constant(1)).field("axisZ", Bind.constant(0));
    }

    /** These module types and {@code more}; names are unique. */
    @AllowAllocation("registries are made once")
    public ModuleRegistry with(ModuleType... more) {
        Map<String, ModuleType> all = new LinkedHashMap<>(types);
        for (ModuleType type : more) {
            if (all.putIfAbsent(type.name(), type) != null) {
                throw new IllegalArgumentException("A module type called " + type.name() + " is registered already");
            }
        }
        return new ModuleRegistry(all);
    }

    /** The module type called {@code name}, or null if there is none. */
    public ModuleType type(String name) {
        return types.get(name);
    }

    /** The names of its module types. */
    public Set<String> names() {
        return types.keySet();
    }
}
