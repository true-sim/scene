package com.truesim.scene.effects;

/**
 * What a module needs of the device beyond the base simulation kernel: the engine compiles its kernel in a variant per
 * capability and runs each emitter in the variant its modules need.
 */
public enum Capability {
    /** Nothing beyond the base kernel. */
    NONE,
    /** Inline ray queries against the frame's ray tracing structures, where the device traces rays. */
    RAY_QUERY
}
