package com.truesim.scene.core;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.ChangeClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ComponentsTest {

    /** A game's own component. */
    record Health(int points) {
    }

    static final ComponentType<Health> HEALTH = ComponentType.of("test.health", Health.class);
    static final ComponentType<String> NOTE = ComponentType.transientOf("test.note", String.class);
    static final ComponentType<String> UNUSED = ComponentType.of("test.unused-type_2", String.class);
    static final ComponentType<?>[] MANY = new ComponentType<?>[20];

    static {
        for (int i = 0; i < MANY.length; i++) {
            MANY[i] = ComponentType.of("test.many-" + i, Integer.class);
        }
    }

    @Test
    void typesHaveStableNamesAndAreFoundByThem() {
        assertThat(HEALTH.name()).isEqualTo("test.health");
        assertThat(HEALTH.valueType()).isEqualTo(Health.class);
        assertThat(HEALTH.saved()).isTrue();
        assertThat(NOTE.saved()).as("runtime state").isFalse();
        assertThat(HEALTH.id()).isNotEqualTo(NOTE.id());
        assertThat(ComponentType.byName("test.health")).isSameAs(HEALTH);
        ComponentType<CameraLens> camera = CameraLens.TYPE; // a type is known once its class is loaded
        assertThat(ComponentType.byName("truesim.camera")).isSameAs(camera);
        assertThat(ComponentType.byName("nobody.declared")).isNull();
        assertThat(HEALTH).hasToString("component test.health");
        assertThat(HEALTH.cast(new Health(1))).isEqualTo(new Health(1));
        assertThatIllegalArgumentException().isThrownBy(() -> ComponentType.of("test.health", Health.class))
            .withMessage("A component type is named test.health already");
        for (String bad : new String[] {"", "Upper", "a..b", ".a", "a.", "with space", "a/b"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ComponentType.of(bad, String.class));
        }
        assertThatNullPointerException().isThrownBy(() -> ComponentType.of(null, String.class));
        assertThatNullPointerException().isThrownBy(() -> ComponentType.of("test.no-class", null));
    }

    @Test
    void storesAreSparseSetsPackedForIteration() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Components components = scene.components();
        assertThat(components.hasStore(HEALTH)).isFalse();
        assertThat(components.get(scene.create(), HEALTH)).as("no store yet").isNull();
        assertThat(components.has(scene.create(), HEALTH)).isFalse();
        assertThat(components.remove(scene.create(), HEALTH)).isFalse();
        long orc = scene.create();
        long elf = scene.create();
        long troll = scene.create();
        components.set(orc, HEALTH, new Health(40));
        components.set(elf, HEALTH, new Health(25));
        components.set(troll, HEALTH, new Health(90));
        ComponentStore<Health> store = components.store(HEALTH);
        assertThat(components.store(HEALTH)).isSameAs(store);
        assertThat(store.type()).isSameAs(HEALTH);
        assertThat(store.scene()).isSameAs(scene);
        assertThat(store.size()).isEqualTo(3);
        assertThat(components.get(elf, HEALTH)).isEqualTo(new Health(25));
        assertThat(store.indexOf(troll)).isEqualTo(2);
        assertThat(store.node(0)).isEqualTo(orc);
        assertThat(store.value(2)).isEqualTo(new Health(90));
        // removing moves the last into the gap
        assertThat(components.remove(orc, HEALTH)).isTrue();
        assertThat(store.node(0)).isEqualTo(troll);
        assertThat(store.indexOf(orc)).isEqualTo(-1);
        assertThat(store.remove(orc)).isFalse();
        assertThat(store.remove(elf)).as("the last one").isTrue();
        assertThat(store.size()).isOne();
        List<Long> seen = new ArrayList<>();
        store.forEach((node, value) -> seen.add(node));
        assertThat(seen).containsExactly(troll);
        assertThat(store.has(NodeHandle.NONE)).isFalse();
        assertThat(store.get(NodeHandle.of(1000, 1))).isNull();
        assertThat(store.has(NodeHandle.of(NodeHandle.index(troll), 2))).as("a stale handle").isFalse();
        assertThat(store).hasToString("test.health store, 1 components");
        assertThatThrownBy(() -> store.node(1)).isInstanceOf(IndexOutOfBoundsException.class);
        // replacing
        components.set(troll, HEALTH, new Health(10));
        assertThat(store.get(troll).points()).isEqualTo(10);
        assertThat(store.size()).isOne();
        // growing: many nodes, far slots
        for (int i = 0; i < 100; i++) {
            store.set(scene.create(), new Health(i));
        }
        assertThat(store.size()).isEqualTo(101);
        assertThat(components.storeCount()).isEqualTo(1);
        assertThat(components.storeAt(0)).isSameAs(store);
        assertThatThrownBy(() -> components.storeAt(1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void settingIsCheckedAndRemovedNodesLoseTheirComponents() {
        ChangeClock clock = new ChangeClock();
        SceneGraph scene = new SceneGraph(clock);
        Components components = scene.components();
        long car = scene.create();
        long wheel = scene.create(car);
        components.set(car, NOTE, "car");
        components.set(wheel, NOTE, "wheel");
        components.set(wheel, HEALTH, new Health(3));
        assertThatNullPointerException().isThrownBy(() -> components.set(car, HEALTH, null));
        assertThatIllegalArgumentException().isThrownBy(() -> components.set(NodeHandle.of(50, 1), HEALTH,
            new Health(1))).withMessage("Not a node of the scene: node 50#1");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ComponentStore<Object> raw = (ComponentStore) components.store(HEALTH);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> raw.set(car, "not health")))
            .isInstanceOf(ClassCastException.class);
                long since = clock.now();
        scene.remove(car);
        assertThat(components.has(car, NOTE)).isFalse();
        assertThat(components.has(wheel, NOTE)).isFalse();
        assertThat(components.has(wheel, HEALTH)).isFalse();
        List<String> changes = new ArrayList<>();
        assertThat(components.store(NOTE).changesSince(since, (node, c) -> changes.add(NodeHandle.toString(node) + c)))
            .isTrue();
        assertThat(changes).containsExactlyInAnyOrder(NodeHandle.toString(car) + ComponentStore.REMOVED,
            NodeHandle.toString(wheel) + ComponentStore.REMOVED);
        // the slot is reused with a new generation: the new node has none
        long again = scene.create();
        assertThat(NodeHandle.index(again)).isIn(NodeHandle.index(car), NodeHandle.index(wheel));
        assertThat(components.has(again, NOTE)).isFalse();
        // only the owner thread changes them
        clock.ownedBy(new Thread());
        assertThatIllegalStateException().isThrownBy(() -> components.set(again, NOTE, "x"));
        assertThatIllegalStateException().isThrownBy(() -> components.store(NOTE).remove(again));
        assertThatIllegalStateException().isThrownBy(() -> components.store(NOTE).changed(again));
        clock.ownedBy(Thread.currentThread());
        components.set(again, NOTE, "fine");
    }

    @Test
    void storesGrowWithTheTypesUsed() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Components components = scene.components();
        long node = scene.create();
        for (int i = MANY.length - 1; i >= 0; i--) {
            @SuppressWarnings("unchecked")
            ComponentType<Integer> type = (ComponentType<Integer>) MANY[i];
            components.set(node, type, i);
        }
        assertThat(components.storeCount()).isEqualTo(MANY.length);
        assertThat(components.has(node, MANY[3])).isTrue();
        assertThat(components.get(node, MANY[19])).isEqualTo(19);
        assertThat(components.hasStore(UNUSED)).isFalse();
        assertThat(components.has(node, UNUSED)).isFalse();
        assertThat(components.store(MANY[0]).has(node)).isTrue();
        assertThat(components.store(MANY[0]).has(Long.MIN_VALUE | 0xFFFF_FFFFL)).as("a slot no handle has").isFalse();
        assertThat(components.remove(node, MANY[4])).isTrue();
        assertThat(components.remove(node, MANY[4])).isFalse();
        assertThat(new SceneGraph(new ChangeClock()).components().hasStore(MANY[19])).isFalse();
        scene.remove(node);
        assertThat(components.store(MANY[5]).size()).isZero();
    }

    @Test
    void changesAreLoggedOnTheScenesClock() {
        ChangeClock clock = new ChangeClock();
        SceneGraph scene = new SceneGraph(clock);
        ComponentStore<Health> store = scene.components().store(HEALTH);
                long a = scene.create();
        long b = scene.create();
        long since = clock.now();
        long version = store.version();
        store.set(a, new Health(1));
        store.set(b, new Health(2));
        store.set(a, new Health(3));
        store.changed(b);
        store.remove(a);
        assertThat(store.version()).isEqualTo(version + 5);
        List<String> log = new ArrayList<>();
        assertThat(store.changesSince(since, (node, changes) -> log.add((node == a ? "a" : "b") + changes))).isTrue();
        assertThat(log).containsExactly("a1", "b1", "a2", "b2", "a4");
        long later = clock.now();
        log.clear();
        assertThat(store.changesSince(later, (node, changes) -> log.add("x"))).isTrue();
        assertThat(log).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> store.changed(a)).withMessageContaining("No test.health");
        // the log grows up to its most, then drops the oldest: a consumer that late rebuilds
        long before = clock.now();
        for (int i = 0; i < ComponentStore.MAX_LOG + 10; i++) {
            store.changed(b);
        }
        assertThat(store.changesSince(before, (node, changes) -> { })).isFalse();
        long recent = clock.now();
        store.changed(b);
        int[] count = {0};
        assertThat(store.changesSince(recent, (node, changes) -> count[0]++)).isTrue();
        assertThat(count[0]).isOne();
    }

    @Test
    void readingAndIteratingAllocateNothing() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        ComponentStore<Health> store = scene.components().store(HEALTH);
        long[] nodes = new long[64];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = scene.create();
            store.set(nodes[i], new Health(i));
        }
        AtomicReference<Health> sink = new AtomicReference<>();
        ComponentStore.Each<Health> each = (node, value) -> sink.set(value);
        ComponentStore.Visitor visitor = (node, changes) -> { };
        long since = scene.clock().now();
        assertAllocationFree(() -> {
            for (int i = 0; i < store.size(); i++) {
                sink.set(store.value(i));
            }
            store.forEach(each);
            sink.set(store.get(nodes[7]));
            sink.set(scene.components().get(nodes[9], HEALTH));
            store.changed(nodes[3]);
            store.changesSince(since, visitor);
        });
    }
}
