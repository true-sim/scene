package com.truesim.scene.effects;

/** Where an emitter's particles simulate, fixed in the asset: nothing moves an emitter between targets. */
public enum Target {
    /** On the GPU, the default: up to millions of particles, never read back. */
    GPU,
    /** On the CPU, on the job system's threads: small effects, and particles gameplay reads. */
    CPU
}
