package com.truesim.scene.effects;

import java.util.Objects;

/**
 * A vector field as data ({@link EffectAssets#VECTOR_FIELD}): {@code width} x {@code height} x {@code depth} cells of
 * three floats each (an acceleration in m/s², x, y, z), x fastest, then y, then z, authored over the box from
 * ({@code minX}, {@code minY}, {@code minZ}) to ({@code maxX}, {@code maxY}, {@code maxZ}). Particles' vector field
 * modules ({@link Forces#field}) name it; the renderer samples it over the module's own box, so the authored bounds are
 * information for tools.
 *
 * <pre>{@code
 * VectorFieldData swirl = new VectorFieldData(16, 16, 16, -1, -1, -1, 1, 1, 1, accelerations);
 * AssetRef<VectorFieldData> ref = AssetRef.generated(EffectAssets.VECTOR_FIELD, ...);   // or FgaImporter for files
 * }</pre>
 *
 * <p>The array is the field's: not copied, and not to be changed once handed to the asset cache.
 */
public record VectorFieldData(int width, int height, int depth, float minX, float minY, float minZ, float maxX,
    float maxY, float maxZ, float[] vectors) {

    public VectorFieldData {
        Objects.requireNonNull(vectors, "vectors");
        if (width < 1 || height < 1 || depth < 1 || vectors.length != 3L * width * height * depth) {
            throw new IllegalArgumentException("A " + width + " x " + height + " x " + depth + " field takes "
                + 3L * width * height * depth + " floats: " + vectors.length);
        }
    }

    /** A field over the box from -1 to 1 on every axis. */
    public VectorFieldData(int width, int height, int depth, float[] vectors) {
        this(width, height, depth, -1, -1, -1, 1, 1, 1, vectors);
    }

    /** How many cells it has. */
    public int cells() {
        return width * height * depth;
    }

    /** Component {@code axis} (0 to 2) of cell ({@code x}, {@code y}, {@code z})'s vector. */
    public float vector(int x, int y, int z, int axis) {
        Objects.checkIndex(axis, 3);
        return vectors[3 * ((Objects.checkIndex(z, depth) * height + Objects.checkIndex(y, height)) * width
            + Objects.checkIndex(x, width)) + axis];
    }

    /** The memory it takes, for the asset cache's budget. */
    public long bytes() {
        return 4L * vectors.length;
    }
}
