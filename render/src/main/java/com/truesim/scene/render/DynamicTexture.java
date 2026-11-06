package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * A texture whose texels change while it is used (core prerequisite 7.9: weather maps, painted maps, noise volumes, a
 * simulation's grid), as an asset ({@link RenderAssets#DYNAMIC_TEXTURE}): its texels ({@link #texels()}, a
 * {@link TextureData}, 2D or 3D) are written on the CPU, and each change names the box it touched ({@link #changed}),
 * which the renderer uploads within its frames, a budget of bytes a frame. Or it is written by GPU passes
 * ({@link #gpuWritten()}): the renderer then makes it a storage image as well, and the CPU's texels are its first
 * contents only.
 *
 * <pre>{@code
 * DynamicTexture wetness = DynamicTexture.of(new TextureData(256, 256, TextureData.ColorSpace.LINEAR, texels));
 * AssetRef<DynamicTexture> ref = AssetRef.generated(RenderAssets.DYNAMIC_TEXTURE, (key, seed) -> wetness, "wet", 1);
 * // each frame, after painting a 16 x 16 patch at (x, y):
 * wetness.changed(x, y, 0, 16, 16, 1);
 * }</pre>
 *
 * <p>Changes are kept in a ring of {@link #KEPT} boxes; a reader that falls further behind (a renderer that missed
 * frames) takes the whole texture again ({@link #change} says so). Readers keep their own place ({@link #changes}):
 * any number of renderers follow one texture. Write and mark changes on one thread, the one the renderer reads them
 * on. Marking a change allocates nothing.
 */
public final class DynamicTexture {

    /** Changed boxes kept for readers behind: more than a few frames' worth. */
    public static final int KEPT = 256;

    private final TextureData texels;
    private final boolean gpuWritten;
    private final int[] boxes = new int[6 * KEPT];
    private long changes;

    private DynamicTexture(TextureData texels, boolean gpuWritten) {
        this.texels = Objects.requireNonNull(texels, "texels");
        this.gpuWritten = gpuWritten;
    }

    /** A texture of {@code texels} that the CPU changes. */
    @AllowAllocation("made once")
    public static DynamicTexture of(TextureData texels) {
        return new DynamicTexture(texels, false);
    }

    /** A texture GPU passes write, starting as {@code texels}. */
    @AllowAllocation("made once")
    public static DynamicTexture gpuWritten(TextureData texels) {
        return new DynamicTexture(texels, true);
    }

    /** Its texels: write them, then say where ({@link #changed}). */
    public TextureData texels() {
        return texels;
    }

    /** Whether GPU passes write it: a storage image too. */
    public boolean gpuWritten() {
        return gpuWritten;
    }

    /**
     * The box at ({@code x}, {@code y}, {@code z}) of {@code width} x {@code height} x {@code depth} texels changed:
     * uploaded within the next frames. Allocates nothing.
     */
    public void changed(int x, int y, int z, int width, int height, int depth) {
        if (x < 0 || y < 0 || z < 0 || width < 1 || height < 1 || depth < 1 || x + width > texels.width()
            || y + height > texels.height() || z + depth > texels.depth()) {
            throw new IllegalArgumentException("A box outside the texture: " + x + ", " + y + ", " + z + " + " + width
                + " x " + height + " x " + depth);
        }
        int at = 6 * (int) (changes % KEPT);
        boxes[at] = x;
        boxes[at + 1] = y;
        boxes[at + 2] = z;
        boxes[at + 3] = width;
        boxes[at + 4] = height;
        boxes[at + 5] = depth;
        changes++;
    }

    /** All of it changed. */
    public void changedAll() {
        changed(0, 0, 0, texels.width(), texels.height(), texels.depth());
    }

    /** How many changes were marked so far: a reader's place is the count it has taken. */
    public long changes() {
        return changes;
    }

    /**
     * Change number {@code change} (0 for the first) into {@code box} (x, y, z, width, height, depth); false if it is
     * no longer kept (the reader fell more than {@link #KEPT} behind: it takes the whole texture again) or not made
     * yet. Allocates nothing.
     */
    public boolean change(long change, int[] box) {
        if (change < 0 || change >= changes || change < changes - KEPT) {
            return false;
        }
        System.arraycopy(boxes, 6 * (int) (change % KEPT), box, 0, 6);
        return true;
    }

    /** The memory it takes, for the asset cache's budget. */
    public long bytes() {
        return texels.bytes();
    }
}
