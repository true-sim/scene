package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.Objects;

/**
 * What a pointer track animates besides nodes, as glTF's {@code KHR_animation_pointer} names it: a material's value, a
 * light's, a camera's, or whether a node shows. Clips key it ({@link Track#Track(AnimationPointer, Track.Interpolation,
 * float[], float[])}), animators blend it as they blend curves, and hand the result to whoever applies it
 * ({@link Animators#pointerSink}): the renderer for materials, the game for lights and cameras. Immutable; equal when
 * it names the same thing (its rest value aside).
 *
 * <pre>{@code
 * AnimationPointer color = new AnimationPointer("/materials/0/pbrMetallicRoughness/baseColorFactor",
 *     AnimationPointer.Target.MATERIAL, 0, "baseColor", materialRef, 1, 1, 1, 1);
 * }</pre>
 */
public final class AnimationPointer {

    /** What kind of thing a pointer animates. */
    public enum Target {
        /**
         * A value of a material asset ({@link #asset()}): its {@link #property()} is the material data's field name
         * ({@code baseColor}, {@code baseColorTextureOffset}), a feature's prefixed by the feature and a colon
         * ({@code clearcoat:strength}).
         */
        MATERIAL,
        /** A value of light {@link #index()} of the model's: {@code color}, {@code intensity}, {@code range}, ... */
        LIGHT,
        /** A value of camera {@link #index()} of the model's: {@code yfov}, {@code znear}, {@code xmag}, ... */
        CAMERA,
        /** Whether skeleton node {@link #index()} shows, with everything under it: 1 visible, 0 hidden. */
        VISIBILITY
    }

    private final String path;
    private final Target target;
    private final int index;
    private final String property;
    private final AssetRef<?> asset;
    private final double[] rest;

    /**
     * A pointer at {@code path} (the source's JSON pointer, or any name) animating {@code property} of {@code target}
     * number {@code index}; {@code asset} the material for {@link Target#MATERIAL} (else null); {@code rest} its value
     * when no clip moves it, one to four components.
     */
    @AllowAllocation("pointers are made when clips load")
    public AnimationPointer(String path, Target target, int index, String property, AssetRef<?> asset,
        double... rest) {
        this.path = Objects.requireNonNull(path, "path");
        this.target = Objects.requireNonNull(target, "target");
        this.property = Objects.requireNonNull(property, "property");
        if (index < 0) {
            throw new IllegalArgumentException("Not an index: " + index);
        }
        if (target == Target.MATERIAL && asset == null) {
            throw new IllegalArgumentException("A material pointer names its material: " + path);
        }
        if (rest.length < 1 || rest.length > 4) {
            throw new IllegalArgumentException("A pointer animates 1 to 4 values: " + rest.length + " at " + path);
        }
        this.index = index;
        this.asset = asset;
        this.rest = rest.clone();
    }

    /** The source's pointer, e.g. {@code /materials/0/pbrMetallicRoughness/baseColorFactor}. */
    public String path() {
        return path;
    }

    public Target target() {
        return target;
    }

    /** The light's or camera's index in its model, the skeleton node's for visibility, the material's in its file. */
    public int index() {
        return index;
    }

    /** What of the target it animates (see {@link Target}). */
    public String property() {
        return property;
    }

    /** The material asset it animates, or null for other targets. */
    public AssetRef<?> asset() {
        return asset;
    }

    /** Values per sample: 1 to 4. */
    public int components() {
        return rest.length;
    }

    /** Component {@code c} of its value when no clip moves it. */
    public double rest(int c) {
        return rest[c];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AnimationPointer p && p.path.equals(path) && p.target == target && p.index == index
            && p.property.equals(property) && Objects.equals(p.asset, asset) && p.rest.length == rest.length;
    }

    @Override
    @AllowAllocation("hashing its parts")
    public int hashCode() {
        return Objects.hash(path, target, index, property, asset, rest.length);
    }

    @Override
    public String toString() {
        return "AnimationPointer[" + path + ": " + target + " " + index + " " + property + "]";
    }
}
