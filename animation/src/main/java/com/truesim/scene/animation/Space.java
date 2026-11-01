package com.truesim.scene.animation;

/** The frame a joint's override is given in ({@link Animator#override}). */
public enum Space {
    /** Relative to the joint's parent, as the pose keeps it. */
    LOCAL,
    /** Relative to the skeleton's root's parent: the instance's frame. */
    MODEL
}
