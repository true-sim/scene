package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.Lane;
import com.truesim.common.jobs.Task;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.jobs.TaskState;
import com.truesim.scene.core.CellGrid;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A streamed world: {@link Layer}s of cells that load in the background and become scene content at the frame
 * boundary. Cells are requested and released with reference counts, so several users of one cell load it once and it
 * goes when the last one lets go. (Interest sources request and release cells automatically on top of this.)
 *
 * <pre>{@code
 * World world = new World(scene, tasks);
 * world.register(PROPS, propLoader);
 * world.request(PROPS, 0, 4, 0, 7);     // loads on the layer's lane
 *
 * // Once per frame, on the main thread:
 * tasks.drainCompleted();               // finished loads attach to the scene here, in submission order
 * scene.update();
 *
 * world.release(PROPS, 0, 4, 0, 7);     // detaches it; a load still queued skips, a running one is discarded
 * }</pre>
 *
 * <p>Use a world on the main thread, the one that drains the task scheduler; loaders' {@link CellLoader#load} runs on
 * lanes and never sees the scene. Requesting, releasing and looking up cells allocate nothing for cells already
 * known; starting a load allocates its task.
 */
public final class World implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(World.class);

    private final SceneGraph scene;
    private final TaskScheduler tasks;
    private final Map<Layer<?>, Registered> layers = new IdentityHashMap<>();

    // Cells, by entry index.
    private Registered[] owner = new Registered[16];
    private int[] level = new int[16];
    private long[] key = new long[16];
    private CellState[] state = new CellState[16];
    private int[] refs = new int[16];
    private Object[] data = new Object[16];
    private AtomicBoolean[] wanted = new AtomicBoolean[16];
    private int[] generation = new int[16];
    private CellContent[] content = new CellContent[16];
    private int[] free = new int[16];
    private int freeCount;
    private int entryCount;

    private int loading;
    private int loaded;
    private int failed;
    private boolean closed;

    /** A world filling {@code scene} with cells loaded on {@code tasks}' lanes. */
    public World(SceneGraph scene, TaskScheduler tasks) {
        this.scene = scene;
        this.tasks = tasks;
    }

    /** A layer, its loader, its lane and its cells by level. */
    private static final class Registered {
        final Layer<?> layer;
        final CellLoader<Object> loader;
        final Lane lane;
        final LongIntMap[] cells;

        @AllowAllocation("registering")
        Registered(Layer<?> layer, CellLoader<Object> loader, Lane lane) {
            this.layer = layer;
            this.loader = loader;
            this.lane = lane;
            this.cells = new LongIntMap[layer.levels().count()];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = new LongIntMap();
            }
        }
    }

    public SceneGraph scene() {
        return scene;
    }

    /** Makes {@code layer}'s cells loadable with {@code loader}. Once per layer. */
    @SuppressWarnings("unchecked")
    @AllowAllocation("registering")
    public <T> void register(Layer<T> layer, CellLoader<T> loader) {
        checkOpen();
        if (layers.containsKey(layer)) {
            throw new IllegalStateException(layer + " is already registered");
        }
        layers.put(layer, new Registered(layer, (CellLoader<Object>) loader, tasks.lane(layer.lane())));
    }

    // ---------------------------------------------------------------- requests

    /**
     * Asks for a cell: the first request starts loading it, later ones only count. Each request needs a matching
     * {@link #release}.
     */
    public void request(Layer<?> layer, int level, int x, int y, int z) {
        checkOpen();
        Registered registered = registered(layer);
        long cellKey = CellGrid.key(x, y, z);
        LongIntMap cells = cells(registered, level);
        int entry = cells.get(cellKey);
        if (entry >= 0) {
            refs[entry]++;
            return;
        }
        entry = newEntry();
        owner[entry] = registered;
        this.level[entry] = level;
        key[entry] = cellKey;
        refs[entry] = 1;
        state[entry] = CellState.LOADING;
        cells.put(cellKey, entry);
        loading++;
        startLoad(entry, new Cell(layer, level, x, y, z));
    }

    /** Gives up one request for a cell: when none are left, it is detached, or its load dropped. */
    public void release(Layer<?> layer, int level, int x, int y, int z) {
        checkOpen();
        int entry = cells(registered(layer), level).get(CellGrid.key(x, y, z));
        if (entry < 0) {
            throw new IllegalStateException("Released more often than requested: " + layer + " level " + level
                + " cell " + x + "," + y + "," + z);
        }
        if (--refs[entry] == 0) {
            drop(entry);
        }
    }

    /** Where a cell stands. */
    public CellState state(Layer<?> layer, int level, int x, int y, int z) {
        int entry = cells(registered(layer), level).get(CellGrid.key(x, y, z));
        return entry < 0 ? CellState.ABSENT : state[entry];
    }

    /** A loaded cell's data, or {@code null} while it is not {@link CellState#LOADED}. */
    public <T> T data(Layer<T> layer, int level, int x, int y, int z) {
        int entry = cells(registered(layer), level).get(CellGrid.key(x, y, z));
        return entry < 0 ? null : layer.type().cast(data[entry]);
    }

    /** A loaded cell's content, or {@code null} while it is not {@link CellState#LOADED}. */
    public CellContent content(Layer<?> layer, int level, int x, int y, int z) {
        int entry = cells(registered(layer), level).get(CellGrid.key(x, y, z));
        return entry < 0 || state[entry] != CellState.LOADED ? null : content[entry];
    }

    /** Cells loading or waiting for the frame boundary. */
    public int loadingCount() {
        return loading;
    }

    /** Cells attached to the scene. */
    public int loadedCount() {
        return loaded;
    }

    /** Cells whose load failed and that are still requested. */
    public int failedCount() {
        return failed;
    }

    // ---------------------------------------------------------------- loading

    @AllowAllocation("starting a load")
    private void startLoad(int entry, Cell cell) {
        Registered registered = owner[entry];
        int expected = generation[entry];
        content[entry].reset(cell);
        // A flag instead of cancelling: cancelling interrupts a running load (which can close its file channels) and
        // drops its result unseen; a load no longer wanted when it starts skips, one finished is discarded.
        AtomicBoolean stillWanted = new AtomicBoolean(true);
        wanted[entry] = stillWanted;
        registered.lane.task(() -> stillWanted.get() ? registered.loader.load(cell) : null)
            .deliver(done -> delivered(entry, expected, registered, done))
            .submit();
    }

    /** A load finished, failed or was cancelled; runs on the main thread when the scheduler is drained. */
    private void delivered(int entry, int expected, Registered registered, Task<?> done) {
        if (generation[entry] != expected) {
            // Released while loading; the slot may hold another cell by now. Hand finished data back.
            if (done.state() == TaskState.SUCCEEDED && done.result() != null) {
                registered.loader.discard(done.result());
            }
            return;
        }
        wanted[entry] = null;
        loading--;
        if (done.state() == TaskState.SUCCEEDED) {
            data[entry] = done.result();
            state[entry] = CellState.LOADED;
            loaded++;
            owner[entry].loader.attach(data[entry], content[entry]);
        } else {
            state[entry] = CellState.FAILED;
            failed++;
            fail(entry, done.failure());
        }
    }

    @AllowAllocation("logging a failure")
    private void fail(int entry, Throwable failure) {
        LOG.warn("Could not load {} level {} cell {},{},{}", owner[entry].layer, level[entry],
            CellGrid.keyX(key[entry]), CellGrid.keyY(key[entry]), CellGrid.keyZ(key[entry]), failure);
    }

    /** Detaches or cancels a cell nobody wants any more, and frees its entry. */
    private void drop(int entry) {
        Registered registered = owner[entry];
        switch (state[entry]) {
            case LOADING -> {
                loading--;
                wanted[entry].set(false); // a queued load skips, a running one is discarded when delivered
                wanted[entry] = null;
            }
            case LOADED -> {
                loaded--;
                registered.loader.detach(data[entry], content[entry]);
            }
            default -> failed--;
        }
        registered.cells[level[entry]].remove(key[entry]);
        generation[entry]++;
        owner[entry] = null;
        data[entry] = null;
        state[entry] = CellState.ABSENT;
        free[freeCount++] = entry;
    }

    @AllowAllocation("growing")
    private int newEntry() {
        if (freeCount > 0) {
            return free[--freeCount];
        }
        if (entryCount == owner.length) {
            int capacity = entryCount * 2;
            owner = Arrays.copyOf(owner, capacity);
            level = Arrays.copyOf(level, capacity);
            key = Arrays.copyOf(key, capacity);
            state = Arrays.copyOf(state, capacity);
            refs = Arrays.copyOf(refs, capacity);
            data = Arrays.copyOf(data, capacity);
            wanted = Arrays.copyOf(wanted, capacity);
            generation = Arrays.copyOf(generation, capacity);
            content = Arrays.copyOf(content, capacity);
            free = Arrays.copyOf(free, capacity);
        }
        content[entryCount] = new CellContent(scene, entryCount + 1); // group 0 is everything not streamed
        return entryCount++;
    }

    private Registered registered(Layer<?> layer) {
        Registered registered = layers.get(layer);
        if (registered == null) {
            throw new IllegalArgumentException(layer + " is not registered");
        }
        return registered;
    }

    private static LongIntMap cells(Registered registered, int level) {
        if (level < 0 || level >= registered.cells.length) {
            throw new IllegalArgumentException(registered.layer + " has no level " + level);
        }
        return registered.cells[level];
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The world is closed");
        }
    }

    /** Detaches every loaded cell and drops every load. The scene and the scheduler stay open. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        for (int entry = 0; entry < entryCount; entry++) {
            if (owner[entry] != null) {
                drop(entry);
            }
        }
        closed = true;
    }
}
