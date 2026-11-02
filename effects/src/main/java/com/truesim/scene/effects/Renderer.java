package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How an emitter's particles are drawn, whatever simulated them: billboards, meshes, ribbons through the particles in
 * spawn order, trails of each particle's past positions, beams, or point lights; with a lighting (E14), a blend from alpha to
 * additive, an order against glass, and whether it is sorted. Further settings by name (textures, flipbooks, stretch,
 * soft fade) are for the engine's renderers, as module fields are for its modules. Immutable, compared by value.
 *
 * <pre>{@code
 * e.render(Renderer.billboard(Renderer.Facing.VELOCITY).set("stretch", 0.04f).asset("texture", SPARK).additive())
 * }</pre>
 *
 * @param kind what it draws
 * @param facing how billboards turn; null for other kinds
 * @param lighting how it is lit
 * @param glass where it draws against blended materials
 * @param alphaToAdd 0 for alpha blending, 1 for additive, between them both (premultiplied either way)
 * @param sorted whether its particles draw back to front
 * @param castsShadows whether mesh particles cast shadows
 * @param settings further settings by name: floats, integers and assets
 */
@AllowAllocation("renderers are made when effects are set up")
public record Renderer(Kind kind, Facing facing, Lighting lighting, GlassOrder glass, float alphaToAdd, boolean sorted,
        boolean castsShadows, Map<String, Object> settings) {

    /** What a renderer draws. */
    public enum Kind {
        /** A camera-facing quad per particle. */
        BILLBOARD,
        /** A mesh instance per particle. */
        MESH,
        /** A strip through the emitter's particles in spawn order, its head at the emitter. */
        RIBBON,
        /** A strip through each particle's past positions. */
        TRAIL,
        /** Segments between two points, with no simulation. */
        BEAM,
        /**
         * A point light per particle (or the brightest of them), its intensity the particle's color times its alpha,
         * casting no shadows: sparks lighting the ground. GPU emitters.
         */
        LIGHT
    }

    /** How billboards turn. */
    public enum Facing {
        /** Towards the camera's position (the eyes' center in VR), up kept up. */
        POSITION,
        /** Parallel to the view plane; on headsets drawn as {@link #POSITION}. */
        VIEW_PLANE,
        /** Along the velocity, stretched by speed: sparks, rain. */
        VELOCITY,
        /** Turning about the emitter's up axis only: fire, grass. */
        AXIS,
        /** By the particle's normal: rings on the ground. */
        FREE
    }

    /** How a renderer is lit. */
    public enum Lighting {
        /** Its color only, emissive. */
        UNLIT,
        /** The lights evaluated at the particle's center, with sun shadow. */
        PER_VERTEX,
        /** Per pixel with a normal map. */
        PER_PIXEL,
        /** Six-way lightmaps, for smoke. */
        SIX_WAY
    }

    /** Where a renderer draws against blended materials. */
    public enum GlassOrder {
        /** After them: right in front of glass, untinted behind it. */
        AFTER,
        /** Before them, so glass tints and refracts it: right behind glass. */
        BEFORE,
        /** Split per pixel by the nearest glass depth. */
        SPLIT,
        /** Into the order-independent blend of the materials: no sort, wrong for additive. */
        ORDER_INDEPENDENT
    }

    /** The most points a trail keeps per particle. */
    public static final int MAX_TRAIL_POINTS = 32;

    public Renderer {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(glass, "glass");
        if ((kind == Kind.BILLBOARD) != (facing != null)) {
            throw new IllegalArgumentException("Billboards and only billboards face: " + kind + " " + facing);
        }
        if (!(alphaToAdd >= 0 && alphaToAdd <= 1)) {
            throw new IllegalArgumentException("Not a blend between alpha and additive: " + alphaToAdd);
        }
        settings = Map.copyOf(settings);
    }

    /** Billboards turning as {@code facing} says, unlit, alpha blended and sorted. */
    public static Renderer billboard(Facing facing) {
        return of(Kind.BILLBOARD, Objects.requireNonNull(facing, "facing"));
    }

    /** Instances of {@code mesh} drawn with {@code material} (assets of the render data), casting no shadows. */
    public static Renderer mesh(AssetRef<?> mesh, AssetRef<?> material) {
        return of(Kind.MESH, null).asset("mesh", mesh).asset("material", material);
    }

    /** A ribbon through the particles in spawn order. */
    public static Renderer ribbon() {
        return of(Kind.RIBBON, null);
    }

    /** A trail of each particle's last {@code points} positions, 2 to {@value #MAX_TRAIL_POINTS}. */
    public static Renderer trail(int points) {
        if (points < 2 || points > MAX_TRAIL_POINTS) {
            throw new IllegalArgumentException("A trail keeps 2 to " + MAX_TRAIL_POINTS + " points: " + points);
        }
        return of(Kind.TRAIL, null).set("points", points);
    }

    /** A beam between two points the effect's parameters or nodes give. */
    public static Renderer beam() {
        return of(Kind.BEAM, null);
    }

    /**
     * A point light at each particle, reaching {@code radius} metres, as bright as {@code intensity} times the
     * particle's color and alpha (a fading spark dims its light). The engine keeps the brightest within a budget per
     * emitter ({@code max} setting) and for all; a {@code fraction} setting lights only that share of the particles.
     */
    public static Renderer light(float radius, float intensity) {
        if (!(radius > 0) || !(intensity >= 0)) {
            throw new IllegalArgumentException("Not a light: " + radius + " m, " + intensity);
        }
        return of(Kind.LIGHT, null).set("radius", radius).set("intensity", intensity);
    }

    private static Renderer of(Kind kind, Facing facing) {
        return new Renderer(kind, facing, Lighting.UNLIT, GlassOrder.AFTER, 0, true, false, Map.of());
    }

    /** This renderer lit as {@code lighting} says. */
    public Renderer lighting(Lighting lighting) {
        return new Renderer(kind, facing, lighting, glass, alphaToAdd, sorted, castsShadows, settings);
    }

    /** This renderer drawn against glass as {@code glass} says. */
    public Renderer glass(GlassOrder glass) {
        return new Renderer(kind, facing, lighting, glass, alphaToAdd, sorted, castsShadows, settings);
    }

    /** This renderer blended {@code alphaToAdd} of the way from alpha to additive. */
    public Renderer alphaToAdd(float alphaToAdd) {
        return new Renderer(kind, facing, lighting, glass, alphaToAdd, sorted, castsShadows, settings);
    }

    /** This renderer additive and unsorted: order does not change a sum. */
    public Renderer additive() {
        return new Renderer(kind, facing, lighting, glass, 1, false, castsShadows, settings);
    }

    /** This renderer drawn back to front, or not. */
    public Renderer sorted(boolean sorted) {
        return new Renderer(kind, facing, lighting, glass, alphaToAdd, sorted, castsShadows, settings);
    }

    /** This renderer's meshes casting shadows, or not. */
    public Renderer castsShadows(boolean castsShadows) {
        return new Renderer(kind, facing, lighting, glass, alphaToAdd, sorted, castsShadows, settings);
    }

    /** This renderer with setting {@code name} the float {@code value}. */
    public Renderer set(String name, float value) {
        return put(name, value);
    }

    /** This renderer with setting {@code name} the integer {@code value}. */
    public Renderer set(String name, int value) {
        return put(name, value);
    }

    /** This renderer with setting {@code name} the asset {@code value}: a texture, a mesh, a material. */
    public Renderer asset(String name, AssetRef<?> value) {
        return put(name, value);
    }

    private Renderer put(String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(settings);
        copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
        return new Renderer(kind, facing, lighting, glass, alphaToAdd, sorted, castsShadows, copy);
    }
}
