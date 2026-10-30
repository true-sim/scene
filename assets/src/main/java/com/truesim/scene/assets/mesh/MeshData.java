package com.truesim.scene.assets.mesh;

import com.truesim.annotations.AllowAllocation;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A mesh as a GPU-driven renderer draws it: meshlets (small clusters of up to 255 vertices and triangles, culled and
 * drawn on the GPU) over quantized vertex streams, with detail levels that work both as a cluster hierarchy and as
 * discrete LODs. Its buffers are little-endian with scalar layout and upload as they are; one big GPU buffer per kind
 * holds every mesh, addressed by buffer device address.
 *
 * <table>
 *   <caption>Buffers</caption>
 *   <tr><th>Buffer</th><th>Per</th><th>Bytes</th><th>Contents</th></tr>
 *   <tr><td>{@link #positions()}</td><td>vertex</td><td>8</td><td>3 × unorm16 in the quantization box, 1 pad</td></tr>
 *   <tr><td>{@link #attributes()}</td><td>vertex</td><td>{@link VertexFormat#stride()}</td>
 *       <td>the {@link VertexAttribute}s of the format, interleaved</td></tr>
 *   <tr><td>{@link #meshletVertices()}</td><td>meshlet vertex</td><td>4</td><td>uint32 vertex index</td></tr>
 *   <tr><td>{@link #meshletTriangles()}</td><td>triangle</td><td>3</td><td>3 × uint8 index into the meshlet's
 *       vertices</td></tr>
 *   <tr><td>{@link #meshlets()}</td><td>meshlet</td><td>32</td><td>vertexOffset u32, triangleOffset u32 (in indices),
 *       vertexCount u8, triangleCount u8, submesh u16, bounding sphere 4 × float32, cone axis 3 × snorm8,
 *       cone cutoff snorm8</td></tr>
 *   <tr><td>{@link #clusterLod()}</td><td>meshlet</td><td>40</td><td>own group: sphere center 3 × float32, radius
 *       float32, error float32; parent group: the same</td></tr>
 * </table>
 *
 * <p><b>Detail, one rule:</b> draw a meshlet when its own group's projected error is at most the allowed error and its
 * parent group's is more. A cluster hierarchy (from simplifying groups of meshlets) gives each group its own sphere and
 * error, so detail varies across the mesh; discrete LODs ({@link Builder#discreteLods()}) give every meshlet of a level
 * the mesh's sphere, the level's error and the next coarser level's, so whole levels switch. Leaves have error 0, the
 * coarsest groups a parent error of +∞.
 *
 * <p><b>Fallback path:</b> without mesh shaders, a meshlet draws as indexed triangles: index {@code k} of the mesh is
 * {@code meshletVertices[vertexOffset + meshletTriangles[k]]}, and a meshlet's first index is its triangleOffset
 * ({@link #expandIndices(Arena)}).
 */
public final class MeshData implements AutoCloseable {

    /** Bytes per vertex in {@link #positions()}. */
    public static final int POSITION_BYTES = 8;
    /** Bytes per meshlet in {@link #meshlets()}. */
    public static final int MESHLET_BYTES = 32;
    /** Bytes per meshlet in {@link #clusterLod()}. */
    public static final int CLUSTER_LOD_BYTES = 40;
    /** The most vertices or triangles a meshlet holds: counts are uint8. */
    public static final int MAX_MESHLET_SIZE = 255;

    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final VertexFormat format;
    private final int vertexCount;
    private final int meshletCount;
    private final int triangleCount;
    private final int materialSlots;
    private final double[] box; // quantization box: min x, y, z, extent x, y, z
    private final Sphere bounds;
    private final List<MeshLevel> levels;
    private final MemorySegment positions;
    private final MemorySegment attributes;
    private final MemorySegment meshletVertices;
    private final MemorySegment meshletTriangles;
    private final MemorySegment meshlets;
    private final MemorySegment clusterLod;
    private final Arena arena; // owns the buffers
    private final AtomicBoolean closed = new AtomicBoolean();

    private MeshData(Builder b, double[] box, Sphere bounds, List<MeshLevel> levels, Arena arena,
        MemorySegment[] segments) {
        this.arena = arena;
        this.format = b.format;
        this.vertexCount = b.vertexCount;
        this.meshletCount = b.meshlets.size();
        this.triangleCount = b.triangleTotal;
        this.materialSlots = b.materialSlots;
        this.box = box;
        this.bounds = bounds;
        this.levels = levels;
        this.positions = segments[0].asReadOnly();
        this.attributes = segments[1].asReadOnly();
        this.meshletVertices = segments[2].asReadOnly();
        this.meshletTriangles = segments[3].asReadOnly();
        this.meshlets = segments[4].asReadOnly();
        this.clusterLod = segments[5].asReadOnly();
    }

    /**
     * Frees the buffers: segments taken from this mesh throw from now on. The asset cache closes meshes it evicts
     * (through {@link com.truesim.scene.assets.AssetLoader#unload}); close those built by hand once uploaded.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            arena.close();
        }
    }

    /** Whether {@link #close()} freed the buffers. */
    public boolean isClosed() {
        return closed.get();
    }

    /** Starts a mesh of {@code vertexCount} vertices with the attributes of {@code format}. */
    @AllowAllocation("building a mesh, at import or load")
    public static Builder builder(VertexFormat format, int vertexCount) {
        return new Builder(format, vertexCount);
    }

    // ---------------------------------------------------------------- header

    public VertexFormat format() {
        return format;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int meshletCount() {
        return meshletCount;
    }

    public int triangleCount() {
        return triangleCount;
    }

    /** How many materials the mesh's submeshes use: meshlets name theirs by {@link #meshletSubmesh}. */
    public int materialSlots() {
        return materialSlots;
    }

    /** The detail levels, coarsest first; together they hold every meshlet once. */
    public List<MeshLevel> levels() {
        return levels;
    }

    /** A sphere around every vertex. */
    public Sphere bounds() {
        return bounds;
    }

    /** The quantization box's corner, also the minimum of the vertex positions, along {@code axis} (0 to 2). */
    public double boxMin(int axis) {
        return box[axis];
    }

    /** The quantization box's size along {@code axis}: positions are {@code min + unorm16 * extent}. */
    public double boxExtent(int axis) {
        return box[3 + axis];
    }

    /** Bytes of all buffers: what the mesh takes in memory and on the GPU. */
    public long bytes() {
        return positions.byteSize() + attributes.byteSize() + meshletVertices.byteSize() + meshletTriangles.byteSize()
            + meshlets.byteSize() + clusterLod.byteSize();
    }

    // ---------------------------------------------------------------- buffers

    public MemorySegment positions() {
        return positions;
    }

    public MemorySegment attributes() {
        return attributes;
    }

    public MemorySegment meshletVertices() {
        return meshletVertices;
    }

    public MemorySegment meshletTriangles() {
        return meshletTriangles;
    }

    public MemorySegment meshlets() {
        return meshlets;
    }

    public MemorySegment clusterLod() {
        return clusterLod;
    }

    // ---------------------------------------------------------------- decoding, e.g. for physics or tools

    /** Vertex {@code vertex}'s position, as the GPU decodes it, into {@code out[0..2]}. */
    public void position(int vertex, double[] out) {
        long o = (long) checkVertex(vertex) * POSITION_BYTES;
        for (int axis = 0; axis < 3; axis++) {
            out[axis] = box[axis]
                + Encoding.fromUnorm(Short.toUnsignedInt(positions.get(U16, o + 2L * axis)), 16) * box[3 + axis];
        }
    }

    /** Vertex {@code vertex}'s unit normal into {@code out[0..2]}. */
    public void normal(int vertex, double[] out) {
        long o = attribute(vertex, VertexAttribute.NORMAL);
        Encoding.fromOctahedral(Encoding.fromSnorm(attributes.get(U16, o), 16),
            Encoding.fromSnorm(attributes.get(U16, o + 2), 16), out);
    }

    /** Vertex {@code vertex}'s unit tangent into {@code out[0..2]} and the bitangent sign (±1) into {@code out[3]}. */
    public void tangent(int vertex, double[] out) {
        long o = attribute(vertex, VertexAttribute.TANGENT);
        short first = attributes.get(U16, o);
        Encoding.fromOctahedral(Encoding.fromSnorm(first, 16), Encoding.fromSnorm(attributes.get(U16, o + 2), 16),
            out);
        out[3] = (first & 1) != 0 ? -1 : 1;
    }

    /** Vertex {@code vertex}'s texture coordinates of {@code set} ({@code UV0} or {@code UV1}) into {@code out[0..1]}. */
    public void uv(VertexAttribute set, int vertex, double[] out) {
        if (set != VertexAttribute.UV0 && set != VertexAttribute.UV1) {
            throw new IllegalArgumentException("Not texture coordinates: " + set);
        }
        long o = attribute(vertex, set);
        out[0] = Encoding.fromHalf(attributes.get(U16, o));
        out[1] = Encoding.fromHalf(attributes.get(U16, o + 2));
    }

    /** Vertex {@code vertex}'s color, or skinning weights, into {@code out[0..3]}. */
    public void unorm4(VertexAttribute attribute, int vertex, double[] out) {
        if (attribute != VertexAttribute.COLOR && attribute != VertexAttribute.WEIGHTS) {
            throw new IllegalArgumentException("Not four unorm8 values: " + attribute);
        }
        long o = attribute(vertex, attribute);
        for (int i = 0; i < 4; i++) {
            out[i] = Encoding.fromUnorm(Byte.toUnsignedInt(attributes.get(ValueLayout.JAVA_BYTE, o + i)), 8);
        }
    }

    /** Vertex {@code vertex}'s four skinning joints into {@code out[0..3]}. */
    public void joints(int vertex, int[] out) {
        long o = attribute(vertex, VertexAttribute.JOINTS);
        for (int i = 0; i < 4; i++) {
            out[i] = Byte.toUnsignedInt(attributes.get(ValueLayout.JAVA_BYTE, o + i));
        }
    }

    private long attribute(int vertex, VertexAttribute attribute) {
        return (long) checkVertex(vertex) * format.stride() + format.offset(attribute);
    }

    private int checkVertex(int vertex) {
        return Objects.checkIndex(vertex, vertexCount);
    }

    public int meshletVertexOffset(int meshlet) {
        return meshlets.get(U32, meshletOffset(meshlet));
    }

    /** Where the meshlet's triangles start in {@link #meshletTriangles()}, in indices (3 per triangle). */
    public int meshletTriangleOffset(int meshlet) {
        return meshlets.get(U32, meshletOffset(meshlet) + 4);
    }

    public int meshletVertexCount(int meshlet) {
        return Byte.toUnsignedInt(meshlets.get(ValueLayout.JAVA_BYTE, meshletOffset(meshlet) + 8));
    }

    public int meshletTriangleCount(int meshlet) {
        return Byte.toUnsignedInt(meshlets.get(ValueLayout.JAVA_BYTE, meshletOffset(meshlet) + 9));
    }

    /** The material slot of the meshlet's submesh. */
    public int meshletSubmesh(int meshlet) {
        return Short.toUnsignedInt(meshlets.get(U16, meshletOffset(meshlet) + 10));
    }

    /** The meshlet's bounding sphere: center into {@code out[0..2]}, radius into {@code out[3]}. */
    public void meshletSphere(int meshlet, double[] out) {
        long o = meshletOffset(meshlet) + 12;
        for (int i = 0; i < 4; i++) {
            out[i] = meshlets.get(F32, o + 4L * i);
        }
    }

    /** The meshlet's cone as stored (snorm8): axis into {@code out[0..2]}, cutoff into {@code out[3]}. */
    public void meshletCone(int meshlet, double[] out) {
        long o = meshletOffset(meshlet) + 28;
        for (int i = 0; i < 4; i++) {
            out[i] = Encoding.fromSnorm(meshlets.get(ValueLayout.JAVA_BYTE, o + i), 8);
        }
    }

    /**
     * The meshlet's detail selection data: own group's sphere (center, radius) and error into {@code out[0..4]}, parent
     * group's into {@code out[5..9]}. A group's projected error uses the distance to its sphere, so a parent's sphere
     * holds its children's.
     */
    public void meshletLod(int meshlet, double[] out) {
        long o = (long) Objects.checkIndex(meshlet, meshletCount) * CLUSTER_LOD_BYTES;
        for (int i = 0; i < 10; i++) {
            out[i] = clusterLod.get(F32, o + 4L * i);
        }
    }

    private long meshletOffset(int meshlet) {
        return (long) Objects.checkIndex(meshlet, meshletCount) * MESHLET_BYTES;
    }

    /**
     * The mesh as one uint32 index buffer for the fallback path, meshlet after meshlet: a meshlet's first index is its
     * triangle offset. The indices are allocated in {@code arena}, which the caller closes once they are uploaded.
     */
    @AllowAllocation("expanding indices, at upload")
    public MemorySegment expandIndices(Arena arena) {
        MemorySegment indices = arena.allocate(3L * triangleCount * Integer.BYTES, Integer.BYTES);
        for (int m = 0; m < meshletCount; m++) {
            int vertexOffset = meshletVertexOffset(m);
            int first = meshletTriangleOffset(m);
            for (int k = first; k < first + 3 * meshletTriangleCount(m); k++) {
                int local = Byte.toUnsignedInt(meshletTriangles.get(ValueLayout.JAVA_BYTE, k));
                indices.set(U32, 4L * k, meshletVertices.get(U32, 4L * (vertexOffset + local)));
            }
        }
        return indices;
    }

    // ---------------------------------------------------------------- building

    /** One meshlet as given to the builder. */
    private record Meshlet(int[] vertices, int[] triangles, int submesh, Sphere bounds, Cone cone, Sphere group,
        double groupError, Sphere parent, double parentError) {
    }

    /**
     * Collects a mesh's vertices, meshlets and levels and encodes them; normally fed by the mesh builder (meshoptimizer)
     * or an importer. Attribute arrays are in float32 or int units per vertex, as each setter says.
     */
    public static final class Builder {
        private final VertexFormat format;
        private final int vertexCount;
        private float[] positions;
        private final float[][] attributeData = new float[VertexAttribute.ALL.length][];
        private int[] joints;
        private int materialSlots = 1;
        private final List<Meshlet> meshlets = new ArrayList<>();
        private final List<MeshLevel> levels = new ArrayList<>();
        private int levelled;
        private int triangleTotal;
        private int meshletVertexTotal;
        private boolean discrete;

        private Builder(VertexFormat format, int vertexCount) {
            this.format = Objects.requireNonNull(format, "format");
            if (vertexCount < 1) {
                throw new IllegalArgumentException("A mesh needs vertices: " + vertexCount);
            }
            this.vertexCount = vertexCount;
        }

        /** Positions: x, y, z per vertex. */
        public Builder positions(float[] xyz) {
            this.positions = check(xyz, 3, "positions");
            return this;
        }

        /** Normals: x, y, z per vertex; normalized when encoded. */
        public Builder normals(float[] xyz) {
            return attribute(VertexAttribute.NORMAL, xyz, 3);
        }

        /** Tangents: x, y, z and the bitangent sign w (±1) per vertex. */
        public Builder tangents(float[] xyzw) {
            return attribute(VertexAttribute.TANGENT, xyzw, 4);
        }

        /** Texture coordinates of {@code set} ({@code UV0} or {@code UV1}): u, v per vertex. */
        public Builder uv(VertexAttribute set, float[] uv) {
            if (set != VertexAttribute.UV0 && set != VertexAttribute.UV1) {
                throw new IllegalArgumentException("Not texture coordinates: " + set);
            }
            return attribute(set, uv, 2);
        }

        /** Linear colors: r, g, b, a in [0, 1] per vertex. */
        public Builder colors(float[] rgba) {
            return attribute(VertexAttribute.COLOR, rgba, 4);
        }

        /** Skinning weights: four in [0, 1] per vertex. */
        public Builder weights(float[] weights) {
            return attribute(VertexAttribute.WEIGHTS, weights, 4);
        }

        /** Skinning joints: four indices from 0 to 255 per vertex. */
        public Builder joints(int[] indices) {
            requireFormat(VertexAttribute.JOINTS);
            if (indices.length != vertexCount * 4) {
                throw new IllegalArgumentException("joints need 4 values per vertex: " + indices.length);
            }
            for (int joint : indices) {
                if (joint < 0 || joint > 255) {
                    throw new IllegalArgumentException("Joints are 0 to 255: " + joint);
                }
            }
            this.joints = indices.clone();
            return this;
        }

        private Builder attribute(VertexAttribute attribute, float[] values, int components) {
            requireFormat(attribute);
            attributeData[attribute.ordinal()] = check(values, components, attribute.name().toLowerCase(Locale.ROOT));
            return this;
        }

        private void requireFormat(VertexAttribute attribute) {
            if (!format.has(attribute)) {
                throw new IllegalArgumentException("The format has no " + attribute);
            }
        }

        private float[] check(float[] values, int components, String what) {
            if (values.length != vertexCount * components) {
                throw new IllegalArgumentException(what + " need " + components + " values per vertex: "
                    + values.length);
            }
            for (float value : values) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException(what + " must be finite: " + value);
                }
            }
            return values.clone();
        }

        /** How many materials the submeshes use; 1 by default. */
        public Builder materialSlots(int slots) {
            if (slots < 1 || slots > 65536) {
                throw new IllegalArgumentException("Material slots are 1 to 65536: " + slots);
            }
            this.materialSlots = slots;
            return this;
        }

        /**
         * Adds a meshlet: its vertices (indices into the mesh's vertices), its triangles (three indices into
         * {@code vertices} each), its submesh's material slot, bounding sphere and normal cone. Without
         * {@link #lod}, it is always drawn (error 0, parent error +∞).
         */
        @AllowAllocation("building a mesh")
        public Builder meshlet(int[] vertices, int[] triangles, int submesh, Sphere bounds, Cone cone) {
            if (vertices.length < 1 || vertices.length > MAX_MESHLET_SIZE || triangles.length < 3
                || triangles.length % 3 != 0 || triangles.length / 3 > MAX_MESHLET_SIZE) {
                throw new IllegalArgumentException("A meshlet has 1 to 255 vertices and 1 to 255 triangles: "
                    + vertices.length + ", " + triangles.length / 3.0);
            }
            for (int vertex : vertices) {
                Objects.checkIndex(vertex, vertexCount);
            }
            for (int local : triangles) {
                Objects.checkIndex(local, vertices.length);
            }
            Objects.checkIndex(submesh, materialSlots);
            meshlets.add(new Meshlet(vertices.clone(), triangles.clone(), submesh, Objects.requireNonNull(bounds,
                "bounds"), Objects.requireNonNull(cone, "cone"), bounds, 0, bounds, Double.POSITIVE_INFINITY));
            triangleTotal += triangles.length / 3;
            meshletVertexTotal += vertices.length;
            return this;
        }

        /**
         * Sets the last meshlet's detail selection data, for a cluster hierarchy: its group's sphere and error, and
         * the sphere and error of the group it was simplified into ({@code +∞} error for the coarsest).
         */
        @AllowAllocation("building a mesh")
        public Builder lod(Sphere group, double error, Sphere parent, double parentError) {
            if (meshlets.isEmpty()) {
                throw new IllegalStateException("Add a meshlet first");
            }
            if (!(error >= 0) || !(parentError >= error)) {
                throw new IllegalArgumentException("Errors must be 0 or more, the parent's at least the group's: "
                    + error + ", " + parentError);
            }
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(parent, "parent");
            Meshlet last = meshlets.removeLast();
            meshlets.add(new Meshlet(last.vertices, last.triangles, last.submesh, last.bounds, last.cone, group, error,
                parent, parentError));
            return this;
        }

        /**
         * Ends a detail level: the meshlets added since the previous level, with their simplification {@code error}
         * (0 for full detail). Levels go coarsest first, errors falling. Without levels, all meshlets form one.
         */
        @AllowAllocation("building a mesh")
        public Builder level(double error) {
            if (meshlets.size() == levelled) {
                throw new IllegalStateException("A level needs meshlets");
            }
            if (!(error >= 0) || !levels.isEmpty() && error > levels.getLast().error()) {
                throw new IllegalArgumentException("Level errors are 0 or more and fall level by level: " + error);
            }
            levels.add(new MeshLevel(levelled, meshlets.size() - levelled, error));
            levelled = meshlets.size();
            return this;
        }

        /**
         * Makes the levels discrete LODs: every meshlet gets the mesh's sphere, its level's error and the next coarser
         * level's as parent error (+∞ for the coarsest), so the selection rule switches whole levels.
         */
        public Builder discreteLods() {
            this.discrete = true;
            return this;
        }

        @AllowAllocation("building a mesh")
        public MeshData build() {
            if (positions == null) {
                throw new IllegalStateException("A mesh needs positions");
            }
            for (VertexAttribute attribute : VertexAttribute.ALL) {
                boolean given = attribute == VertexAttribute.JOINTS ? joints != null
                    : attributeData[attribute.ordinal()] != null;
                if (format.has(attribute) && !given) {
                    throw new IllegalStateException("The format has " + attribute + " but none were given");
                }
            }
            if (meshlets.isEmpty()) {
                throw new IllegalStateException("A mesh needs meshlets");
            }
            if (levelled != meshlets.size()) {
                if (levels.isEmpty()) {
                    levels.add(new MeshLevel(0, meshlets.size(), 0));
                } else {
                    throw new IllegalStateException("Meshlets after the last level: end it with level()");
                }
            }
            double[] box = box();
            Sphere bounds = sphere(box);
            Arena arena = Arena.ofShared(); // read from any thread, freed by close()
            MemorySegment[] segments = {
                encodePositions(arena, box),
                encodeAttributes(arena),
                arena.allocate(4L * meshletVertexTotal, 4),
                arena.allocate(3L * triangleTotal, 4),
                arena.allocate((long) MESHLET_BYTES * meshlets.size(), 16),
                arena.allocate((long) CLUSTER_LOD_BYTES * meshlets.size(), 8)
            };
            encodeMeshlets(segments, bounds);
            return new MeshData(this, box, bounds, List.copyOf(levels), arena, segments);
        }

        private double[] box() {
            double[] box = new double[6];
            for (int axis = 0; axis < 3; axis++) {
                double min = Double.POSITIVE_INFINITY;
                double max = Double.NEGATIVE_INFINITY;
                for (int v = 0; v < vertexCount; v++) {
                    min = Math.min(min, positions[3 * v + axis]);
                    max = Math.max(max, positions[3 * v + axis]);
                }
                box[axis] = min;
                box[3 + axis] = max - min;
            }
            return box;
        }

        private Sphere sphere(double[] box) {
            double cx = box[0] + box[3] / 2;
            double cy = box[1] + box[4] / 2;
            double cz = box[2] + box[5] / 2;
            double radius = 0;
            for (int v = 0; v < vertexCount; v++) {
                double dx = positions[3 * v] - cx;
                double dy = positions[3 * v + 1] - cy;
                double dz = positions[3 * v + 2] - cz;
                radius = Math.max(radius, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            return new Sphere(cx, cy, cz, radius);
        }

        private MemorySegment encodePositions(Arena arena, double[] box) {
            MemorySegment out = arena.allocate((long) POSITION_BYTES * vertexCount, 8);
            for (int v = 0; v < vertexCount; v++) {
                for (int axis = 0; axis < 3; axis++) {
                    double extent = box[3 + axis];
                    double t = extent == 0 ? 0 : (positions[3 * v + axis] - box[axis]) / extent;
                    out.set(U16, (long) POSITION_BYTES * v + 2L * axis, (short) Encoding.unorm(t, 16));
                }
            }
            return out;
        }

        private MemorySegment encodeAttributes(Arena arena) {
            int stride = format.stride();
            MemorySegment out = arena.allocate((long) stride * vertexCount, 4);
            double[] oct = new double[2];
            for (int v = 0; v < vertexCount; v++) {
                long base = (long) stride * v;
                for (VertexAttribute attribute : VertexAttribute.ALL) {
                    if (format.has(attribute)) {
                        encode(out, base + format.offset(attribute), attribute, v, oct);
                    }
                }
            }
            return out;
        }

        private void encode(MemorySegment out, long o, VertexAttribute attribute, int v, double[] oct) {
            float[] a = attributeData[attribute.ordinal()];
            switch (attribute) {
                case NORMAL -> {
                    Encoding.octahedral(a[3 * v], a[3 * v + 1], a[3 * v + 2], oct);
                    out.set(U16, o, (short) Encoding.snorm(oct[0], 16));
                    out.set(U16, o + 2, (short) Encoding.snorm(oct[1], 16));
                }
                case TANGENT -> {
                    Encoding.octahedral(a[4 * v], a[4 * v + 1], a[4 * v + 2], oct);
                    int first = Encoding.snorm(oct[0], 16) & ~1 | (a[4 * v + 3] < 0 ? 1 : 0);
                    out.set(U16, o, (short) first);
                    out.set(U16, o + 2, (short) Encoding.snorm(oct[1], 16));
                }
                case UV0, UV1 -> {
                    out.set(U16, o, Encoding.half(a[2 * v]));
                    out.set(U16, o + 2, Encoding.half(a[2 * v + 1]));
                }
                case JOINTS -> {
                    for (int i = 0; i < 4; i++) {
                        out.set(ValueLayout.JAVA_BYTE, o + i, (byte) joints[4 * v + i]);
                    }
                }
                default -> { // COLOR, WEIGHTS
                    for (int i = 0; i < 4; i++) {
                        out.set(ValueLayout.JAVA_BYTE, o + i, (byte) Encoding.unorm(a[4 * v + i], 8));
                    }
                }
            }
        }

        private void encodeMeshlets(MemorySegment[] segments, Sphere meshBounds) {
            MemorySegment vertices = segments[2];
            MemorySegment triangles = segments[3];
            MemorySegment records = segments[4];
            MemorySegment lods = segments[5];
            int vertexOffset = 0;
            int triangleOffset = 0;
            for (int m = 0; m < meshlets.size(); m++) {
                Meshlet meshlet = meshlets.get(m);
                for (int i = 0; i < meshlet.vertices.length; i++) {
                    vertices.set(U32, 4L * (vertexOffset + i), meshlet.vertices[i]);
                }
                for (int i = 0; i < meshlet.triangles.length; i++) {
                    triangles.set(ValueLayout.JAVA_BYTE, triangleOffset + i, (byte) meshlet.triangles[i]);
                }
                long o = (long) MESHLET_BYTES * m;
                records.set(U32, o, vertexOffset);
                records.set(U32, o + 4, triangleOffset);
                records.set(ValueLayout.JAVA_BYTE, o + 8, (byte) meshlet.vertices.length);
                records.set(ValueLayout.JAVA_BYTE, o + 9, (byte) (meshlet.triangles.length / 3));
                records.set(U16, o + 10, (short) meshlet.submesh);
                putSphere(records, o + 12, meshlet.bounds);
                putCone(records, o + 28, meshlet.cone);
                putLod(lods, (long) CLUSTER_LOD_BYTES * m, meshlet, m, meshBounds);
                vertexOffset += meshlet.vertices.length;
                triangleOffset += meshlet.triangles.length;
            }
        }

        private void putLod(MemorySegment lods, long o, Meshlet meshlet, int m, Sphere meshBounds) {
            if (!discrete) {
                putGroup(lods, o, meshlet.group, meshlet.groupError);
                putGroup(lods, o + 20, meshlet.parent, meshlet.parentError);
                return;
            }
            int level = 0;
            while (m >= levels.get(level).firstMeshlet() + levels.get(level).meshletCount()) {
                level++;
            }
            double parentError = level == 0 ? Double.POSITIVE_INFINITY : levels.get(level - 1).error();
            putGroup(lods, o, meshBounds, levels.get(level).error());
            putGroup(lods, o + 20, meshBounds, parentError);
        }

        /** Center and radius as 4 × float32. */
        private static void putSphere(MemorySegment out, long o, Sphere sphere) {
            out.set(F32, o, (float) sphere.x());
            out.set(F32, o + 4, (float) sphere.y());
            out.set(F32, o + 8, (float) sphere.z());
            out.set(F32, o + 12, (float) sphere.radius());
        }

        /** A group's sphere and error as 5 × float32. */
        private static void putGroup(MemorySegment out, long o, Sphere sphere, double error) {
            putSphere(out, o, sphere);
            out.set(F32, o + 16, (float) error);
        }

        /**
         * The cone as snorm8: the axis normalized and rounded, the cutoff rounded up (and one step more, for the
         * axis's rounding), so quantization never culls a meshlet that faces the camera.
         */
        private static void putCone(MemorySegment out, long o, Cone cone) {
            double length = Math.sqrt(cone.axisX() * cone.axisX() + cone.axisY() * cone.axisY()
                + cone.axisZ() * cone.axisZ());
            double scale = length == 0 ? 0 : 1 / length;
            out.set(ValueLayout.JAVA_BYTE, o, (byte) Encoding.snorm(cone.axisX() * scale, 8));
            out.set(ValueLayout.JAVA_BYTE, o + 1, (byte) Encoding.snorm(cone.axisY() * scale, 8));
            out.set(ValueLayout.JAVA_BYTE, o + 2, (byte) Encoding.snorm(cone.axisZ() * scale, 8));
            int cutoff = cone.cutoff() >= 1 || length == 0 ? 127
                : Math.min(127, (int) Math.ceil(cone.cutoff() * 127) + 1);
            out.set(ValueLayout.JAVA_BYTE, o + 3, (byte) cutoff);
        }
    }
}
