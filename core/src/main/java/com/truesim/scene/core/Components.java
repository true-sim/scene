package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * The components on a scene's nodes (application-api.md D46), one {@link ComponentStore} per {@link ComponentType},
 * made when the type is first used. Reach it with {@link SceneGraph#components()}:
 *
 * <pre>{@code
 * Components components = scene.components();
 * components.set(lamp, LightSource.TYPE, LightSource.point(10, 1, 0.9f, 0.8f, 800));
 * components.set(lamp, Pickable.TYPE, Pickable.bounds());
 * LightSource light = components.get(lamp, LightSource.TYPE);
 * scene.remove(lamp);                                  // its components go with it
 * }</pre>
 *
 * <p>Systems keep the stores of the types they know ({@link #store}) and iterate or follow them; a type no system knows
 * is just carried along (and saved). {@link #storeCount}/{@link #storeAt} list the stores that exist, e.g. to save every
 * component of a node. Used on the scene's thread; reading allocates nothing.
 */
public final class Components {

    private final SceneGraph scene;
    private ComponentStore<?>[] byType = new ComponentStore<?>[16];
    private ComponentStore<?>[] stores = new ComponentStore<?>[16];
    private int count;

    Components(SceneGraph scene) {
        this.scene = scene;
    }

    /** The store of {@code type}'s components, made on first use. */
    @SuppressWarnings("unchecked")
    public <T> ComponentStore<T> store(ComponentType<T> type) {
        int id = type.id();
        if (id < byType.length && byType[id] != null) {
            return (ComponentStore<T>) byType[id];
        }
        return make(type);
    }

    @AllowAllocation("a store per type, made on first use")
    private <T> ComponentStore<T> make(ComponentType<T> type) {
        if (type.id() >= byType.length) {
            byType = Arrays.copyOf(byType, Math.max(type.id() + 1, byType.length * 2));
        }
        if (count == stores.length) {
            stores = Arrays.copyOf(stores, count * 2);
        }
        ComponentStore<T> store = new ComponentStore<>(scene, type);
        byType[type.id()] = store;
        stores[count++] = store;
        return store;
    }

    /** Whether {@code type}'s store was made: a type never used has none. */
    public boolean hasStore(ComponentType<?> type) {
        return type.id() < byType.length && byType[type.id()] != null;
    }

    /** Gives {@code node} {@code value} as its {@code type} component (replacing one it had). */
    public <T> void set(long node, ComponentType<T> type, T value) {
        store(type).set(node, value);
    }

    /** {@code node}'s {@code type} component, or null. */
    @SuppressWarnings("unchecked")
    public <T> T get(long node, ComponentType<T> type) {
        return hasStore(type) ? (T) byType[type.id()].get(node) : null;
    }

    /** Whether {@code node} has a {@code type} component. */
    public boolean has(long node, ComponentType<?> type) {
        return hasStore(type) && byType[type.id()].has(node);
    }

    /** Takes {@code node}'s {@code type} component away; false if it had none. */
    public boolean remove(long node, ComponentType<?> type) {
        return hasStore(type) && byType[type.id()].remove(node);
    }

    /** How many stores exist. */
    public int storeCount() {
        return count;
    }

    /** Store {@code i} (0 to {@link #storeCount()} - 1), in the order they were made. */
    public ComponentStore<?> storeAt(int i) {
        return stores[java.util.Objects.checkIndex(i, count)];
    }

    /** Removes the components of the first {@code removed} nodes the scene removes at once: one entry per store. */
    void nodesRemoved(long[] nodes, int removed) {
        for (int i = 0; i < count; i++) {
            stores[i].nodesRemoved(nodes, removed);
        }
    }
}
