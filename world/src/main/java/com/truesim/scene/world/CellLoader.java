package com.truesim.scene.world;

/**
 * Loads a {@link Layer}'s cells and turns them into scene content, in two steps:
 *
 * <ol>
 *   <li>{@link #load} runs on the layer's lane, in the background: read files, fetch, decode or generate. It must not
 *       touch the scene.</li>
 *   <li>{@link #attach} runs on the main thread at the frame boundary ({@code TaskScheduler.drainCompleted()}): create
 *       nodes through the {@link CellContent}, which remembers them, so unloading removes exactly them.</li>
 * </ol>
 *
 * <pre>{@code
 * world.register(PROPS, new CellLoader<>() {
 *     public PropCell load(Cell cell) throws IOException {
 *         return PropCell.read(archive, cell.level(), cell.x(), cell.y(), cell.z());
 *     }
 *
 *     public void attach(PropCell props, CellContent content) {
 *         for (Prop prop : props.props()) {
 *             long node = content.create();
 *             content.scene().setLocal(node, prop.transform());
 *         }
 *     }
 * });
 * }</pre>
 *
 * @param <T> what a loaded cell holds
 */
public interface CellLoader<T> {

    /** Loads a cell's data, in the background. Exceptions mark the cell failed. */
    T load(Cell cell) throws Exception;

    /** Creates the cell's scene content from its data, on the main thread. */
    void attach(T data, CellContent content);

    /**
     * Removes the cell's scene content, on the main thread. By default removes every node created through
     * {@code content}; override to release other resources too, and call {@link CellContent#removeAll()}.
     */
    default void detach(T data, CellContent content) {
        content.removeAll();
    }

    /**
     * Data that was loaded but is no longer wanted, e.g. released while loading, and was never attached: release what
     * it holds. Does nothing by default.
     */
    default void discard(T data) {
    }
}
