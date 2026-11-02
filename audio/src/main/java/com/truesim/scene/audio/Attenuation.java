package com.truesim.scene.audio;

import com.truesim.common.math.Maths;
import java.util.Objects;

/**
 * How a 3D sound changes with distance from its listener: the gain by a curve between a minimum and a maximum
 * distance, a low-pass from air (a cutoff near and one far, interpolated in octaves), the stereo spread of a source
 * with a size, a reverb send near and far, and an optional cone. Immutable, equal by value; the reads allocate
 * nothing, and use {@link Maths} only for the low-pass (call it per block, not per sample).
 *
 * <pre>{@code
 * static final Attenuation OUTDOOR = Attenuation.builder()
 *     .distance(2, 120).curve(Curve.INVERSE).lowPass(20_000, 4_000)
 *     .spread(3, 0).reverbSend(0.1f, 0.6f).build();
 * }</pre>
 *
 * @param min           the distance within which the gain is 1, metres
 * @param max           the distance at which the laws reach 0, metres; beyond it a sound may go virtual
 * @param curve         the gain by distance
 * @param lowPassNear   the cutoff at the minimum distance, Hz; 0 for no low-pass
 * @param lowPassFar    the cutoff at the maximum distance, Hz
 * @param radius        the source's radius, metres: the spread is the part of the field it fills
 * @param minSpread     the least spread, 0 (a point) to 1 (all around)
 * @param reverbNear    the reverb send at the minimum distance, 0 to 1
 * @param reverbFar     the reverb send at the maximum distance
 * @param coneInner     the cone's full-gain angle, degrees; 360 for no cone
 * @param coneOuter     the cone's angle beyond which the gain is {@code coneOuterGain}
 * @param coneOuterGain the gain outside the cone
 */
public record Attenuation(float min, float max, Curve curve, float lowPassNear, float lowPassFar, float radius,
        float minSpread, float reverbNear, float reverbFar, float coneInner, float coneOuter, float coneOuterGain) {

    /** 1 to 50 m, inverse distance, nothing else: what sounds without their own attenuation use. */
    public static final Attenuation DEFAULT = near(1, 50);

    public Attenuation {
        Objects.requireNonNull(curve, "curve");
        if (!(min > 0 && max > min) || Float.isInfinite(max)) {
            throw new IllegalArgumentException("Not distances from near to far: " + min + " to " + max);
        }
        if (lowPassNear < 0 || lowPassFar < 0 || (lowPassNear > 0) != (lowPassFar > 0)) {
            throw new IllegalArgumentException("Low-pass cutoffs are both positive, or both 0 for none");
        }
        if (radius < 0 || minSpread < 0 || minSpread > 1) {
            throw new IllegalArgumentException("Not a spread: radius " + radius + ", least " + minSpread);
        }
        if (reverbNear < 0 || reverbNear > 1 || reverbFar < 0 || reverbFar > 1) {
            throw new IllegalArgumentException("Reverb sends are 0 to 1: " + reverbNear + ", " + reverbFar);
        }
        if (!(coneInner >= 0 && coneOuter >= coneInner && coneOuter <= 360 && coneOuterGain >= 0
            && coneOuterGain <= 1)) {
            throw new IllegalArgumentException("Not a cone: " + coneInner + " to " + coneOuter + " degrees, gain "
                + coneOuterGain);
        }
    }

    /** Inverse distance from {@code min} to {@code max} metres, without low-pass, spread, reverb send or cone. */
    public static Attenuation near(double min, double max) {
        return builder().distance(min, max).build();
    }

    /** An attenuation to describe, from {@link #DEFAULT}'s values. */
    public static Builder builder() {
        return new Builder();
    }

    /** {@code distance} between near (0) and far (1), clamped. */
    private float far(float distance) {
        return Math.min(1, Math.max(0, (distance - min) / (max - min)));
    }

    /** The gain at {@code distance} metres. */
    public float gain(float distance) {
        return curve.gain(distance, min, max);
    }

    /** The low-pass cutoff at {@code distance}, Hz, interpolated in octaves; 0 for none. */
    public float lowPass(float distance) {
        if (lowPassNear == 0) {
            return 0;
        }
        return (float) (lowPassNear * Maths.pow(lowPassFar / lowPassNear, far(distance)));
    }

    /** The spread at {@code distance}: the part of the field the source fills, 0 to 1. */
    public float spread(float distance) {
        return Math.max(minSpread, Math.min(1, radius / Math.max(distance, 1e-3f)));
    }

    /** The reverb send at {@code distance}, 0 to 1. */
    public float reverbSend(float distance) {
        return reverbNear + (reverbFar - reverbNear) * far(distance);
    }

    /** The cone's gain at {@code degrees} off the emitter's forward. */
    public float coneGain(float degrees) {
        float half = Math.abs(degrees) * 2; // compared with the cone's full angles
        if (half <= coneInner) {
            return 1;
        }
        if (half >= coneOuter) {
            return coneOuterGain;
        }
        return 1 + (coneOuterGain - 1) * (half - coneInner) / (coneOuter - coneInner);
    }

    /** Describes an attenuation. */
    public static final class Builder {

        private float min = 1;
        private float max = 50;
        private Curve curve = Curve.INVERSE;
        private float lowPassNear;
        private float lowPassFar;
        private float radius;
        private float minSpread;
        private float reverbNear;
        private float reverbFar;
        private float coneInner = 360;
        private float coneOuter = 360;
        private float coneOuterGain = 1;

        private Builder() {
        }

        /** Full gain within {@code min} metres, falling to {@code max}. */
        public Builder distance(double min, double max) {
            this.min = (float) min;
            this.max = (float) max;
            return this;
        }

        /** The gain by distance. */
        public Builder curve(Curve curve) {
            this.curve = curve;
            return this;
        }

        /** A low-pass from {@code nearHz} at the minimum distance to {@code farHz} at the maximum. */
        public Builder lowPass(double nearHz, double farHz) {
            lowPassNear = (float) nearHz;
            lowPassFar = (float) farHz;
            return this;
        }

        /** A source of {@code radius} metres, spread at least {@code least} (0 to 1). */
        public Builder spread(double radius, double least) {
            this.radius = (float) radius;
            minSpread = (float) least;
            return this;
        }

        /** A reverb send from {@code near} at the minimum distance to {@code far} at the maximum. */
        public Builder reverbSend(float near, float far) {
            reverbNear = near;
            reverbFar = far;
            return this;
        }

        /** A cone: full gain within {@code inner} degrees, {@code outerGain} beyond {@code outer}. */
        public Builder cone(double inner, double outer, double outerGain) {
            coneInner = (float) inner;
            coneOuter = (float) outer;
            coneOuterGain = (float) outerGain;
            return this;
        }

        public Attenuation build() {
            return new Attenuation(min, max, curve, lowPassNear, lowPassFar, radius, minSpread, reverbNear,
                reverbFar, coneInner, coneOuter, coneOuterGain);
        }
    }
}
