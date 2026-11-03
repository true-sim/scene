package com.truesim.scene.meshbuild;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.MeshLevel;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * Coarser levels of skinned meshes simplified with their joint weights (animation A10): posed, they stay closer to the
 * posed full-detail mesh than levels simplified by positions alone, which collapse a straight limb's elbow away.
 */
class SkinnedSimplificationTest {

    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int SEGMENTS = 16; // around
    private static final double RADIUS = 0.1;

    /** A straight open tube along y, {@code rings} rings from 0 to {@code length}, skinned to a chain of joints. */
    private record Tube(float[] positions, float[] weights, int[] joints, int[] indices, int jointCount,
                        double length) {

        int vertexCount() {
            return positions.length / 3;
        }

        /** Simplified with its skin. */
        MeshBuilder skinned() {
            return new MeshBuilder(VertexFormat.of(VertexAttribute.JOINTS, VertexAttribute.WEIGHTS), vertexCount())
                .positions(positions).attribute(VertexAttribute.WEIGHTS, weights).joints(joints).submesh(indices);
        }

        /** Simplified by positions alone. */
        MeshBuilder plain() {
            return new MeshBuilder(VertexFormat.POSITIONS_ONLY, vertexCount()).positions(positions).submesh(indices);
        }
    }

    /**
     * {@code jointCount} joints, each {@code length / jointCount} long, joint j starting at y = j * that; each ring
     * blends linearly between the two joints nearest it over {@code blend} of a joint's length around their pivot,
     * the larger weight first, as exporters store them.
     */
    private static Tube tube(int rings, double length, int jointCount, double blend) {
        return tube(rings, length, jointCount, blend, true);
    }

    private static Tube tube(int rings, double length, int jointCount, double blend, boolean largerFirst) {
        int vertices = rings * SEGMENTS;
        float[] positions = new float[3 * vertices];
        float[] weights = new float[4 * vertices];
        int[] joints = new int[4 * vertices];
        double bone = length / jointCount;
        for (int r = 0; r < rings; r++) {
            double y = length * r / (rings - 1);
            int below = Math.min((int) (y / bone + 0.5), jointCount - 1); // nearest pivot at below * bone, below >= 1
            double t = below == 0 ? 1 : Math.clamp((y - below * bone) / (blend * bone) + 0.5, 0, 1);
            int lower = Math.max(below - 1, 0);
            for (int s = 0; s < SEGMENTS; s++) {
                int v = r * SEGMENTS + s;
                double angle = 2 * Math.PI * s / SEGMENTS;
                positions[3 * v] = (float) (RADIUS * Math.cos(angle));
                positions[3 * v + 1] = (float) y;
                positions[3 * v + 2] = (float) (RADIUS * Math.sin(angle));
                boolean upperFirst = largerFirst ? t >= 0.5 : below < lower;
                joints[4 * v] = upperFirst ? below : lower;
                joints[4 * v + 1] = upperFirst ? lower : below;
                weights[4 * v] = (float) (upperFirst ? t : 1 - t);
                weights[4 * v + 1] = (float) (upperFirst ? 1 - t : t);
            }
        }
        int[] indices = new int[(rings - 1) * SEGMENTS * 6];
        int at = 0;
        for (int r = 0; r < rings - 1; r++) {
            for (int s = 0; s < SEGMENTS; s++) {
                int a = r * SEGMENTS + s;
                int b = r * SEGMENTS + (s + 1) % SEGMENTS;
                int c = a + SEGMENTS;
                int d = b + SEGMENTS;
                for (int i : new int[] {a, c, b, b, c, d}) {
                    indices[at++] = i;
                }
            }
        }
        return new Tube(positions, weights, joints, indices, jointCount, length);
    }

    /**
     * Joint j's matrix (2D, about z, as 6 values) in a pose bending each joint by {@code angle} at its pivot relative
     * to its parent: linear blend skinning moves a vertex by its joints' matrices, weighted.
     */
    private static double[][] pose(Tube tube, double angle) {
        double bone = tube.length() / tube.jointCount();
        double[][] matrices = new double[tube.jointCount()][];
        matrices[0] = new double[] {1, 0, 0, 1, 0, 0};
        for (int j = 1; j < tube.jointCount(); j++) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double py = j * bone;
            // local: rotate about (0, py): x' = cos x - sin (y - py), y' = sin x + cos (y - py) + py
            double[] local = {cos, sin, -sin, cos, sin * py, py - cos * py};
            matrices[j] = compose(matrices[j - 1], local);
        }
        return matrices;
    }

    /** a after b: matrices {m00, m10, m01, m11, tx, ty}. */
    private static double[] compose(double[] a, double[] b) {
        return new double[] {a[0] * b[0] + a[2] * b[1], a[1] * b[0] + a[3] * b[1], a[0] * b[2] + a[2] * b[3],
            a[1] * b[2] + a[3] * b[3], a[0] * b[4] + a[2] * b[5] + a[4], a[1] * b[4] + a[3] * b[5] + a[5]};
    }

    /**
     * The tube's vertex at each of the mesh's (the nearest to its quantized position), so meshes built without the
     * skin pose as well.
     */
    private static int[] originals(MeshData mesh, Tube tube) {
        int[] out = new int[mesh.vertexCount()];
        double[] p = new double[3];
        float[] q = tube.positions();
        for (int v = 0; v < out.length; v++) {
            mesh.position(v, p);
            double best = Double.POSITIVE_INFINITY;
            for (int o = 0; o < tube.vertexCount(); o++) {
                double dx = q[3 * o] - p[0];
                double dy = q[3 * o + 1] - p[1];
                double dz = q[3 * o + 2] - p[2];
                double d = dx * dx + dy * dy + dz * dz;
                if (d < best) {
                    best = d;
                    out[v] = o;
                }
            }
        }
        return out;
    }

    /** Vertex {@code v} of the tube, posed. */
    private static double[] posed(Tube tube, int v, double[][] matrices) {
        float[] p = tube.positions();
        double x = 0;
        double y = 0;
        for (int k = 4 * v; k < 4 * v + 4; k++) {
            double[] m = matrices[tube.joints()[k]];
            x += tube.weights()[k] * (m[0] * p[3 * v] + m[2] * p[3 * v + 1] + m[4]);
            y += tube.weights()[k] * (m[1] * p[3 * v] + m[3] * p[3 * v + 1] + m[5]);
        }
        return new double[] {x, y, p[3 * v + 2]};
    }

    /** The posed triangles of the meshlets {@code select} picks, as 9 values each. */
    private static List<double[]> triangles(MeshData mesh, Tube tube, double[][] matrices, IntPredicate select) {
        MemorySegment indices = mesh.expandIndices(Arena.ofAuto());
        int[] originals = originals(mesh, tube);
        List<double[]> out = new ArrayList<>();
        for (int m = 0; m < mesh.meshletCount(); m++) {
            if (!select.test(m)) {
                continue;
            }
            int first = mesh.meshletTriangleOffset(m);
            for (int k = first; k < first + 3 * mesh.meshletTriangleCount(m); k += 3) {
                double[] triangle = new double[9];
                for (int i = 0; i < 3; i++) {
                    System.arraycopy(posed(tube, originals[indices.get(U32, 4L * (k + i))], matrices), 0, triangle, 3 * i, 3);
                }
                out.add(triangle);
            }
        }
        return out;
    }

    /** The worst distance from one of the tube's vertices, posed, to the posed triangles. */
    private static double deviation(List<double[]> triangles, Tube tube, double[][] matrices) {
        double worst = 0;
        for (int v = 0; v < tube.vertexCount(); v++) {
            double[] p = posed(tube, v, matrices);
            double best = Double.POSITIVE_INFINITY;
            for (double[] t : triangles) {
                best = Math.min(best, distance(p, t));
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }

    /** The distance from p to triangle t (closest point, as in Ericson's Real-Time Collision Detection). */
    private static double distance(double[] p, double[] t) {
        double[] a = {t[0], t[1], t[2]};
        double[] b = {t[3], t[4], t[5]};
        double[] c = {t[6], t[7], t[8]};
        double[] ab = sub(b, a);
        double[] ac = sub(c, a);
        double[] ap = sub(p, a);
        double d1 = dot(ab, ap);
        double d2 = dot(ac, ap);
        if (d1 <= 0 && d2 <= 0) {
            return length(ap);
        }
        double[] bp = sub(p, b);
        double d3 = dot(ab, bp);
        double d4 = dot(ac, bp);
        if (d3 >= 0 && d4 <= d3) {
            return length(bp);
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double v = d1 / (d1 - d3);
            return length(sub(p, add(a, ab, v)));
        }
        double[] cp = sub(p, c);
        double d5 = dot(ab, cp);
        double d6 = dot(ac, cp);
        if (d6 >= 0 && d5 <= d6) {
            return length(cp);
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double w = d2 / (d2 - d6);
            return length(sub(p, add(a, ac, w)));
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return length(sub(p, add(b, sub(c, b), w)));
        }
        double denom = 1 / (va + vb + vc);
        double v = vb * denom;
        double w = vc * denom;
        double[] q = add(add(a, ab, v), ac, w);
        return length(sub(p, q));
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] add(double[] a, double[] b, double s) {
        return new double[] {a[0] + b[0] * s, a[1] + b[1] * s, a[2] + b[2] * s};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    /** The posed deviation of the discrete levels, summed. */
    private static double discrete(MeshData mesh, Tube tube, double[][] matrices) {
        double sum = 0;
        for (MeshLevel level : mesh.levels()) {
            sum += deviation(triangles(mesh, tube, matrices,
                m -> m >= level.firstMeshlet() && m < level.firstMeshlet() + level.meshletCount()), tube, matrices);
        }
        return sum;
    }

    /**
     * The posed deviation of the hierarchy's cuts (the meshlets drawn at an error threshold) with the most triangles
     * up to a half, a quarter, an eighth and a sixteenth of the full mesh's, summed: compared at equal triangle
     * budgets, as the skin raises the errors.
     */
    private static double hierarchy(MeshData mesh, Tube tube, double[][] matrices) {
        double[] lod = new double[10];
        TreeSet<Double> thresholds = new TreeSet<>();
        for (int m = 0; m < mesh.meshletCount(); m++) {
            mesh.meshletLod(m, lod);
            thresholds.add(lod[4]);
        }
        double sum = 0;
        for (int budget = tube.indices().length / 6; budget >= tube.indices().length / 48; budget /= 2) {
            for (double threshold : thresholds) {
                List<double[]> triangles = triangles(mesh, tube, matrices, m -> {
                    mesh.meshletLod(m, lod);
                    return lod[4] <= threshold && lod[9] > threshold;
                });
                if (triangles.size() <= budget) {
                    sum += deviation(triangles, tube, matrices);
                    break;
                }
            }
        }
        return sum;
    }

    @Test
    void posedCoarserLevelsOfSkinnedMeshesDeviateLessThanPlainOnes() {
        // A straight tube collapses along its length for free by position; posed, the joints' blend bends it there.
        Object[][] cases = {
            {"an elbow", tube(65, 2, 2, 0.2), Math.PI / 2},
            {"a hard seam", tube(64, 2, 2, 0), Math.PI / 2}, // weights compared slot by slot: both sides alike
            {"a chain, joints in index order", tube(97, 3, 6, 0.4, false), Math.PI / 6},
            {"40 joints sharing slots", tube(161, 4, 40, 0.5), Math.PI / 24},
        };
        for (Object[] c : cases) {
            Tube tube = (Tube) c[1];
            double[][] matrices = pose(tube, (double) c[2]);
            double skinned = discrete(tube.skinned().buildDiscrete(5, 0.5), tube, matrices);
            double plain = discrete(tube.plain().buildDiscrete(5, 0.5), tube, matrices);
            assertThat(skinned).as("discrete levels of %s", c[0]).isLessThan(0.8 * plain);
            skinned = hierarchy(tube.skinned().groupSize(4).buildHierarchy(), tube, matrices);
            plain = hierarchy(tube.plain().groupSize(4).buildHierarchy(), tube, matrices);
            assertThat(skinned).as("cluster hierarchy of %s", c[0]).isLessThan(0.8 * plain);
        }
    }

    @Test
    void jointsMeetingAroundOneVertexBeyondTheSlotsShareThem() {
        // A fan of 12 vertices around a center, each bound wholly to a joint of its own: all 13 meet at the center.
        int ring = 12;
        float[] positions = new float[3 * (ring + 1)];
        float[] weights = new float[4 * (ring + 1)];
        int[] joints = new int[4 * (ring + 1)];
        int[] indices = new int[3 * ring];
        for (int v = 0; v < ring; v++) {
            double angle = 2 * Math.PI * v / ring;
            positions[3 * v] = (float) Math.cos(angle);
            positions[3 * v + 2] = (float) Math.sin(angle);
            indices[3 * v] = ring;
            indices[3 * v + 1] = (v + 1) % ring;
            indices[3 * v + 2] = v;
        }
        for (int v = 0; v <= ring; v++) {
            weights[4 * v] = 1;
            joints[4 * v] = v;
        }
        MeshData mesh = new MeshBuilder(VertexFormat.of(VertexAttribute.JOINTS, VertexAttribute.WEIGHTS), ring + 1)
            .positions(positions).attribute(VertexAttribute.WEIGHTS, weights).joints(joints).submesh(indices)
            .buildDiscrete(3, 0.5);
        assertThat(mesh.levels().getLast().meshletCount()).isOne();
        assertThat(mesh.levels()).hasSizeGreaterThan(1); // the center collapses onto the locked border
    }

    @Test
    void weightsWithoutJointsSimplifyByPositions() {
        Tube tube = tube(33, 2, 2, 0.2);
        MeshData plain = tube.plain().buildDiscrete(4, 0.5);
        MeshData weighted = new MeshBuilder(VertexFormat.of(VertexAttribute.WEIGHTS), tube.vertexCount())
            .positions(tube.positions()).attribute(VertexAttribute.WEIGHTS, tube.weights()).submesh(tube.indices())
            .buildDiscrete(4, 0.5);
        assertThat(weighted.levels()).isEqualTo(plain.levels());
    }
}
