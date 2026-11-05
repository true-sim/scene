package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.TransformArray;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.PickGeometry;
import com.truesim.scene.core.PickMesh;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.core.ScenePicker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Picks nodes by their {@link Renderable}s' meshes: the {@link ScenePicker.Resolver} of {@code Pickable.mesh()}
 * pickables given no geometry, as models spawn them. A static mesh is its asset's shared {@link PickMesh} ({@link PickMeshes});
 * a skinned one is posed on the CPU for its node, against the joints as they are now: its vertices skinned by their
 * joints' world transforms relative to the node (as the renderer poses them), then its triangle BVH refit, only when a
 * ray reaches its box and its joints moved since the last pose. So a character is picked where it is drawn this frame,
 * without waiting for the GPU (application-api.md, picking: no added latency). Morph targets move the vertices
 * first, by the node's {@link MorphWeights}, as the renderer applies them.
 *
 * <pre>{@code
 * picker.resolver(new RenderablePicking(scene, ref -> cache.loaded(ref)));   // null until a mesh is loaded
 * }</pre>
 *
 * <p>{@code meshes} gives a mesh asset's data once loaded, null before (the node is hit by its box until then); it must
 * not allocate. Posing allocates the node's copy once; one thread.
 */
public final class RenderablePicking implements ScenePicker.Resolver {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT_UNALIGNED
        .withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt U32 = ValueLayout.JAVA_INT_UNALIGNED
        .withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);

    private final ComponentStore<Renderable> renderables;
    private final ComponentStore<MorphWeights> morphs;
    private final Function<AssetRef<MeshData>, MeshData> meshes;
    private final PickMeshes pickMeshes;
    private final SceneGraph scene;
    // skinned copies, by node slot
    private Skinned[] skinned = new Skinned[0];
    private long poses;
    private final double[] nodeMatrix = new double[12];
    private final double[] nodeInverse = new double[12];
    private final double[] joint = new double[12];
    private final double[] bind = new double[12];

    /**
     * Picks the meshes of {@code scene}'s renderables, their data from {@code meshes}, sharing the pick meshes of
     * {@code pickMeshes}.
     */
    public RenderablePicking(SceneGraph scene, Function<AssetRef<MeshData>, MeshData> meshes, PickMeshes pickMeshes) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.meshes = Objects.requireNonNull(meshes, "meshes");
        this.pickMeshes = Objects.requireNonNull(pickMeshes, "pickMeshes");
        this.renderables = scene.components().store(Renderable.TYPE);
        this.morphs = scene.components().store(MorphWeights.TYPE);
    }

    /** As {@link #RenderablePicking(SceneGraph, Function, PickMeshes)} with pick meshes of its own. */
    @AllowAllocation("set up once")
    public RenderablePicking(SceneGraph scene, Function<AssetRef<MeshData>, MeshData> meshes) {
        this(scene, meshes, new PickMeshes());
    }

    /** The shared pick meshes. */
    public PickMeshes pickMeshes() {
        return pickMeshes;
    }

    /** How many times a skinned mesh was posed (a test counts them). */
    public long poses() {
        return poses;
    }

    @Override
    public PickGeometry geometry(long node) {
        Renderable renderable = renderables.get(node);
        if (renderable == null) {
            return null;
        }
        MeshData mesh = meshes.apply(renderable.mesh());
        if (mesh == null) {
            return null;
        }
        SkinBinding binding = renderable.skin();
        SkinBinding skin = binding != null && mesh.jointCount() > 0 ? binding : null;
        MorphWeights weights = morphs.get(node);
        if (skin == null && !morphed(weights, mesh)) {
            return pickMeshes.of(mesh);
        }
        Skinned s = skinned(node, mesh, skin);
        long moved = scene.version(node);
        int present = 0;
        for (int j = 0; skin != null && j < skin.jointCount(); j++) {
            long jointNode = skin.joint(j);
            if (scene.contains(jointNode)) {
                moved = Math.max(moved, scene.version(jointNode));
                present++;
            }
        }
        boolean reweighed = false;
        for (int t = 0; t < s.morphWeights.length; t++) {
            float w = weights != null ? weights.weight(t) : 0;
            reweighed |= w != s.morphWeights[t];
            s.morphWeights[t] = w;
        }
        if (moved != s.posedAt || present != s.present || reweighed) { // moved, a joint removed, weights changed
            pose(node, s);
            s.posedAt = moved;
            s.present = present;
        }
        return s.mesh;
    }

    /** Whether {@code weights} (or null: none) weigh any of {@code mesh}'s morph targets. */
    private static boolean morphed(MorphWeights weights, MeshData mesh) {
        int count = weights == null ? 0 : Math.min(weights.count(), mesh.morphTargetCount());
        for (int t = 0; t < count; t++) {
            if (weights.weight(t) != 0) {
                return true;
            }
        }
        return false;
    }

    /** The posed copy of {@code node}, made (or remade, for another mesh or skin) when needed. */
    private Skinned skinned(long node, MeshData mesh, SkinBinding skin) {
        int slot = NodeHandle.index(node);
        if (slot >= skinned.length) {
            grow(slot + 1);
        }
        Skinned s = skinned[slot];
        if (s == null || s.node != node || s.data != mesh || s.skin != skin) {
            s = new Skinned(node, mesh, skin);
            skinned[slot] = s;
        }
        return s;
    }

    @AllowAllocation("growing with the scene")
    private void grow(int length) {
        skinned = Arrays.copyOf(skinned, Math.max(length, skinned.length * 2));
    }

    /**
     * Poses {@code s}'s vertices as the renderer does: each rest place moved by its morph targets' deltas times their
     * weights (glTF: morphing before skinning), then, skinned, the weighted sum of its joints' skinning matrices applied.
     */
    private void pose(long node, Skinned s) {
        poses++;
        if (s.skin == null) {
            morph(s, s.mesh.positions());
            s.mesh.refit();
            return;
        }
        float[] rest = morph(s, s.morphed);
        TransformArray world = scene.worldTransforms();
        matrix(world, NodeHandle.index(node), nodeMatrix);
        invert(nodeMatrix, nodeInverse);
        SkinBinding skin = s.skin;
        for (int j = 0; j < skin.jointCount(); j++) {
            long jointNode = skin.joint(j);
            if (scene.contains(jointNode)) {
                matrix(world, NodeHandle.index(jointNode), joint);
            } else {
                System.arraycopy(nodeMatrix, 0, joint, 0, 12); // a joint gone: its vertices at rest, in the node's space
            }
            for (int r = 0; r < 3; r++) { // the inverse bind matrix, column-major 4x4, as 3 rows of 4
                for (int c = 0; c < 4; c++) {
                    bind[r * 4 + c] = skin.inverseBind(j, c * 4 + r);
                }
            }
            multiply(joint, bind, s.scratch);
            multiply(nodeInverse, s.scratch, s.matrices, j * 12);
        }
        float[] out = s.mesh.positions();
        int vertices = rest.length / 3;
        for (int v = 0; v < vertices; v++) {
            double x = rest[v * 3];
            double y = rest[v * 3 + 1];
            double z = rest[v * 3 + 2];
            double px = 0;
            double py = 0;
            double pz = 0;
            for (int i = 0; i < 4; i++) {
                float w = s.weights[v * 4 + i];
                if (w == 0) {
                    continue;
                }
                int m = s.joints[v * 4 + i] * 12;
                double[] mat = s.matrices;
                px += w * (mat[m] * x + mat[m + 1] * y + mat[m + 2] * z + mat[m + 3]);
                py += w * (mat[m + 4] * x + mat[m + 5] * y + mat[m + 6] * z + mat[m + 7]);
                pz += w * (mat[m + 8] * x + mat[m + 9] * y + mat[m + 10] * z + mat[m + 11]);
            }
            out[v * 3] = (float) px;
            out[v * 3 + 1] = (float) py;
            out[v * 3 + 2] = (float) pz;
        }
        s.mesh.refit();
    }

    /** {@code s}'s rest places moved by its morph deltas and weights, into {@code out}; returns it. */
    private static float[] morph(Skinned s, float[] out) {
        System.arraycopy(s.rest, 0, out, 0, s.rest.length);
        int vertices = s.rest.length / 3;
        for (int v = 0; v < vertices; v++) {
            for (int r = s.recordStart[v]; r < s.recordStart[v + 1]; r++) {
                float w = s.morphWeights[s.recordTarget[r]];
                out[v * 3] += w * s.recordDelta[r * 3];
                out[v * 3 + 1] += w * s.recordDelta[r * 3 + 1];
                out[v * 3 + 2] += w * s.recordDelta[r * 3 + 2];
            }
        }
        return out;
    }

    /** Slot {@code slot}'s transform (translation, rotation, scale) as 3 rows of 4. */
    private static void matrix(TransformArray transforms, int slot, double[] m) {
        MemorySegment s = transforms.segment();
        long o = slot * transforms.stride();
        double qx = s.get(DOUBLE, o + 24);
        double qy = s.get(DOUBLE, o + 32);
        double qz = s.get(DOUBLE, o + 40);
        double qw = s.get(DOUBLE, o + 48);
        double sx = s.get(DOUBLE, o + 56);
        double sy = s.get(DOUBLE, o + 64);
        double sz = s.get(DOUBLE, o + 72);
        m[0] = (1 - 2 * (qy * qy + qz * qz)) * sx;
        m[1] = 2 * (qx * qy - qz * qw) * sy;
        m[2] = 2 * (qx * qz + qy * qw) * sz;
        m[3] = s.get(DOUBLE, o);
        m[4] = 2 * (qx * qy + qz * qw) * sx;
        m[5] = (1 - 2 * (qx * qx + qz * qz)) * sy;
        m[6] = 2 * (qy * qz - qx * qw) * sz;
        m[7] = s.get(DOUBLE, o + 8);
        m[8] = 2 * (qx * qz - qy * qw) * sx;
        m[9] = 2 * (qy * qz + qx * qw) * sy;
        m[10] = (1 - 2 * (qx * qx + qy * qy)) * sz;
        m[11] = s.get(DOUBLE, o + 16);
    }

    /** The inverse of affine {@code m} (3 rows of 4) into {@code out}. */
    static void invert(double[] m, double[] out) {
        double a = m[0];
        double b = m[1];
        double c = m[2];
        double d = m[4];
        double e = m[5];
        double f = m[6];
        double g = m[8];
        double h = m[9];
        double i = m[10];
        double co0 = e * i - f * h;
        double co1 = f * g - d * i;
        double co2 = d * h - e * g;
        double det = a * co0 + b * co1 + c * co2;
        double inv = 1 / det;
        out[0] = co0 * inv;
        out[1] = (c * h - b * i) * inv;
        out[2] = (b * f - c * e) * inv;
        out[4] = co1 * inv;
        out[5] = (a * i - c * g) * inv;
        out[6] = (c * d - a * f) * inv;
        out[8] = co2 * inv;
        out[9] = (b * g - a * h) * inv;
        out[10] = (a * e - b * d) * inv;
        double tx = m[3];
        double ty = m[7];
        double tz = m[11];
        out[3] = -(out[0] * tx + out[1] * ty + out[2] * tz);
        out[7] = -(out[4] * tx + out[5] * ty + out[6] * tz);
        out[11] = -(out[8] * tx + out[9] * ty + out[10] * tz);
    }

    private static void multiply(double[] l, double[] r, double[] out) {
        multiply(l, r, out, 0);
    }

    /** {@code l * r} of affine matrices (3 rows of 4) into {@code out} at {@code at}. */
    static void multiply(double[] l, double[] r, double[] out, int at) {
        for (int row = 0; row < 3; row++) {
            double l0 = l[row * 4];
            double l1 = l[row * 4 + 1];
            double l2 = l[row * 4 + 2];
            double l3 = l[row * 4 + 3];
            out[at + row * 4] = l0 * r[0] + l1 * r[4] + l2 * r[8];
            out[at + row * 4 + 1] = l0 * r[1] + l1 * r[5] + l2 * r[9];
            out[at + row * 4 + 2] = l0 * r[2] + l1 * r[6] + l2 * r[10];
            out[at + row * 4 + 3] = l0 * r[3] + l1 * r[7] + l2 * r[11] + l3;
        }
    }

    /** A node's skinned copy of a mesh: rest positions, joints (the skin's) and weights per vertex, its posed mesh. */
    private static final class Skinned {
        final long node;
        final MeshData data;
        final SkinBinding skin;
        final float[] rest;
        final int[] joints;
        final float[] weights;
        final double[] matrices;
        final double[] scratch = new double[12];
        final PickMesh mesh;
        final float[] morphed;      // the rest places morphed, before skinning
        final float[] morphWeights; // the weights posed with
        final int[] recordStart;    // by vertex: its first morph record, the next vertex's after its last
        final int[] recordTarget;
        final float[] recordDelta;
        long posedAt = -1;
        int present;

        @AllowAllocation("a copy per skinned node, made when first picked")
        Skinned(long node, MeshData data, SkinBinding skin) {
            this.node = node;
            this.data = data;
            this.skin = skin;
            int vertices = data.vertexCount();
            rest = new float[vertices * 3];
            joints = new int[vertices * 4];
            weights = new float[vertices * 4];
            double[] p = new double[4];
            int[] j = new int[4];
            for (int v = 0; v < vertices; v++) {
                data.position(v, p);
                rest[v * 3] = (float) p[0];
                rest[v * 3 + 1] = (float) p[1];
                rest[v * 3 + 2] = (float) p[2];
                if (skin == null) {
                    continue;
                }
                data.joints(v, j);
                data.unorm4(VertexAttribute.WEIGHTS, v, p);
                for (int i = 0; i < 4; i++) {
                    joints[v * 4 + i] = Math.min(data.paletteJoint(j[i]), skin.jointCount() - 1); // a short skin: its last
                    weights[v * 4 + i] = (float) p[i];
                }
            }
            matrices = new double[12 * (skin == null ? 0 : skin.jointCount())];
            mesh = new PickMesh(rest.clone(), PickMeshes.indices(data));
            morphed = new float[rest.length];
            morphWeights = new float[data.morphTargetCount()];
            // the morph records, by vertex: each a target and its position delta
            MemorySegment offsets = data.morphOffsets();
            MemorySegment records = data.morphRecords();
            recordStart = new int[vertices + 1];
            int count = morphWeights.length == 0 ? 0 : offsets.get(U32, 4L * vertices);
            recordTarget = new int[count];
            recordDelta = new float[3 * count];
            for (int v = 0; v <= vertices && morphWeights.length > 0; v++) {
                recordStart[v] = offsets.get(U32, 4L * v);
            }
            for (int r = 0; r < count; r++) {
                long o = (long) MeshData.MORPH_RECORD_BYTES * r;
                recordTarget[r] = records.get(U16, o) & 0xffff;
                for (int i = 0; i < 3; i++) {
                    recordDelta[3 * r + i] = Float.float16ToFloat(records.get(U16, o + 2 + 2L * i));
                }
            }
        }
    }
}
