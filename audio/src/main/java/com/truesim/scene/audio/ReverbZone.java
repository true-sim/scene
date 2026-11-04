package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * A reverb zone as a component on a node (application-api.md D46; saved as {@code truesim.audio.reverb-zone}): a box
 * or a sphere in the node's frame with a reverb, a fade distance outside its shape and a priority. {@link ReverbZones}
 * is the world's view of them; cell loaders add zones with their cells' nodes, so zones stream with cells and go with
 * their nodes. A node has at most one.
 *
 * <pre>{@code
 * scene.components().set(hall, ReverbZone.TYPE, ReverbZone.box(20, 6, 30, 2, AudioEffect.named("reverb"), 0));
 * }</pre>
 */
public final class ReverbZone {

    /** The reverb zone component type. */
    public static final ComponentType<ReverbZone> TYPE = ComponentType.of("truesim.audio.reverb-zone",
        ReverbZone.class);

    /** Half extents (box) or the radius in the first (sphere, the others negative). */
    final double ex;
    final double ey;
    final double ez;
    final double fade;
    final AudioEffect reverb;
    final int priority;
    final double[] place = {0, 0, 0, 0, 0, 0, 1}; // position, then rotation x, y, z, w

    private ReverbZone(double ex, double ey, double ez, double fade, AudioEffect reverb, int priority) {
        if (!(fade >= 0) || Double.isInfinite(fade)) {
            throw new IllegalArgumentException("Not a fade distance: " + fade);
        }
        this.ex = ex;
        this.ey = ey;
        this.ez = ez;
        this.fade = fade;
        this.reverb = Objects.requireNonNull(reverb, "reverb");
        this.priority = priority;
    }

    /**
     * A box zone {@code hx} by {@code hy} by {@code hz} metres from its node's centre in the node's frame, fading out over
     * {@code fade} metres outside, with {@code reverb}; higher {@code priority} wins where zones overlap.
     */
    @AllowAllocation("components are made when a scene is built")
    public static ReverbZone box(double hx, double hy, double hz, double fade, AudioEffect reverb, int priority) {
        if (!(hx > 0 && hy > 0 && hz > 0)) {
            throw new IllegalArgumentException("Not a box: " + hx + " by " + hy + " by " + hz);
        }
        return new ReverbZone(hx, hy, hz, fade, reverb, priority);
    }

    /** A sphere zone of {@code radius} metres, as {@link #box} otherwise. */
    @AllowAllocation("components are made when a scene is built")
    public static ReverbZone sphere(double radius, double fade, AudioEffect reverb, int priority) {
        if (!(radius > 0)) {
            throw new IllegalArgumentException("Not a radius: " + radius);
        }
        return new ReverbZone(radius, -1, -1, fade, reverb, priority);
    }

    /** Whether it is a sphere (else a box). */
    public boolean sphere() {
        return ey < 0;
    }

    /** A box's half extent along {@code axis}, a sphere's radius for axis 0. */
    public double extent(int axis) {
        return switch (Objects.checkIndex(axis, 3)) {
            case 0 -> ex;
            case 1 -> ey;
            default -> ez;
        };
    }

    public double fade() {
        return fade;
    }

    public AudioEffect reverb() {
        return reverb;
    }

    public int priority() {
        return priority;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return (sphere() ? "sphere" : "box") + " reverb zone, priority " + priority;
    }
}
