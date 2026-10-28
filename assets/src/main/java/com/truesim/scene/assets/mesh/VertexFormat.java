package com.truesim.scene.assets.mesh;

/**
 * Which optional attributes a mesh's vertices have, and where each lies in the interleaved attribute stream: in
 * {@link VertexAttribute} order, 4 bytes each. Positions always exist, in a stream of their own.
 *
 * <pre>{@code
 * VertexFormat lit = VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.TANGENT, VertexAttribute.UV0);
 * lit.stride();                          // 12 bytes per vertex
 * lit.offset(VertexAttribute.UV0);       // 8
 * }</pre>
 *
 * @param mask one bit per attribute, by ordinal
 */
public record VertexFormat(int mask) {

    /** Positions only, e.g. collision proxies or shadow casters. */
    public static final VertexFormat POSITIONS_ONLY = new VertexFormat(0);

    public VertexFormat {
        if ((mask & ~((1 << VertexAttribute.ALL.length) - 1)) != 0) {
            throw new IllegalArgumentException("Not a vertex format mask: " + mask);
        }
    }

    public static VertexFormat of(VertexAttribute... attributes) {
        int mask = 0;
        for (VertexAttribute attribute : attributes) {
            mask |= attribute.bit();
        }
        return new VertexFormat(mask);
    }

    public boolean has(VertexAttribute attribute) {
        return (mask & attribute.bit()) != 0;
    }

    /** Bytes per vertex in the attribute stream. */
    public int stride() {
        return Integer.bitCount(mask) * VertexAttribute.BYTES;
    }

    /** Where {@code attribute} lies in a vertex of the attribute stream. */
    public int offset(VertexAttribute attribute) {
        if (!has(attribute)) {
            throw new IllegalArgumentException("This format has no " + attribute);
        }
        return Integer.bitCount(mask & (attribute.bit() - 1)) * VertexAttribute.BYTES;
    }
}
