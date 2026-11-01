package com.truesim.scene.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Motions of the game's own: what they play at a phase is all the animator needs. */
class MotionTest {

    static final AnimationEvent STEP = AnimationEvent.of("step");

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Animators animators = new Animators(scene);

    /** Five clips, each a fifth: more than a contents' first room. In order, or reversed past half a phase. */
    record Fifths(Rig rig, Clip[] clips, boolean reorders) implements Motion {

        @Override
        public double duration(Animator animator) {
            return 1;
        }

        @Override
        public void contents(Animator animator, double phase, Contents out) {
            for (int i = 0; i < clips.length; i++) {
                int c = reorders && phase > 0.5 ? clips.length - 1 - i : i;
                out.add(clips[c], phase * clips[c].duration(), 0.2);
            }
        }
    }

    @Test
    void aGamesMotionPlaysWhatItSaysAtEachPhase() {
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip[] clips = new Clip[5];
        for (int i = 0; i < clips.length; i++) {
            clips[i] = r.clip(RigTest.at("c" + i, i, 1, "root")).event(0.25, STEP).event(0.6, STEP);
        }
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        Fifths fifths = new Fifths(rig, clips, false);
        assertThat(fifths.additive()).isFalse();
        assertThat(fifths.group()).isNull();
        long root = scene.create();
        long arm = scene.create(root);
        long[] nodes = {root, arm, scene.create(arm)};
        Animator a = animators.add(rig, nodes);
        List<Float> steps = new ArrayList<>();
        animators.listen(STEP, (node, event, weight) -> steps.add(weight));
        ClipState state = a.layer(base).play(fifths);
        animators.update(0.3);
        // Their mean: (0 + 1 + 2 + 3 + 4) / 5.
        assertThat(scene.local(root).translation().x()).isCloseTo(2, within(1e-6));
        assertThat(steps).as("each clip's step, by its weight").hasSize(5).containsOnly(0.2f);
        assertThat(state.duration()).isEqualTo(1);
        // Contents that change order pair nothing: no events that update.
        steps.clear();
        Fifths reordering = new Fifths(rig, clips, true);
        a.layer(base).play(reordering).time(0.45);
        animators.update(0); // the start's events, at 0.45: none
        steps.clear();
        animators.update(0.3); // from 0.45 to 0.75: past half a phase, reversed
        assertThat(steps).hasSize(1).as("only the middle clip keeps its place");
    }

    @Test
    void rootMotionPairsClipsByTheirPlace() {
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip[] clips = new Clip[5];
        for (int i = 0; i < clips.length; i++) {
            clips[i] = r.clip(SyncTest.walking(2 * i, false));
        }
        Rig.Layer base = r.layer();
        r.rootMotion(r.joint("root"), RootMotion.XZ);
        Rig rig = r.build();
        long root = scene.create();
        long arm = scene.create(root);
        Animator a = animators.add(rig, new long[] {root, arm, scene.create(arm)});
        a.layer(base).play(new Fifths(rig, clips, true)).time(0.45);
        animators.update(0);
        animators.update(0.3); // reversed past half a phase: the middle clip alone counts, a fifth of 4 * 0.3
        assertThat(a.rootDelta().dz()).isCloseTo(0.2 * 4 * 0.3, within(1e-6));
    }

    @Test
    void contentsAreReadByPlace() {
        Motion.Contents contents = new Motion.Contents();
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip one = r.clip(RigTest.at("one", 1, 1, "root"));
        contents.add(one, 0.5, 0.75);
        assertThat(contents.size()).isOne();
        assertThat(contents.clip(0)).isSameAs(one);
        assertThat(contents.time(0)).isEqualTo(0.5);
        assertThat(contents.weight(0)).isEqualTo(0.75);
        assertThatThrownBy(() -> contents.clip(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> contents.time(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> contents.weight(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatNullPointerException().isThrownBy(() -> contents.add(null, 0, 1));
        contents.clear();
        assertThat(contents.heaviest()).isEqualTo(-1);
    }
}
