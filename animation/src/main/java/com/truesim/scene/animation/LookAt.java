package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A joint (a head, an eye, a gun) turned to point an axis of its own at a target, the turn shared down a chain of the
 * joints above it (a neck, a spine), each taking its share, the joint itself the rest; never past an angle limit from
 * where the animation points it. Set up on a rig ({@link Rig.Builder#lookAt}); its target and weight are each
 * animator's ({@link Animator#lookAt}).
 *
 * <pre>{@code
 * LookAt look = r.lookAt(head, 0, 0, 1).chain(neck, 0.4).chain(spine, 0.2).limit(Maths.toRadians(70));
 * a.lookAt(look).target(tx, ty, tz).weight(0.8);
 * }</pre>
 */
public final class LookAt {

    private final Rig rig;
    final int index;
    final int joint;
    final double[] axis;
    int[] chain = new int[0];
    double[] shares = new double[0];
    double limit = Math.PI;

    LookAt(Rig rig, int index, int joint, double[] axis) {
        this.rig = rig;
        this.index = index;
        this.joint = joint;
        this.axis = axis;
    }

    public Rig rig() {
        return rig;
    }

    /**
     * Has {@code above} (above the joint, and above the chain's joints so far) take {@code share} (0 to 1) of the turn
     * left when it comes; the chain turns from its top down. While the rig is set up.
     */
    @AllowAllocation("setting up a rig")
    public LookAt chain(Joint above, double share) {
        rig.checkSettingUp();
        int index = rig.jointIndex(above);
        int below = chain.length == 0 ? joint : chain[chain.length - 1];
        if (!rig.isAbove(index, below)) {
            throw new IllegalArgumentException(above + " is not above the chain's last joint");
        }
        if (!(share >= 0 && share <= 1)) {
            throw new IllegalArgumentException("A share from 0 to 1: " + share);
        }
        chain = Arrays.copyOf(chain, chain.length + 1);
        shares = Arrays.copyOf(shares, shares.length + 1);
        chain[chain.length - 1] = index;
        shares[shares.length - 1] = share;
        return this;
    }

    /** Turns no further than {@code radians} from where the animation points it; while the rig is set up. */
    public LookAt limit(double radians) {
        rig.checkSettingUp();
        if (!(radians >= 0 && radians <= Math.PI)) {
            throw new IllegalArgumentException("A limit from 0 to pi: " + radians);
        }
        limit = radians;
        return this;
    }

    public double limit() {
        return limit;
    }
}
