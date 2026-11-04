package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.PickMesh;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The {@link PickMesh}es of mesh assets, for precise picking: one per {@link MeshData}, made from its CPU copy on first
 * use and kept as long as the asset is (dropped with it), shared by every node showing the mesh.
 *
 * <p>A mesh's triangles are those of its leaf meshlets, whose own error is 0: a cluster hierarchy's finest level is not
 * all of the mesh, the leaves are. Positions are dequantized into the mesh's space. Skinned meshes are posed per node
 * ({@link RenderablePicking}). Used on one thread.
 */
public final class PickMeshes {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final Map<MeshData, PickMesh> meshes = new WeakHashMap<>();

    /** The pick mesh of {@code mesh}, made on the first call. */
    @AllowAllocation("made once per mesh asset")
    public PickMesh of(MeshData mesh) {
        return meshes.computeIfAbsent(mesh, PickMeshes::build);
    }

    /** How many meshes have one. */
    public int count() {
        return meshes.size();
    }

    /** Drops {@code mesh}'s pick mesh (the asset was unloaded). */
    public void forget(MeshData mesh) {
        meshes.remove(mesh);
    }

    /** A pick mesh of {@code mesh}'s leaf meshlets' triangles, over all its vertices. */
    @AllowAllocation("made once per mesh asset")
    public static PickMesh build(MeshData mesh) {
        int vertices = mesh.vertexCount();
        float[] positions = new float[vertices * 3];
        double[] p = new double[3];
        for (int v = 0; v < vertices; v++) {
            mesh.position(v, p);
            positions[v * 3] = (float) p[0];
            positions[v * 3 + 1] = (float) p[1];
            positions[v * 3 + 2] = (float) p[2];
        }
        return new PickMesh(positions, indices(mesh));
    }

    /** The vertex indices of {@code mesh}'s leaf meshlets' triangles, three per triangle. */
    @AllowAllocation("made once per mesh asset")
    static int[] indices(MeshData mesh) {
        MemorySegment meshlets = mesh.meshlets();
        MemorySegment lod = mesh.clusterLod();
        MemorySegment vertexIndices = mesh.meshletVertices();
        MemorySegment triangles = mesh.meshletTriangles();
        int count = 0;
        for (int m = 0; m < mesh.meshletCount(); m++) {
            if (leaf(lod, m)) {
                count += Byte.toUnsignedInt(meshlets.get(ValueLayout.JAVA_BYTE, (long) m * MeshData.MESHLET_BYTES + 9));
            }
        }
        int[] indices = new int[count * 3];
        int k = 0;
        for (int m = 0; m < mesh.meshletCount(); m++) {
            if (!leaf(lod, m)) {
                continue;
            }
            long record = (long) m * MeshData.MESHLET_BYTES;
            int vertexOffset = meshlets.get(U32, record);
            int triangleOffset = meshlets.get(U32, record + 4);
            int triangleCount = Byte.toUnsignedInt(meshlets.get(ValueLayout.JAVA_BYTE, record + 9));
            for (int i = 0; i < triangleCount * 3; i++) {
                int local = Byte.toUnsignedInt(triangles.get(ValueLayout.JAVA_BYTE, triangleOffset + i));
                indices[k++] = vertexIndices.get(U32, (long) (vertexOffset + local) * 4);
            }
        }
        return indices;
    }

    /** Whether meshlet {@code m} is a leaf: its own group's error is 0 (every meshlet of a mesh without a hierarchy). */
    private static boolean leaf(MemorySegment lod, int m) {
        return lod.get(F32, (long) m * MeshData.CLUSTER_LOD_BYTES + 16) == 0;
    }
}
