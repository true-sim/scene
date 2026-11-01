package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.jobs.JobSystem;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.Objects;

/**
 * Every animator of a scene, updated together once a frame before the scene's own {@link SceneGraph#update()}: clips
 * move on, animators sample in parallel when given jobs, then poses are written to the scene one after another, on its
 * thread. Morph weights go to a {@link MorphSink} (the renderer's).
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

    /** Animators per job: sampling one is a few microseconds. */
    private static final int GRAIN = 4;

    private final SceneGraph scene;
    private final JobSystem jobs;
    private Animator[] animators = new Animator[8];
    private int count;
    private MorphSink morphs;
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

    /**
     * An animator posing {@code nodes}, the scene nodes of {@code skeleton}'s nodes in its order (as a model's
     * instance made them).
     */
    @AllowAllocation("animators are made when models spawn")
    public Animator add(Skeleton skeleton, long[] nodes) {
        Animator animator = new Animator(skeleton, nodes);
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

    /** Moves every clip on by {@code seconds} and poses the scene; on the scene's thread. Allocates nothing. */
    public void update(double seconds) {
        if (!(seconds >= 0) || seconds == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("Not a time step: " + seconds);
        }
        for (int i = 0; i < count; i++) {
            animators[i].advance(seconds);
        }
        if (jobs != null && count > GRAIN) {
            jobs.parallelFor(count, GRAIN, sample);
        } else {
            sample.run(0, count, 0);
        }
        for (int i = 0; i < count; i++) {
            animators[i].write(scene, morphs);
        }
    }
}
