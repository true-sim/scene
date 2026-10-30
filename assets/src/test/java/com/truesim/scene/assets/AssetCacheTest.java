package com.truesim.scene.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.jobs.LaneSpec;
import com.truesim.common.jobs.TaskScheduler;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Assets load once, are shared while held, stay cached within a budget, and leave least recently released first. */
class AssetCacheTest {

    /** A loaded "mesh": its name and a size in bytes. */
    record Mesh(String name, long bytes) {
    }

    record RockParams(double size, int detail) {
    }

    private static final AssetType<Mesh> MESH = AssetType.of("mesh", Mesh.class, Mesh::bytes);
    private static final AssetType<Mesh> PROCEDURAL =
        AssetType.of("procedural", Mesh.class, Mesh::bytes, LaneSpec.platform("generating", 2));
    private static final AssetId BARRIER = new AssetId(1, 2);
    private static final AssetId TREE = new AssetId(3, 4);
    private static final AssetId HOUSE = new AssetId(5, 6);
    private static final AssetId BROKEN = new AssetId(7, 8);

    /** Asset bytes in memory: an asset's resource holds its own name; the broken asset is missing. */
    private static final AssetSource SOURCE = (id, resource) -> id.equals(BROKEN) ? null
        : new ByteArrayInputStream((id + (resource.isEmpty() ? "" : "/" + resource)).getBytes(StandardCharsets.UTF_8));

    private final TaskScheduler tasks = new TaskScheduler();
    private final AssetCache cache = new AssetCache(tasks);
    private final Map<String, AtomicInteger> loads = new ConcurrentHashMap<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gate;

    private final Generator<RockParams, Mesh> rocks = (params, seed) -> {
        loads.computeIfAbsent("rock " + params + " " + seed, k -> new AtomicInteger()).incrementAndGet();
        return new Mesh("rock " + params.size() + " " + seed, 50);
    };

    AssetCacheTest() {
        cache.setSource(SOURCE);
        cache.register(MESH, new AssetLoader<>() {
            @Override
            public Mesh load(AssetInput input) throws Exception {
                loads.computeIfAbsent(input.id() + (input.part() == 0 ? "" : "/" + input.part()),
                    k -> new AtomicInteger()).incrementAndGet();
                CountDownLatch wait = gate;
                if (wait != null) {
                    assertThat(wait.await(10, TimeUnit.SECONDS)).isTrue();
                }
                try (InputStream in = input.part() == 0 ? input.open() : input.open(String.valueOf(input.part()))) {
                    String name = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return new Mesh(name, input.id().equals(HOUSE) ? 300 : 100);
                }
            }

            @Override
            public void unload(Mesh data) {
                events.add("unload " + data.name());
            }
        });
        cache.addListener(new AssetCache.Listener() {
            @Override
            public void loaded(AssetRef<?> ref, Object data) {
                events.add("loaded " + ((Mesh) data).name());
            }

            @Override
            public void evicted(AssetRef<?> ref, Object data) {
                events.add("evicted " + ((Mesh) data).name());
            }

            @Override
            public void failed(AssetRef<?> ref, Throwable cause) {
                events.add("failed " + cause.getMessage());
            }
        });
    }

    @AfterEach
    void stop() {
        cache.close();
        tasks.close();
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            tasks.drainCompleted();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    private static AssetRef<Mesh> barrierRef() {
        return AssetRef.of(MESH, BARRIER);
    }

    private int loads(Object id) {
        return loads.getOrDefault(id.toString(), new AtomicInteger()).get();
    }

    @Test
    void removedListenersHearNoMore() throws InterruptedException {
        List<String> heard = new ArrayList<>();
        AssetCache.Listener listener = new AssetCache.Listener() {
            @Override
            public void loaded(AssetRef<?> ref, Object data) {
                heard.add("loaded");
            }

            @Override
            public void evicted(AssetRef<?> ref, Object data) {
                heard.add("evicted");
            }
        };
        cache.addListener(listener);
        cache.removeListener(listener);
        cache.removeListener(listener); // not a listener any more: nothing happens
        AssetRef<Mesh> barrier = barrierRef();
        cache.acquire(barrier);
        await(() -> cache.get(barrier) != null);
        assertThat(heard).isEmpty();
        assertThat(events).anyMatch(event -> event.startsWith("loaded ")); // the other listener still hears
        cache.release(barrier);
    }

    @Test
    void anAssetLoadsOnceHoweverManyHoldIt() throws InterruptedException {
        AssetRef<Mesh> barrier = AssetRef.of(MESH, BARRIER);
        gate = new CountDownLatch(1);
        cache.acquire(barrier);
        cache.acquire(AssetRef.of(MESH, new AssetId(1, 2))); // an equal reference joins the load in flight
        assertThat(cache.state(barrier)).isEqualTo(AssetState.LOADING);
        assertThat(cache.get(barrier)).isNull();
        assertThat(cache.loadingCount()).isEqualTo(1);
        gate.countDown();
        gate = null;
        await(() -> cache.state(barrier) == AssetState.LOADED);
        assertThat(cache.get(barrier).name()).isEqualTo(BARRIER.toString());
        assertThat(cache.holders(barrier)).isEqualTo(2);
        cache.acquire(barrier); // loaded: the same data
        assertThat(loads(BARRIER)).isEqualTo(1);
        assertThat(events).containsExactly("loaded " + BARRIER);
        assertThat(cache.residentBytes()).isEqualTo(100);
        assertThat(cache.cachedBytes()).isZero();
        assertThat(cache.size()).isEqualTo(1);
        // Parts are assets of their own.
        AssetRef<Mesh> far = barrier.part(2);
        assertThat(far).isNotEqualTo(barrier);
        assertThat(far.part()).isEqualTo(2);
        cache.acquire(far);
        await(() -> cache.state(far) == AssetState.LOADED);
        assertThat(cache.get(far).name()).isEqualTo(BARRIER + "/2");
    }

    @Test
    void releasedAssetsStayCachedWithinTheBudgetLeastRecentlyReleasedGoingFirst() throws InterruptedException {
        AssetRef<Mesh> barrier = AssetRef.of(MESH, BARRIER);
        AssetRef<Mesh> tree = AssetRef.of(MESH, TREE);
        AssetRef<Mesh> house = AssetRef.of(MESH, HOUSE);
        for (AssetRef<Mesh> ref : List.of(barrier, tree, house)) {
            cache.acquire(ref);
        }
        await(() -> cache.loadingCount() == 0);
        cache.setBudget(400);
        cache.release(tree);
        cache.release(barrier);
        assertThat(cache.cachedBytes()).isEqualTo(200);
        assertThatIllegalStateException().isThrownBy(() -> cache.release(tree)); // cached, not held
        cache.acquire(barrier); // held again: no reload, no longer cached
        assertThat(cache.cachedBytes()).isEqualTo(100);
        assertThat(loads(BARRIER)).isEqualTo(1);
        cache.release(barrier);
        cache.acquire(tree);
        cache.release(tree); // now the most recently released
        cache.release(house); // 500 bytes cached: over the budget, the barrier goes first
        assertThat(cache.state(barrier)).isEqualTo(AssetState.ABSENT);
        assertThat(cache.state(tree)).isEqualTo(AssetState.LOADED);
        assertThat(cache.cachedBytes()).isEqualTo(400);
        assertThat(events).containsSubsequence("evicted " + BARRIER, "unload " + BARRIER);
        cache.setBudget(300); // the tree goes, then the house fits
        assertThat(cache.state(tree)).isEqualTo(AssetState.ABSENT);
        assertThat(cache.state(house)).isEqualTo(AssetState.LOADED);
        assertThat(cache.residentBytes()).isEqualTo(300);
        cache.acquire(barrier); // evicted: loads again
        await(() -> cache.state(barrier) == AssetState.LOADED);
        assertThat(loads(BARRIER)).isEqualTo(2);
        cache.setBudget(0); // held assets are never evicted
        assertThat(cache.state(barrier)).isEqualTo(AssetState.LOADED);
        assertThat(cache.state(house)).isEqualTo(AssetState.ABSENT);
    }

    @Test
    void assetsReleasedWhileLoadingAreCachedWhenTheyArrive() throws InterruptedException {
        AssetRef<Mesh> tree = AssetRef.of(MESH, TREE);
        AssetRef<Mesh> house = AssetRef.of(MESH, HOUSE);
        gate = new CountDownLatch(1);
        cache.acquire(tree);
        cache.acquire(house);
        cache.release(tree);
        cache.acquire(tree); // held again while loading
        cache.release(tree);
        cache.release(house);
        cache.setBudget(350);
        assertThat(cache.get(barrierRef())).isNull(); // absent
        gate.countDown();
        gate = null;
        while (tasks.pending() > 0) {
            Thread.sleep(1); // both finish before one frame boundary delivers them, in the order they started
        }
        tasks.drainCompleted();
        assertThat(cache.loadingCount()).isZero();
        // Both were cached as they arrived, in the order they started; the house did not fit next to the tree, which
        // was released least recently.
        assertThat(cache.state(tree)).isEqualTo(AssetState.ABSENT);
        assertThat(cache.state(house)).isEqualTo(AssetState.LOADED);
        assertThat(cache.holders(house)).isZero();
        assertThat(events).containsSubsequence("loaded " + TREE, "loaded " + HOUSE, "evicted " + TREE);
    }

    /** Asset data holding native memory, say: closed when evicted. */
    static final class Held implements AutoCloseable {
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    void evictedAssetsAreClosed() throws InterruptedException {
        AssetCache cache = new AssetCache(tasks); // its own: the shared one hears meshes only
        cache.setSource(SOURCE);
        AssetType<Held> held = AssetType.of("held", Held.class, h -> 10);
        List<Held> made = new CopyOnWriteArrayList<>();
        cache.register(held, input -> {
            Held data = new Held();
            made.add(data);
            return data;
        });
        AssetRef<Held> loaded = AssetRef.of(held, TREE);
        AssetRef<Held> generated = AssetRef.generated(held, (params, seed) -> {
            Held data = new Held();
            made.add(data);
            return data;
        }, "params", 1);
        cache.acquire(loaded);
        cache.acquire(generated);
        await(() -> cache.loadingCount() == 0);
        assertThat(made).hasSize(2).allMatch(h -> !h.closed.get());
        cache.setBudget(0);
        cache.release(loaded);
        cache.release(generated);
        assertThat(made).allMatch(h -> h.closed.get()); // by the loader's default unload, and by the cache
        // Closing that fails: unchecked exceptions as they are, checked ones wrapped.
        AutoCloseable unchecked = () -> {
            throw new IllegalArgumentException("unchecked");
        };
        AutoCloseable checked = () -> {
            throw new java.io.IOException("checked");
        };
        assertThatIllegalArgumentException().isThrownBy(() -> AssetLoader.closeIfCloseable(unchecked));
        assertThatIllegalStateException().isThrownBy(() -> AssetLoader.closeIfCloseable(checked))
            .withCauseInstanceOf(java.io.IOException.class);
        AssetLoader.closeIfCloseable("not closeable"); // nothing
    }

    @Test
    void equalRecipesAreOneGeneratedAsset() throws InterruptedException {
        AssetRef<Mesh> rock = AssetRef.generated(PROCEDURAL, rocks, new RockParams(2.5, 3), 42);
        AssetRef<Mesh> same = AssetRef.generated(PROCEDURAL, rocks, new RockParams(2.5, 3), 42);
        AssetRef<Mesh> otherSeed = AssetRef.generated(PROCEDURAL, rocks, new RockParams(2.5, 3), 43);
        AssetRef<Mesh> otherParams = AssetRef.generated(PROCEDURAL, rocks, new RockParams(2.0, 3), 42);
        Generator<RockParams, Mesh> otherGenerator = (params, seed) -> new Mesh("other", 1);
        assertThat(same).isEqualTo(rock).hasSameHashCodeAs(rock);
        assertThat(rock).isNotEqualTo(otherSeed).isNotEqualTo(otherParams)
            .isNotEqualTo(AssetRef.generated(PROCEDURAL, otherGenerator, new RockParams(2.5, 3), 42))
            .isNotEqualTo(AssetRef.generated(MESH, rocks, new RockParams(2.5, 3), 42))
            .isNotEqualTo(AssetRef.of(PROCEDURAL, BARRIER)).isNotEqualTo("rock");
        assertThat(rock.isGenerated()).isTrue();
        assertThat(rock.id()).isNull();
        assertThat(rock.type()).isSameAs(PROCEDURAL);
        assertThat(rock.toString()).contains("generated", "seed 42");
        cache.acquire(rock);
        cache.acquire(same);
        cache.acquire(otherSeed);
        await(() -> cache.loadingCount() == 0);
        assertThat(cache.get(same).name()).isEqualTo("rock 2.5 42");
        assertThat(cache.get(otherSeed).name()).isEqualTo("rock 2.5 43");
        assertThat(loads("rock " + new RockParams(2.5, 3) + " 42")).isEqualTo(1);
        assertThatIllegalStateException().isThrownBy(() -> rock.part(1));
        cache.release(otherSeed);
        cache.setBudget(0); // evicted: generated assets need no source to unload them
        assertThat(cache.state(otherSeed)).isEqualTo(AssetState.ABSENT);
        assertThat(events).doesNotContain("unload rock 2.5 43");
        // A generated asset of a type with a source: the source does not unload it either.
        AssetRef<Mesh> generatedMesh = AssetRef.generated(MESH, rocks, new RockParams(1, 1), 7);
        cache.acquire(generatedMesh);
        await(() -> cache.loadingCount() == 0);
        cache.release(generatedMesh);
        assertThat(cache.state(generatedMesh)).isEqualTo(AssetState.ABSENT);
        assertThat(events).doesNotContain("unload rock 1.0 7");
    }

    @Test
    void failedLoadsStayFailedWhileHeldAndRetryLater() throws InterruptedException {
        cache.addListener(new AssetCache.Listener() { // hears failures by default: nothing
            @Override
            public void loaded(AssetRef<?> ref, Object data) {
            }

            @Override
            public void evicted(AssetRef<?> ref, Object data) {
            }
        });
        AssetRef<Mesh> broken = AssetRef.of(MESH, BROKEN);
        cache.acquire(broken);
        await(() -> cache.state(broken) == AssetState.FAILED);
        assertThat(cache.failure(broken)).hasMessage("No source has asset " + BROKEN);
        assertThat(events).contains("failed No source has asset " + BROKEN);
        assertThat(cache.get(broken)).isNull();
        cache.acquire(broken); // still failed
        assertThat(loads(BROKEN)).isEqualTo(1);
        cache.release(broken);
        cache.release(broken); // forgotten: a later acquire tries again
        assertThat(cache.state(broken)).isEqualTo(AssetState.ABSENT);
        assertThat(cache.failure(broken)).isNull();
        cache.acquire(broken);
        cache.release(broken); // released while loading: forgotten when it fails
        await(() -> cache.loadingCount() == 0);
        assertThat(loads(BROKEN)).isEqualTo(2);
        assertThat(cache.state(broken)).isEqualTo(AssetState.ABSENT);
    }

    @Test
    void closingEvictsEverythingAndUnloadsLateArrivals() throws InterruptedException {
        AssetRef<Mesh> barrier = AssetRef.of(MESH, BARRIER);
        AssetRef<Mesh> tree = AssetRef.of(MESH, TREE);
        AssetRef<Mesh> house = AssetRef.of(MESH, HOUSE);
        cache.acquire(barrier);
        cache.acquire(tree);
        await(() -> cache.loadingCount() == 0);
        cache.release(tree);
        gate = new CountDownLatch(1);
        cache.acquire(house);
        await(() -> loads(HOUSE) == 1);
        cache.close();
        cache.close(); // once is enough
        assertThat(events).contains("evicted " + BARRIER, "unload " + BARRIER, "evicted " + TREE, "unload " + TREE);
        assertThat(cache.residentBytes()).isZero();
        assertThat(cache.size()).isZero();
        gate.countDown();
        gate = null;
        await(() -> events.contains("unload " + HOUSE));
        assertThatIllegalStateException().isThrownBy(() -> cache.acquire(barrier));
        assertThatIllegalStateException().isThrownBy(() -> cache.register(MESH, input -> null));
        // A failed load arriving after closing is dropped.
        AssetCache other = new AssetCache(tasks);
        other.register(MESH, input -> {
            throw new IllegalStateException("late");
        }, SOURCE);
        other.acquire(AssetRef.of(MESH, BROKEN));
        other.close();
        await(() -> other.loadingCount() == 0);
    }

    @Test
    void misuseIsChecked() {
        AssetRef<Mesh> barrier = AssetRef.of(MESH, BARRIER);
        assertThatIllegalStateException().isThrownBy(() -> cache.release(barrier));
        assertThatIllegalStateException().isThrownBy(() -> cache.register(MESH, input -> null));
        assertThatIllegalArgumentException().isThrownBy(() -> cache.acquire(AssetRef.of(PROCEDURAL, BARRIER)))
            .withMessageContaining("no loader");
        AssetCache unsourced = new AssetCache(tasks);
        unsourced.register(MESH, input -> null);
        assertThatIllegalStateException().isThrownBy(() -> unsourced.acquire(barrier)).withMessageContaining("set one");
        assertThatNullPointerException().isThrownBy(() -> unsourced.setSource(null));
        assertThatNullPointerException().isThrownBy(() -> unsourced.register(PROCEDURAL, input -> null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> cache.setBudget(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> barrier.part(-1));
        assertThatNullPointerException().isThrownBy(() -> cache.addListener(null));
        assertThatNullPointerException().isThrownBy(() -> cache.register(PROCEDURAL, null));
        assertThatNullPointerException().isThrownBy(() -> new AssetCache(null));
        assertThatNullPointerException().isThrownBy(() -> AssetRef.of(MESH, null));
        assertThatNullPointerException().isThrownBy(() -> AssetRef.generated(MESH, rocks, null, 0));
        assertThat(cache.holders(barrier)).isZero();
        assertThat(cache.failure(barrier)).isNull();
        assertThat(barrier.toString()).isEqualTo("mesh " + BARRIER);
        assertThat(barrier.part(1).toString()).endsWith(" part 1");
        assertThat(barrier).isEqualTo(barrier).isNotEqualTo(barrier.part(1)).isNotEqualTo(AssetRef.of(MESH, TREE));
        assertThat(MESH.name()).isEqualTo("mesh");
        assertThat(MESH.type()).isEqualTo(Mesh.class);
        assertThat(MESH.lane()).isSameAs(AssetType.LOADING);
        assertThat(MESH).hasToString("asset type mesh");
        AssetLoader<Mesh> loader = input -> null;
        loader.unload(new Mesh("x", 1)); // unloading does nothing by default, but for AutoCloseable data
    }

    @Test
    void idsAreThirtyTwoHexDigits() {
        AssetId id = new AssetId(0x0123456789abcdefL, 0xfedcba9876543210L);
        assertThat(id).hasToString("0123456789abcdeffedcba9876543210");
        assertThat(AssetId.parse(id.toString())).isEqualTo(id);
        assertThatIllegalArgumentException().isThrownBy(() -> AssetId.parse("abc"));
        assertThatIllegalArgumentException().isThrownBy(() -> AssetId.parse("0123456789abcdefXedcba9876543210"));
    }
}
