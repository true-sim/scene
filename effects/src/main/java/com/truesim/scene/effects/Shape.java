package com.truesim.scene.effects;

import com.truesim.scene.assets.AssetRef;

/**
 * Emission shapes, initializers placing each particle at its birth and giving it a direction that
 * {@link Init#speed} sends it along. Shapes are in the emitter's frame: its node's (or joint's) position and rotation
 * with the effect's offset, +Y up.
 *
 * <pre>{@code
 * e.init(Shape.cone(0.5f, 0.02f), Init.speed(Bind.random(4, 11)))
 * e.init(Shape.mesh(Shape.SURFACE), Shape.mask(BURN_MASK), Init.speed(Bind.random(0.2f, 0.5f)))  // GPU emitters
 * }</pre>
 *
 * <p>The mesh, bone and mask shapes are the GPU's only (the CPU never sees posed vertices or textures): the engine
 * hands them the mesh of the renderable the effect's node draws (a skinned one's posed copy) and the joints an emitter
 * runs along.
 */
public final class Shape {

    /** At the emitter's position, in a random direction. */
    public static final String POINT = "shape.point";
    /** In a sphere (or a shell of it), directed outwards. */
    public static final String SPHERE = "shape.sphere";
    /** In a box centered on the emitter, in a random direction. */
    public static final String BOX = "shape.box";
    /** On a disc in the emitter's XZ plane, in directions within an angle of +Y. */
    public static final String CONE = "shape.cone";
    /** On a disc in the emitter's XZ plane, directed along +Y. */
    public static final String DISC = "shape.disc";
    /**
     * On the mesh the effect's node draws, uniform by area ({@link #SURFACE}), on its vertices ({@link #VERTICES}) or
     * in its volume ({@link #VOLUME}), directed along the surface's normal (outwards from the middle in the volume);
     * a skinned mesh as posed. GPU only.
     */
    public static final String MESH = "shape.mesh";
    /** Along a chain of joints the engine names, uniform by length, within a radius of it, directed outwards. GPU only. */
    public static final String BONES = "shape.bones";
    /**
     * A texture or density mask over the shapes before it: each particle is kept with the probability of the mask's
     * channel where it was placed (or only where it reaches a threshold), the others are born dead. GPU only.
     */
    public static final String MASK = "shape.mask";

    /** {@link #MESH}'s mode: on its surface, uniform by area. */
    public static final int SURFACE = 0;
    /** {@link #MESH}'s mode: on its vertices, each as likely. */
    public static final int VERTICES = 1;
    /** {@link #MESH}'s mode: in its volume, uniform. */
    public static final int VOLUME = 2;

    /** {@link #MASK}'s channel: the texture's red, green, blue, alpha (0 to 3) or luminance. */
    public static final int LUMINANCE = 4;

    private Shape() {
    }

    /** At the emitter's position, in a random direction. */
    public static Module point() {
        return Module.of(POINT);
    }

    /** In a ball of {@code radius} metres, directed outwards. */
    public static Module sphere(float radius) {
        return sphere(radius, 1);
    }

    /**
     * In the outer {@code thickness} of a ball of {@code radius} metres, as a fraction of the radius (0: on its
     * surface, 1: anywhere in it), directed outwards.
     */
    public static Module sphere(float radius, float thickness) {
        return Module.of(SPHERE).with("radius", radius).with("thickness", thickness);
    }

    /** In a box of {@code x} by {@code y} by {@code z} metres centered on the emitter, in a random direction. */
    public static Module box(float x, float y, float z) {
        return Module.of(BOX).with("x", x).with("y", y).with("z", z);
    }

    /**
     * On a disc of {@code radius} metres in the emitter's XZ plane, in directions at most {@code angle} radians from
     * +Y.
     */
    public static Module cone(float angle, float radius) {
        return Module.of(CONE).with("angle", angle).with("radius", radius);
    }

    /** On a disc of {@code radius} metres in the emitter's XZ plane, directed along +Y. */
    public static Module disc(float radius) {
        return Module.of(DISC).with("radius", radius);
    }

    /** On the mesh the effect's node draws, as {@code mode} says: {@link #SURFACE}, {@link #VERTICES} or {@link #VOLUME}. */
    public static Module mesh(int mode) {
        if (mode < SURFACE || mode > VOLUME) {
            throw new IllegalArgumentException("Not a mesh mode: " + mode);
        }
        return Module.of(MESH).with("mode", (float) mode);
    }

    /**
     * On the mesh as {@code mode} says, places facing up the most: a particle is kept with the chance
     * {@code 1 - upward + upward * max(0, n.y)} for the surface's normal {@code n} in the emitter's frame (0: as
     * {@link #mesh(int)}, 1: only where the surface faces up, the more the more it does); the others are born dead.
     */
    public static Module mesh(int mode, float upward) {
        if (!(upward >= 0 && upward <= 1)) {
            throw new IllegalArgumentException("Not an upward weight: " + upward);
        }
        return mesh(mode).with("upward", upward);
    }

    /** Along the joints the engine gives the emitter, within {@code radius} metres of the segments between them. */
    public static Module bones(float radius) {
        return Module.of(BONES).with("radius", radius);
    }

    /**
     * Masked by {@code texture}'s luminance, a density: at a mesh's texture coordinates, else the position in the
     * emitter's XZ plane across a square metre about it.
     */
    public static Module mask(AssetRef<?> texture) {
        return Module.of(MASK).with("mask", texture);
    }

    /**
     * Masked by channel {@code channel} of {@code texture} (0 to 3, or {@link #LUMINANCE}): a density where
     * {@code threshold} is 0, else kept only where it reaches {@code threshold}; projected on the emitter's XZ plane
     * across {@code sizeX} by {@code sizeZ} metres where the shape has no texture coordinates.
     */
    public static Module mask(AssetRef<?> texture, int channel, float threshold, float sizeX, float sizeZ) {
        if (channel < 0 || channel > LUMINANCE) {
            throw new IllegalArgumentException("Not a channel: " + channel);
        }
        return mask(texture).with("channel", (float) channel).with("threshold", threshold).with("sizeX", sizeX)
            .with("sizeZ", sizeZ);
    }
}
