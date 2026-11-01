package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A chain of any length (a tail, a tentacle, a spine, a crane) solved to reach a target by FABRIK (Aristidou and
 * Lasenby's forward and backward reaching inverse kinematics): its joints' positions pulled to the target and back to
 * the root a few times, each bone keeping its length, each joint bending no more than its cone limit from the bone
 * before it; then the joints turned to point their bones along the solution. Set up on a rig ({@link Rig.Builder#fabrik});
 * its target and weight are each animator's ({@link Animator#fabrik}).
 *
 * <pre>{@code
 * FabrikChain tail = r.fabrik(r.joint("tail_0"), r.joint("tail_9")).limit(r.joint("tail_3"), 0.3).iterations(12);
 * a.fabrik(tail).target(x, y, z).weight(1);
 * }</pre>
 */
public final class FabrikChain {

    private final Rig rig;
    final int index;
    final int[] joints;   // from the root down to the tip, each the parent of the next
    final double[] limits; // by place in the chain: how far its bone may turn from the bone before, radians
    private int iterations = 10;
    private double tolerance = 1e-4;

    FabrikChain(Rig rig, int index, int[] joints) {
        this.rig = rig;
        this.index = index;
        this.joints = joints;
        this.limits = new double[joints.length];
        Arrays.fill(limits, Math.PI);
    }

    public Rig rig() {
        return rig;
    }

    /** How many joints it has, root and tip included. */
    public int size() {
        return joints.length;
    }

    /** Passes back and forth at most; while the rig is set up. */
    public FabrikChain iterations(int iterations) {
        rig.checkSettingUp();
        if (iterations < 1) {
            throw new IllegalArgumentException("At least one pass: " + iterations);
        }
        this.iterations = iterations;
        return this;
    }

    public int iterations() {
        return iterations;
    }

    /** How near the tip must come to stop early; while the rig is set up. */
    public FabrikChain tolerance(double distance) {
        rig.checkSettingUp();
        if (!(distance >= 0) || distance == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a distance: " + distance);
        }
        this.tolerance = distance;
        return this;
    }

    public double tolerance() {
        return tolerance;
    }

    /**
     * Lets {@code joint}'s bone (to the next joint) turn at most {@code radians} from the bone before it (for the root,
     * from where the animation points it); while the rig is set up.
     */
    @AllowAllocation("setting up a rig")
    public FabrikChain limit(Joint joint, double radians) {
        rig.checkSettingUp();
        int j = rig.jointIndex(joint);
        int place = -1;
        for (int i = 0; i < joints.length - 1; i++) {
            if (joints[i] == j) {
                place = i;
            }
        }
        if (place < 0) {
            throw new IllegalArgumentException("Not a joint with a bone of the chain: " + joint);
        }
        if (!(radians >= 0 && radians <= Math.PI)) {
            throw new IllegalArgumentException("A limit from 0 to pi: " + radians);
        }
        limits[place] = radians;
        return this;
    }

    /** {@code joint}'s limit; pi (none) unless set. */
    public double limit(Joint joint) {
        int j = rig.jointIndex(joint);
        for (int i = 0; i < joints.length; i++) {
            if (joints[i] == j) {
                return limits[i];
            }
        }
        throw new IllegalArgumentException("Not a joint of the chain: " + joint);
    }
}
