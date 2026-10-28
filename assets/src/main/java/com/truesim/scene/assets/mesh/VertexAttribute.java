package com.truesim.scene.assets.mesh;

/**
 * An optional vertex attribute and its encoding in the attribute stream; positions have a stream of their own. Each
 * takes 4 bytes; in a {@link VertexFormat} they lie in this order.
 */
public enum VertexAttribute {
    /** Unit normal, octahedral: 2 × snorm16. */
    NORMAL,
    /** Unit tangent, octahedral: 2 × snorm16; the bitangent sign (w) is the lowest bit of the first (1: negative). */
    TANGENT,
    /** First texture coordinates: 2 × float16. */
    UV0,
    /** Second texture coordinates, e.g. lightmaps: 2 × float16. */
    UV1,
    /** Vertex color, linear RGBA: 4 × unorm8. */
    COLOR,
    /** Four skinning joint indices: 4 × uint8. */
    JOINTS,
    /** Four skinning weights: 4 × unorm8. */
    WEIGHTS;

    /** Bytes each vertex takes for this attribute. */
    public static final int BYTES = 4;

    /** All attributes, in order: a copy made once. */
    static final VertexAttribute[] ALL = values();

    int bit() {
        return 1 << ordinal();
    }
}
