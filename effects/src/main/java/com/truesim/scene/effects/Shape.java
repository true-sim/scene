package com.truesim.scene.effects;

/**
 * Emission shapes, initializers placing each particle at its birth and giving it a direction that
 * {@link Init#speed} sends it along. Shapes are in the emitter's frame: its node's (or joint's) position and rotation
 * with the effect's offset, +Y up.
 *
 * <pre>{@code
 * e.init(Shape.cone(0.5f, 0.02f), Init.speed(Bind.random(4, 11)))
 * }</pre>
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
}
