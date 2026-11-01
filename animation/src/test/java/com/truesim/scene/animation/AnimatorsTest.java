package com.truesim.scene.animation;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.JobSystem;
import com.truesim.common.math.Quat;
import com.truesim.common.math.Transform;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.animation.Track.Interpolation;
import com.truesim.scene.animation.Track.Property;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnimatorsTest {

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final JobSystem jobs = new JobSystem(3);

    /** Hip at (0, 1, 0) scaled 2, a knee under it at (0, -0.5, 0), a prop not animated. */
    private static Skeleton leg() {
        double[] rest = {
            0, 1, 0, 0, 0, 0, 1, 2, 2, 2,
            0, -0.5, 0, 0, 0, 0, 1, 1, 1, 1,
            0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        return new Skeleton(new String[] {"hip", "knee", "prop"}, new int[] {-1, 0, 0}, rest);
    }

    private long[] spawn() {
        long hip = scene.create();
        return new long[] {hip, scene.create(hip), scene.create(hip)};
    }

    /** The knee bending 90 degrees about x over 1 s, the hip rising from 1 to 2, and two morph weights on the hip. */
    private static AnimationClip bend() {
        float h = (float) Math.sqrt(0.5);
        return new AnimationClip("bend", List.of(
            new Track("knee", 1, Property.ROTATION, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {0, 0, 0, 1, h, 0, 0, h}, 0),
            new Track("hip", 0, Property.TRANSLATION, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {0, 1, 0, 0, 2, 0}, 0),
            new Track("hip", 0, Property.WEIGHTS, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {0, 1, 1, 0}, 2),
            new Track("knee", 1, Property.SCALE, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {1, 1, 1, 3, 3, 3}, 0),
            new Track("tail", 7, Property.SCALE, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {1, 1, 1, 3, 3, 3}, 0)));
    }

    @AfterEach
    void close() {
        jobs.close();
    }

    @Test
    void posesTheNodesAClipMovesFromItsRestAndKeys() {
        Animators animators = new Animators(scene);
        long[] nodes = spawn();
        scene.setTranslation(nodes[2], 5, 5, 5); // the game's: no track moves it
        Animator animator = animators.add(leg(), nodes);
        List<double[]> morphs = new ArrayList<>();
        animators.morphSink((node, weights, offset, count) -> {
            assertThat(node).isEqualTo(nodes[0]);
            morphs.add(Arrays.copyOfRange(weights, offset, offset + count));
        });
        ClipState state = animator.play(bend());
        assertThat(animator.playing()).isSameAs(state);
        assertThat(animator.skeleton().size()).isEqualTo(3);
        assertThat(animator.node(1)).isEqualTo(nodes[1]);
        assertThat(animator.weightCount(0)).isEqualTo(2);
        assertThat(animator.weightCount(1)).isZero();
        animators.update(0.5);
        Transform knee = scene.local(nodes[1]);
        assertThat(knee.translation()).isEqualTo(Vec3.of(0, -0.5, 0)); // from rest: not animated
        assertThat(knee.scale()).isEqualTo(Vec3.of(2, 2, 2));
        Quat expected = Quat.fromAxisAngle(Vec3.of(1, 0, 0), Math.PI / 4);
        assertThat(knee.rotation().x()).isCloseTo(expected.x(), within(1e-6));
        assertThat(knee.rotation().w()).isCloseTo(expected.w(), within(1e-6));
        Transform hip = scene.local(nodes[0]);
        assertThat(hip.translation()).isEqualTo(Vec3.of(0, 1.5, 0));
        assertThat(hip.scale()).isEqualTo(Vec3.of(2, 2, 2));
        assertThat(scene.local(nodes[2]).translation()).isEqualTo(Vec3.of(5, 5, 5));
        assertThat(animator.weight(0, 0)).isEqualTo(0.5);
        assertThat(morphs).hasSize(1);
        assertThat(morphs.get(0)).containsExactly(0.5, 0.5);
        assertThatThrownBy(() -> animator.weight(0, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        // Without a sink, weights are only kept.
        animators.morphSink(null);
        animators.update(0.25);
        assertThat(morphs).hasSize(1);
        assertThat(animator.weight(0, 0)).isEqualTo(0.75);
        // Stopped, the pose stays.
        animator.stop();
        assertThat(animator.playing()).isNull();
        assertThat(animator.weightCount(0)).as("the rig's layout stays").isEqualTo(2);
        animators.update(0.25);
        assertThat(scene.local(nodes[0]).translation()).isEqualTo(Vec3.of(0, 1.75, 0));
    }

    @Test
    void clipsFindNodesByNameOnOtherRigs() {
        // A rig ordered differently, with a node the clip does not know.
        double[] rest = new double[30];
        for (int n = 0; n < 3; n++) {
            rest[10 * n + 6] = 1;
            rest[10 * n + 7] = 1;
            rest[10 * n + 8] = 1;
            rest[10 * n + 9] = 1;
        }
        Skeleton other = new Skeleton(new String[] {"root", "hip", "knee"}, new int[] {-1, 0, 1}, rest);
        long root = scene.create();
        long hip = scene.create(root);
        long knee = scene.create(hip);
        Animator animator = new Animators(scene).add(other, new long[] {root, hip, knee});
        animator.play(bend());
        assertThat(animator.weightCount(1)).isEqualTo(2); // found by name: the clip's node 0 is this rig's 1
        // A nameless track of a node index that does not match is skipped.
        Animators animators = new Animators(scene);
        Animator nameless = animators.add(other, new long[] {root, hip, knee});
        nameless.play(new AnimationClip("", List.of(ClipTest.moving("", 1, 1), ClipTest.moving("x", 9, 1))));
        animators.update(1);
        assertThat(scene.local(hip).translation()).isEqualTo(Vec3.of(0, 0, 0));
        // By index where the name matches.
        nameless.play(new AnimationClip("", List.of(ClipTest.moving("hip", 1, 1), ClipTest.moving("", -1, 1))));
        animators.update(0.5);
        assertThat(scene.local(hip).translation()).isEqualTo(Vec3.of(0.5, 0, 0));
    }

    @Test
    void manyAnimatorsSampleInParallelAndUpdateWithoutAllocating() {
        Animators animators = new Animators(scene, jobs);
        animators.update(0.1); // too few to share out
        AnimationClip clip = bend();
        List<long[]> rigs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long[] nodes = spawn();
            rigs.add(nodes);
            animators.add(leg(), nodes).play(clip).time(i * 0.05);
        }
        animators.update(0.25);
        for (int i = 0; i < 12; i++) {
            double expected = 1 + i * 0.05 + 0.25;
            assertThat(scene.local(rigs.get(i)[0]).translation().y()).isCloseTo(expected, within(1e-6));
        }
        assertAllocationFree(() -> animators.update(1 / 60.0));
        assertThat(animators.size()).isEqualTo(12);
    }

    @Test
    void scalesKeyedToNothingPoseAtAMillionth() {
        assertThat(Animator.visible(0)).isEqualTo(1e-6);
        assertThat(Animator.visible(-0.0)).isEqualTo(-1e-6);
        assertThat(Animator.visible(-2e-7)).isEqualTo(-1e-6);
        assertThat(Animator.visible(0.5)).isEqualTo(0.5);
        Animators animators = new Animators(scene);
        long[] nodes = spawn();
        animators.add(leg(), nodes).play(new AnimationClip("", List.of(new Track("hip", 0, Property.SCALE,
            Interpolation.STEP, new float[] {0}, new float[] {0, 1, -2e-7f}, 0))));
        animators.update(0);
        assertThat(scene.local(nodes[0]).scale()).isEqualTo(Vec3.of(1e-6, 1, -1e-6));
    }

    @Test
    void animatorsComeAndGo() {
        Animators animators = new Animators(scene);
        Animator first = animators.add(leg(), spawn());
        Animator second = animators.add(leg(), spawn());
        assertThat(animators.remove(first)).isTrue();
        assertThat(animators.remove(first)).isFalse();
        assertThat(animators.size()).isEqualTo(1);
        animators.update(0.1); // nothing plays: nothing written
        assertThat(animators.remove(second)).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> animators.update(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> animators.update(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> animators.update(Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> animators.add(leg(), new long[2]));
    }
}
