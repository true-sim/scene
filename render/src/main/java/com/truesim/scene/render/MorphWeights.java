package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.ComponentType;
import com.truesim.scene.core.SceneGraph;
import java.util.Objects;

/**
 * The morph target weights of a node's {@link Renderable}, as a component (application-api.md D46): target {@code t}
 * of the mesh weighs {@code weight(t)}, targets past those set 0. Animated every frame, so changed in place and not
 * logged: renderers read them each update. {@link ModelData#spawn} sets a model's default weights.
 *
 * <pre>{@code
 * MorphWeights.write(scene, face, weights, 0, weights.length);          // made on first write
 * animators.morphSink((node, w, offset, count) -> MorphWeights.write(scene, node, w, offset, count));
 * float smile = scene.components().get(face, MorphWeights.TYPE).weight(2);
 * }</pre>
 *
 * <p>Writing allocates only when the count grows; reading allocates nothing. Saved as {@code truesim.morph-weights}.
 */
public final class MorphWeights {

    /** The morph weights component type, saved as {@code truesim.morph-weights}. */
    public static final ComponentType<MorphWeights> TYPE =
        ComponentType.of("truesim.morph-weights", MorphWeights.class);

    private float[] weights;
    private int count;

    /** No weights: every target weighs 0. */
    @AllowAllocation("components are made when a scene is built")
    public MorphWeights() {
        weights = new float[0];
    }

    /**
     * Sets the weights to {@code values[offset..offset + count)}; targets past them weigh 0. Allocates only when the
     * count grows.
     */
    public MorphWeights set(double[] values, int offset, int count) {
        Objects.checkFromIndexSize(offset, count, values.length);
        if (weights.length < count) {
            grow(count);
        }
        for (int i = 0; i < count; i++) {
            weights[i] = (float) values[offset + i];
        }
        this.count = count;
        return this;
    }

    @AllowAllocation("room for more weights than before")
    private void grow(int length) {
        weights = new float[length];
    }

    /** How many weights are set. */
    public int count() {
        return count;
    }

    /** Morph target {@code target}'s weight: 0 past those set. */
    public float weight(int target) {
        if (target < 0) {
            throw new IndexOutOfBoundsException("Not a target: " + target);
        }
        return target < count ? weights[target] : 0;
    }

    /**
     * Sets {@code node}'s morph weights to {@code values[offset..offset + count)}, giving it the component on first use.
     * Its signature fits an animator's morph sink. On the scene's thread (its clock's owner, if any); allocates only on
     * first use and when the count grows.
     */
    public static void write(SceneGraph scene, long node, double[] values, int offset, int count) {
        ChangeClock clock = scene.clock();
        Thread owner = clock.owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(notOwner(owner));
        }
        ComponentStore<MorphWeights> store = scene.components().store(TYPE);
        MorphWeights weights = store.get(node);
        if (weights == null) {
            weights = added(store, node);
        }
        weights.set(values, offset, count);
    }

    @AllowAllocation("the component, on a node's first write")
    private static MorphWeights added(ComponentStore<MorphWeights> store, long node) {
        MorphWeights weights = new MorphWeights();
        store.set(node, weights);
        return weights;
    }

    @AllowAllocation("diagnostics")
    private static String notOwner(Thread owner) {
        return "Morph weights change on " + owner.getName() + " only, not on " + Thread.currentThread().getName();
    }
}
