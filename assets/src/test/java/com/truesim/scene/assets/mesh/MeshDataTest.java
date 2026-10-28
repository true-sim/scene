package com.truesim.scene.assets.mesh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** The mesh format: encoded as the GPU reads it, decoded as shaders do, with both kinds of detail levels. */
class MeshDataTest {

    private static final VertexFormat ALL_ATTRIBUTES = VertexFormat.of(VertexAttribute.values());
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** A 2 x 1 grid of quads: 6 vertices, 4 triangles; its 2 meshlets each hold one quad. */
    private static final float[] POSITIONS = {
        0, 0, 0, 1, 0, 0, 2, 0, 0,
        0, 0, 1, 1, 0.5f, 1, 2, 0, 1
    };
    private static final int[] TRIANGLES = {0, 3, 1, 1, 3, 4, 1, 4, 2, 2, 4, 5};

    private static MeshData.Builder grid() {
        float[] normals = new float[18];
        float[] tangents = new float[24];
        float[] uvs = new float[12];
        float[] colors = new float[24];
        float[] weights = new float[24];
        int[] joints = new int[24];
        for (int v = 0; v < 6; v++) {
            normals[3 * v + 1] = 1;
            normals[3 * v + 2] = v == 4 ? -0.3f : 0; // one normal pointing below the horizon
            tangents[4 * v] = 1;
            tangents[4 * v + 3] = v % 2 == 0 ? 1 : -1;
            uvs[2 * v] = POSITIONS[3 * v] / 2;
            uvs[2 * v + 1] = POSITIONS[3 * v + 2];
            for (int i = 0; i < 4; i++) {
                colors[4 * v + i] = i / 3f;
                weights[4 * v + i] = i == 0 ? 1 : 0;
                joints[4 * v + i] = v * 10 + i;
            }
        }
        return MeshData.builder(ALL_ATTRIBUTES, 6)
            .positions(POSITIONS)
            .normals(normals)
            .tangents(tangents)
            .uv(VertexAttribute.UV0, uvs)
            .uv(VertexAttribute.UV1, uvs)
            .colors(colors)
            .weights(weights)
            .joints(joints)
            .materialSlots(2);
    }

    private static MeshData.Builder twoMeshlets(MeshData.Builder builder) {
        return builder
            .meshlet(new int[] {0, 3, 1, 4}, new int[] {0, 1, 2, 2, 1, 3}, 0, new Sphere(0.5, 0, 0.5, 0.8),
                new Cone(0, 1, 0, 0.5))
            .meshlet(new int[] {1, 4, 2, 5}, new int[] {0, 1, 2, 2, 1, 3}, 1, new Sphere(1.5, 0, 0.5, 0.8),
                Cone.NONE);
    }

    @Test
    void attributesDecodeAsTheGpuReadsThem() {
        MeshData mesh = twoMeshlets(grid()).build();
        assertThat(mesh.vertexCount()).isEqualTo(6);
        assertThat(mesh.format().stride()).isEqualTo(28);
        assertThat(mesh.positions().byteSize()).isEqualTo(6 * 8);
        assertThat(mesh.attributes().byteSize()).isEqualTo(6 * 28);
        assertThat(mesh.boxMin(0)).isZero();
        assertThat(mesh.boxExtent(0)).isEqualTo(2);
        assertThat(mesh.boxExtent(1)).isEqualTo(0.5);
        assertThat(mesh.bounds().x()).isEqualTo(1);
        assertThat(mesh.bounds().radius()).isCloseTo(Math.sqrt(1 + 0.0625 + 0.25), within(1e-12));
        double[] out = new double[4];
        int[] joints = new int[4];
        for (int v = 0; v < 6; v++) {
            mesh.position(v, out);
            for (int axis = 0; axis < 3; axis++) {
                assertThat(out[axis]).isCloseTo(POSITIONS[3 * v + axis], within(mesh.boxExtent(axis) / 65535));
            }
            mesh.normal(v, out);
            double length = v == 4 ? Math.sqrt(1 + 0.09) : 1;
            assertThat(out[1]).isCloseTo(1 / length, within(1e-4));
            assertThat(out[2]).isCloseTo(v == 4 ? -0.3 / length : 0, within(1e-4));
            mesh.tangent(v, out);
            assertThat(out[0]).isCloseTo(1, within(1e-4));
            assertThat(out[3]).isEqualTo(v % 2 == 0 ? 1 : -1);
            mesh.uv(VertexAttribute.UV0, v, out);
            assertThat(out[1]).isCloseTo(POSITIONS[3 * v + 2], within(1e-3));
            mesh.uv(VertexAttribute.UV1, v, out);
            assertThat(out[0]).isCloseTo(POSITIONS[3 * v] / 2, within(1e-3));
            mesh.unorm4(VertexAttribute.COLOR, v, out);
            assertThat(out[1]).isCloseTo(1 / 3.0, within(0.5 / 255));
            mesh.unorm4(VertexAttribute.WEIGHTS, v, out);
            assertThat(out[0]).isEqualTo(1);
            mesh.joints(v, joints);
            assertThat(joints).containsExactly(v * 10, v * 10 + 1, v * 10 + 2, v * 10 + 3);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> mesh.uv(VertexAttribute.NORMAL, 0, out));
        assertThatIllegalArgumentException().isThrownBy(() -> mesh.unorm4(VertexAttribute.UV0, 0, out));
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> mesh.position(6, out));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> mesh.positions().set(ValueLayout.JAVA_BYTE, 0, (byte) 1)); // read-only
    }

    @Test
    void meshletsDrawWithMeshShadersOrAsIndexedTriangles() {
        MeshData mesh = twoMeshlets(grid()).build();
        assertThat(mesh.meshletCount()).isEqualTo(2);
        assertThat(mesh.triangleCount()).isEqualTo(4);
        assertThat(mesh.materialSlots()).isEqualTo(2);
        assertThat(mesh.meshletVertexOffset(1)).isEqualTo(4);
        assertThat(mesh.meshletTriangleOffset(1)).isEqualTo(6);
        assertThat(mesh.meshletVertexCount(1)).isEqualTo(4);
        assertThat(mesh.meshletTriangleCount(1)).isEqualTo(2);
        assertThat(mesh.meshletSubmesh(1)).isEqualTo(1);
        double[] out = new double[10];
        mesh.meshletSphere(1, out);
        assertThat(out).startsWith(1.5, 0, 0.5, 0.800000011920929);
        // Cones: the axis rounded, the cutoff rounded up, so a facing meshlet is never culled.
        mesh.meshletCone(0, out);
        assertThat(out[1]).isEqualTo(1);
        assertThat(out[3]).isGreaterThan(0.5);
        mesh.meshletCone(1, out);
        assertThat(out[3]).isEqualTo(1); // never culls
        // The fallback index buffer: the original triangles, meshlet after meshlet.
        MemorySegment indices = mesh.expandIndices();
        int[] expanded = new int[12];
        for (int k = 0; k < 12; k++) {
            expanded[k] = indices.get(U32, 4L * k);
        }
        assertThat(expanded).containsExactly(TRIANGLES);
        assertThat(mesh.bytes()).isEqualTo(48 + 168 + 32 + 12 + 64 + 80);
        assertThat(mesh.meshletVertices().byteSize()).isEqualTo(32);
        assertThat(mesh.meshletTriangles().byteSize()).isEqualTo(12);
        assertThat(mesh.meshlets().byteSize()).isEqualTo(2L * MeshData.MESHLET_BYTES);
        assertThat(mesh.clusterLod().byteSize()).isEqualTo(2L * MeshData.CLUSTER_LOD_BYTES);
        // Without levels: one level, every meshlet always drawn.
        assertThat(mesh.levels()).containsExactly(new MeshLevel(0, 2, 0));
        mesh.meshletLod(0, out);
        assertThat(out[3]).isEqualTo((float) 0.8); // the meshlet's own sphere
        assertThat(out[4]).isZero();
        assertThat(out[9]).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void levelsWorkAsAClusterHierarchy() {
        Sphere coarse = new Sphere(1, 0, 0.5, 1.2);
        MeshData mesh = MeshData.builder(VertexFormat.POSITIONS_ONLY, 6)
            .positions(POSITIONS)
            .meshlet(new int[] {0, 2, 3, 5}, new int[] {0, 2, 1, 1, 2, 3}, 0, coarse, Cone.NONE) // the simplified
            .lod(coarse, 0.25, coarse, Double.POSITIVE_INFINITY)
            .level(0.25)
            .meshlet(new int[] {0, 3, 1, 4}, new int[] {0, 1, 2, 2, 1, 3}, 0, new Sphere(0.5, 0, 0.5, 0.8),
                Cone.NONE)
            .lod(new Sphere(0.5, 0, 0.5, 0.8), 0, coarse, 0.25)
            .meshlet(new int[] {1, 4, 2, 5}, new int[] {0, 1, 2, 2, 1, 3}, 0, new Sphere(1.5, 0, 0.5, 0.8),
                Cone.NONE)
            .lod(new Sphere(1.5, 0, 0.5, 0.8), 0, coarse, 0.25)
            .level(0)
            .build();
        assertThat(mesh.levels()).containsExactly(new MeshLevel(0, 1, 0.25), new MeshLevel(1, 2, 0));
        double[] lod = new double[10];
        mesh.meshletLod(2, lod);
        assertThat(lod).containsExactly(1.5, 0, 0.5, (float) 0.8, 0, 1, 0, 0.5, (float) 1.2, 0.25);
        assertThat(mesh.attributes().byteSize()).isZero();
    }

    @Test
    void levelsWorkAsDiscreteLods() {
        MeshData mesh = MeshData.builder(VertexFormat.POSITIONS_ONLY, 6)
            .positions(POSITIONS)
            .meshlet(new int[] {0, 2, 3, 5}, new int[] {0, 2, 1, 1, 2, 3}, 0, new Sphere(1, 0, 0.5, 1.2), Cone.NONE)
            .level(0.5)
            .meshlet(new int[] {0, 3, 1, 4}, new int[] {0, 1, 2, 2, 1, 3}, 0, new Sphere(0.5, 0, 0.5, 0.8),
                Cone.NONE)
            .meshlet(new int[] {1, 4, 2, 5}, new int[] {0, 1, 2, 2, 1, 3}, 0, new Sphere(1.5, 0, 0.5, 0.8),
                Cone.NONE)
            .level(0)
            .discreteLods()
            .build();
        double[] lod = new double[10];
        Sphere b = mesh.bounds();
        mesh.meshletLod(0, lod); // the coarsest level: its error, parent +∞, around the whole mesh
        assertThat(lod).containsExactly((float) b.x(), (float) b.y(), (float) b.z(), (float) b.radius(), 0.5,
            (float) b.x(), (float) b.y(), (float) b.z(), (float) b.radius(), Double.POSITIVE_INFINITY);
        mesh.meshletLod(2, lod); // full detail: error 0, parent the coarser level's
        assertThat(lod[4]).isZero();
        assertThat(lod[9]).isEqualTo(0.5);
    }

    @Test
    void buildingIsChecked() {
        MeshData.Builder empty = MeshData.builder(VertexFormat.POSITIONS_ONLY, 6);
        assertThatIllegalStateException().isThrownBy(empty::build).withMessageContaining("positions");
        empty.positions(POSITIONS);
        assertThatIllegalStateException().isThrownBy(empty::build).withMessageContaining("meshlets");
        assertThatIllegalStateException().isThrownBy(() -> empty.lod(new Sphere(0, 0, 0, 1), 0,
            new Sphere(0, 0, 0, 1), 1));
        assertThatIllegalStateException().isThrownBy(() -> empty.level(0));
        assertThatIllegalArgumentException().isThrownBy(() -> MeshData.builder(VertexFormat.POSITIONS_ONLY, 0));
        assertThatNullPointerException().isThrownBy(() -> MeshData.builder(null, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.positions(new float[3]));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.positions(new float[] {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Float.NaN, 0, 0}));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.normals(new float[18]));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.joints(new int[24]));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.uv(VertexAttribute.COLOR, new float[12]));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.materialSlots(0));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.materialSlots(65537));
        Sphere s = new Sphere(0, 0, 0, 1);
        int[] tri = {0, 1, 2};
        assertThatIllegalArgumentException().isThrownBy(() -> empty.meshlet(new int[0], tri, 0, s, Cone.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.meshlet(new int[256], tri, 0, s, Cone.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.meshlet(new int[3], new int[2], 0, s, Cone.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.meshlet(new int[3], new int[4], 0, s, Cone.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.meshlet(new int[3], new int[768], 0, s,
            Cone.NONE));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
            .isThrownBy(() -> empty.meshlet(new int[] {0, 1, 6}, tri, 0, s, Cone.NONE));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
            .isThrownBy(() -> empty.meshlet(new int[] {0, 1, 2}, new int[] {0, 1, 3}, 0, s, Cone.NONE));
        assertThatExceptionOfType(IndexOutOfBoundsException.class)
            .isThrownBy(() -> empty.meshlet(new int[] {0, 1, 2}, tri, 1, s, Cone.NONE));
        assertThatNullPointerException().isThrownBy(() -> empty.meshlet(new int[] {0, 1, 2}, tri, 0, null, Cone.NONE));
        assertThatNullPointerException().isThrownBy(() -> empty.meshlet(new int[] {0, 1, 2}, tri, 0, s, null));
        empty.meshlet(new int[] {0, 1, 2}, tri, 0, s, new Cone(0, 0, 0, 0.5)); // a zero axis: never culls
        assertThatIllegalArgumentException().isThrownBy(() -> empty.lod(s, -1, s, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.lod(s, 2, s, 1));
        assertThatNullPointerException().isThrownBy(() -> empty.lod(null, 0, s, 1));
        assertThatNullPointerException().isThrownBy(() -> empty.lod(s, 0, null, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> empty.level(-1));
        empty.level(1);
        empty.meshlet(new int[] {0, 1, 2}, tri, 0, s, Cone.NONE);
        assertThatIllegalArgumentException().isThrownBy(() -> empty.level(2)); // errors fall level by level
        assertThatIllegalStateException().isThrownBy(empty::build).withMessageContaining("last level");
        MeshData built = empty.level(0).build();
        double[] cone = new double[4];
        built.meshletCone(0, cone);
        assertThat(cone[3]).isEqualTo(1);
        // Attributes in the format must be given, and only those.
        MeshData.Builder missing = MeshData.builder(VertexFormat.of(VertexAttribute.JOINTS), 6).positions(POSITIONS)
            .meshlet(new int[] {0, 1, 2}, tri, 0, s, Cone.NONE);
        assertThatIllegalStateException().isThrownBy(missing::build).withMessageContaining("JOINTS");
        assertThatIllegalArgumentException().isThrownBy(() -> missing.joints(new int[3]));
        int[] badJoints = new int[24];
        badJoints[5] = 256;
        assertThatIllegalArgumentException().isThrownBy(() -> missing.joints(badJoints));
        badJoints[5] = -1;
        assertThatIllegalArgumentException().isThrownBy(() -> missing.joints(badJoints));
        assertThatIllegalStateException().isThrownBy(() -> MeshData.builder(VertexFormat.of(VertexAttribute.UV0), 6)
            .positions(POSITIONS).meshlet(new int[] {0, 1, 2}, tri, 0, s, Cone.NONE).build())
            .withMessageContaining("UV0");
    }

    @Test
    void formatsEncodingsAndBoundsAreChecked() {
        VertexFormat lit = VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.TANGENT, VertexAttribute.UV0);
        assertThat(lit.stride()).isEqualTo(12);
        assertThat(lit.offset(VertexAttribute.UV0)).isEqualTo(8);
        assertThat(lit.has(VertexAttribute.COLOR)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> lit.offset(VertexAttribute.COLOR));
        assertThatIllegalArgumentException().isThrownBy(() -> new VertexFormat(1 << 7));
        assertThat(VertexFormat.POSITIONS_ONLY.stride()).isZero();
        // A flat mesh: its box has no height, every height decodes to the plane.
        MeshData flat = MeshData.builder(VertexFormat.POSITIONS_ONLY, 3).positions(new float[] {0, 2, 0, 1, 2, 0, 0, 2, 1})
            .meshlet(new int[] {0, 1, 2}, new int[] {0, 1, 2}, 0, new Sphere(0.5, 2, 0.5, 1), Cone.NONE).build();
        double[] p = new double[3];
        flat.position(1, p);
        assertThat(p).containsExactly(1, 2, 0);
        assertThat(flat.boxExtent(1)).isZero();
        double[] out = new double[3];
        Encoding.octahedral(0, 0, 0, out);
        assertThat(out[0]).isZero();
        Encoding.octahedral(0.3, -0.4, -0.866, out);
        Encoding.fromOctahedral(out[0], out[1], out);
        assertThat(out[2]).isCloseTo(-0.866 / Math.sqrt(0.09 + 0.16 + 0.866 * 0.866), within(1e-9));
        assertThat(Encoding.fromSnorm(-32768, 16)).isEqualTo(-1);
        assertThat(Encoding.unorm(2, 8)).isEqualTo(255);
        assertThat(Encoding.snorm(-2, 8)).isEqualTo(-127);
        assertThat(Encoding.fromHalf(Encoding.half(0.5))).isEqualTo(0.5);
        assertThatIllegalArgumentException().isThrownBy(() -> new Sphere(0, 0, 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Sphere(Double.NaN, 0, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new Sphere(0, 0, 0, Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> new Cone(0, 0, 1, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new Cone(0, 0, 1, Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> new Cone(Double.NaN, 0, 1, 0));
    }
}
