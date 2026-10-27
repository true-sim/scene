package com.truesim.scene.world;

import com.truesim.common.jobs.LaneSpec;

/**
 * A kind of data the world streams by cell, e.g. terrain, props or collision: a typed key with its {@link Levels} and
 * the lane its cells load on. Declare layers as constants and register a {@link CellLoader} for each with
 * {@link World#register}:
 *
 * <pre>{@code
 * static final Layer<TerrainTile> TERRAIN = Layer.of("terrain", TerrainTile.class, Levels.grid(64, 6));
 * static final Layer<PropCell> PROPS = Layer.of("props", PropCell.class, Levels.hlod(32, 4));
 * }</pre>
 *
 * <p>Layers are compared by identity, like lane specs: the constant is the key, its name only labels it.
 *
 * @param <T> what a loaded cell of this layer holds
 */
public final class Layer<T> {

    /** The lane layers load on unless they name their own: virtual threads, 16 loads at once. */
    public static final LaneSpec LOADING = LaneSpec.virtual("scene-loading", 16);

    private final String name;
    private final Class<T> type;
    private final Levels levels;
    private final LaneSpec lane;

    private Layer(String name, Class<T> type, Levels levels, LaneSpec lane) {
        this.name = name;
        this.type = type;
        this.levels = levels;
        this.lane = lane;
    }

    /** A layer loading on {@link #LOADING}. */
    public static <T> Layer<T> of(String name, Class<T> type, Levels levels) {
        return new Layer<>(name, type, levels, LOADING);
    }

    /** A layer loading on its own lane, e.g. a serial one for an archive read in order. */
    public static <T> Layer<T> of(String name, Class<T> type, Levels levels, LaneSpec lane) {
        return new Layer<>(name, type, levels, lane);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    public Levels levels() {
        return levels;
    }

    public LaneSpec lane() {
        return lane;
    }

    @Override
    public String toString() {
        return "layer " + name;
    }
}
