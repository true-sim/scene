package com.truesim.scene.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Animators updating every few updates: in turns, keeping time. */
class LevelOfDetailTest {

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Animators animators = new Animators(scene);

    @Test
    void animatorsUpdatingEveryFewTakeTurnsAndKeepTime() {
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip walk = r.clip(SyncTest.walking(2, false)).event(0.25, RigTest.STEP);
        Rig.Layer base = r.layer();
        r.rootMotion(r.joint("root"), RootMotion.XZ);
        Rig rig = r.build();
        List<Animator> crowd = new ArrayList<>();
        List<ClipState> states = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long root = scene.create();
            long arm = scene.create(root);
            Animator a = animators.add(rig, new long[] {root, arm, scene.create(arm)}).updateEvery(3);
            assertThat(a.updateEvery()).isEqualTo(3);
            states.add(a.layer(base).play(walk));
            crowd.add(a);
        }
        List<Float> steps = new ArrayList<>();
        animators.listen(RigTest.STEP, (node, event, weight) -> steps.add(weight));
        // One of the three each update, in turns.
        int[] updates = new int[3];
        for (int tick = 0; tick < 3; tick++) {
            animators.update(0.1);
            int updating = 0;
            for (int i = 0; i < 3; i++) {
                if (crowd.get(i).updated()) {
                    updating++;
                    updates[i]++;
                }
            }
            assertThat(updating).as("update " + tick).isOne();
        }
        assertThat(updates).containsExactly(1, 1, 1);
        // Each keeps time: on its turn, all the time since its last added up. Turns 0, 1 and 2 last updated at the
        // 4th, 6th and 5th of these six updates.
        animators.update(0.1);
        animators.update(0.1);
        animators.update(0.1);
        assertThat(states.get(0).time()).isCloseTo(0.4, within(1e-9));
        assertThat(states.get(1).time()).isCloseTo(0.6, within(1e-9));
        assertThat(states.get(2).time()).isCloseTo(0.5, within(1e-9));
        assertThat(steps).as("each one's step at 0.25, once").hasSize(3);
        // Root motion on its updates, for the time since its last; none in between.
        Animator first = crowd.get(0);
        animators.update(0.1);
        double moved = first.updated() ? first.rootDelta().dz() : -1;
        animators.update(0.1);
        assertThat(first.updated() ? 1 : first.rootDelta().dz()).isZero();
        assertThat(moved).isCloseTo(0.6, within(1e-6)); // 0.3 s at 2 per second
        // Back to every update.
        first.updateEvery(1);
        animators.update(0.1);
        assertThat(first.updated()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> first.updateEvery(0));
    }
}
