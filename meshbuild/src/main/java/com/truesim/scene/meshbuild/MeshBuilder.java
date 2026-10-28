package com.truesim.scene.meshbuild;

import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_SimplifyErrorAbsolute;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_SimplifyLockBorder;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_SimplifySparse;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_buildMeshlets;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_buildMeshletsBound;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_computeMeshletBounds;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_computeSphereBounds;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_optimizeMeshlet;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_optimizeVertexCache;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_optimizeVertexFetchRemap;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_partitionClusters;
import static org.lwjgl.util.meshoptimizer.MeshOptimizer.meshopt_simplify;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.mesh.Cone;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.Sphere;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.meshoptimizer.MeshoptBounds;
import org.lwjgl.util.meshoptimizer.MeshoptMeshlet;

/**
 * Builds {@link MeshData} from plain vertices and triangles with meshoptimizer: vertex cache order, meshlets with
 * bounds and normal cones, detail levels, and vertex fetch order. Three ways to build:
 *
 * <ul>
 *   <li>{@link #buildHierarchy()}: a cluster hierarchy, as Nanite does it. Meshlets are partitioned into groups,
 *       each group is simplified to half its triangles with its border locked (so neighboring groups still meet),
 *       and the result is split into meshlets again, level by level until one group remains. Each meshlet knows its
 *       group's error and its parent group's, so the GPU picks detail per meshlet without cracks.</li>
 *   <li>{@link #buildDiscrete(int, double)}: discrete LODs, each a simplification of the whole mesh to a fraction of
 *       the previous level's triangles, switched whole.</li>
 *   <li>{@link #buildFlat()}: meshlets at full detail only, e.g. for small props.</li>
 * </ul>
 *
 * <pre>{@code
 * MeshData mesh = new MeshBuilder(VertexFormat.of(NORMAL, UV0), vertexCount)
 *     .positions(positions)
 *     .attribute(VertexAttribute.NORMAL, normals)
 *     .attribute(VertexAttribute.UV0, uvs)
 *     .submesh(bodyIndices)                  // material slot 0
 *     .submesh(glassIndices)                 // material slot 1
 *     .buildHierarchy();
 * }</pre>
 *
 * <p>Simplification keeps original vertices, so every level shares one vertex buffer; vertices no level uses are
 * dropped, and the rest are ordered as the meshlets first use them, coarsest level first, so a mesh streaming in by
 * levels reads its vertices front to back. Submeshes are built apart and never share a meshlet. Errors are in the
 * mesh's units. The output is deterministic. Build at import or load time, not per frame; one builder per thread.
 */
@AllowAllocation("building meshes, offline")
public final class MeshBuilder {

    /** Fewer triangles kept than this fraction counts as progress when simplifying; more, and a group stops. */
    private static final double MIN_REDUCTION = 0.85;

    private final VertexFormat format;
    private final int vertexCount;
    private float[] positions;
    private final float[][] attributes = new float[VertexAttribute.values().length][];
    private int[] joints;
    private final List<int[]> submeshes = new ArrayList<>();
    private int maxVertices = 64;
    private int maxTriangles = 124;
    private float coneWeight = 0.25f;
    private int groupSize = 8;

    /** A builder for a mesh of {@code vertexCount} vertices with the attributes of {@code format}. */
    @AllowAllocation("building meshes, offline")
    public MeshBuilder(VertexFormat format, int vertexCount) {
        this.format = Objects.requireNonNull(format, "format");
        if (vertexCount < 1) {
            throw new IllegalArgumentException("A mesh needs vertices: " + vertexCount);
        }
        this.vertexCount = vertexCount;
    }

    /** Positions: x, y, z per vertex. */
    public MeshBuilder positions(float[] xyz) {
        if (xyz.length != 3 * vertexCount) {
            throw new IllegalArgumentException("Positions need 3 values per vertex: " + xyz.length);
        }
        this.positions = xyz.clone();
        return this;
    }

    /**
     * An attribute's values per vertex, as {@link MeshData.Builder} takes them: normals x, y, z; tangents x, y, z, w;
     * texture coordinates u, v; colors and weights four each.
     */
    public MeshBuilder attribute(VertexAttribute attribute, float[] values) {
        if (attribute == VertexAttribute.JOINTS) {
            throw new IllegalArgumentException("Joints are integers: use joints()");
        }
        if (!format.has(attribute)) {
            throw new IllegalArgumentException("The format has no " + attribute);
        }
        attributes[attribute.ordinal()] = values.clone();
        return this;
    }

    /** Skinning joints: four indices from 0 to 255 per vertex. */
    public MeshBuilder joints(int[] indices) {
        this.joints = indices.clone();
        return this;
    }

    /** Adds a submesh: triangles (three vertex indices each) drawn with the next material slot. */
    @AllowAllocation("building meshes, offline")
    public MeshBuilder submesh(int[] indices) {
        if (indices.length < 3 || indices.length % 3 != 0) {
            throw new IllegalArgumentException("A submesh needs whole triangles: " + indices.length + " indices");
        }
        for (int index : indices) {
            Objects.checkIndex(index, vertexCount);
        }
        submeshes.add(indices.clone());
        return this;
    }

    /**
     * Meshlet limits: at most {@code vertices} vertices and {@code triangles} triangles each (64 and 124 by default,
     * good for most GPUs' mesh shaders); up to 255 each.
     */
    public MeshBuilder meshletSize(int vertices, int triangles) {
        if (vertices < 3 || vertices > MeshData.MAX_MESHLET_SIZE || triangles < 1 || triangles > 252
            || triangles % 4 != 0) {
            throw new IllegalArgumentException("Meshlets hold 3 to 255 vertices and 4 to 252 triangles, a multiple of "
                + "4: " + vertices + ", " + triangles);
        }
        this.maxVertices = vertices;
        this.maxTriangles = triangles;
        return this;
    }

    /**
     * How much meshlet building favors tight normal cones (better backface culling) over compact meshlets, from 0 to
     * 1; 0.25 by default.
     */
    public MeshBuilder coneWeight(double weight) {
        if (!(weight >= 0 && weight <= 1)) {
            throw new IllegalArgumentException("The cone weight is 0 to 1: " + weight);
        }
        this.coneWeight = (float) weight;
        return this;
    }

    /** How many meshlets a cluster hierarchy simplifies together; 8 by default. */
    public MeshBuilder groupSize(int meshlets) {
        if (meshlets < 2 || meshlets > 64) {
            throw new IllegalArgumentException("Groups hold 2 to 64 meshlets: " + meshlets);
        }
        this.groupSize = meshlets;
        return this;
    }

    // ---------------------------------------------------------------- building

    /** Meshlets at full detail only: one level. */
    @AllowAllocation("building meshes, offline")
    public MeshData buildFlat() {
        try (Work work = new Work()) {
            List<List<Cluster>> levels = new ArrayList<>();
            List<Cluster> level = new ArrayList<>();
            for (int s = 0; s < submeshes.size(); s++) {
                level.addAll(work.split(work.optimized(s), s, null, 0));
            }
            levels.add(level);
            return assemble(levels, new double[] {0}, false);
        }
    }

    /** A cluster hierarchy: detail chosen per meshlet on the GPU, from full detail down to a few meshlets. */
    @AllowAllocation("building meshes, offline")
    public MeshData buildHierarchy() {
        try (Work work = new Work()) {
            List<List<Cluster>> byDepth = new ArrayList<>();
            for (int s = 0; s < submeshes.size(); s++) {
                List<Cluster> pending = work.split(work.optimized(s), s, null, 0);
                add(byDepth, 0, pending);
                for (int depth = 1; pending.size() > 1; depth++) {
                    pending = work.simplifyGroups(pending, s);
                    if (!pending.isEmpty()) { // empty when no group simplified: the last level's meshlets are roots
                        add(byDepth, depth, pending);
                    }
                }
            }
            double[] errors = new double[byDepth.size()];
            double max = 0;
            for (int depth = 0; depth < errors.length; depth++) {
                for (Cluster cluster : byDepth.get(depth)) {
                    max = Math.max(max, cluster.selfError);
                }
                errors[depth] = max;
            }
            return assemble(rootsToCoarsest(byDepth), errors, false);
        }
    }

    /**
     * Discrete LODs: full detail, then up to {@code maxLevels - 1} simplifications of the whole mesh, each to
     * {@code ratio} of the previous level's triangles (submesh borders locked, so materials still meet), until
     * simplifying stops paying.
     */
    @AllowAllocation("building meshes, offline")
    public MeshData buildDiscrete(int maxLevels, double ratio) {
        if (maxLevels < 1 || !(ratio > 0 && ratio < 1)) {
            throw new IllegalArgumentException("Need at least one level and a ratio between 0 and 1: " + maxLevels
                + ", " + ratio);
        }
        try (Work work = new Work()) {
            List<int[]> current = new ArrayList<>();
            for (int s = 0; s < submeshes.size(); s++) {
                current.add(work.optimized(s));
            }
            List<List<int[]>> lods = new ArrayList<>();
            List<Double> lodErrors = new ArrayList<>();
            lods.add(current);
            lodErrors.add(0.0);
            double target = 1;
            for (int level = 1; level < maxLevels; level++) {
                target *= ratio;
                List<int[]> next = new ArrayList<>();
                double error = lodErrors.getLast();
                long before = 0;
                long after = 0;
                for (int s = 0; s < submeshes.size(); s++) {
                    int[] original = lods.getFirst().get(s);
                    float[] resultError = new float[1];
                    int[] simplified = work.simplify(original, (int) (original.length / 3 * target) * 3,
                        resultError);
                    next.add(simplified);
                    error = Math.max(error, resultError[0]);
                    before += current.get(s).length;
                    after += simplified.length;
                }
                if (!progress(after, before)) {
                    break;
                }
                lods.add(next);
                lodErrors.add(error);
                current = next;
            }
            List<List<Cluster>> levels = new ArrayList<>();
            double[] errors = new double[lods.size()];
            for (int level = 0; level < lods.size(); level++) {
                List<Cluster> clusters = new ArrayList<>();
                for (int s = 0; s < submeshes.size(); s++) {
                    clusters.addAll(work.split(lods.get(level).get(s), s, null, 0));
                }
                levels.add(clusters);
                errors[level] = lodErrors.get(level);
            }
            return assemble(levels, errors, true);
        }
    }

    /**
     * Whether simplifying {@code before} indices to {@code kept} made progress: something kept, and at most
     * {@code MIN_REDUCTION} of it. One comparison: kept lies in [3, MIN_REDUCTION * before].
     */
    private static boolean progress(long kept, long before) {
        double low = 3;
        double high = before * MIN_REDUCTION;
        return Math.abs(kept - (low + high) / 2) <= (high - low) / 2;
    }

    /**
     * Moves every root (a meshlet whose group would not simplify further: parent error +∞) into the coarsest level, so
     * all that can be drawn at coarse detail comes first: renderers may skip levels too fine for a distance, and
     * streaming loads the coarsest level first. No level empties: below the coarsest, every depth holds members of a
     * group that did simplify (its submesh went deeper), so levels and their errors stay as they are.
     */
    private static List<List<Cluster>> rootsToCoarsest(List<List<Cluster>> byDepth) {
        int coarsest = byDepth.size() - 1;
        List<List<Cluster>> out = new ArrayList<>();
        List<Cluster> moved = new ArrayList<>();
        for (int depth = 0; depth < coarsest; depth++) {
            List<Cluster> kept = new ArrayList<>();
            for (Cluster cluster : byDepth.get(depth)) {
                (cluster.parentError == Double.POSITIVE_INFINITY ? moved : kept).add(cluster);
            }
            out.add(kept);
        }
        List<Cluster> top = new ArrayList<>(byDepth.get(coarsest));
        top.addAll(moved);
        out.add(top);
        return out;
    }

    private static void add(List<List<Cluster>> byDepth, int depth, List<Cluster> clusters) {
        while (byDepth.size() <= depth) {
            byDepth.add(new ArrayList<>());
        }
        byDepth.get(depth).addAll(clusters);
    }

    /**
     * Writes the levels coarsest first: vertices reordered as the meshlets first use them (unused ones dropped), then
     * the meshlets with their bounds and, for a hierarchy, their detail selection data.
     */
    private MeshData assemble(List<List<Cluster>> finestFirst, double[] errors, boolean discrete) {
        List<Cluster> order = new ArrayList<>();
        for (int level = finestFirst.size() - 1; level >= 0; level--) {
            order.addAll(finestFirst.get(level));
        }
        int[] remap = fetchOrder(order);
        int used = 0;
        for (int v : remap) {
            used = Math.max(used, v + 1);
        }
        MeshData.Builder builder = MeshData.builder(format, used).positions(remapped(positions, 3, remap, used))
            .materialSlots(submeshes.size());
        for (VertexAttribute attribute : VertexAttribute.values()) {
            float[] values = attributes[attribute.ordinal()];
            if (values != null) {
                int components = values.length / vertexCount;
                float[] out = remapped(values, components, remap, used);
                switch (attribute) {
                    case NORMAL -> builder.normals(out);
                    case TANGENT -> builder.tangents(out);
                    case UV0, UV1 -> builder.uv(attribute, out);
                    case COLOR -> builder.colors(out);
                    default -> builder.weights(out); // WEIGHTS; JOINTS never get here
                }
            }
        }
        if (joints != null) {
            builder.joints(remappedJoints(remap, used));
        }
        for (int level = finestFirst.size() - 1; level >= 0; level--) {
            for (Cluster cluster : finestFirst.get(level)) {
                int[] vertices = new int[cluster.vertices.length];
                for (int i = 0; i < vertices.length; i++) {
                    vertices[i] = remap[cluster.vertices[i]];
                }
                builder.meshlet(vertices, cluster.triangles, cluster.submesh, cluster.bounds, cluster.cone);
                if (!discrete) {
                    builder.lod(cluster.self, cluster.selfError, cluster.parent, cluster.parentError);
                }
            }
            builder.level(errors[level]);
        }
        if (discrete) {
            builder.discreteLods();
        }
        return builder.build();
    }

    /** The new index of each vertex, in order of first use by the meshlets; unused vertices get -1. */
    private int[] fetchOrder(List<Cluster> order) {
        int total = 0;
        for (Cluster cluster : order) {
            total += cluster.vertices.length;
        }
        IntBuffer indices = MemoryUtil.memAllocInt(total);
        IntBuffer remap = MemoryUtil.memAllocInt(vertexCount);
        try {
            for (Cluster cluster : order) {
                indices.put(cluster.vertices);
            }
            indices.flip();
            meshopt_optimizeVertexFetchRemap(remap, indices);
            int[] out = new int[vertexCount];
            remap.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(indices);
            MemoryUtil.memFree(remap);
        }
    }

    private static float[] remapped(float[] values, int components, int[] remap, int used) {
        float[] out = new float[used * components];
        for (int v = 0; v < remap.length; v++) {
            if (remap[v] >= 0) {
                System.arraycopy(values, v * components, out, remap[v] * components, components);
            }
        }
        return out;
    }

    private int[] remappedJoints(int[] remap, int used) {
        int[] out = new int[used * 4];
        for (int v = 0; v < remap.length; v++) {
            if (remap[v] >= 0) {
                System.arraycopy(joints, v * 4, out, remap[v] * 4, 4);
            }
        }
        return out;
    }

    /** A meshlet being built: its vertices, local triangles, bounds and detail selection data. */
    @AllowAllocation("building meshes, offline")
    private static final class Cluster {
        final int[] vertices;
        final int[] triangles;
        final int submesh;
        final Sphere bounds;
        final Cone cone;
        final Sphere self;
        final double selfError;
        Sphere parent;
        double parentError = Double.POSITIVE_INFINITY;

        Cluster(int[] vertices, int[] triangles, int submesh, Sphere bounds, Cone cone, Sphere self,
            double selfError) {
            this.vertices = vertices;
            this.triangles = triangles;
            this.submesh = submesh;
            this.bounds = bounds;
            this.cone = cone;
            this.self = self == null ? bounds : self;
            this.selfError = selfError;
            this.parent = this.self;
        }

        /** Its triangles as vertex indices of the mesh. */
        int[] indices() {
            int[] out = new int[triangles.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = vertices[triangles[i]];
            }
            return out;
        }
    }

    /** One build's native buffers: the positions, shared by every meshoptimizer call. */
    @AllowAllocation("building meshes, offline")
    private final class Work implements AutoCloseable {
        final FloatBuffer nativePositions;

        Work() {
            if (positions == null) {
                throw new IllegalStateException("A mesh needs positions");
            }
            if (submeshes.isEmpty()) {
                throw new IllegalStateException("A mesh needs a submesh");
            }
            nativePositions = MemoryUtil.memAllocFloat(positions.length).put(positions).flip();
        }

        /** Submesh {@code s}'s triangles in vertex cache order. */
        int[] optimized(int s) {
            int[] source = submeshes.get(s);
            IntBuffer in = MemoryUtil.memAllocInt(source.length).put(source).flip();
            IntBuffer out = MemoryUtil.memAllocInt(source.length);
            try {
                meshopt_optimizeVertexCache(out, in, vertexCount);
                int[] result = new int[source.length];
                out.get(result);
                return result;
            } finally {
                MemoryUtil.memFree(in);
                MemoryUtil.memFree(out);
            }
        }

        /** Splits triangles into meshlets, each with its bounds and the given detail selection data. */
        List<Cluster> split(int[] indices, int submesh, Sphere self, double selfError) {
            int bound = (int) meshopt_buildMeshletsBound(indices.length, maxVertices, maxTriangles);
            MeshoptMeshlet.Buffer meshlets = MeshoptMeshlet.malloc(bound);
            IntBuffer meshletVertices = MemoryUtil.memAllocInt(bound * maxVertices);
            ByteBuffer meshletTriangles = MemoryUtil.memAlloc(bound * maxTriangles * 3);
            IntBuffer in = MemoryUtil.memAllocInt(indices.length).put(indices).flip();
            MeshoptBounds bounds = MeshoptBounds.malloc();
            try {
                int count = (int) meshopt_buildMeshlets(meshlets, meshletVertices, meshletTriangles, in,
                    nativePositions, vertexCount, 12, maxVertices, maxTriangles, coneWeight);
                List<Cluster> out = new ArrayList<>(count);
                for (int m = 0; m < count; m++) {
                    MeshoptMeshlet meshlet = meshlets.get(m);
                    IntBuffer vertices = meshletVertices.slice(meshlet.vertex_offset(), meshlet.vertex_count());
                    ByteBuffer triangles = meshletTriangles.slice(meshlet.triangle_offset(),
                        meshlet.triangle_count() * 3);
                    meshopt_optimizeMeshlet(vertices, triangles);
                    meshopt_computeMeshletBounds(vertices, triangles, nativePositions, vertexCount, 12, bounds);
                    int[] vertexArray = new int[vertices.remaining()];
                    vertices.get(0, vertexArray);
                    int[] triangleArray = new int[triangles.remaining()];
                    for (int i = 0; i < triangleArray.length; i++) {
                        triangleArray[i] = Byte.toUnsignedInt(triangles.get(i));
                    }
                    Sphere sphere = new Sphere(bounds.center(0), bounds.center(1), bounds.center(2), bounds.radius());
                    Cone cone = new Cone(bounds.cone_axis(0), bounds.cone_axis(1), bounds.cone_axis(2),
                        Math.clamp(bounds.cone_cutoff(), -1, 1));
                    out.add(new Cluster(vertexArray, triangleArray, submesh, sphere, cone, self, selfError));
                }
                return out;
            } finally {
                meshlets.free();
                MemoryUtil.memFree(meshletVertices);
                MemoryUtil.memFree(meshletTriangles);
                MemoryUtil.memFree(in);
                bounds.free();
            }
        }

        /**
         * One level of a cluster hierarchy: partitions {@code clusters} into groups, simplifies each group to half
         * its triangles with its border locked and splits it into meshlets again. A group that will not simplify
         * enough keeps its meshlets as roots. Returns the new meshlets.
         */
        List<Cluster> simplifyGroups(List<Cluster> clusters, int submesh) {
            int[] groupOf = partition(clusters);
            int groups = 0;
            for (int g : groupOf) {
                groups = Math.max(groups, g + 1);
            }
            List<Cluster> next = new ArrayList<>();
            for (int g = 0; g < groups; g++) {
                List<Cluster> members = new ArrayList<>();
                for (int c = 0; c < clusters.size(); c++) {
                    if (groupOf[c] == g) {
                        members.add(clusters.get(c));
                    }
                }
                int total = 0;
                for (Cluster member : members) {
                    total += member.triangles.length;
                }
                int[] merged = new int[total];
                int at = 0;
                double childError = 0;
                for (Cluster member : members) {
                    int[] indices = member.indices();
                    System.arraycopy(indices, 0, merged, at, indices.length);
                    at += indices.length;
                    childError = Math.max(childError, member.selfError);
                }
                float[] resultError = new float[1];
                int[] simplified = simplify(merged, merged.length / 6 * 3, resultError);
                if (!progress(simplified.length, merged.length)) {
                    continue; // will not simplify: its meshlets stay roots
                }
                Sphere group = enclose(members);
                double error = childError + resultError[0];
                for (Cluster member : members) {
                    member.parent = group;
                    member.parentError = error;
                }
                next.addAll(split(simplified, submesh, group, error));
            }
            return next;
        }

        /** Each cluster's group: spatially close, connected clusters, about {@code groupSize} each. */
        private int[] partition(List<Cluster> clusters) {
            int total = 0;
            for (Cluster cluster : clusters) {
                total += cluster.triangles.length;
            }
            IntBuffer indices = MemoryUtil.memAllocInt(total);
            IntBuffer counts = MemoryUtil.memAllocInt(clusters.size());
            IntBuffer out = MemoryUtil.memAllocInt(clusters.size());
            try {
                for (Cluster cluster : clusters) {
                    indices.put(cluster.indices());
                    counts.put(cluster.triangles.length);
                }
                indices.flip();
                counts.flip();
                meshopt_partitionClusters(out, indices, counts, nativePositions, vertexCount, 12, groupSize);
                int[] result = new int[clusters.size()];
                out.get(result);
                return result;
            } finally {
                MemoryUtil.memFree(indices);
                MemoryUtil.memFree(counts);
                MemoryUtil.memFree(out);
            }
        }

        /** A sphere around the members' detail spheres, so a parent's sphere holds its children's. */
        private Sphere enclose(List<Cluster> members) {
            FloatBuffer centers = MemoryUtil.memAllocFloat(3 * members.size());
            FloatBuffer radii = MemoryUtil.memAllocFloat(members.size());
            MeshoptBounds bounds = MeshoptBounds.malloc();
            try {
                for (Cluster member : members) {
                    centers.put((float) member.self.x()).put((float) member.self.y()).put((float) member.self.z());
                    radii.put((float) member.self.radius());
                }
                centers.flip();
                radii.flip();
                meshopt_computeSphereBounds(centers, members.size(), 12, radii, 4, bounds);
                return new Sphere(bounds.center(0), bounds.center(1), bounds.center(2), bounds.radius());
            } finally {
                MemoryUtil.memFree(centers);
                MemoryUtil.memFree(radii);
                bounds.free();
            }
        }

        /**
         * Simplifies triangles towards {@code targetIndices}, keeping original vertices and their open border, so
         * neighbors still meet. Writes the error, in mesh units, to {@code error[0]}.
         */
        int[] simplify(int[] indices, int targetIndices, float[] error) {
            IntBuffer in = MemoryUtil.memAllocInt(indices.length).put(indices).flip();
            IntBuffer out = MemoryUtil.memAllocInt(indices.length);
            FloatBuffer resultError = MemoryUtil.memAllocFloat(1);
            try {
                int options = meshopt_SimplifyErrorAbsolute | meshopt_SimplifySparse | meshopt_SimplifyLockBorder;
                int count = (int) meshopt_simplify(out, in, nativePositions, vertexCount, 12, targetIndices,
                    Float.MAX_VALUE, options, resultError);
                error[0] = resultError.get(0);
                int[] result = new int[count];
                out.get(0, result);
                return result;
            } finally {
                MemoryUtil.memFree(in);
                MemoryUtil.memFree(out);
                MemoryUtil.memFree(resultError);
            }
        }

        @Override
        public void close() {
            MemoryUtil.memFree(nativePositions);
        }
    }
}
