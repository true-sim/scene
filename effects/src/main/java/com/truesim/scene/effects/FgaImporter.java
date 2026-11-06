package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Imports vector fields in the FGA format ({@code .fga}, the text format vector field tools export) as
 * {@link VectorFieldData}: register it for the extension
 * ({@code paths.importers().add(EffectAssets.VECTOR_FIELD, "fga", FgaImporter.INSTANCE)}).
 *
 * <p>An FGA file is numbers separated by commas (and any white space): the cells along x, y and z, the box's minimum
 * and maximum corners, then three numbers per cell, x fastest, then y, then z. Files may come from mods, so the sizes
 * are checked before anything is allocated, and a file with too few or too many numbers is refused.
 */
public final class FgaImporter implements AssetLoader<VectorFieldData> {

    /** The importer: it keeps nothing. */
    public static final FgaImporter INSTANCE = new FgaImporter();

    /** The most cells a side: 1024. */
    public static final int MAX_SIDE = 1024;
    /** The most cells in all: 2^24 (192 MiB of floats). */
    public static final long MAX_CELLS = 1L << 24;

    private FgaImporter() {
    }

    @Override
    @AllowAllocation("importing vector fields")
    public VectorFieldData load(AssetInput input) throws IOException {
        try (InputStream in = input.open()) {
            return read(in, input.path() != null ? input.path().toString() : input.id().toString());
        }
    }

    /** Reads an FGA vector field from {@code in}; {@code what} names it in errors. */
    @AllowAllocation("importing vector fields")
    public static VectorFieldData read(InputStream in, String what) throws IOException {
        String[] numbers = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim().split("[\\s,]+");
        if (numbers.length < 9) {
            throw new IOException(what + ": an FGA file starts with its size and box, 9 numbers: " + numbers.length);
        }
        int width = side(numbers[0], what);
        int height = side(numbers[1], what);
        int depth = side(numbers[2], what);
        long cells = (long) width * height * depth;
        if (cells > MAX_CELLS) {
            throw new IOException(what + ": " + width + " x " + height + " x " + depth + " cells, more than "
                + MAX_CELLS);
        }
        if (numbers.length != 9 + 3 * cells) {
            throw new IOException(what + ": " + width + " x " + height + " x " + depth + " cells take "
                + (9 + 3 * cells) + " numbers: " + numbers.length);
        }
        float[] box = new float[6];
        for (int i = 0; i < 6; i++) {
            box[i] = number(numbers[3 + i], what);
        }
        float[] vectors = new float[(int) (3 * cells)];
        for (int i = 0; i < vectors.length; i++) {
            vectors[i] = number(numbers[9 + i], what);
        }
        return new VectorFieldData(width, height, depth, box[0], box[1], box[2], box[3], box[4], box[5], vectors);
    }

    private static int side(String number, String what) throws IOException {
        int side;
        try {
            side = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            throw new IOException(what + ": a size of " + number, e);
        }
        if (side < 1 || side > MAX_SIDE) {
            throw new IOException(what + ": " + side + " cells a side, not 1 to " + MAX_SIDE);
        }
        return side;
    }

    private static float number(String number, String what) throws IOException {
        float value;
        try {
            value = Float.parseFloat(number);
        } catch (NumberFormatException e) {
            throw new IOException(what + ": not a number: " + number, e);
        }
        if (!Float.isFinite(value)) {
            throw new IOException(what + ": not a finite number: " + number);
        }
        return value;
    }
}
