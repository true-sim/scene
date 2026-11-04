package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The typed key of a kind of component (application-api.md D46): what a node can carry besides its transform, such as
 * a camera, a light, a listener or a pick shape. Each type has a stable name, its identity in saved games and tools,
 * and the class of its values; declare types as constants of the module that owns them:
 *
 * <pre>{@code
 * public static final ComponentType<Health> HEALTH = ComponentType.of("acme.health", Health.class);
 *
 * scene.components().set(orc, HEALTH, new Health(40));
 * Health h = scene.components().get(orc, HEALTH);           // null without one
 * }</pre>
 *
 * <p>Names are lower case words joined by dots, dashes or underscores, prefixed by the owner ({@code truesim.} for the
 * engine's own), and must never change once games save them. A name is taken once per process: a second type of the
 * same name throws, so two plugins cannot shadow each other's components. {@link #byName} finds a type when a save is
 * read. Types whose values are runtime state (hover, a playing sound's handle) are {@linkplain #transientOf transient}:
 * saves skip them.
 *
 * <p>Stores are per scene ({@link Components}); systems that do not know a type never see it.
 *
 * @param <T> the class of its values
 */
public final class ComponentType<T> {

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*");
    private static final Map<String, ComponentType<?>> BY_NAME = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();
    private static int next;

    private final String name;
    private final Class<T> valueType;
    private final boolean saved;
    private final int id;

    private ComponentType(String name, Class<T> valueType, boolean saved, int id) {
        this.name = name;
        this.valueType = valueType;
        this.saved = saved;
        this.id = id;
    }

    /** A saved component type named {@code name}, holding values of {@code valueType}. */
    public static <T> ComponentType<T> of(String name, Class<T> valueType) {
        return register(name, valueType, true);
    }

    /** A component type whose values are runtime state, never saved (hovered, a pointer's capture). */
    public static <T> ComponentType<T> transientOf(String name, Class<T> valueType) {
        return register(name, valueType, false);
    }

    @AllowAllocation("types are declared once, as constants")
    private static <T> ComponentType<T> register(String name, Class<T> valueType, boolean saved) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(valueType, "valueType");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a component type name (lower case words joined by '.', '-' or '_'): "
                + name);
        }
        synchronized (LOCK) {
            if (BY_NAME.containsKey(name)) {
                throw new IllegalArgumentException("A component type is named " + name + " already");
            }
            ComponentType<T> type = new ComponentType<>(name, valueType, saved, next++);
            BY_NAME.put(name, type);
            return type;
        }
    }

    /** The type named {@code name}, or null: e.g. reading a save that names a type no plugin declares any more. */
    public static ComponentType<?> byName(String name) {
        return BY_NAME.get(name);
    }

    /** Its stable name. */
    public String name() {
        return name;
    }

    /** The class of its values. */
    public Class<T> valueType() {
        return valueType;
    }

    /** Whether saves keep it (false for {@linkplain #transientOf runtime state}). */
    public boolean saved() {
        return saved;
    }

    /** A small number of its own, for stores kept in arrays: dense from 0 in the order types were declared. */
    public int id() {
        return id;
    }

    /** Checks {@code value} is one of its values, for code that has a type only as {@code ComponentType<?>}. */
    public T cast(Object value) {
        return valueType.cast(value);
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return "component " + name;
    }
}
