package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plays all its children, cross-fading between neighbours by a parameter: each child is at full volume at its point
 * and fades out linearly towards its neighbours' (an engine's loops by speed).
 *
 * <pre>{@code
 * Blend.by(SPEED).at(0, idle).at(10, cruise).at(30, fast)
 * }</pre>
 *
 * @param parameter the parameter read
 * @param points    each child's point, increasing
 * @param children  the children
 * @param shaping   volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Blend(SoundParameter parameter, List<Float> points, List<SoundNode> children, Shaping shaping)
        implements SoundNode {

    public Blend {
        Objects.requireNonNull(parameter, "parameter");
        points = List.copyOf(points);
        children = List.copyOf(children);
        Objects.requireNonNull(shaping, "shaping");
    }

    /** A blend by {@code parameter}, to give children. */
    public static Blend by(SoundParameter parameter) {
        return new Blend(parameter, List.of(), List.of(), Shaping.NONE);
    }

    /** A copy with {@code child} at full volume at {@code point}. */
    public Blend at(double point, SoundNode child) {
        List<Float> p = new ArrayList<>(points);
        p.add((float) point);
        List<SoundNode> c = new ArrayList<>(children);
        c.add(child);
        return new Blend(parameter, p, c, shaping);
    }

    /** Child {@code i}'s gain at parameter value {@code value}. */
    public float gain(int i, float value) {
        float p = points.get(i);
        if (value < p) {
            return i == 0 ? 1 : fade(value, points.get(i - 1), p);
        }
        return i == points.size() - 1 ? 1 : 1 - fade(value, p, points.get(i + 1));
    }

    private static float fade(float value, float from, float to) {
        return Math.min(1, Math.max(0, (value - from) / (to - from)));
    }

    @Override
    public Blend with(Shaping shaping) {
        return new Blend(parameter, points, children, shaping);
    }
}
