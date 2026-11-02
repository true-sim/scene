package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.Objects;

/**
 * Every animator of a scene, updated together once a frame before the scene's own {@link SceneGraph#update()}: clips
 * move on, animators sample in parallel when given jobs, then poses are written to the scene one after another, on its
 * thread; then the events they passed go to their listeners, animator by animator. Morph weights go to a
 * {@link MorphSink} (the renderer's), the values of pointer tracks to a {@link PointerSink} (the renderer's for
 * materials, a {@code NodeVisibility} for visibility, the game's for lights and cameras).
 *
 * <pre>{@code
 * Animators animators = new Animators(scene, jobs);
 * Animator hero = animators.add(model.skeleton(), nodes);
 * hero.play(idle);
 * // each frame
 * animators.update(dt);
 * scene.update();
 * }</pre>
 */
public final class Animators {

    /** Takes morph weights an animator set: the renderer deforms the node's mesh with them. */
    @FunctionalInterface
    public interface MorphSink {
        /** Node {@code node}'s morph weights are {@code weights[offset..offset + count)}; copy what is kept. */
        void weights(long node, double[] weights, int offset, int count);
    }

    /**
     * Takes the values of what an animator's pointer tracks animate (materials, lights, cameras, visibility), once per
     * update while it plays, each pointer its clips key; a sink applies those it knows and ignores the rest.
     */
    @FunctionalInterface
    public interface PointerSink {
        /**
         * {@code animator}'s value of {@code pointer} is {@code values[offset..offset + count)}; copy what is kept.
         * A {@link AnimationPointer.Target#VISIBILITY visibility}'s node is {@code animator.node(pointer.index())}.
         */
        void value(Animator animator, AnimationPointer pointer, double[] values, int offset, int count);

        /** This sink, then {@code next}: two sinks as one, each taking the pointers it knows. */
        @AllowAllocation("set up once")
        default PointerSink andThen(PointerSink next) {
            Objects.requireNonNull(next, "next");
            return (animator, pointer, values, offset, count) -> {
                value(animator, pointer, values, offset, count);
                next.value(animator, pointer, values, offset, count);
            };
        }
    }

    /** Hears an event as an animator's playback passes it. */
    @FunctionalInterface
    public interface Listener {
        /**
         * {@code event} passed on the animator posing {@code node} (its skeleton's first node), its motion weighing
         * {@code weight} (its layer's weight times its own and its fade).
         */
        void fired(long node, AnimationEvent event, float weight);
    }

    /** Animators per job: sampling one is a few microseconds. */
    private static final int GRAIN = 4;

    private final SceneGraph scene;
    private final JobSystem jobs;
    private Animator[] animators = new Animator[8];
    private int count;
    private MorphSink morphs;
    private PointerSink pointers;
    private long ticks; // updates so far, for animators updating every few
    private int added;  // animators added so far, for their turns
    private AnimationEvent[] listened = new AnimationEvent[0];
    private Listener[] listeners = new Listener[0];
    private final JobSystem.RangeTask sample = (from, to, worker) -> {
        for (int i = from; i < to; i++) {
            animators[i].sample();
        }
    };

    /** Animators of {@code scene}, sampled on the updating thread. */
    public Animators(SceneGraph scene) {
        this(scene, null);
    }

    /** Animators of {@code scene}, sampled on {@code jobs} (null: on the updating thread). */
    @AllowAllocation("set up once")
    public Animators(SceneGraph scene, JobSystem jobs) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.jobs = jobs;
    }

    /** Where morph weights go; null drops them. */
    public void morphSink(MorphSink morphs) {
        this.morphs = morphs;
    }

    /** Where pointer tracks' values go ({@link PointerSink#andThen} combines sinks); null drops them. */
    public void pointerSink(PointerSink pointers) {
        this.pointers = pointers;
    }

    /**
     * An animator of a rig of its own posing {@code nodes}, the scene nodes of {@code skeleton}'s nodes in its order (as
     * a model's instance made them): the quick way, playing clips with {@link Animator#play(AnimationClip)}.
     */
    @AllowAllocation("animators are made when models spawn")
    public Animator add(Skeleton skeleton, long[] nodes) {
        return add(Rig.of(skeleton), nodes);
    }

    /** An animator of {@code rig} posing {@code nodes}, the scene nodes of its skeleton's nodes in its order. */
    @AllowAllocation("animators are made when models spawn")
    public Animator add(Rig rig, long[] nodes) {
        Animator animator = new Animator(rig, nodes, scene);
        animator.turn(added++);
        if (count == animators.length) {
            animators = Arrays.copyOf(animators, 2 * count);
        }
        animators[count++] = animator;
        return animator;
    }

    /** Stops updating {@code animator}; its nodes keep their pose. False if it was not one of these. */
    public boolean remove(Animator animator) {
        for (int i = 0; i < count; i++) {
            if (animators[i] == animator) {
                animators[i] = animators[--count];
                animators[count] = null;
                return true;
            }
        }
        return false;
    }

    public int size() {
        return count;
    }

    /** Calls {@code listener} whenever an animator's playback passes {@code event}, after the poses are written. */
    @AllowAllocation("adding a listener")
    public void listen(AnimationEvent event, Listener listener) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(listener, "listener");
        listened = Arrays.copyOf(listened, listened.length + 1);
        listeners = Arrays.copyOf(listeners, listeners.length + 1);
        listened[listened.length - 1] = event;
        listeners[listeners.length - 1] = listener;
    }

    void fire(long node, AnimationEvent event, float weight) {
        for (int i = 0; i < listened.length; i++) {
            if (listened[i] == event) {
                listeners[i].fired(node, event, weight);
            }
        }
    }

    /** Moves every clip on by {@code seconds} and poses the scene; on the scene's thread. Allocates nothing. */
    public void update(double seconds) {
        if (!(seconds >= 0) || seconds == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time step: " + seconds);
        }
        for (int i = 0; i < count; i++) {
            animators[i].tick(ticks, seconds);
        }
        ticks++;
        if (jobs != null && count > GRAIN) {
            jobs.parallelFor(count, GRAIN, sample);
        } else {
            sample.run(0, count, 0);
        }
        for (int i = 0; i < count; i++) {
            animators[i].write(scene, morphs, pointers);
        }
        for (int i = 0; i < count; i++) {
            animators[i].dispatch(this); // in order: deterministic, whatever the threads
        }
    }
}
