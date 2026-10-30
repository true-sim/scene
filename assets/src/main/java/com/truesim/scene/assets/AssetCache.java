package com.truesim.scene.assets;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.Lane;
import com.truesim.common.jobs.Task;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.jobs.TaskState;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All loaded asset data of a process, keyed by {@link AssetRef}, so each asset loads once however many nodes, cells or
 * other assets use it. Users {@link #acquire} an asset and {@link #release} it when done; the first acquire starts its
 * load (the type's {@link AssetLoader} reading from its {@link AssetSource}, or the asset's {@link Generator}), and
 * later ones join the load in flight or get
 * the loaded data. An asset nobody holds stays cached until the memory budget needs the room, least recently released
 * first, so streaming back and forth does not reload.
 *
 * <pre>{@code
 * AssetCache assets = new AssetCache(tasks);
 * assets.setSource(AssetSource.firstOf(AssetSource.directory(patches), cdn.through(AssetSource.GZIP)));
 * assets.register(MESH, GLTF_MESHES);        // a loader: reads the format from any source
 * assets.setBudget(512L << 20);
 * assets.addListener(gpuResources);          // uploads on loaded, frees on evicted
 *
 * assets.acquire(barrier);                   // e.g. when a cell attaches
 * MeshData mesh = assets.get(barrier);       // null until loaded
 * assets.release(barrier);                   // when the cell goes
 * }</pre>
 *
 * <p>Loads finish at the frame boundary ({@code TaskScheduler.drainCompleted()}): those done by the same boundary in
 * the order they started, so residency never changes in between. Use the cache on the main thread, the one that
 * drains the scheduler.
 */
public final class AssetCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AssetCache.class);

    /** Hears of assets becoming resident and leaving the cache, e.g. the engine's GPU resource cache. */
    public interface Listener {

        /** {@code ref}'s data has loaded. */
        void loaded(AssetRef<?> ref, Object data);

        /** {@code ref} left the cache: free what was made from {@code data}. */
        void evicted(AssetRef<?> ref, Object data);

        /** {@code ref} could not load, for {@code cause}: what waits for it would wait for ever. Nothing by default. */
        default void failed(AssetRef<?> ref, Throwable cause) {
        }
    }

    /** One asset in the cache; unreferenced loaded ones are linked in release order. */
    private static final class Entry {
        final AssetRef<?> ref;
        AssetState state = AssetState.LOADING;
        int refs;
        Object data;
        long bytes;
        Throwable failure;
        Entry older;
        Entry newer;

        Entry(AssetRef<?> ref) {
            this.ref = ref;
        }
    }

    private final TaskScheduler tasks;
    /** A type's loader, and its own source or {@code null} for the cache's. */
    private record Registered(AssetLoader<?> loader, AssetSource source) {
    }

    /** One asset's resources, from its source: what a loader reads. */
    private record Input(AssetId id, int part, AssetSource source) implements AssetInput {

        @Override
        public InputStream open(String resource) throws IOException {
            InputStream in = source.open(id, Objects.requireNonNull(resource, "resource"));
            if (in == null) {
                throw new FileNotFoundException(missing(id, resource));
            }
            return in;
        }

        @AllowAllocation("reporting a missing resource")
        private static String missing(AssetId id, String resource) {
            return "No source has " + (AssetSource.MAIN.equals(resource) ? "asset " + id : resource + " of asset " + id);
        }
    }

    private final Map<AssetType<?>, Registered> registered = new IdentityHashMap<>();
    private AssetSource source;
    private final Map<AssetRef<?>, Entry> entries = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private Entry oldest;
    private Entry newest;
    private long budget = Long.MAX_VALUE;
    private long residentBytes;
    private long cachedBytes;
    private int loading;
    private boolean closed;

    /** A cache loading on {@code tasks}' lanes. */
    @AllowAllocation("set up once")
    public AssetCache(TaskScheduler tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    /**
     * Where stored assets come from, for types registered without a source of their own; chain several with
     * {@link AssetSource#firstOf}. None by default.
     */
    public void setSource(AssetSource assetSource) {
        this.source = Objects.requireNonNull(assetSource, "source");
    }

    /**
     * Makes stored assets of {@code type} loadable: {@code loader} reads them from the cache's source
     * ({@link #setSource}). Once per type; generated assets need no loader.
     */
    public <T> void register(AssetType<T> type, AssetLoader<T> loader) {
        register(type, loader, null, false);
    }

    /** Like {@link #register(AssetType, AssetLoader)}, reading from {@code typeSource} instead of the cache's source. */
    public <T> void register(AssetType<T> type, AssetLoader<T> loader, AssetSource typeSource) {
        register(type, loader, Objects.requireNonNull(typeSource, "source"), true);
    }

    @AllowAllocation("registering")
    private void register(AssetType<?> type, AssetLoader<?> loader, AssetSource typeSource, boolean own) {
        checkOpen();
        if (registered.containsKey(type)) {
            throw new IllegalStateException(type + " has a loader already");
        }
        registered.put(type, new Registered(Objects.requireNonNull(loader, "loader"), own ? typeSource : null));
    }

    @AllowAllocation("adding a listener")
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Stops telling {@code listener} of loads and evictions, e.g. when what it uploaded is closed. */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * How many bytes of loaded assets nobody holds may stay cached: beyond it, the least recently released go.
     * Unlimited by default; assets in use are never evicted, whatever the budget.
     */
    public void setBudget(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("The budget is 0 bytes or more: " + bytes);
        }
        this.budget = bytes;
        evictOverBudget();
    }

    // ---------------------------------------------------------------- use

    /** Holds {@code ref}: starts loading it unless it is loading or loaded already. Each acquire needs a release. */
    public void acquire(AssetRef<?> ref) {
        checkOpen();
        Entry entry = entries.get(ref);
        if (entry == null) {
            entry = start(ref);
        } else if (entry.refs == 0 && entry.state == AssetState.LOADED) {
            unlink(entry); // held again: no longer up for eviction
        }
        entry.refs++;
    }

    /** Lets go of {@code ref}: at no holders a loaded asset stays cached for later, a failed one is forgotten. */
    public void release(AssetRef<?> ref) {
        checkOpen();
        Entry entry = entries.get(ref);
        if (entry == null || entry.refs == 0) {
            throw new IllegalStateException("Released more often than acquired: " + ref);
        }
        if (--entry.refs == 0) {
            switch (entry.state) {
                case LOADED -> {
                    link(entry);
                    evictOverBudget();
                }
                case FAILED -> entries.remove(ref); // a later acquire tries again
                default -> { } // still loading: cached when it arrives
            }
        }
    }

    /** {@code ref}'s data, or {@code null} unless it is {@link AssetState#LOADED}. */
    public <T> T get(AssetRef<T> ref) {
        Entry entry = entries.get(ref);
        return entry == null || entry.state != AssetState.LOADED ? null : ref.type().type().cast(entry.data);
    }

    public AssetState state(AssetRef<?> ref) {
        Entry entry = entries.get(ref);
        return entry == null ? AssetState.ABSENT : entry.state;
    }

    /** Why {@code ref} failed to load; {@code null} unless it is {@link AssetState#FAILED}. */
    public Throwable failure(AssetRef<?> ref) {
        Entry entry = entries.get(ref);
        return entry == null ? null : entry.failure;
    }

    /** How many hold {@code ref}. */
    public int holders(AssetRef<?> ref) {
        Entry entry = entries.get(ref);
        return entry == null ? 0 : entry.refs;
    }

    /** Bytes of loaded assets nobody holds, kept for later within the budget. */
    public long cachedBytes() {
        return cachedBytes;
    }

    /** Bytes of loaded assets, held or cached. */
    public long residentBytes() {
        return residentBytes;
    }

    /** Assets loading now. */
    public int loadingCount() {
        return loading;
    }

    /** Assets in the cache, in any state. */
    public int size() {
        return entries.size();
    }

    // ---------------------------------------------------------------- loading

    @AllowAllocation("starting a load")
    private Entry start(AssetRef<?> ref) {
        Registered registration = registered.get(ref.type());
        AssetLoader<?> loader = null;
        Input input = null;
        if (!ref.isGenerated()) {
            if (registration == null) {
                throw new IllegalArgumentException(ref.type() + " has no loader for " + ref);
            }
            AssetSource from = registration.source() != null ? registration.source() : source;
            if (from == null) {
                throw new IllegalStateException("No source to load " + ref + " from: set one");
            }
            loader = registration.loader();
            input = new Input(ref.id(), ref.part(), from);
        }
        Entry entry = new Entry(ref);
        entries.put(ref, entry);
        loading++;
        Lane lane = tasks.lane(ref.type().lane());
        AssetLoader<?> typeLoader = loader;
        Input assetInput = input;
        lane.task(() -> typeLoader == null ? ref.generate() : typeLoader.load(assetInput))
            .deliver(done -> delivered(entry, done))
            .submit();
        return entry;
    }

    /** A load finished; runs on the main thread when the scheduler is drained. */
    private void delivered(Entry entry, Task<?> done) {
        loading--;
        if (closed) {
            if (done.state() == TaskState.SUCCEEDED) {
                unload(entry.ref, done.result());
            }
            return;
        }
        if (done.state() != TaskState.SUCCEEDED) {
            entry.state = AssetState.FAILED;
            entry.failure = done.failure();
            fail(entry);
            if (entry.refs == 0) {
                entries.remove(entry.ref);
            }
            return;
        }
        entry.data = done.result();
        entry.bytes = entry.ref.type().bytes(entry.data);
        entry.state = AssetState.LOADED;
        residentBytes += entry.bytes;
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).loaded(entry.ref, entry.data);
        }
        if (entry.refs == 0) { // released while loading
            link(entry);
            evictOverBudget();
        }
    }

    @AllowAllocation("logging a failure")
    private void fail(Entry entry) {
        LOG.warn("Could not load {}", entry.ref, entry.failure);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).failed(entry.ref, entry.failure);
        }
    }

    /** Evicts the least recently released assets until the cached ones fit the budget. */
    private void evictOverBudget() {
        while (oldest != null && cachedBytes > budget) {
            evict(oldest);
        }
    }

    /** Takes a loaded asset out of the cache: listeners hear of it, its loader unloads it. */
    private void evict(Entry entry) {
        if (entry.refs == 0) {
            unlink(entry);
        }
        entries.remove(entry.ref);
        residentBytes -= entry.bytes;
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).evicted(entry.ref, entry.data);
        }
        unload(entry.ref, entry.data);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void unload(AssetRef<?> ref, Object data) {
        if (ref.isGenerated()) {
            AssetLoader.closeIfCloseable(data); // made here, so freed here
            return;
        }
        ((AssetLoader) registered.get(ref.type()).loader()).unload(data);
    }

    private void link(Entry entry) {
        cachedBytes += entry.bytes;
        entry.older = newest;
        entry.newer = null;
        if (newest == null) {
            oldest = entry;
        } else {
            newest.newer = entry;
        }
        newest = entry;
    }

    private void unlink(Entry entry) {
        cachedBytes -= entry.bytes;
        if (entry.older == null) {
            oldest = entry.newer;
        } else {
            entry.older.newer = entry.newer;
        }
        if (entry.newer == null) {
            newest = entry.older;
        } else {
            entry.newer.older = entry.older;
        }
        entry.older = null;
        entry.newer = null;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The asset cache is closed");
        }
    }

    /** Evicts every loaded asset, held or not; loads still running are unloaded when they arrive. */
    @Override
    @AllowAllocation("closing")
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.state == AssetState.LOADED) {
                evict(entry);
            }
        }
        entries.clear();
    }
}
