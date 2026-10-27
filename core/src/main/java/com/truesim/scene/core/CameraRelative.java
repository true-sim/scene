package com.truesim.scene.core;

import com.truesim.common.math.TransformArray;
import com.truesim.common.math.geometry.AabbArray;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Writes world transforms and bounds relative to an origin, usually the camera, as {@code float}s for the GPU.
 * Positions are {@code double} and can be thousands of kilometres from the world's origin; subtracting the camera in
 * {@code double} first keeps what the GPU sees precise near the camera, where precision matters.
 *
 * <pre>{@code
 * TransformArray world = scene.worldTransforms();
 * for (long node : visible) {
 *     CameraRelative.writeMatrix(world, NodeHandle.index(node), cx, cy, cz, instances, offset);
 *     offset += CameraRelative.MATRIX_BYTES;
 * }
 * }</pre>
 *
 * <p>Writes are little-endian (GPU byte order) and unaligned, and allocate nothing.
 */
public final class CameraRelative {

    /** Bytes of a 4x4 float matrix. */
    public static final int MATRIX_BYTES = 16 * Float.BYTES;
    /** Bytes of a 3x4 float matrix. */
    public static final int AFFINE_BYTES = 12 * Float.BYTES;
    /** Bytes of a box as six floats. */
    public static final int BOUNDS_BYTES = 6 * Float.BYTES;

    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;

    private CameraRelative() {
    }

    /**
     * Writes transform {@code index} relative to ({@code ox}, {@code oy}, {@code oz}) as a column-major 4x4 matrix
     * (16 floats, as GLSL, HLSL column_major and Slang expect by default) at {@code offset} in {@code target}.
     */
    public static void writeMatrix(TransformArray transforms, int index, double ox, double oy, double oz,
        MemorySegment target, long offset) {
        write(transforms, index, ox, oy, oz, target, offset, true);
    }

    /**
     * Writes transform {@code index} relative to ({@code ox}, {@code oy}, {@code oz}) as a row-major 3x4 matrix (12
     * floats: the three rows of the rotation-scale part, each followed by its translation), the compact form for
     * instance data and ray tracing instances.
     */
    public static void writeAffine(TransformArray transforms, int index, double ox, double oy, double oz,
        MemorySegment target, long offset) {
        write(transforms, index, ox, oy, oz, target, offset, false);
    }

    /** Writes box {@code index} relative to the origin as min x, y, z then max x, y, z. */
    public static void writeBounds(AabbArray bounds, int index, double ox, double oy, double oz, MemorySegment target,
        long offset) {
        put(target, offset, 0, bounds.get(index, 0) - ox);
        put(target, offset, 1, bounds.get(index, 1) - oy);
        put(target, offset, 2, bounds.get(index, 2) - oz);
        put(target, offset, 3, bounds.get(index, 3) - ox);
        put(target, offset, 4, bounds.get(index, 4) - oy);
        put(target, offset, 5, bounds.get(index, 5) - oz);
    }

    /**
     * Writes the transform's scaled rotation axes and its translation relative to the origin, as a column-major 4x4
     * matrix or a row-major 3x4 one.
     */
    private static void write(TransformArray transforms, int index, double ox, double oy, double oz,
        MemorySegment target, long offset, boolean columnMajor4x4) {
        MemorySegment s = transforms.segment();
        long o = index * transforms.stride();
        double qx = s.get(DOUBLE, o + 3 * Double.BYTES);
        double qy = s.get(DOUBLE, o + 4 * Double.BYTES);
        double qz = s.get(DOUBLE, o + 5 * Double.BYTES);
        double qw = s.get(DOUBLE, o + 6 * Double.BYTES);
        double sx = s.get(DOUBLE, o + 7 * Double.BYTES);
        double sy = s.get(DOUBLE, o + 8 * Double.BYTES);
        double sz = s.get(DOUBLE, o + 9 * Double.BYTES);
        // Columns: the x, y and z axes scaled, then the translation.
        double xx = (1 - 2 * (qy * qy + qz * qz)) * sx;
        double xy = 2 * (qx * qy + qz * qw) * sx;
        double xz = 2 * (qx * qz - qy * qw) * sx;
        double yx = 2 * (qx * qy - qz * qw) * sy;
        double yy = (1 - 2 * (qx * qx + qz * qz)) * sy;
        double yz = 2 * (qy * qz + qx * qw) * sy;
        double zx = 2 * (qx * qz + qy * qw) * sz;
        double zy = 2 * (qy * qz - qx * qw) * sz;
        double zz = (1 - 2 * (qx * qx + qy * qy)) * sz;
        double tx = s.get(DOUBLE, o) - ox;
        double ty = s.get(DOUBLE, o + Double.BYTES) - oy;
        double tz = s.get(DOUBLE, o + 2 * Double.BYTES) - oz;
        if (columnMajor4x4) {
            put(target, offset, 0, xx);
            put(target, offset, 1, xy);
            put(target, offset, 2, xz);
            put(target, offset, 3, 0);
            put(target, offset, 4, yx);
            put(target, offset, 5, yy);
            put(target, offset, 6, yz);
            put(target, offset, 7, 0);
            put(target, offset, 8, zx);
            put(target, offset, 9, zy);
            put(target, offset, 10, zz);
            put(target, offset, 11, 0);
            put(target, offset, 12, tx);
            put(target, offset, 13, ty);
            put(target, offset, 14, tz);
            put(target, offset, 15, 1);
        } else {
            put(target, offset, 0, xx);
            put(target, offset, 1, yx);
            put(target, offset, 2, zx);
            put(target, offset, 3, tx);
            put(target, offset, 4, xy);
            put(target, offset, 5, yy);
            put(target, offset, 6, zy);
            put(target, offset, 7, ty);
            put(target, offset, 8, xz);
            put(target, offset, 9, yz);
            put(target, offset, 10, zz);
            put(target, offset, 11, tz);
        }
    }

    private static void put(MemorySegment target, long offset, int element, double value) {
        target.set(FLOAT, offset + (long) element * Float.BYTES, (float) value);
    }
}
