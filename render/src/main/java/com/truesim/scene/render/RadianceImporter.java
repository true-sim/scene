package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Imports Radiance HDR images ({@code .hdr}, RGBE: the format HDR environment panoramas come in) as linear half-float
 * textures ({@link TextureData.Format#RGBA16F}, alpha 1): register it for the extension
 * ({@code paths.importers().add(RenderAssets.TEXTURE, "hdr", RadianceImporter.INSTANCE)}). Reads flat and run-length
 * scanlines and both row orders; values beyond the half-float range are clamped to it. Files may come from mods, so
 * every size and run is checked before it is used.
 */
public final class RadianceImporter implements AssetLoader<TextureData> {

    /** The importer: it keeps nothing. */
    public static final RadianceImporter INSTANCE = new RadianceImporter();

    /** The largest image read: 32768 texels a side, 2^28 in all (2 GiB of half floats). */
    private static final int MAX_SIDE = 1 << 15;
    private static final long MAX_TEXELS = 1L << 28;
    private static final float MAX_HALF = 65504;
    private static final ValueLayout.OfShort HALF = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private RadianceImporter() {
    }

    @Override
    @AllowAllocation("importing images")
    public TextureData load(AssetInput input) throws IOException {
        try (InputStream in = input.open()) {
            return read(in, input.path() != null ? input.path().toString() : input.id().toString());
        }
    }

    /** Reads a Radiance HDR image from {@code in}; {@code what} names it in errors. */
    @AllowAllocation("importing images")
    public static TextureData read(InputStream in, String what) throws IOException {
        InputStream data = new BufferedInputStream(in);
        String magic = line(data, what);
        if (!magic.startsWith("#?RADIANCE") && !magic.startsWith("#?RGBE")) {
            throw new IOException("Not a Radiance HDR image: " + what);
        }
        for (String line = line(data, what); !line.isEmpty(); line = line(data, what)) {
            if (line.startsWith("FORMAT=") && !line.equals("FORMAT=32-bit_rle_rgbe")) {
                throw new IOException(what + ": RGBE only, not " + line.substring(7));
            }
        }
        // The resolution: "-Y height +X width" (rows top to bottom), or "+Y height +X width" (bottom to top).
        String[] size = line(data, what).trim().split("\\s+");
        if (size.length != 4 || !(size[0].equals("-Y") || size[0].equals("+Y")) || !size[2].equals("+X")) {
            throw new IOException(what + ": rows of +X texels, -Y or +Y, only: " + String.join(" ", size));
        }
        int height;
        int width;
        try {
            height = Integer.parseInt(size[1]);
            width = Integer.parseInt(size[3]);
        } catch (NumberFormatException e) {
            throw new IOException(what + ": a size of " + String.join(" ", size), e);
        }
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_TEXELS) {
            throw new IOException(what + ": " + width + " x " + height + " texels, not 1 to " + MAX_SIDE + " a side");
        }
        boolean bottomUp = size[0].equals("+Y");
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        byte[] rgbe = new byte[4 * width];
        for (int row = 0; row < height; row++) {
            scanline(data, rgbe, width, what);
            long base = 8L * width * (bottomUp ? height - 1 - row : row);
            for (int x = 0; x < width; x++) {
                int e = rgbe[4 * x + 3] & 0xff;
                float scale = e == 0 ? 0 : Math.scalb(1f, e - 136);
                for (int c = 0; c < 3; c++) {
                    float v = e == 0 ? 0 : Math.min(((rgbe[4 * x + c] & 0xff) + 0.5f) * scale, MAX_HALF);
                    texels.set(HALF, base + 8L * x + 2L * c, Float.floatToFloat16(v));
                }
                texels.set(HALF, base + 8L * x + 6, Float.floatToFloat16(1));
            }
        }
        return new TextureData(width, height, TextureData.ColorSpace.LINEAR, TextureData.Format.RGBA16F, texels);
    }

    /**
     * Reads one scanline of {@code width} texels into {@code rgbe}: run-length encoded per channel (starting 2, 2 and the
     * width), or flat, where the old run-length form repeats the texel before for (1, 1, 1, count).
     */
    private static void scanline(InputStream in, byte[] rgbe, int width, String what) throws IOException {
        int a = next(in, what);
        int b = next(in, what);
        int c = next(in, what);
        int d = next(in, what);
        if (width >= 8 && width < 32768 && a == 2 && b == 2 && (c & 0x80) == 0) {
            if ((c << 8 | d) != width) {
                throw new IOException(what + ": a scanline of " + (c << 8 | d) + " texels in a row of " + width);
            }
            for (int channel = 0; channel < 4; channel++) {
                int x = 0;
                while (x < width) {
                    int count = next(in, what);
                    if (count > 128) { // a run of one value
                        count -= 128;
                        if (x + count > width) {
                            throw new IOException(what + ": a run past the scanline's end");
                        }
                        byte value = (byte) next(in, what);
                        for (int i = 0; i < count; i++) {
                            rgbe[4 * x++ + channel] = value;
                        }
                    } else { // count literal values
                        if (count == 0 || x + count > width) {
                            throw new IOException(what + ": a run of " + count + " values at " + x + " of " + width);
                        }
                        for (int i = 0; i < count; i++) {
                            rgbe[4 * x++ + channel] = (byte) next(in, what);
                        }
                    }
                }
            }
            return;
        }
        int x = 0;
        int shift = 0;
        while (true) {
            if (a == 1 && b == 1 && c == 1) { // the old form: repeat the texel before
                if (x == 0) {
                    throw new IOException(what + ": a repeat with no texel before it");
                }
                int count = d << shift;
                if (x + count > width) {
                    throw new IOException(what + ": a repeat past the scanline's end");
                }
                for (int i = 0; i < count; i++, x++) {
                    System.arraycopy(rgbe, 4 * (x - 1), rgbe, 4 * x, 4);
                }
                shift += 8;
            } else {
                rgbe[4 * x] = (byte) a;
                rgbe[4 * x + 1] = (byte) b;
                rgbe[4 * x + 2] = (byte) c;
                rgbe[4 * x + 3] = (byte) d;
                x++;
                shift = 0;
            }
            if (x == width) {
                return;
            }
            a = next(in, what);
            b = next(in, what);
            c = next(in, what);
            d = next(in, what);
        }
    }

    private static int next(InputStream in, String what) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException(what + ": the image ends early");
        }
        return b;
    }

    /** A header line, without its newline; at most 4096 bytes. */
    private static String line(InputStream in, String what) throws IOException {
        byte[] bytes = new byte[4096];
        int n = 0;
        for (int b = next(in, what); b != '\n'; b = next(in, what)) {
            if (n == bytes.length) {
                throw new IOException(what + ": a header line longer than 4096 bytes");
            }
            bytes[n++] = (byte) b;
        }
        return new String(bytes, 0, n, StandardCharsets.US_ASCII);
    }
}
