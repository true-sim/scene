package com.truesim.scene.render;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * A texture as data: {@code width} x {@code height} texels of RGBA, rows top to bottom, as 8-bit values or as half
 * floats ({@link Format}). Colors (base color, emission) are {@link ColorSpace#SRGB}; data (normals,
 * occlusion-roughness-metalness) and light (HDR images, as environments) are {@link ColorSpace#LINEAR}. The engine
 * makes the mip chain. With a {@code depth} over 1 it is a 3D texture (a volume: noise, a vector field, a density
 * grid): {@code depth} slices of {@code width} x {@code height} texels one after the other, sampled in 3D, without a
 * mip chain.
 *
 * <pre>{@code
 * TextureData albedo = new TextureData(512, 512, TextureData.ColorSpace.SRGB, rgba);
 * TextureData sky = new TextureData(2048, 1024, TextureData.ColorSpace.LINEAR, TextureData.Format.RGBA16F, halves);
 * TextureData noise = new TextureData(64, 64, TextureData.ColorSpace.LINEAR, TextureData.Format.RGBA8, texels, 64);
 * }</pre>
 */
public record TextureData(int width, int height, ColorSpace colorSpace, Format format, MemorySegment texels,
    int depth) {

    /** How texel values are read. */
    public enum ColorSpace {
        /** Colors, stored sRGB-encoded. */
        SRGB,
        /** Data, or light, read as it is. */
        LINEAR
    }

    /** How a texel is stored. */
    public enum Format {
        /** 4 bytes: red, green, blue, alpha, 0 to 255. */
        RGBA8(4),
        /** 8 bytes: red, green, blue, alpha as IEEE half floats, little-endian: light beyond 1 (HDR). */
        RGBA16F(8);

        private final int bytes;

        Format(int bytes) {
            this.bytes = bytes;
        }

        /** The bytes a texel takes. */
        public int bytes() {
            return bytes;
        }
    }

    public TextureData {
        Objects.requireNonNull(colorSpace, "colorSpace");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(texels, "texels");
        if (format == Format.RGBA16F && colorSpace == ColorSpace.SRGB) {
            throw new IllegalArgumentException("Half-float texels are linear, not sRGB");
        }
        long bytes = (long) format.bytes() * width * height * depth;
        if (depth > 1 && (width < 1 || height < 1 || texels.byteSize() != bytes)) {
            throw new IllegalArgumentException("A " + width + " x " + height + " x " + depth + " texture takes " + bytes
                + " bytes of " + format + ": " + texels.byteSize());
        }
        if (width < 1 || height < 1 || depth < 1 || texels.byteSize() != bytes) {
            throw new IllegalArgumentException("A " + width + " x " + height + " texture takes " + bytes
                + " bytes of " + format + ": " + texels.byteSize());
        }
    }

    /** A 2D texture: {@code width} x {@code height} texels of {@code format}. */
    public TextureData(int width, int height, ColorSpace colorSpace, Format format, MemorySegment texels) {
        this(width, height, colorSpace, format, texels, 1);
    }

    /** {@code width} x {@code height} texels of 8-bit RGBA. */
    public TextureData(int width, int height, ColorSpace colorSpace, MemorySegment texels) {
        this(width, height, colorSpace, Format.RGBA8, texels);
    }

    /** Whether it is a 3D texture: more than one slice. */
    public boolean volume() {
        return depth > 1;
    }

    /** The memory the texels take, for the asset cache's budget. */
    public long bytes() {
        return texels.byteSize();
    }
}
