package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * The nodes an animator poses: their names (how clips made for other models find them), parents, and rest transforms
 * (translation x, y, z, rotation x, y, z, w, scale x, y, z: 10 per node), each parent before its children. A model's
 * node tree gives one ({@code ModelData.skeleton()}). Immutable.
 */
public final class Skeleton {

    /** Values per node in {@link #rest}: translation, rotation, scale. */
    public static final int TRANSFORM = 10;

    private final String[] names;
    private final int[] parents;
    private final double[] rest;

    /** Nodes called {@code names}, under {@code parents} (-1: a root), at rest as {@code rest} says (10 per node). */
    @AllowAllocation("skeletons are made when models load")
    public Skeleton(String[] names, int[] parents, double[] rest) {
        if (names.length != parents.length || rest.length != TRANSFORM * names.length) {
            throw new IllegalArgumentException("A name, a parent and a transform of 10 values per node: "
                + names.length + ", " + parents.length + ", " + rest.length);
        }
        for (int i = 0; i < parents.length; i++) {
            if (parents[i] < -1 || parents[i] >= i) {
                throw new IllegalArgumentException("Node " + i + "'s parent " + parents[i] + " is not a node before it");
            }
            Objects.requireNonNull(names[i], "names");
        }
        this.names = names.clone();
        this.parents = parents.clone();
        this.rest = rest.clone();
    }

    public int size() {
        return names.length;
    }

    public String name(int node) {
        return names[node];
    }

    public int parent(int node) {
        return parents[node];
    }

    /** Value {@code i} (0 to 9) of node {@code node}'s rest transform. */
    public double rest(int node, int i) {
        return rest[TRANSFORM * node + Objects.checkIndex(i, TRANSFORM)];
    }

    /** The first node called {@code name}, or -1. */
    public int find(String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Copies the rest transforms into {@code pose}. Allocates nothing. */
    void restPose(double[] pose) {
        System.arraycopy(rest, 0, pose, 0, rest.length);
    }
}
