package com.truesim.scene.audio;

import java.util.Objects;

/**
 * A value the game sets per playing sound ({@code instance.set(RPM, 3000)}), which curves on nodes read (volume and
 * pitch by parameter, blends, scatter rates). Declared once as a typed constant with its default and range; equal by
 * value.
 *
 * <pre>{@code
 * static final SoundParameter RPM = SoundParameter.of("rpm", 800, 0, 9000);
 * }</pre>
 *
 * @param name     its name, unique within a sound
 * @param fallback its value until set
 * @param min      the least value it takes; values set are clamped
 * @param max      the greatest value it takes
 */
public record SoundParameter(String name, float fallback, float min, float max) {

    public SoundParameter {
        Objects.requireNonNull(name, "name");
        if (!(min <= fallback && fallback <= max)) {
            throw new IllegalArgumentException("Parameter " + name + ": " + fallback + " is not in [" + min + ", "
                + max + "]");
        }
    }

    /** A parameter called {@code name}, {@code fallback} until set, taking values in [{@code min}, {@code max}]. */
    public static SoundParameter of(String name, float fallback, float min, float max) {
        return new SoundParameter(name, fallback, min, max);
    }

    /** {@code value} clamped to its range; its default for NaN. */
    public float clamp(float value) {
        return value != value ? fallback : Math.min(max, Math.max(min, value));
    }
}
