package com.truesim.scene.effects;

/**
 * The Java twin of a game's module (effects.md E24): what its Slang struct does to a particle, for CPU emitters, which
 * {@link CpuSimulation} runs as the GPU kernel runs the struct, at birth for an initializer, each step after the motion
 * and collision for an updater. A module without one is GPU only: CPU emitters naming it are refused when the effect
 * is built. The engine's build wires it into the module's type from the struct's {@code [ModuleTwin("class")]}; by
 * hand, {@link ModuleType.Builder#twin}. Called from the simulator's threads: it keeps no state of its own.
 *
 * <pre>{@code
 * public final class GustTwin implements ModuleTwin {   // [ParticleModule("game.gust")]: float strength
 *     public void run(TwinParticle p) {
 *         float a = p.value(0);                           // fields by index, in alphabetical order of their names
 *         p.velocity(p.vx() + a * p.h(), p.vy(), p.vz());
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ModuleTwin {

    /** Runs the module on {@code p}. */
    void run(TwinParticle p);
}
