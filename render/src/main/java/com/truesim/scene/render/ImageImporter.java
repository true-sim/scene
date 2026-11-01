package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import com.truesim.scene.assets.AssetPath;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import javax.imageio.ImageIO;

/**
 * Imports PNG, JPEG and WebP images as textures, through ImageIO (WebP by the TwelveMonkeys plugin): register it for the
 * extensions it reads
 * ({@code paths.importers().add(RenderAssets.TEXTURE, "png", ImageImporter.INSTANCE)}). Images are colors (sRGB)
 * unless the path's fragment says {@code linear} ({@code rock_normal.png#linear}), as data textures are read; glTF
 * materials name their data textures that way. Texels are 8-bit RGBA, rows top to bottom, in memory the garbage
 * collector frees.
 */
public final class ImageImporter implements AssetLoader<TextureData> {

    /** The importer: it keeps nothing. */
    public static final ImageImporter INSTANCE = new ImageImporter();

    /** The fragment asking for linear texels: data, not colors. */
    public static final String LINEAR = "linear";

    private ImageImporter() {
    }

    @Override
    @AllowAllocation("importing images")
    public TextureData load(AssetInput input) throws IOException {
        try (InputStream in = input.open()) {
            return read(in, colorSpace(input.path()), describe(input));
        }
    }

    /**
     * Reads a PNG or JPEG image from {@code in} as a texture in {@code colorSpace}: for importers of formats holding
     * images (a glTF file's embedded ones). {@code what} names it in errors.
     */
    @AllowAllocation("importing images")
    public static TextureData read(InputStream in, TextureData.ColorSpace colorSpace, String what) throws IOException {
        BufferedImage image = ImageIO.read(in);
        if (image == null) {
            throw new IOException("Not an image the JDK reads: " + what);
        }
        return texture(image, colorSpace);
    }

    private static String describe(AssetInput input) {
        return input.path() != null ? input.path().toString() : input.id().toString();
    }

    /** The color space {@code path}'s fragment asks for; sRGB without a path. */
    static TextureData.ColorSpace colorSpace(AssetPath path) {
        return path != null && LINEAR.equals(path.fragment()) ? TextureData.ColorSpace.LINEAR
            : TextureData.ColorSpace.SRGB;
    }

    /** {@code image}'s texels as RGBA texture data in {@code colorSpace}. */
    @AllowAllocation("importing images")
    static TextureData texture(BufferedImage image, TextureData.ColorSpace colorSpace) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        MemorySegment texels = Arena.ofAuto().allocate(4L * argb.length);
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int rgba = (pixel << 8) | (pixel >>> 24); // ARGB to RGBA, read as bytes R, G, B, A
            texels.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN), 4L * i, rgba);
        }
        return new TextureData(width, height, colorSpace, texels);
    }
}
