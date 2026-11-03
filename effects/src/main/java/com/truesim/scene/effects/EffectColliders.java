package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.TransformArray;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * Colliders on scene nodes that particles bounce off (E10, effects.md §2.7): planes, spheres, boxes and capsules,
 * each on a node (moving and turning with it) and in layers that {@link Collide#colliders collision modules} pick by
 * mask, as Godot's particle colliders are. A scene's effects have one set ({@link Effects#colliders()}); each
 * {@link Effects#advance} takes their nodes' world transforms, so the GPU kernel and {@link CpuSimulation} see the
 * same colliders. Sizes are in metres: node scales do not apply. A collider whose node is removed goes with it.
 *
 * <pre>{@code
 * EffectColliders colliders = effects.colliders();
 * colliders.plane(floor, EffectColliders.ALL_LAYERS);  // the node's XZ plane, particles on its +Y side
 * colliders.box(crate, 0.5f, 0.5f, 0.5f, 1);                    // half extents, layer 1
 * }</pre>
 */
public final class EffectColliders {

    /** A collider's shape. */
    public enum Kind {
        /** The node's XZ plane; particles stay on its +Y side. */
        PLANE,
        /** A sphere about the node of a radius. */
        SPHERE,
        /** A box about the node of half extents along its axes. */
        BOX,
        /** A capsule along the node's Y axis: a radius about a segment of a half length each way. */
        CAPSULE
    }

    /** Every layer: masks are 24 bits, exact in the floats modules take them as. */
    public static final int ALL_LAYERS = 0xFFFFFF;

    private static final Kind[] KINDS = Kind.values();

    private final SceneGraph scene;
    private long[] nodes = new long[4];
    private int[] kinds = new int[4];
    private int[] layers = new int[4];
    private float[] sizes = new float[12];
    private double[] positions = new double[12];
    private double[] rotations = new double[16];
    private int count;

    /** Colliders on nodes of {@code scene}. */
    EffectColliders(SceneGraph scene) {
        this.scene = Objects.requireNonNull(scene, "scene");
    }

    /** A plane: the XZ plane of {@code node}, particles kept on its +Y side; in {@code layers}. */
    public void plane(long node, int layers) {
        checkOwner();
        add(node, Kind.PLANE, layers, 0, 0, 0);
    }

    /** A sphere of {@code radius} about {@code node}. */
    public void sphere(long node, float radius, int layers) {
        checkOwner();
        add(node, Kind.SPHERE, layers, radius, radius, radius);
    }

    /** A box of half extents {@code x}, {@code y} and {@code z} along {@code node}'s axes. */
    public void box(long node, float x, float y, float z, int layers) {
        checkOwner();
        add(node, Kind.BOX, layers, x, y, z);
    }

    /** A capsule of {@code radius} about {@code node}'s Y axis from {@code -halfLength} to {@code halfLength}. */
    public void capsule(long node, float radius, float halfLength, int layers) {
        checkOwner();
        add(node, Kind.CAPSULE, layers, radius, halfLength, 0);
    }

    @AllowAllocation("colliders are added when the scene is set up")
    private void add(long node, Kind kind, int mask, float x, float y, float z) {
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        if (mask < 0 || mask > ALL_LAYERS) {
            throw new IllegalArgumentException("Not a layer mask of 24 bits: " + mask);
        }
        if (!(x >= 0 && y >= 0 && z >= 0)) {
            throw new IllegalArgumentException("Not a size: " + x + ", " + y + ", " + z);
        }
        if (count == nodes.length) {
            int n = 2 * count;
            nodes = Arrays.copyOf(nodes, n);
            kinds = Arrays.copyOf(kinds, n);
            layers = Arrays.copyOf(layers, n);
            sizes = Arrays.copyOf(sizes, 3 * n);
            positions = Arrays.copyOf(positions, 3 * n);
            rotations = Arrays.copyOf(rotations, 4 * n);
        }
        int i = count++;
        nodes[i] = node;
        kinds[i] = kind.ordinal();
        layers[i] = mask;
        sizes[3 * i] = x;
        sizes[3 * i + 1] = y;
        sizes[3 * i + 2] = z;
        take(i);
    }

    /** Removes the colliders on {@code node}. */
    public void remove(long node) {
        checkOwner();
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (nodes[i] != node) {
                move(i, kept++);
            }
        }
        count = kept;
    }

    private void move(int from, int to) {
        nodes[to] = nodes[from];
        kinds[to] = kinds[from];
        layers[to] = layers[from];
        System.arraycopy(sizes, 3 * from, sizes, 3 * to, 3);
        System.arraycopy(positions, 3 * from, positions, 3 * to, 3);
        System.arraycopy(rotations, 4 * from, rotations, 4 * to, 4);
    }

    /** Takes the colliders' nodes' world transforms; those of removed nodes go. Allocates nothing. */
    void update() {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (scene.contains(nodes[i])) {
                move(i, kept);
                take(kept++);
            }
        }
        count = kept;
    }

    private void take(int i) {
        TransformArray world = scene.worldTransforms();
        MemorySegment s = world.segment();
        long base = NodeHandle.index(nodes[i]) * world.stride();
        for (int axis = 0; axis < 3; axis++) {
            positions[3 * i + axis] = s.get(ValueLayout.JAVA_DOUBLE, base + 8L * axis);
        }
        for (int axis = 0; axis < 4; axis++) {
            rotations[4 * i + axis] = s.get(ValueLayout.JAVA_DOUBLE, base + 24 + 8L * axis);
        }
    }

    /** How many colliders there are. */
    public int count() {
        return count;
    }

    /** Collider {@code i}'s shape. */
    public Kind kind(int i) {
        return KINDS[kinds[Objects.checkIndex(i, count)]];
    }

    /** Its node. */
    public long node(int i) {
        return nodes[Objects.checkIndex(i, count)];
    }

    /** Its layers. */
    public int layers(int i) {
        return layers[Objects.checkIndex(i, count)];
    }

    /**
     * Its size along {@code axis}: a sphere's radius (on every axis), a box's half extents, a capsule's radius (0) and
     * half length (1); 0 for a plane.
     */
    public float size(int i, int axis) {
        return sizes[3 * Objects.checkIndex(i, count) + Objects.checkIndex(axis, 3)];
    }

    /** Its node's world position along {@code axis}, as of the last advance. */
    public double position(int i, int axis) {
        return positions[3 * Objects.checkIndex(i, count) + Objects.checkIndex(axis, 3)];
    }

    /** Its node's world rotation, a quaternion's x, y, z and w for {@code axis} 0 to 3. */
    public double rotation(int i, int axis) {
        return rotations[4 * Objects.checkIndex(i, count) + Objects.checkIndex(axis, 4)];
    }

    /** Throws unless the calling thread may change the colliders: any, or the clock's owner (ChangeClock.ownedBy). */
    private void checkOwner() {
        Thread owner = scene.clock().owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(notOwner(owner));
        }
    }

    @AllowAllocation("diagnostics")
    private static String notOwner(Thread owner) {
        return "The effect colliders change on " + owner.getName() + " only, not on "
            + Thread.currentThread().getName();
    }
}
