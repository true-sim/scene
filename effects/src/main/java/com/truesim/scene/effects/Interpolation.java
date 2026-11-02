package com.truesim.scene.effects;

/** How a curve or gradient goes from a key to the next. */
public enum Interpolation {
    /** Holds the key's value until the next key. */
    STEP,
    /** A straight line to the next key's value. */
    LINEAR,
    /**
     * A cubic Hermite segment to the next key, its slopes those of the neighbouring keys (Catmull-Rom, one-sided at the
     * ends): smooth through the keys.
     */
    CUBIC
}
