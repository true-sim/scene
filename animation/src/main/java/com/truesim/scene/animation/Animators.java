package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.Objects;

/**
 * Every animator of a scene, updated together once a frame before the scene's own {@link SceneGraph#update()}: clips
 * move on, animators sample in parallel when given jobs (but for foot placement's {@link Ground} probes, asked one
 * animator after another on the updating thread between parallel parts: decision A13), then poses are written to the
 * scene one after another, on its thread; then the events they passed go to their listeners, animator by animator. Morph weights go to a
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
 *
 * <p>An animator may run on fixed steps instead ({@link Animator#fixedSteps}, decision A5): {@link #step} updates those,
 * once per fixed step of the world, and {@link #update} the others, once per frame.
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
    private long steps; // fixed steps so far, alike
    private int added;  // animators added so far, for their turns
    // The animators of the update or step running: those updated every frame, or those on fixed steps.
    private Animator[] running = new Animator[8];
    private int runningCount;
    private AnimationEvent[] listened = new AnimationEvent[0];
    private Listener[] listeners = new Listener[0];
    private final JobSystem.RangeTask sample = (from, to, worker) -> {
        for (int i = from; i < to; i++) {
            running[i].sample();
        }
    };

    private final JobSystem.RangeTask resume = (from, to, worker) -> {
        for (int i = from; i < to; i++) {
            running[i].resume();
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
        checkOwner();
        this.morphs = morphs;
    }

    /** Where pointer tracks' values go ({@link PointerSink#andThen} combines sinks); null drops them. */
    public void pointerSink(PointerSink pointers) {
        checkOwner();
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
        checkOwner();
        Animator animator = new Animator(rig, nodes, scene);
        animator.turn(added++);
        if (count == animators.length) {
            animators = Arrays.copyOf(animators, 2 * count);
            running = new Animator[2 * count];
        }
        animators[count++] = animator;
        return animator;
    }

    /** Stops updating {@code animator}; its nodes keep their pose. False if it was not one of these. */
    public boolean remove(Animator animator) {
        checkOwner();
        for (int i = 0; i < count; i++) {
            if (animators[i] == animator) {
                animators[i] = animators[--count];
                animators[count] = null;
                Arrays.fill(running, null);
                return true;
            }
        }
        return false;
    }

    public int size() {
        return count;
    }

    /**
     * The animator posing {@code node} as its skeleton's first node (the node a {@link Listener} hears of), or null.
     * Allocates nothing.
     */
    public Animator animator(long node) {
        for (int i = 0; i < count; i++) {
            if (animators[i].node(0) == node) {
                return animators[i];
            }
        }
        return null;
    }

    /** Calls {@code listener} whenever an animator's playback passes {@code event}, after the poses are written. */
    @AllowAllocation("adding a listener")
    public void listen(AnimationEvent event, Listener listener) {
        checkOwner();
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

    /**
     * Moves the clips of every animator updated each frame (all but those on {@link Animator#fixedSteps fixed steps}) on
     * by {@code seconds} and poses the scene; on the scene's thread. Allocates nothing.
     */
    public void update(double seconds) {
        run(seconds, false, ticks++);
    }

    /**
     * Moves the clips of the animators on {@link Animator#fixedSteps fixed steps} on by {@code seconds}, one step, and
     * poses the scene, as {@link #update} does for the others: an engine calls it in each fixed step of the world, so
     * they keep the world's simulated time whatever the frame rate (decision A5). An animator updating every few
     * ({@link Animator#updateEvery}) counts steps then. Allocates nothing.
     */
    public void step(double seconds) {
        run(seconds, true, steps++);
    }

    private void run(double seconds, boolean fixed, long tick) {
        checkOwner();
        if (!(seconds >= 0) || seconds == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time step: " + seconds);
        }
        runningCount = 0;
        for (int i = 0; i < count; i++) {
            if (animators[i].fixedSteps() == fixed) {
                running[runningCount++] = animators[i];
            }
        }
        if (runningCount == 0) {
            return;
        }
        for (int i = 0; i < runningCount; i++) {
            running[i].tick(tick, seconds);
        }
        run(sample);
        // Foot placement's ground probes in the serial part, in order (A13); the modifiers then go on in parallel
        while (answer()) {
            run(resume);
        }
        for (int i = 0; i < runningCount; i++) {
            running[i].write(scene, morphs, pointers);
        }
        for (int i = 0; i < runningCount; i++) {
            running[i].dispatch(this); // in order: deterministic, whatever the threads
        }
    }

    /** Runs {@code task} over the animators: on the jobs when there are enough of them, else here. */
    private void run(JobSystem.RangeTask task) {
        if (jobs != null && runningCount > GRAIN) {
            jobs.parallelFor(runningCount, GRAIN, task);
        } else {
            task.run(0, runningCount, 0);
        }
    }

    /** Asks the ground for the feet waiting, animator by animator on this thread; whether any were. */
    private boolean answer() {
        boolean any = false;
        for (int i = 0; i < runningCount; i++) {
            if (running[i].waiting()) {
                running[i].answer();
                any = true;
            }
        }
        return any;
    }

    /** Throws unless the calling thread may change the animators: any, or the clock's owner (ChangeClock.ownedBy). */
    private void checkOwner() {
        Thread owner = scene.clock().owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(notOwner(owner));
        }
    }

    @AllowAllocation("diagnostics")
    private static String notOwner(Thread owner) {
        return "The animators change on " + owner.getName() + " only, not on " + Thread.currentThread().getName();
    }
}
