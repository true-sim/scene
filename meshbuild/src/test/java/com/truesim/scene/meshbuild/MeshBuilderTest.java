package com.truesim.scene.meshbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/** Meshes built with meshoptimizer: complete, within limits, and with detail levels that keep the surface whole. */
class MeshBuilderTest {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int N = 64; // quads per side

    /** A bumpy N x N grid of quads, 1 unit apart: height = 0.6 sin(x / 3) cos(z / 4). */
    private static float[] grid() {
        float[] positions = new float[(N + 1) * (N + 1) * 3];
        for (int z = 0; z <= N; z++) {
            for (int x = 0; x <= N; x++) {
                int v = z * (N + 1) + x;
                positions[3 * v] = x;
                positions[3 * v + 1] = (float) (0.6 * Math.sin(x / 3.0) * Math.cos(z / 4.0));
                positions[3 * v + 2] = z;
            }
        }
        return positions;
    }

    /** The grid's triangles, two per quad, for quads with {@code fromX <= x < toX}. */
    private static int[] triangles(int fromX, int toX, int rows) {
        int[] indices = new int[(toX - fromX) * rows * 6];
        int at = 0;
        for (int z = 0; z < rows; z++) {
            for (int x = fromX; x < toX; x++) {
                int a = z * (N + 1) + x;
                int b = a + 1;
                int c = a + N + 1;
                int d = c + 1;
                int[] quad = {a, c, b, b, c, d};
                System.arraycopy(quad, 0, indices, at, 6);
                at += 6;
            }
        }
        return indices;
    }

    private static int vertices() {
        return (N + 1) * (N + 1);
    }

    /** The area of meshlet {@code m}'s triangles, from decoded positions. */
    private static double area(MeshData mesh, MemorySegment indices, int m) {
        double area = 0;
        double[] a = new double[3];
        double[] b = new double[3];
        double[] c = new double[3];
        int first = mesh.meshletTriangleOffset(m);
        for (int k = first; k < first + 3 * mesh.meshletTriangleCount(m); k += 3) {
            mesh.position(indices.get(U32, 4L * k), a);
            mesh.position(indices.get(U32, 4L * (k + 1)), b);
            mesh.position(indices.get(U32, 4L * (k + 2)), c);
            double ux = b[0] - a[0];
            double uy = b[1] - a[1];
            double uz = b[2] - a[2];
            double vx = c[0] - a[0];
            double vy = c[1] - a[1];
            double vz = c[2] - a[2];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            area += Math.sqrt(nx * nx + ny * ny + nz * nz) / 2;
        }
        return area;
    }

    private static double totalArea(MeshData mesh, MemorySegment indices, MeshLevel level) {
        double area = 0;
        for (int m = level.firstMeshlet(); m < level.firstMeshlet() + level.meshletCount(); m++) {
            area += area(mesh, indices, m);
        }
        return area;
    }

    @Test
    void flatMeshesKeepEveryTriangleInMeshletsWithinLimits() {
        MeshData mesh = new MeshBuilder(VertexFormat.POSITIONS_ONLY, vertices())
            .positions(grid())
            .submesh(triangles(0, N, N))
            .meshletSize(64, 124)
            .buildFlat();
        assertThat(mesh.triangleCount()).isEqualTo(N * N * 2);
        assertThat(mesh.levels()).hasSize(1);
        assertThat(mesh.vertexCount()).isEqualTo(vertices());
        double[] p = new double[3];
        double[] sphere = new double[4];
        for (int m = 0; m < mesh.meshletCount(); m++) {
            assertThat(mesh.meshletVertexCount(m)).isLessThanOrEqualTo(64);
            assertThat(mesh.meshletTriangleCount(m)).isLessThanOrEqualTo(124);
            mesh.meshletSphere(m, sphere);
            for (int i = 0; i < mesh.meshletVertexCount(m); i++) {
                mesh.position(mesh.meshletVertices().get(U32, 4L * (mesh.meshletVertexOffset(m) + i)), p);
                double d = Math.sqrt(Math.pow(p[0] - sphere[0], 2) + Math.pow(p[1] - sphere[1], 2)
                    + Math.pow(p[2] - sphere[2], 2));
                assertThat(d).isLessThanOrEqualTo(sphere[3] + 1e-3);
            }
        }
        // Mostly full meshlets: at most 35 % more than the minimum.
        assertThat(mesh.meshletCount()).isLessThanOrEqualTo((int) Math.ceil(N * N * 2 / 124.0 * 1.35));
    }

    @Test
    void clusterHierarchiesKeepTheSurfaceWholeAtEveryDetail() {
        MeshData mesh = new MeshBuilder(VertexFormat.POSITIONS_ONLY, vertices())
            .positions(grid())
            .submesh(triangles(0, N, N))
            .buildHierarchy();
        var levels = mesh.levels();
        assertThat(levels.size()).isGreaterThanOrEqualTo(4);
        assertThat(levels.getLast().error()).isZero(); // full detail
        assertThat(levels.getFirst().meshletCount()).isLessThan(levels.getLast().meshletCount() / 8);
        for (int i = 1; i < levels.size(); i++) {
            assertThat(levels.get(i).error()).isLessThanOrEqualTo(levels.get(i - 1).error());
        }
        MemorySegment indices = mesh.expandIndices();
        double fullArea = totalArea(mesh, indices, levels.getLast());
        int leafTriangles = 0;
        for (int m = levels.getLast().firstMeshlet(); m < mesh.meshletCount(); m++) {
            leafTriangles += mesh.meshletTriangleCount(m);
        }
        assertThat(leafTriangles).isEqualTo(N * N * 2);
        // Each meshlet's parent group holds its group, with an error at least as large.
        double[] lod = new double[10];
        for (int m = 0; m < mesh.meshletCount(); m++) {
            mesh.meshletLod(m, lod);
            assertThat(lod[9]).isGreaterThanOrEqualTo(lod[4]);
            if (lod[9] != Double.POSITIVE_INFINITY) {
                double d = Math.sqrt(Math.pow(lod[5] - lod[0], 2) + Math.pow(lod[6] - lod[1], 2)
                    + Math.pow(lod[7] - lod[2], 2));
                assertThat(d + lod[3]).isLessThanOrEqualTo(lod[8] * 1.0001 + 1e-4);
            }
        }
        // Cut the hierarchy at several errors with the selection rule: the selected meshlets cover the surface once.
        int previous = Integer.MAX_VALUE;
        for (double threshold : new double[] {0, 0.001, 0.01, 0.05, 0.2, 1e9}) {
            double area = 0;
            int selected = 0;
            for (int m = 0; m < mesh.meshletCount(); m++) {
                mesh.meshletLod(m, lod);
                if (lod[4] <= threshold && lod[9] > threshold) {
                    area += area(mesh, indices, m);
                    selected++;
                }
            }
            assertThat(area).as("area at error %s", threshold).isCloseTo(fullArea, within(fullArea * 0.02));
            assertThat(selected).isLessThanOrEqualTo(previous);
            previous = selected;
        }
        assertThat(previous).isLessThan(8); // the coarsest cut: a handful of meshlets
    }

    @Test
    void discreteLodsSimplifyTheWholeMeshLevelByLevel() {
        MeshData mesh = new MeshBuilder(VertexFormat.POSITIONS_ONLY, vertices())
            .positions(grid())
            .submesh(triangles(0, N / 2, N))
            .submesh(triangles(N / 2, N, N)) // two materials: their seam stays closed
            .buildDiscrete(4, 0.5);
        var levels = mesh.levels();
        assertThat(levels).hasSize(4);
        MemorySegment indices = mesh.expandIndices();
        double fullArea = totalArea(mesh, indices, levels.getLast());
        int previousTriangles = 0;
        double[] lod = new double[10];
        for (MeshLevel level : levels) { // coarsest first
            int triangles = 0;
            for (int m = level.firstMeshlet(); m < level.firstMeshlet() + level.meshletCount(); m++) {
                triangles += mesh.meshletTriangleCount(m);
                mesh.meshletLod(m, lod);
                assertThat(lod[4]).isEqualTo((float) level.error());
            }
            assertThat(triangles).isGreaterThan(previousTriangles);
            previousTriangles = triangles;
            assertThat(totalArea(mesh, indices, level)).isCloseTo(fullArea, within(fullArea * 0.02));
        }
        assertThat(levels.getFirst().error()).isPositive();
        // More levels than pay off: building stops when simplifying stops reducing.
        MeshData many = new MeshBuilder(VertexFormat.POSITIONS_ONLY, vertices()).positions(grid())
            .submesh(triangles(0, N, N)).buildDiscrete(40, 0.5);
        assertThat(many.levels().size()).isLessThan(40);
    }

    @Test
    void attributesFollowTheirVerticesAndSubmeshesStayApart() {
        int count = vertices();
        float[] positions = grid();
        float[] normals = new float[count * 3];
        float[] tangents = new float[count * 4];
        float[] uvs = new float[count * 2];
        float[] colors = new float[count * 4];
        float[] weights = new float[count * 4];
        int[] joints = new int[count * 4];
        for (int v = 0; v < count; v++) {
            normals[3 * v + 1] = 1;
            tangents[4 * v] = 1;
            tangents[4 * v + 3] = v % 2 == 0 ? 1 : -1;
            uvs[2 * v] = positions[3 * v] / N;
            uvs[2 * v + 1] = positions[3 * v + 2] / N;
            colors[4 * v] = (v % 255) / 255f;
            weights[4 * v] = 1;
            joints[4 * v] = v % 200;
        }
        MeshData mesh = new MeshBuilder(VertexFormat.of(VertexAttribute.values()), count)
            .positions(positions)
            .attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.TANGENT, tangents)
            .attribute(VertexAttribute.UV0, uvs)
            .attribute(VertexAttribute.UV1, uvs)
            .attribute(VertexAttribute.COLOR, colors)
            .attribute(VertexAttribute.WEIGHTS, weights)
            .joints(joints)
            .submesh(triangles(0, 8, 8)) // only part of the grid: unused vertices are dropped
            .submesh(triangles(8, 16, 8))
            .coneWeight(0.5)
            .groupSize(4)
            .buildHierarchy();
        assertThat(mesh.vertexCount()).isEqualTo(17 * 9);
        assertThat(mesh.materialSlots()).isEqualTo(2);
        double[] p = new double[3];
        double[] uv = new double[2];
        double[] color = new double[4];
        int[] joint = new int[4];
        for (int v = 0; v < mesh.vertexCount(); v++) {
            mesh.position(v, p);
            int original = (int) Math.round(p[2]) * (N + 1) + (int) Math.round(p[0]);
            mesh.uv(VertexAttribute.UV0, v, uv);
            assertThat(uv[0]).isCloseTo(positions[3 * original] / N, within(1e-3));
            mesh.unorm4(VertexAttribute.COLOR, v, color);
            assertThat(color[0]).isCloseTo((original % 255) / 255.0, within(1e-6));
            mesh.joints(v, joint);
            assertThat(joint[0]).isEqualTo(original % 200);
        }
        for (int m = 0; m < mesh.meshletCount(); m++) {
            int submesh = mesh.meshletSubmesh(m);
            for (int i = 0; i < mesh.meshletVertexCount(m); i++) {
                mesh.position(mesh.meshletVertices().get(U32, 4L * (mesh.meshletVertexOffset(m) + i)), p);
                assertThat(p[0]).isBetween(submesh * 8 - 1e-3, submesh * 8 + 8 + 1e-3);
            }
        }
    }

    @Test
    void groupsThatWillNotSimplifyStayRoots() {
        // A strip one quad wide: every vertex is on the border, which simplification keeps.
        MeshData mesh = new MeshBuilder(VertexFormat.POSITIONS_ONLY, vertices())
            .positions(grid())
            .submesh(triangles(0, 1, N))
            .meshletSize(16, 8)
            .buildHierarchy();
        assertThat(mesh.levels()).hasSize(1);
        double[] lod = new double[10];
        for (int m = 0; m < mesh.meshletCount(); m++) {
            mesh.meshletLod(m, lod);
            assertThat(lod[9]).isEqualTo(Double.POSITIVE_INFINITY);
        }
    }

    @Test
    void buildsAreDeterministic() {
        MeshData a = new MeshBuilder(VertexFormat.POSITIONS_ONLY, vertices()).positions(grid())
            .submesh(triangles(0, N, N)).buildHierarchy();
        MeshData b = new MeshBuilder(VertexFormat.POSITIONS_ONLY, vertices()).positions(grid())
            .submesh(triangles(0, N, N)).buildHierarchy();
        assertThat(a.positions().mismatch(b.positions())).isEqualTo(-1);
        assertThat(a.meshlets().mismatch(b.meshlets())).isEqualTo(-1);
        assertThat(a.clusterLod().mismatch(b.clusterLod())).isEqualTo(-1);
        assertThat(a.meshletTriangles().mismatch(b.meshletTriangles())).isEqualTo(-1);
    }

    @Test
    void misuseIsChecked() {
        assertThatNullPointerException().isThrownBy(() -> new MeshBuilder(null, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new MeshBuilder(VertexFormat.POSITIONS_ONLY, 0));
        MeshBuilder builder = new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 3);
        assertThatIllegalStateException().isThrownBy(builder::buildFlat).withMessageContaining("positions");
        builder.positions(new float[] {0, 0, 0, 1, 0, 0, 0, 0, 1});
        assertThatIllegalStateException().isThrownBy(builder::buildFlat).withMessageContaining("submesh");
        assertThatIllegalArgumentException().isThrownBy(() -> builder.positions(new float[3]));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.attribute(VertexAttribute.JOINTS, new float[12]));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.attribute(VertexAttribute.UV0, new float[6]));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.submesh(new int[] {0, 1}));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.submesh(new int[0]));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.submesh(new int[] {0, 1, 2, 0}));
        assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> builder.submesh(new int[] {0, 1, 3}));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.meshletSize(2, 124));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.meshletSize(256, 124));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.meshletSize(64, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.meshletSize(64, 256));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.meshletSize(64, 125));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.coneWeight(-0.1));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.coneWeight(1.1));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.groupSize(1));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.groupSize(65));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.buildDiscrete(0, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.buildDiscrete(2, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> builder.buildDiscrete(2, 1));
        builder.submesh(new int[] {0, 1, 2});
        assertThatIllegalStateException().isThrownBy(builder::buildFlat).withMessageContaining("NORMAL"); // not given
        builder.attribute(VertexAttribute.NORMAL, new float[] {0, 1, 0, 0, 1, 0, 0, 1, 0});
        assertThat(builder.buildFlat().triangleCount()).isEqualTo(1);
        assertThat(builder.buildDiscrete(1, 0.5).levels()).hasSize(1);
    }
}
