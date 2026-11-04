package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * A collider particles bounce off, as a component on a node (application-api.md D46; saved as
 * {@code truesim.effects.collider}): a plane, sphere, box or capsule moving and turning with its node, in the layers
 * collision modules pick by mask. {@link EffectColliders} is the scene's view of them; a node has one at most.
 *
 * <pre>{@code
 * scene.components().set(crate, EffectCollider.TYPE, EffectCollider.box(0.5f, 0.5f, 0.5f, 1));
 * }</pre>
 */
public final class EffectCollider {

    /** The effect collider component type. */
    public static final ComponentType<EffectCollider> TYPE = ComponentType.of("truesim.effects.collider",
        EffectCollider.class);

    private final EffectColliders.Kind kind;
    private final int layers;
    private final float x;
    private final float y;
    private final float z;

    private EffectCollider(EffectColliders.Kind kind, int layers, float x, float y, float z) {
        if (layers < 0 || layers > EffectColliders.ALL_LAYERS) {
            throw new IllegalArgumentException("Not a layer mask of 24 bits: " + layers);
        }
        if (!(x >= 0 && y >= 0 && z >= 0)) {
            throw new IllegalArgumentException("Not a size: " + x + ", " + y + ", " + z);
        }
        this.kind = kind;
        this.layers = layers;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** The node's XZ plane, particles kept on its +Y side. */
    @AllowAllocation("components are made when a scene is built")
    public static EffectCollider plane(int layers) {
        return new EffectCollider(EffectColliders.Kind.PLANE, layers, 0, 0, 0);
    }

    /** A sphere of {@code radius} about the node. */
    @AllowAllocation("components are made when a scene is built")
    public static EffectCollider sphere(float radius, int layers) {
        return new EffectCollider(EffectColliders.Kind.SPHERE, layers, radius, radius, radius);
    }

    /** A box of half extents along the node's axes. */
    @AllowAllocation("components are made when a scene is built")
    public static EffectCollider box(float x, float y, float z, int layers) {
        return new EffectCollider(EffectColliders.Kind.BOX, layers, x, y, z);
    }

    /** A capsule of {@code radius} about the node's Y axis from {@code -halfLength} to {@code halfLength}. */
    @AllowAllocation("components are made when a scene is built")
    public static EffectCollider capsule(float radius, float halfLength, int layers) {
        return new EffectCollider(EffectColliders.Kind.CAPSULE, layers, radius, halfLength, 0);
    }

    public EffectColliders.Kind kind() {
        return kind;
    }

    public int layers() {
        return layers;
    }

    /** Its size along {@code axis}, as {@link EffectColliders#size} says. */
    public float size(int axis) {
        return switch (Objects.checkIndex(axis, 3)) {
            case 0 -> x;
            case 1 -> y;
            default -> z;
        };
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return kind + " collider in layers " + Integer.toHexString(layers);
    }
}
