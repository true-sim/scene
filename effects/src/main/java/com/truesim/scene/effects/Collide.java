package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.util.ArrayList;
import java.util.List;

/**
 * Collision of particles (E10, effects.md §2.7), chosen per emitter: a {@link Collision} chain of methods, the first
 * the emitter's target and the device have being used ({@code Collide.rays().orElse(Collide.depth())}), each
 * responding by bouncing, sliding, sticking, dying or snapping to the surface. Methods:
 * <ul>
 *   <li>{@link #colliders}: the scene's {@link EffectColliders} in the layers of a mask; GPU and CPU;</li>
 *   <li>{@link #height}: the ground's height field (the game's {@link ParticleWorld#height} on the CPU, a texture of
 *       it around the camera on the GPU); GPU and CPU;</li>
 *   <li>{@link #depth}: the last frame's depth of a view (right on screen only); GPU only;</li>
 *   <li>{@link #rays}: ray queries against the frame's ray tracing structures, where the device traces rays; GPU
 *       only;</li>
 *   <li>{@link #world}: rays against the game's physics scene ({@link ParticleWorld#raycast}); CPU only.</li>
 * </ul>
 *
 * <pre>{@code
 * e.collide(Collide.rays().orElse(Collide.depth()).bounce(0.35f).friction(0.2f))
 * }</pre>
 *
 * <p>Every method's module has the fields {@code response} (a {@link Response}'s ordinal), {@code restitution} (of the
 * speed into the surface, bouncing), {@code friction} (the share of the speed along the surface lost at a contact),
 * {@code radius} (the particle's, metres; 0: half its size) and {@code fallback} (1: used only when the method before
 * it in the chain is not there); colliders also {@code layers}, depth also {@code thickness} (metres behind the depth
 * that still count as touching it).
 */
public final class Collide {

    /** Analytic colliders on nodes. */
    public static final String COLLIDERS = "collide.colliders";
    /** The ground's height field. */
    public static final String HEIGHT = "collide.height";
    /** The last frame's depth of a view. */
    public static final String DEPTH = "collide.depth";
    /** Ray queries against the ray tracing structures. */
    public static final String RAYS = "collide.rays";
    /** Rays against the game's physics scene. */
    public static final String WORLD = "collide.world";

    /** What a particle does when it hits. */
    public enum Response {
        /** Bounces: the speed into the surface turned back, scaled by the restitution, friction along it. */
        BOUNCE,
        /** Slides along the surface: the speed into it gone, friction along it. */
        SLIDE,
        /** Stops where it hit and stays there. */
        STICK,
        /** Dies: drawn no more, gone at the next step. */
        KILL,
        /** Is placed on the surface and frozen there: leaves settling, decals, puddles. */
        SNAP
    }

    private Collide() {
    }

    /** Whether {@code module} is a fallback in a collision chain: its {@code fallback} field 1. */
    static boolean fallback(Module module) {
        return module.value("fallback") instanceof Binding b && b.kind() == Binding.Kind.CONSTANT
            && b.value() * b.scale() >= 0.5f;
    }

    /** The scene's colliders in {@code layers} (a mask of {@link EffectColliders} layers). */
    public static Collision colliders(int layers) {
        if (layers < 0 || layers > EffectColliders.ALL_LAYERS) {
            throw new IllegalArgumentException("Not a layer mask of 24 bits: " + layers);
        }
        return Collision.of(Module.of(COLLIDERS).with("layers", layers));
    }

    /** The ground's height field. */
    public static Collision height() {
        return Collision.of(Module.of(HEIGHT));
    }

    /** The last frame's depth of the simulation's collision view, {@code thickness} metres thick. */
    public static Collision depth(float thickness) {
        return Collision.of(Module.of(DEPTH).with("thickness", thickness));
    }

    /** The last frame's depth, half a metre thick. */
    public static Collision depth() {
        return depth(0.5f);
    }

    /** Ray queries against the ray tracing structures. */
    public static Collision rays() {
        return Collision.of(Module.of(RAYS));
    }

    /** Rays against the game's physics scene, through the simulation's {@link ParticleWorld}. */
    public static Collision world() {
        return Collision.of(Module.of(WORLD));
    }

    /**
     * A chain of collision methods, the first the emitter's target (and the device) has being used, with one response.
     * Immutable; {@link EmitterData.Builder#collide} adds it to an emitter's updaters.
     *
     * @param modules its methods' modules, the first the preferred, the others flagged as fallbacks
     */
    public record Collision(List<Module> modules) {

        @AllowAllocation("collisions are made when effects are set up")
        public Collision {
            modules = List.copyOf(modules);
        }

        @AllowAllocation("collisions are made when effects are set up")
        static Collision of(Module module) {
            return new Collision(List.of(module.with("response", Response.BOUNCE.ordinal())
                .with("restitution", 0.5f)));
        }

        /** This chain, then {@code next}'s methods where none of these is there (with this chain's response). */
        @AllowAllocation("collisions are made when effects are set up")
        public Collision orElse(Collision next) {
            List<Module> all = new ArrayList<>(modules);
            Module first = modules.getFirst();
            for (Module m : next.modules) {
                Module fallback = m.with("fallback", 1f);
                for (String field : new String[] {"response", "restitution", "friction", "radius"}) {
                    if (first.value(field) instanceof Binding b) {
                        fallback = fallback.with(field, b);
                    }
                }
                all.add(fallback);
            }
            return new Collision(all);
        }

        /** Bouncing back with {@code restitution} of the speed into the surface. */
        public Collision bounce(float restitution) {
            return set("restitution", restitution).respond(Response.BOUNCE);
        }

        /** Losing {@code friction} (0 to 1) of the speed along the surface at a contact. */
        public Collision friction(float friction) {
            return set("friction", friction);
        }

        /** Sliding along the surface. */
        public Collision slide() {
            return respond(Response.SLIDE);
        }

        /** Stopping and staying where it hit. */
        public Collision stick() {
            return respond(Response.STICK);
        }

        /** Dying where it hit. */
        public Collision kill() {
            return respond(Response.KILL);
        }

        /** Placed on the surface and frozen there. */
        public Collision snap() {
            return respond(Response.SNAP);
        }

        /** The particles' collision radius, in metres (0, the default: half their size). */
        public Collision radius(float radius) {
            return set("radius", radius);
        }

        private Collision respond(Response response) {
            return set("response", response.ordinal());
        }

        @AllowAllocation("collisions are made when effects are set up")
        private Collision set(String field, float value) {
            List<Module> all = new ArrayList<>(modules.size());
            for (Module m : modules) {
                all.add(m.with(field, value));
            }
            return new Collision(all);
        }
    }
}
