package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * How much a layer moves each joint, 0 to 1: an upper-body layer moves the spine and what hangs from it and leaves the
 * legs to the layer below. Made on a rig ({@link Rig.Builder#mask}); immutable.
 *
 * <pre>{@code
 * Mask upper = r.mask().include(spine).exclude(r.joint("hand_l")).weight(spine, 0.5).build();
 * }</pre>
 */
public final class Mask {

    private final Rig rig;
    private final float[] weights;

    private Mask(Rig rig, float[] weights) {
        this.rig = rig;
        this.weights = weights;
    }

    public Rig rig() {
        return rig;
    }

    /** Joint {@code joint}'s weight. */
    public float weight(Joint joint) {
        return weights[joint.index()];
    }

    float weight(int joint) {
        return weights[joint];
    }

    /** Builds a mask: nothing included at first, joints included with what hangs from them. */
    public static final class Builder {

        private final Rig rig;
        private final float[] weights;

        @AllowAllocation("setting up a rig")
        Builder(Rig rig) {
            this.rig = rig;
            this.weights = new float[rig.skeleton().size()];
        }

        /** Weighs {@code joint} and everything under it 1. */
        public Builder include(Joint joint) {
            return under(joint, 1);
        }

        /** Weighs {@code joint} and everything under it 0. */
        public Builder exclude(Joint joint) {
            return under(joint, 0);
        }

        /** Weighs {@code joint} alone {@code weight}, 0 to 1. */
        public Builder weight(Joint joint, double weight) {
            if (!(weight >= 0 && weight <= 1)) {
                throw new IllegalArgumentException("A weight from 0 to 1: " + weight);
            }
            weights[check(joint)] = (float) weight;
            return this;
        }

        private Builder under(Joint joint, float weight) {
            int root = check(joint);
            Skeleton skeleton = rig.skeleton();
            for (int j = root; j < skeleton.size(); j++) { // children come after their parents
                int up = j;
                while (up > root) {
                    up = skeleton.parent(up);
                }
                if (up == root) {
                    weights[j] = weight;
                }
            }
            return this;
        }

        private int check(Joint joint) {
            Objects.requireNonNull(joint, "joint");
            if (joint.index() < 0 || joint.index() >= weights.length
                || !rig.skeleton().name(joint.index()).equals(joint.name())) {
                throw new IllegalArgumentException("Not a joint of the rig: " + joint);
            }
            return joint.index();
        }

        @AllowAllocation("setting up a rig")
        public Mask build() {
            return new Mask(rig, weights.clone());
        }
    }
}
