package com.truesim.scene.render;

import com.truesim.scene.assets.AssetRef;
import java.util.Objects;

/**
 * Points or lines as data, as glTF's point and line primitives are: positions, optional colors, and indices (one per
 * point, two per line segment; strips and loops unrolled), drawn with a material. Meshes are triangles in meshlets, so
 * these are an asset of their own; a model's node names them next to its mesh ({@link ModelData.Node#lines()}).
 *
 * @param kind points or line segments
 * @param positions x, y, z per vertex
 * @param colors linear r, g, b, a per vertex; empty for none
 * @param indices one vertex per point, two per segment
 * @param material what it is drawn with
 */
public record LineData(Kind kind, float[] positions, float[] colors, int[] indices, AssetRef<MaterialData> material) {

    /** What the indices make. */
    public enum Kind {
        /** One point per index. */
        POINTS,
        /** One segment per two indices. */
        LINES
    }

    public LineData {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(material, "material");
        if (positions.length % 3 != 0) {
            throw new IllegalArgumentException("Positions are 3 floats each: " + positions.length);
        }
        int vertices = positions.length / 3;
        if (colors.length != 0 && colors.length != 4 * vertices) {
            throw new IllegalArgumentException("Colors are 4 floats per vertex, or none: " + colors.length);
        }
        if (kind == Kind.LINES && indices.length % 2 != 0) {
            throw new IllegalArgumentException("Lines take two indices each: " + indices.length);
        }
        for (int index : indices) {
            Objects.checkIndex(index, vertices);
        }
    }

    /** How many vertices it has. */
    public int vertexCount() {
        return positions.length / 3;
    }

    /** The memory it takes, for the asset cache's budget. */
    public long bytes() {
        return 64L + 4L * (positions.length + colors.length + indices.length);
    }
}
