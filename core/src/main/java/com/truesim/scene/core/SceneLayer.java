package com.truesim.scene.core;

/**
 * A named layer, for filtering what renders, collides or is found by queries. A node is on any of 32 layers at once
 * ({@link SceneGraph#setLayers}); declare the game's layers as constants instead of passing bare bits:
 *
 * <pre>{@code
 * static final SceneLayer WORLD = SceneLayer.of("world", 0);
 * static final SceneLayer UI = SceneLayer.of("ui", 1);
 * static final SceneLayer TRIGGERS = SceneLayer.of("triggers", 2);
 *
 * scene.setLayers(node, WORLD.mask() | TRIGGERS.mask());
 * }</pre>
 *
 * @param name for diagnostics
 * @param bit 0 to 31
 */
public record SceneLayer(String name, int bit) {

    /** The layer every node starts on. */
    public static final SceneLayer DEFAULT = new SceneLayer("default", 0);

    public SceneLayer {
        if (bit < 0 || bit >= Integer.SIZE) {
            throw new IllegalArgumentException("A layer bit is 0 to 31: " + bit);
        }
    }

    public static SceneLayer of(String name, int bit) {
        return new SceneLayer(name, bit);
    }

    /** This layer's bit in a layer mask. */
    public int mask() {
        return 1 << bit;
    }

    /** Whether {@code mask} includes this layer. */
    public boolean in(int mask) {
        return (mask & mask()) != 0;
    }
}
