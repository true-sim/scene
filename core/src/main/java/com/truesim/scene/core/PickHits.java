package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * What one ray picked ({@link ScenePicker#pick}): the pickable nodes it hit, nearest first, each with its distance
 * along the ray and the part struck (a triangle, -1 for a box), and the ray itself, for hit points. Reuse one per
 * pointer; reading allocates nothing.
 */
public final class PickHits {

    private final long[] nodes;
    private final double[] distances;
    private final int[] parts;
    private final double[] ray = new double[6];
    private double reach;
    private int count;

    /** Room for {@code capacity} hits (how deep pass-through can go). */
    @AllowAllocation("one per pointer")
    public PickHits(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Room for no hits: " + capacity);
        }
        nodes = new long[capacity];
        distances = new double[capacity];
        parts = new int[capacity];
    }

    /** How many hits it has room for. */
    public int capacity() {
        return nodes.length;
    }

    /** How many nodes the ray hit (up to the capacity). */
    public int count() {
        return count;
    }

    /** Hit {@code i}'s node, nearest first. */
    public long node(int i) {
        return nodes[Objects.checkIndex(i, count)];
    }

    /** Hit {@code i}'s distance along the ray, in units of its direction's length. */
    public double distance(int i) {
        return distances[Objects.checkIndex(i, count)];
    }

    /** The part hit {@code i} struck: a triangle of a mesh, or -1. */
    public int part(int i) {
        return parts[Objects.checkIndex(i, count)];
    }

    /** Hit {@code i}'s world point into {@code out[0..2]}. */
    public void point(int i, double[] out) {
        double t = distance(i);
        out[0] = ray[0] + ray[3] * t;
        out[1] = ray[1] + ray[4] * t;
        out[2] = ray[2] + ray[5] * t;
    }

    /** The index of {@code node}'s hit, or -1. */
    public int indexOf(long node) {
        for (int i = 0; i < count; i++) {
            if (nodes[i] == node) {
                return i;
            }
        }
        return -1;
    }

    /** Component {@code i} of the ray: origin x, y, z, then direction x, y, z. */
    public double ray(int i) {
        return ray[Objects.checkIndex(i, 6)];
    }

    /** How far the ray reached. */
    public double reach() {
        return reach;
    }

    /** Starts over with the ray, no hits. */
    public void reset(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance) {
        ray[0] = ox;
        ray[1] = oy;
        ray[2] = oz;
        ray[3] = dx;
        ray[4] = dy;
        ray[5] = dz;
        reach = maxDistance;
        count = 0;
    }

    /** No hits, no ray. */
    public void clear() {
        reset(0, 0, 0, 0, 0, 0, 0);
    }

    /** Adds a hit; false (nothing added) when full. */
    public boolean add(long node, double distance, int part) {
        if (count == nodes.length) {
            return false;
        }
        nodes[count] = node;
        distances[count] = distance;
        parts[count++] = part;
        return true;
    }

    /** Takes {@code other}'s ray and as many of its hits as fit. */
    public void copyFrom(PickHits other) {
        System.arraycopy(other.ray, 0, ray, 0, 6);
        reach = other.reach;
        count = Math.min(other.count, nodes.length);
        System.arraycopy(other.nodes, 0, nodes, 0, count);
        System.arraycopy(other.distances, 0, distances, 0, count);
        System.arraycopy(other.parts, 0, parts, 0, count);
    }

    /** Whether it holds the same ray, reach as {@code ox ... maxDistance}. */
    boolean sameRay(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance) {
        long differ = bits(ray[0], ox) | bits(ray[1], oy) | bits(ray[2], oz) | bits(ray[3], dx) | bits(ray[4], dy)
            | bits(ray[5], dz) | bits(reach, maxDistance);
        return differ == 0; // bit for bit
    }

    private static long bits(double a, double b) {
        return Double.doubleToRawLongBits(a) ^ Double.doubleToRawLongBits(b);
    }
}
