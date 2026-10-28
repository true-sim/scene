package com.truesim.scene.render;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * A texture as data: {@code width} x {@code height} texels of 8-bit RGBA, rows top to bottom. Colors (base color,
 * emission) are {@link ColorSpace#SRGB}; data (normals, occlusion-roughness-metalness) is {@link ColorSpace#LINEAR}.
 * The engine makes the mip chain.
 *
 * <pre>{@code
 * TextureData albedo = new TextureData(512, 512, TextureData.ColorSpace.SRGB, rgba);
 * }</pre>
 */
public record TextureData(int width, int height, ColorSpace colorSpace, MemorySegment texels) {

    /** How texel values are read. */
    public enum ColorSpace {
        /** Colors, stored sRGB-encoded. */
        SRGB,
        /** Data, read as it is. */
        LINEAR
    }

    public TextureData {
        Objects.requireNonNull(colorSpace, "colorSpace");
        Objects.requireNonNull(texels, "texels");
        if (width < 1 || height < 1 || texels.byteSize() != 4L * width * height) {
            throw new IllegalArgumentException("A " + width + " x " + height + " texture takes " + 4L * width * height
                + " bytes of RGBA: " + texels.byteSize());
        }
    }

    /** The memory the texels take, for the asset cache's budget. */
    public long bytes() {
        return texels.byteSize();
    }
}
