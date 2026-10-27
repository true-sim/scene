package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.Lane;
import com.truesim.common.jobs.Task;
import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.jobs.TaskState;
import com.truesim.scene.core.CellGrid;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
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
 * <p>Finished loads attach when the scheduler is drained, within the attach budget ({@link #setAttachBudget}); cells
 * waiting for the budget attach in later frames.
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
    private Priority[] priority = new Priority[16];
    private boolean[] ready = new boolean[16];
    private int[] generation = new int[16];
    private CellContent[] content = new CellContent[16];
    private int[] free = new int[16];
    private int freeCount;
    private int entryCount;

    private int loading;
    private int loaded;
    private int failed;
    private boolean closed;
    private final List<InterestSource> sources = new ArrayList<>();
    private double hysteresis = 0.15;
    private int attachBudget = Integer.MAX_VALUE;
    private int attachedThisFrame;
    private int[] readyQueue = new int[16];
    private int readyCount;

    /** A world filling {@code scene} with cells loaded on {@code tasks}' lanes. */
    @AllowAllocation("set up once")
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
        request(layer, level, x, y, z, Priority.NORMAL);
    }

    /**
     * Asks for a cell with a priority: higher priority cells load and attach first. Interest sources request with
     * their own priority; a cell keeps the highest it was asked with.
     */
    public void request(Layer<?> layer, int level, int x, int y, int z, Priority urgency) {
        checkOpen();
        Registered registered = registered(layer);
        long cellKey = CellGrid.key(x, y, z);
        LongIntMap cells = cells(registered, level);
        int entry = cells.get(cellKey);
        if (entry >= 0) {
            refs[entry]++;
            if (urgency.compareTo(priority[entry]) > 0) {
                priority[entry] = urgency;
            }
            return;
        }
        entry = newEntry();
        owner[entry] = registered;
        this.level[entry] = level;
        key[entry] = cellKey;
        refs[entry] = 1;
        priority[entry] = urgency;
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

    // ---------------------------------------------------------------- interest

    /**
     * Adds an observer that wants what {@code profile} says around its position; move it with
     * {@link InterestSource#moveTo} and close it when the observer goes. Its cells load on the next {@link #update()}.
     */
    @AllowAllocation("adding an observer")
    public InterestSource addInterest(InterestProfile profile, Priority priority) {
        checkOpen();
        for (InterestProfile.Entry entry : profile.entries()) {
            registered(entry.layer());
        }
        InterestSource source = new InterestSource(this, profile, priority);
        sources.add(source);
        return source;
    }

    /** The open interest sources. */
    public int interestCount() {
        return sources.size();
    }

    /**
     * Brings the cells in line with the interest sources: for every source that moved, requests the cells it now
     * needs and releases the ones it no longer needs. Requests are counted, so cells several sources share load once
     * and stay until the last lets go. Call once per frame on the main thread, e.g. before draining the scheduler.
     */
    public void update() {
        checkOpen();
        attachedThisFrame = 0;
        attachWaiting();
        for (int i = 0; i < sources.size(); i++) {
            InterestSource source = sources.get(i);
            if (source.moved) {
                source.moved = false;
                refresh(source);
            }
        }
        for (int i = 0; i < sources.size(); i++) {
            releaseReplaced(sources.get(i));
        }
    }

    /** Recomputes a source's cells for each layer of its profile and applies the difference. */
    private void refresh(InterestSource source) {
        selecting = source;
        double grace = 1 + hysteresis;
        for (int i = 0; i < source.wanted.length; i++) {
            InterestProfile.Entry entry = source.profile().entries().get(i);
            LongIntMap[] next = source.next[i];
            for (LongIntMap level : next) {
                level.clear();
            }
            selectingLevels = entry.layer().levels();
            selectingDetail = entry.detail();
            selectingInto = next;
            selectingPrevious = source.wanted[i];
            if (entry.detail().isFull()) {
                selectingLevel = 0;
                selectingReach = entry.range().radius();
                forEachCellInReach(0, selectingReach * grace, addFull);
            } else if (entry.detail().isScreenError()) {
                int coarsest = entry.layer().levels().count() - 1;
                selectingLevel = coarsest;
                selectingReach = entry.range().radius();
                forEachCellInReach(coarsest, selectingReach * grace, addBanded);
            } else {
                int coarsest = entry.detail().bandCount() - 1;
                selectingLevel = coarsest;
                selectingReach = entry.detail().band(coarsest);
                forEachCellInReach(coarsest, selectingReach * grace, addBanded);
            }
            LongIntMap[] wanted = source.wanted[i];
            LongIntMap[] retained = source.retained[i];
            boolean holdUntilReplaced = !entry.detail().isFull();
            for (int level = 0; level < next.length; level++) {
                requestNew(entry.layer(), level, next[level], wanted[level], retained[level]);
                dropGone(entry.layer(), level, wanted[level], next[level], retained[level], holdUntilReplaced);
            }
            source.wanted[i] = next;
            source.next[i] = wanted;
        }
        selecting = null;
        selectingInto = null;
        selectingPrevious = null;
    }

    /** Requests the cells newly wanted; one still held from before is wanted again without a second request. */
    private void requestNew(Layer<?> layer, int level, LongIntMap now, LongIntMap before, LongIntMap retained) {
        for (int slot = 0; slot < now.capacity(); slot++) {
            long cell = now.keyAt(slot);
            if (cell >= 0 && before.get(cell) < 0) {
                if (retained.get(cell) >= 0) {
                    retained.remove(cell);
                } else {
                    request(layer, level, CellGrid.keyX(cell), CellGrid.keyY(cell), CellGrid.keyZ(cell));
                }
            }
        }
    }

    /**
     * Lets go of the cells no longer wanted: at once for full detail, while band cells are held until the cells
     * replacing them in their area have loaded, so a level swap never leaves a hole.
     */
    private void dropGone(Layer<?> layer, int level, LongIntMap before, LongIntMap now, LongIntMap retained,
        boolean holdUntilReplaced) {
        for (int slot = 0; slot < before.capacity(); slot++) {
            long cell = before.keyAt(slot);
            if (cell >= 0 && now.get(cell) < 0) {
                if (holdUntilReplaced) {
                    retained.put(cell, 1);
                } else {
                    release(layer, level, CellGrid.keyX(cell), CellGrid.keyY(cell), CellGrid.keyZ(cell));
                }
            }
        }
    }

    /** Releases the held cells of every source whose replacements have all loaded (or failed). */
    private void releaseReplaced(InterestSource source) {
        for (int i = 0; i < source.retained.length; i++) {
            Layer<?> layer = source.profile().entries().get(i).layer();
            LongIntMap[] retained = source.retained[i];
            for (int level = 0; level < retained.length; level++) {
                LongIntMap held = retained[level];
                int slot = 0;
                while (slot < held.capacity()) {
                    long cell = held.keyAt(slot);
                    if (cell >= 0 && replaced(layer, source.wanted[i], level, cell)) {
                        release(layer, level, CellGrid.keyX(cell), CellGrid.keyY(cell), CellGrid.keyZ(cell));
                        held.remove(cell); // a later entry may shift into this slot: look at it again
                    } else {
                        slot++;
                    }
                }
            }
        }
    }

    /**
     * Whether every wanted cell overlapping a held one has loaded or failed (true when none overlaps). Looks only where
     * an overlap can be: the coarser cells containing it, and the finer cells inside it, descending only below cells
     * that are not wanted themselves, since the wanted cells cover the area once.
     */
    private boolean replaced(Layer<?> layer, LongIntMap[] wanted, int level, long cell) {
        Levels levels = layer.levels();
        int x = CellGrid.keyX(cell);
        int y = CellGrid.keyY(cell);
        int z = CellGrid.keyZ(cell);
        for (int coarser = level + 1; coarser < wanted.length; coarser++) {
            long factor = levels.scale(coarser) / levels.scale(level);
            if (loading(layer, wanted, coarser, floorDiv(x, factor), floorDiv(y, factor), floorDiv(z, factor))) {
                return false;
            }
        }
        return finerLoaded(layer, wanted, level, x, y, z);
    }

    /** Whether the wanted cells inside cell (x, y, z) of {@code level}, at finer levels, have all loaded or failed. */
    private boolean finerLoaded(Layer<?> layer, LongIntMap[] wanted, int level, int x, int y, int z) {
        if (level == 0) {
            return true;
        }
        boolean volumetric = layer.levels().isVolumetric();
        int r = layer.levels().ratio(level);
        int y0 = volumetric ? r * y : 0;
        int y1 = volumetric ? r * y + r - 1 : 0;
        for (int cy = y0; cy <= y1; cy++) {
            for (int cz = r * z; cz < r * z + r; cz++) {
                for (int cx = r * x; cx < r * x + r; cx++) {
                    if (wanted[level - 1].get(CellGrid.key(cx, cy, cz)) >= 0) {
                        if (loading(layer, wanted, level - 1, cx, cy, cz)) {
                            return false;
                        }
                    } else if (!finerLoaded(layer, wanted, level - 1, cx, cy, cz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** {@code value / divisor}, rounded down (towards negative infinity). */
    private static int floorDiv(int value, long divisor) {
        long quotient = value / divisor;
        return (int) (value < 0 && quotient * divisor != value ? quotient - 1 : quotient);
    }

    /** Whether a cell is wanted and still loading. */
    private boolean loading(Layer<?> layer, LongIntMap[] wanted, int level, int x, int y, int z) {
        return wanted[level].get(CellGrid.key(x, y, z)) >= 0 && state(layer, level, x, y, z) == CellState.LOADING;
    }

    /** Whether everything {@code source} wants has loaded (or failed), e.g. to end a loading screen. */
    public boolean isReady(InterestSource source) {
        if (source.moved) {
            return false;
        }
        for (int i = 0; i < source.wanted.length; i++) {
            Layer<?> layer = source.profile().entries().get(i).layer();
            LongIntMap[] wanted = source.wanted[i];
            for (int level = 0; level < wanted.length; level++) {
                LongIntMap cells = wanted[level];
                for (int slot = 0; slot < cells.capacity(); slot++) {
                    long key = cells.keyAt(slot);
                    if (key >= 0 && state(layer, level, CellGrid.keyX(key), CellGrid.keyY(key), CellGrid.keyZ(key))
                        == CellState.LOADING) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Attaches at most {@code cells} loaded cells per frame (a frame runs from one {@link #update()} to the next),
     * so a burst of finished loads, e.g. after a teleport, spreads over frames instead of stalling one. Cells that wait
     * attach highest priority first, then coarsest. Unlimited by default.
     */
    public void setAttachBudget(int cells) {
        if (cells < 1) {
            throw new IllegalArgumentException("The attach budget is at least one cell: " + cells);
        }
        this.attachBudget = cells;
    }

    /** Loaded cells waiting for room in the attach budget. */
    public int waitingCount() {
        return readyCount;
    }

    /**
     * How far beyond a band or range a cell already held is kept (0.15: 15 %), so a source moving back and forth near
     * a boundary does not load and unload the same cells over and over. A cell switches to finer detail at the band
     * distance, but back to coarser only this much further out.
     */
    public void setHysteresis(double fraction) {
        if (!(fraction >= 0) || fraction > 1) {
            throw new IllegalArgumentException("Hysteresis is a fraction from 0 to 1: " + fraction);
        }
        this.hysteresis = fraction;
    }

    // The selection running in refresh(): fields, so the visitors below are made once.
    private InterestSource selecting;
    private Levels selectingLevels;
    private Detail selectingDetail;
    private LongIntMap[] selectingInto;
    private LongIntMap[] selectingPrevious;
    private int selectingLevel;
    private double selectingReach;
    private final CellGrid.CellVisitor addFull = (x, y, z) -> {
        double distance = distanceSquared(selectingLevel, x, y, z);
        long key = CellGrid.key(x, y, z);
        if (distance <= square(selectingReach)
            || distance <= square(selectingReach * (1 + hysteresis)) && selectingPrevious[0].get(key) >= 0) {
            selectingInto[0].put(key, 1);
        }
    };
    private final CellGrid.CellVisitor addBanded = (x, y, z) -> {
        double distance = distanceSquared(selectingLevel, x, y, z);
        if (distance <= square(selectingReach)
            || distance <= square(selectingReach * (1 + hysteresis)) && held(selectingLevel, x, y, z)) {
            refine(selectingLevel, x, y, z);
        }
    };

    /** Visits the cells of {@code level} whose box may lie within {@code reach} of the selecting source. */
    private void forEachCellInReach(int level, double reach, CellGrid.CellVisitor visitor) {
        InterestSource s = selecting;
        boolean volumetric = selectingLevels.isVolumetric();
        selectingLevels.grid(level).forEachCell(s.x - reach, volumetric ? s.y - reach : 0, s.z - reach, s.x + reach,
            volumetric ? s.y + reach : 0, s.z + reach, visitor);
    }

    /**
     * Takes a cell, or, if the source is closer to it than the next finer band, its finer cells instead, each refined
     * the same way: the result covers the cell's area once, finest where nearest. A cell already split stays split up
     * to the hysteresis beyond the band.
     */
    private void refine(int level, int x, int y, int z) {
        long key = CellGrid.key(x, y, z);
        if (level > 0) {
            // Split nearer than the band; within the hysteresis beyond it only if it was split before (the history is
            // looked up only there, as it is the costly part).
            double distance = distanceSquared(level, x, y, z);
            double band = band(level - 1);
            if (distance < square(band) || distance < square(band * (1 + hysteresis))
                && selectingPrevious[level].get(key) < 0 && heldBelow(level, x, y, z)) {
                boolean volumetric = selectingLevels.isVolumetric();
                int r = selectingLevels.ratio(level);
                int y0 = volumetric ? r * y : 0;
                int y1 = volumetric ? r * y + r - 1 : 0;
                for (int cy = y0; cy <= y1; cy++) {
                    for (int cz = r * z; cz < r * z + r; cz++) {
                        for (int cx = r * x; cx < r * x + r; cx++) {
                            refine(level - 1, cx, cy, cz);
                        }
                    }
                }
                return;
            }
        }
        selectingInto[level].put(key, 1);
    }

    /**
     * The distance within which cells of level {@code finer} replace the next coarser ones: the profile's band, or for
     * screen-space error the distance at which a coarser cell would look as big as the allowed error.
     */
    private double band(int finer) {
        if (selectingDetail.isScreenError()) {
            return selectingLevels.grid(finer + 1).size() * selectingDetail.errorPerMeter() * selecting.projection
                / selectingDetail.pixels();
        }
        return selectingDetail.band(finer);
    }

    /** Whether the source held this cell, or finer cells within it, before. */
    private boolean held(int level, int x, int y, int z) {
        return selectingPrevious[level].get(CellGrid.key(x, y, z)) >= 0 || heldBelow(level, x, y, z);
    }

    /** Whether the source held finer cells within this cell before. */
    private boolean heldBelow(int level, int x, int y, int z) {
        if (level == 0) {
            return false;
        }
        boolean volumetric = selectingLevels.isVolumetric();
        int r = selectingLevels.ratio(level);
        int y0 = volumetric ? r * y : 0;
        int y1 = volumetric ? r * y + r - 1 : 0;
        for (int cy = y0; cy <= y1; cy++) {
            for (int cz = r * z; cz < r * z + r; cz++) {
                for (int cx = r * x; cx < r * x + r; cx++) {
                    if (held(level - 1, cx, cy, cz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The squared distance from the selecting source to a cell's box; flat levels ignore height. */
    private double distanceSquared(int level, int x, int y, int z) {
        CellGrid grid = selectingLevels.grid(level);
        double size = grid.size();
        InterestSource s = selecting;
        double dx = axisDistance(s.x, grid.cellMin(x), size);
        double dy = selectingLevels.isVolumetric() ? axisDistance(s.y, grid.cellMin(y), size) : 0;
        double dz = axisDistance(s.z, grid.cellMin(z), size);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double square(double value) {
        return value * value;
    }

    private static double axisDistance(double position, double min, double size) {
        return position < min ? min - position : Math.max(0, position - (min + size));
    }

    /** A source closed (always before the world: closing the world closes its sources): its cells are released. */
    void closed(InterestSource source) {
        for (int i = 0; i < source.wanted.length; i++) {
            Layer<?> layer = source.profile().entries().get(i).layer();
            for (int level = 0; level < source.wanted[i].length; level++) {
                releaseAll(layer, level, source.wanted[i][level]);
                releaseAll(layer, level, source.retained[i][level]);
            }
        }
        sources.remove(source);
    }

    private void releaseAll(Layer<?> layer, int level, LongIntMap cells) {
        for (int slot = 0; slot < cells.capacity(); slot++) {
            long cell = cells.keyAt(slot);
            if (cell >= 0) {
                release(layer, level, CellGrid.keyX(cell), CellGrid.keyY(cell), CellGrid.keyZ(cell));
            }
        }
        cells.clear();
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
            .priority(priority[entry].ordinal() * Levels.MAX_LEVELS + level[entry]) // urgent first, then coarse
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
        if (done.state() == TaskState.SUCCEEDED) {
            data[entry] = done.result();
            if (readyCount == 0 && attachedThisFrame < attachBudget) {
                attach(entry);
            } else {
                enqueue(entry);
            }
        } else {
            loading--;
            state[entry] = CellState.FAILED;
            failed++;
            fail(entry, done.failure());
        }
    }

    /** Attaches a loaded cell to the scene. */
    private void attach(int entry) {
        attachedThisFrame++;
        loading--;
        state[entry] = CellState.LOADED;
        loaded++;
        owner[entry].loader.attach(data[entry], content[entry]);
    }

    /** Keeps a loaded cell waiting for a frame with room in the attach budget. */
    @AllowAllocation("growing")
    private void enqueue(int entry) {
        if (readyCount == readyQueue.length) {
            readyQueue = Arrays.copyOf(readyQueue, readyCount * 2);
        }
        ready[entry] = true;
        readyQueue[readyCount++] = entry;
    }

    /**
     * Attaches waiting cells up to the budget: highest priority first, then coarsest (it covers the most), then the
     * longest waiting.
     */
    private void attachWaiting() {
        while (readyCount > 0 && attachedThisFrame < attachBudget) {
            int best = -1;
            for (int i = 0; i < readyCount; i++) {
                int entry = readyQueue[i];
                if (best < 0 || better(entry, readyQueue[best])) {
                    best = i;
                }
            }
            int entry = readyQueue[best];
            readyCount--;
            System.arraycopy(readyQueue, best + 1, readyQueue, best, readyCount - best);
            ready[entry] = false;
            attach(entry);
        }
    }

    private boolean better(int entry, int than) {
        int byPriority = priority[entry].compareTo(priority[than]);
        return byPriority != 0 ? byPriority > 0 : level[entry] > level[than];
    }

    /** Takes a dropped cell out of the waiting queue. */
    private void unqueue(int entry) {
        int i = 0;
        while (readyQueue[i] != entry) { // it is there: ready[entry] says so
            i++;
        }
        readyCount--;
        System.arraycopy(readyQueue, i + 1, readyQueue, i, readyCount - i);
        ready[entry] = false;
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
                if (ready[entry]) { // loaded, waiting for the attach budget
                    unqueue(entry);
                    registered.loader.discard(data[entry]);
                } else {
                    wanted[entry].set(false); // a queued load skips, a running one is discarded when delivered
                    wanted[entry] = null;
                }
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
            priority = Arrays.copyOf(priority, capacity);
            ready = Arrays.copyOf(ready, capacity);
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
        for (int i = sources.size() - 1; i >= 0; i--) {
            sources.get(i).close();
        }
        for (int entry = 0; entry < entryCount; entry++) {
            if (owner[entry] != null) {
                drop(entry);
            }
        }
        closed = true;
    }
}
