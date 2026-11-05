package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;

/**
 * A light as a component on a node (application-api.md D46): a point light at the node's origin, a spot light along
 * its -Z within a cone, or a directional light along its -Z, as glTF's punctual lights; the engine's light sync places
 * each in the renderer's lights from the node's world transform, following moves and changes.
 *
 * <pre>{@code
 * long lamp = scene.create(room);
 * scene.components().set(lamp, LightSource.TYPE, LightSource.point(1, 0.85f, 0.7f, 40).range(12));
 * light.intensity(0);                                 // switched off in place: the sync follows
 * light.shadow(ShadowSpec.maps(ShadowSpec.AUTO));      // shadows by a map, sized by the engine
 * }</pre>
 *
 * <p>Colour is linear; intensity is candela for point and spot lights (the illuminance at 1 m), lux for directional
 * ones, so the renderer's irradiance is colour times intensity. A range of {@link #NO_RANGE} falls off with the square
 * of the distance for ever. Changed in place by its setters, each counting {@link #version()}; nothing allocates.
 */
public final class LightSource {

    /** The light component type, saved as {@code truesim.light}. */
    public static final ComponentType<LightSource> TYPE = ComponentType.of("truesim.light", LightSource.class);

    /** No range: the inverse square for ever. */
    public static final double NO_RANGE = Double.POSITIVE_INFINITY;

    /** What kind of light it is. */
    public enum Kind {
        /** From the node's origin in every direction. */
        POINT,
        /** From the node's origin along its -Z, within a cone. */
        SPOT,
        /** Parallel rays along the node's -Z: the sun. */
        DIRECTIONAL
    }

    private Kind kind;
    private float r;
    private float g;
    private float b;
    private double intensity;
    private double range = NO_RANGE;
    private double innerCone;
    private double outerCone = Math.PI / 4;
    private ShadowSpec shadow = ShadowSpec.NONE;
    private long version;

    private LightSource(Kind kind) {
        this.kind = kind;
    }

    /** A point light of colour {@code r, g, b} and {@code intensity} candela. */
    @AllowAllocation("components are made when a scene is built")
    public static LightSource point(float r, float g, float b, double intensity) {
        return new LightSource(Kind.POINT).color(r, g, b).intensity(intensity);
    }

    /** A spot light, full within {@code innerCone} radians of its axis, faded out at {@code outerCone}. */
    @AllowAllocation("components are made when a scene is built")
    public static LightSource spot(float r, float g, float b, double intensity, double innerCone, double outerCone) {
        return new LightSource(Kind.SPOT).color(r, g, b).intensity(intensity).cone(innerCone, outerCone);
    }

    /** A directional light of {@code intensity} lux. */
    @AllowAllocation("components are made when a scene is built")
    public static LightSource directional(float r, float g, float b, double intensity) {
        return new LightSource(Kind.DIRECTIONAL).color(r, g, b).intensity(intensity);
    }

    /** Sets its colour (linear, 0 or more); returns it. */
    public LightSource color(float red, float green, float blue) {
        if (!(red >= 0 && green >= 0 && blue >= 0) || Float.isInfinite(red + green + blue)) {
            throw new IllegalArgumentException("Not a light colour: " + red + ", " + green + ", " + blue);
        }
        r = red;
        g = green;
        b = blue;
        version++;
        return this;
    }

    /** Sets its intensity (0 or more: candela, or lux for a directional light); returns it. */
    public LightSource intensity(double value) {
        if (!(value >= 0) || value == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not an intensity: " + value);
        }
        intensity = value;
        version++;
        return this;
    }

    /** Sets how far it reaches (more than 0, or {@link #NO_RANGE}); returns it. */
    public LightSource range(double value) {
        if (!(value > 0)) {
            throw new IllegalArgumentException("Not a range: " + value);
        }
        range = value;
        version++;
        return this;
    }

    /** Sets a spot's cone: full within {@code inner} radians, none past {@code outer} (up to pi / 2); returns it. */
    public LightSource cone(double inner, double outer) {
        if (!(inner >= 0 && inner <= outer && outer <= Math.PI / 2)) {
            throw new IllegalArgumentException("Not a cone: " + inner + " to " + outer);
        }
        innerCone = inner;
        outerCone = outer;
        version++;
        return this;
    }

    /**
     * Sets how it casts shadows ({@link ShadowSpec}; {@link ShadowSpec#NONE}: none, as at first); returns it. The
     * engine's shadow owner follows it from the next frame, without a rebuild.
     */
    public LightSource shadow(ShadowSpec spec) {
        shadow = java.util.Objects.requireNonNull(spec, "spec");
        version++;
        return this;
    }

    /** Changes its kind; returns it. */
    public LightSource kind(Kind value) {
        kind = java.util.Objects.requireNonNull(value, "kind");
        version++;
        return this;
    }

    public Kind kind() {
        return kind;
    }

    public float red() {
        return r;
    }

    public float green() {
        return g;
    }

    public float blue() {
        return b;
    }

    public double intensity() {
        return intensity;
    }

    public double range() {
        return range;
    }

    public double innerCone() {
        return innerCone;
    }

    public double outerCone() {
        return outerCone;
    }

    /** How it casts shadows. */
    public ShadowSpec shadow() {
        return shadow;
    }

    /** Whether it casts shadows: its {@link #shadow() spec}'s method is not {@link ShadowMethod#NONE}. */
    public boolean castsShadows() {
        return shadow.casts();
    }

    /** A count its setters raise: a sync compares it to see a change made in place. */
    public long version() {
        return version;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return kind + " light " + r + ", " + g + ", " + b + " x " + intensity;
    }
}
