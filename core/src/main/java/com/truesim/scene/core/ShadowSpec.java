package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * How a light casts shadows (application-api.md D46, engine#120): a {@link ShadowMethod} and what it needs, carried by
 * its {@link LightSource}. The engine's shadow owner reads it every frame, so changing it (another method, size or
 * softness) changes the light's shadows from the next frame, without rebuilding anything. Immutable: keep the specs a
 * game switches between, so switching allocates nothing.
 *
 * <pre>{@code
 * sun.shadow(ShadowSpec.cascades(4, 2048, 300));          // cascaded maps to 300 m
 * lamp.shadow(ShadowSpec.maps(ShadowSpec.AUTO));          // a map sized by how big the light shows on screen
 * torch.shadow(ShadowSpec.softRayTraced(0.05));           // ray traced, from a sphere of 5 cm: soft, denoised
 * }</pre>
 *
 * <ul>
 *   <li>{@code resolution}: texels per side of each map, a power of two; {@link #AUTO}: chosen each frame from how big
 *   the light shows on screen (cascades: from the view), within {@code minSize} and {@code maxSize}.</li>
 *   <li>{@code cascades}: directional lights' cascades; {@link #AUTO}: as many as the distance needs.</li>
 *   <li>{@code distance}: how far from the camera a directional light's maps reach (in units); a ray-traced
 *   directional light's rays end there (0: unbounded).</li>
 *   <li>{@code bias}: maps' bias in texels (along the normal, and a third of it deep); NaN: the engine's setting.</li>
 *   <li>{@code softness}: the light's size, for soft ray-traced shadows: a directional light's angular radius in
 *   radians (the sun's is about 0.0047), a point or spot light's radius in units. 0: hard.</li>
 *   <li>{@code importance}: weighs the light against others when the maps' budget is short (1: as any light).</li>
 * </ul>
 *
 * @param method     how it casts shadows
 * @param resolution texels per side of a map, a power of two from 16 to 16384, or {@link #AUTO}
 * @param minSize    with {@link #AUTO}: the smallest map it is given (smaller and it casts none)
 * @param maxSize    with {@link #AUTO}: the largest
 * @param cascades   a directional light's cascades, 1 to 8, or {@link #AUTO}
 * @param distance   how far its maps (or rays) reach, 0 or more; 0 for maps: the engine's default
 * @param bias       maps' bias in texels, 0 to 64, or NaN for the engine's setting
 * @param softness   its size: angular radius (directional) or radius (point, spot), 0 or more
 * @param importance its weight against other lights for the maps' budget, more than 0
 */
public record ShadowSpec(ShadowMethod method, int resolution, int minSize, int maxSize, int cascades, double distance,
    float bias, double softness, float importance) {

    /** A size or count chosen by the engine each frame. */
    public static final int AUTO = 0;
    /** The smallest map size. */
    public static final int MIN_SIZE = 16;
    /** The largest map size. */
    public static final int MAX_SIZE = 16384;
    /** The most cascades. */
    public static final int MAX_CASCADES = 8;

    /** No shadows: what a light casts until given a spec. */
    public static final ShadowSpec NONE = new ShadowSpec(ShadowMethod.NONE, AUTO, 64, 2048, AUTO, 0, Float.NaN, 0, 1);

    public ShadowSpec {
        Objects.requireNonNull(method, "method");
        if (resolution != AUTO && !size(resolution)) {
            throw new IllegalArgumentException("A map size is a power of two from 16 to 16384, or AUTO: " + resolution);
        }
        if (!size(minSize) || !size(maxSize) || minSize > maxSize) {
            throw new IllegalArgumentException("Map sizes from " + minSize + " to " + maxSize);
        }
        if (cascades < 0 || cascades > MAX_CASCADES) {
            throw new IllegalArgumentException("1 to 8 cascades, or AUTO: " + cascades);
        }
        if (!(distance >= 0) || Double.isInfinite(distance)) {
            throw new IllegalArgumentException("Not a distance: " + distance);
        }
        if (!Float.isNaN(bias) && !(bias >= 0 && bias <= 64)) {
            throw new IllegalArgumentException("A bias of 0 to 64 texels, or NaN: " + bias);
        }
        if (!(softness >= 0) || Double.isInfinite(softness)) {
            throw new IllegalArgumentException("Not a softness: " + softness);
        }
        if (!(importance > 0) || Float.isInfinite(importance)) {
            throw new IllegalArgumentException("Not an importance: " + importance);
        }
    }

    private static boolean size(int texels) {
        return texels >= MIN_SIZE && texels <= MAX_SIZE && Integer.bitCount(texels) == 1;
    }

    /** Shadow maps of {@code resolution} texels per side ({@link #AUTO}: by the light's size on screen). */
    @AllowAllocation("specs are made when a scene is set up")
    public static ShadowSpec maps(int resolution) {
        return NONE.method(ShadowMethod.MAPS).resolution(resolution);
    }

    /**
     * {@code count} cascaded maps ({@link #AUTO}: as the distance needs) of {@code resolution} texels per side
     * ({@link #AUTO}: by the view), reaching {@code distance} units from the camera: for directional lights; other
     * lights take one map.
     */
    @AllowAllocation("specs are made when a scene is set up")
    public static ShadowSpec cascades(int count, int resolution, double distance) {
        return new ShadowSpec(ShadowMethod.CASCADES, resolution, 256, 4096, count, distance, Float.NaN, 0, 1);
    }

    /** Hard shadows traced per pixel. */
    @AllowAllocation("specs are made when a scene is set up")
    public static ShadowSpec rayTraced() {
        return NONE.method(ShadowMethod.RAY_TRACED);
    }

    /**
     * Soft shadows traced per pixel and denoised, from a light of {@code softness}: a directional light's angular
     * radius in radians, a point or spot light's radius in units.
     */
    @AllowAllocation("specs are made when a scene is set up")
    public static ShadowSpec softRayTraced(double softness) {
        return NONE.method(ShadowMethod.SOFT_RAY_TRACED).softness(softness);
    }

    /** This spec with {@code value} as its method. */
    @AllowAllocation("specs are made when a scene is set up")
    public ShadowSpec method(ShadowMethod value) {
        return new ShadowSpec(value, resolution, minSize, maxSize, cascades, distance, bias, softness, importance);
    }

    /** This spec with maps of {@code value} texels per side, or {@link #AUTO}. */
    @AllowAllocation("specs are made when a scene is set up")
    public ShadowSpec resolution(int value) {
        return new ShadowSpec(method, value, minSize, maxSize, cascades, distance, bias, softness, importance);
    }

    /** This spec with {@link #AUTO} maps between {@code min} and {@code max} texels per side. */
    @AllowAllocation("specs are made when a scene is set up")
    public ShadowSpec auto(int min, int max) {
        return new ShadowSpec(method, AUTO, min, max, cascades, distance, bias, softness, importance);
    }

    /** This spec with {@code count} cascades, or {@link #AUTO}. */
    @AllowAllocation("specs are made when a scene is set up")
    public ShadowSpec cascades(int count) {
        return new ShadowSpec(method, resolution, minSize, maxSize, count, distance, bias, softness, importance);
    }

    /** This spec reaching {@code units}. */
    @AllowAllocation("specs are made when a scene is set up")
    public ShadowSpec distance(double units) {
        return new ShadowSpec(method, resolution, minSize, maxSize, cascades, units, bias, softness, importance);
    }

    /** This spec with a bias of {@code texels} (NaN: the engine's setting). */
    @AllowAllocation("specs are made when a scene is set up")
    public ShadowSpec bias(float texels) {
        return new ShadowSpec(method, resolution, minSize, maxSize, cascades, distance, texels, softness, importance);
    }

    /** This spec from a light of {@code size}: angular radius (directional) or radius (point, spot). */
    @AllowAllocation("specs are made when a scene is set up")
    public ShadowSpec softness(double size) {
        return new ShadowSpec(method, resolution, minSize, maxSize, cascades, distance, bias, size, importance);
    }

    /** This spec weighing {@code weight} against other lights for the maps' budget. */
    @AllowAllocation("specs are made when a scene is set up")
    public ShadowSpec importance(float weight) {
        return new ShadowSpec(method, resolution, minSize, maxSize, cascades, distance, bias, softness, weight);
    }

    /** Whether the light casts shadows at all. */
    public boolean casts() {
        return method != ShadowMethod.NONE;
    }
}
