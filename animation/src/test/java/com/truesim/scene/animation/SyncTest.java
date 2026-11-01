package com.truesim.scene.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.animation.Track.Interpolation;
import com.truesim.scene.animation.Track.Property;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Sync groups and markers, and root motion. */
class SyncTest {

    static final SyncGroup LOCOMOTION = SyncGroup.of("locomotion");
    static final SyncMarker LEFT = SyncMarker.of("left");
    static final SyncMarker RIGHT = SyncMarker.of("right");
    static final SyncMarker MID = SyncMarker.of("mid");
    static final AnimationEvent STEP = AnimationEvent.of("step");

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Animators animators = new Animators(scene);

    private long[] spawn() {
        long root = scene.create();
        long arm = scene.create(root);
        return new long[] {root, arm, scene.create(arm)};
    }

    @Test
    void followersKeepTheLeadersPhaseMarkerByMarker() {
        Rig.Builder r = Rig.builder(RigTest.body());
        // A walk with its feet down at 0 and 0.5 of 1 s; a run of 0.6 s with them at 0.1 and 0.4.
        Clip walk = r.clip(RigTest.at("walk", 1, 1, "root")).marker(0.5, RIGHT).marker(0, LEFT).sync(LOCOMOTION)
            .event(0.6, STEP);
        Clip run = r.clip(RigTest.at("run", 3, 0.6f, "root")).marker(0.1, LEFT).marker(0.4, RIGHT).sync(LOCOMOTION)
            .event(0.3, STEP);
        Clip plain = r.clip(RigTest.at("plain", 5, 2, "root")).sync(LOCOMOTION);
        Clip odd = r.clip(RigTest.at("odd", 5, 2, "root")).marker(0.5, MID).marker(1, LEFT).marker(1.5, LEFT)
            .sync(LOCOMOTION);
        assertThat(walk.group()).isSameAs(LOCOMOTION);
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn());
        List<Float> steps = new ArrayList<>();
        animators.listen(STEP, (node, event, weight) -> steps.add(weight));
        ClipState walking = a.layer(base).play(walk);
        ClipState running = a.layer(base).play(run, 10); // light: it follows the walk
        assertThat(running.group()).isSameAs(LOCOMOTION);
        animators.update(0.25);
        // The walk halfway from left to right: the run as far between its own, at 0.1 + 0.5 * 0.3 = 0.25 s.
        assertThat(walking.time()).isCloseTo(0.25, within(1e-6));
        assertThat(running.time()).isCloseTo(0.25, within(1e-6));
        animators.update(0.5); // the walk past its right foot: from right to left round the loop's end
        assertThat(walking.time()).isCloseTo(0.75, within(1e-6));
        assertThat(running.time()).isCloseTo(0.55, within(1e-6));
        assertThat(steps).as("the walk's step at 0.6; the follower fires none").hasSize(1);
        // Made to lead, the light run leads: the walk follows it.
        running.lead(true);
        assertThat(running.leads()).isTrue();
        animators.update(0.06); // the run from 0.55 past its end to 0.01: before its first marker
        assertThat(running.time()).isCloseTo(0.01, within(1e-6));
        // Between the run's right (0.4) and left (0.1, a loop on): 0.21 of 0.3 → the walk from 0.5 by 0.7 of 0.5.
        assertThat(walking.time()).isCloseTo(0.85, within(1e-6));
        // A follower without markers takes the leader's phase as it is; so does one without the leader's pair.
        ClipState plainly = a.layer(base).play(plain, 10);
        animators.update(0.06);
        assertThat(plainly.phase()).isCloseTo(running.phase(), within(1e-6));
        ClipState oddly = a.layer(base).play(odd, 10);
        animators.update(0.06);
        assertThat(oddly.phase()).isCloseTo(running.phase(), within(1e-6));
        // Out of the group, it moves on its own.
        oddly.sync(null);
        double before = oddly.phase();
        animators.update(0.2);
        assertThat(oddly.phase()).isCloseTo(before + 0.1, within(1e-6));
        assertThat(LOCOMOTION).hasToString("SyncGroup[locomotion]");
        assertThat(LOCOMOTION.name()).isEqualTo("locomotion");
        assertThat(LEFT).hasToString("SyncMarker[left]");
        assertThat(LEFT.name()).isEqualTo("left");
        assertThatNullPointerException().isThrownBy(() -> SyncGroup.of(null));
        assertThatNullPointerException().isThrownBy(() -> SyncMarker.of(null));
        assertThatIllegalStateException().isThrownBy(() -> walk.marker(0, LEFT));
        assertThatIllegalStateException().isThrownBy(() -> walk.sync(null));
    }

    @Test
    void leadersAreTheNewestMadeToLeadOrTheHeaviest() {
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip one = r.clip(RigTest.at("one", 1, 1, "root")).sync(LOCOMOTION);
        Clip two = r.clip(RigTest.at("two", 2, 2, "root")).sync(LOCOMOTION);
        Clip three = r.clip(RigTest.at("three", 3, 4, "root")).sync(LOCOMOTION);
        Clip instant = r.clip(new AnimationClip("instant", List.of(new Track("root", 0, Property.TRANSLATION,
            Interpolation.STEP, new float[] {0}, new float[] {0, 0, 0}, 0)))).marker(0, LEFT).sync(LOCOMOTION);
        Clip marked = r.clip(RigTest.at("marked", 1, 1, "root")).marker(0, LEFT).sync(LOCOMOTION);
        Rig.Layer first = r.layer();
        Rig.Layer second = r.layer();
        Rig.Layer third = r.layer();
        Rig.Layer fourth = r.layer();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn());
        // Not leading, then the newest made to lead, an older one made to lead, and one not leading after them.
        ClipState plain = a.layer(first).play(three);
        ClipState older = a.layer(third).play(three).lead(true);
        ClipState newer = a.layer(second).play(two).lead(true);
        ClipState last = a.layer(fourth).play(one);
        a.layer(first).weight(0.5);
        a.layer(second).weight(0.75);
        a.layer(third).weight(0.25);
        animators.update(0.4);
        assertThat(newer.phase()).as("the newest made to lead moves on").isCloseTo(0.2, within(1e-6));
        assertThat(older.phase()).isCloseTo(0.2, within(1e-6));
        assertThat(plain.phase()).isCloseTo(0.2, within(1e-6));
        assertThat(last.phase()).isCloseTo(0.2, within(1e-6));
        last.lead(true); // newer still, after the others: it leads now
        animators.update(0.05);
        assertThat(last.phase()).isCloseTo(0.25, within(1e-6));
        assertThat(newer.phase()).isCloseTo(0.25, within(1e-6));
        newer.lead(false);
        older.lead(false);
        last.lead(false);
        last.time(0.2);
        animators.update(0.1); // the heaviest leads: the fourth layer's, 0.1 of 1 s
        assertThat(last.phase()).isCloseTo(0.3, within(1e-6));
        assertThat(plain.phase()).isCloseTo(0.3, within(1e-6));
        // A leader without markers, a follower with: as far through.
        ClipState marking = a.layer(second).play(marked);
        animators.update(0.1);
        assertThat(marking.phase()).isCloseTo(last.phase(), within(1e-6));
        // Instant clips with markers: at their start.
        a.layer(fourth).stop(0);
        a.layer(third).stop(0);
        a.layer(second).play(instant);
        a.layer(first).play(instant);
        animators.update(0.1);
        assertThat(a.layer(first).playing().phase()).isZero();
        ClipState after = a.layer(first).play(marked);
        animators.update(0.1);
        assertThat(after.phase()).isZero();
    }

    @Test
    void theHeaviestLeadsAndBlendSpacesGoByTheirHeavierClip() {
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip slow = r.clip(RigTest.at("slow", 0, 2, "root")).marker(0, LEFT).marker(1, RIGHT);
        Clip fast = r.clip(RigTest.at("fast", 2, 1, "root")).marker(0, LEFT).marker(0.25, RIGHT);
        BlendSpace1 move = r.blendSpace1().at(0, slow).at(1, fast).sync(LOCOMOTION);
        assertThat(move.group()).isSameAs(LOCOMOTION);
        Clip once = r.clip(RigTest.at("once", 1, 1, "root")).marker(0, LEFT).marker(0.5, RIGHT);
        Rig.Layer base = r.layer();
        Rig.Layer top = r.layer();
        Rig rig = r.build();
        assertThatIllegalArgumentException().isThrownBy(() -> Rig.builder(RigTest.body()).clip(
            RigTest.at("x", 0, 1, "root")).marker(Double.NaN, LEFT));
        assertThatIllegalArgumentException().isThrownBy(() -> Rig.builder(RigTest.body()).clip(
            RigTest.at("x", 0, 1, "root")).marker(-1, LEFT));
        assertThatIllegalArgumentException().isThrownBy(() -> Rig.builder(RigTest.body()).clip(
            RigTest.at("x", 0, 1, "root")).marker(Double.POSITIVE_INFINITY, LEFT));
        assertThatNullPointerException().isThrownBy(() -> Rig.builder(RigTest.body()).clip(
            RigTest.at("x", 0, 1, "root")).marker(0, null));
        assertThatIllegalStateException().isThrownBy(() -> move.sync(null));
        Animator a = animators.add(rig, spawn());
        ClipState moving = a.layer(base).play(move);
        a.input(move, 0.75); // the fast clip weighs more: its markers count
        ClipState following = a.layer(top).playOnce(once, 0, 0).sync(LOCOMOTION);
        a.layer(top).weight(0.5); // lighter than the base's blend space
        animators.update(0.1);
        // The blend space's phase moves by 0.1 s of 0.25 * 2 + 0.75 * 1 = 1.25 s; the fast clip at 0.08 s of 1, its
        // left to right 0 to 0.25: 0.32 of the way → the once-played clip at 0.32 of 0 to 0.5: 0.16 s.
        assertThat(moving.phase()).isCloseTo(0.08, within(1e-6));
        assertThat(following.time()).isCloseTo(0.16, within(1e-6));
        a.input(move, 0.25); // the slow one weighs more: 0.08 of 2 s = 0.16, left to right 0 to 1 → 0.08 s
        animators.update(0);
        assertThat(following.time()).isCloseTo(0.08, within(1e-6));
        // A follower that does not loop stays within its ends.
        moving.lead(true).time(1.2);
        following.phase = 1;
        animators.update(0);
        assertThat(following.phase()).isBetween(0.0, 1.0);
    }

    /** A clip moving the root 2 forward (+z) and a quarter turn about y over 1 s; or up 1 and back, with heights. */
    static AnimationClip walking(float forward, boolean turn) {
        float h = (float) Math.sqrt(0.5);
        List<Track> tracks = new ArrayList<>();
        tracks.add(new Track("root", 0, Property.TRANSLATION, Interpolation.LINEAR, new float[] {0, 0.5f, 1},
            new float[] {0, 0, 0, 0, 1, forward / 2, 0, 0, forward}, 0));
        if (turn) {
            tracks.add(new Track("root", 0, Property.ROTATION, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {0, 0, 0, 1, 0, h, 0, h}, 0));
        }
        return new AnimationClip("walk", tracks);
    }

    @Test
    void rootMotionIsTakenOutOfThePoseAndHandedOver() {
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip walk = r.clip(walking(2, false));
        Clip still = r.clip(RigTest.at("still", 0, 1, "arm"));
        Clip nod = r.additive(walking(2, false), Clip.Reference.FIRST_FRAME);
        Rig.Layer base = r.layer();
        Rig.Layer adds = r.layer().additive();
        r.rootMotion(r.joint("root"), RootMotion.XZ);
        assertThatIllegalArgumentException().isThrownBy(() -> r.rootMotion(new Joint(1, "root"), RootMotion.XZ));
        assertThatNullPointerException().isThrownBy(() -> r.rootMotion(r.joint("root"), null));
        Rig rig = r.build();
        assertThat(rig.rootMotion()).isEqualTo(RootMotion.XZ);
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        ClipState state = a.layer(base).play(walk);
        a.layer(adds).play(nod); // additive layers add no root motion
        animators.update(0.25);
        RootDelta delta = a.rootDelta();
        assertThat(delta.dz()).isCloseTo(0.5, within(1e-6));
        assertThat(delta.dx()).isZero();
        assertThat(delta.dy()).as("not taken").isZero();
        assertThat(delta.yaw()).isZero();
        assertThat(scene.local(nodes[0]).translation().z()).as("in place").isZero();
        assertThat(scene.local(nodes[0]).translation().y()).as("its height kept, the nod's added")
            .isCloseTo(1, within(1e-6));
        animators.update(0.5); // to 0.75
        assertThat(delta.dz()).isCloseTo(1, within(1e-6));
        animators.update(0.5); // past the end, to 0.25: 0.5 to the end and 0.5 after
        assertThat(delta.dz()).isCloseTo(1, within(1e-6));
        state.speed(-1);
        animators.update(0.5); // back past the start, to 0.75
        assertThat(delta.dz()).isCloseTo(-1, within(1e-6));
        state.pause();
        animators.update(0.5);
        assertThat(delta.dz()).as("paused: no motion").isZero();
        // Half the layer: half the motion; nothing playing there: none.
        state.resume().speed(1);
        a.layer(base).weight(0.5);
        animators.update(0.25);
        assertThat(delta.dz()).isCloseTo(0.25, within(1e-6));
        a.layer(base).weight(1).play(still);
        animators.update(0.25);
        assertThat(delta.dz()).as("a clip not moving the root").isZero();
        a.layer(base).stop(0);
        animators.update(0.25);
        assertThat(delta.dz()).isZero();
        assertThat(delta.toString()).contains("yaw");
    }

    @Test
    void turningRootMotionIsInTheFrameTheRootFacedAndBlends() {
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip turning = r.clip(walking(2, true));
        Clip straight = r.clip(walking(4, false));
        BlendSpace1 both = r.blendSpace1().at(0, turning).at(1, straight);
        Rig.Layer base = r.layer();
        r.rootMotion(r.joint("root"), RootMotion.XYZ_YAW);
        Rig rig = r.build();
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        a.layer(base).play(turning).time(0.5);
        animators.update(0.25);
        RootDelta delta = a.rootDelta();
        assertThat(delta.yaw()).isCloseTo(Math.PI / 8, within(1e-6));
        assertThat(delta.dy()).as("heights taken").isCloseTo(-0.5, within(1e-6));
        // Facing 45 degrees at the start, 0.5 forward in the parent is partly sideways in the root's frame.
        double c = Math.cos(Math.PI / 4);
        assertThat(delta.dz()).isCloseTo(0.5 * c, within(1e-6));
        assertThat(delta.dx()).isCloseTo(-0.5 * c, within(1e-6));
        assertThat(scene.local(nodes[0]).rotation().y()).as("the turn taken out").isCloseTo(0, within(1e-6));
        animators.update(0.5); // past the end: the second part turned by the first's turn
        assertThat(delta.yaw()).isCloseTo(Math.PI / 4, within(1e-6));
        // A blend space blends its clips' motions.
        a.layer(base).play(both);
        a.input(both, 1);
        animators.update(0.25);
        assertThat(delta.dz()).isCloseTo(1, within(1e-6));
        a.input(both, 0.5);
        animators.update(0.25);
        assertThat(delta.yaw()).isCloseTo(Math.PI / 16, within(1e-6));
    }

    @Test
    void theTurnTakenKeepsTheRestPosesAndNoRotationNeedsNone() {
        double[] rest = new double[30];
        float h = (float) Math.sqrt(0.5);
        for (int j = 0; j < 3; j++) {
            rest[10 * j + 6] = 1;
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        rest[4] = h; // the root at rest a quarter turn about y
        rest[6] = h;
        Skeleton turned = new Skeleton(new String[] {"root", "arm", "hand"}, new int[] {-1, 0, 1}, rest);
        Rig.Builder r = Rig.builder(turned);
        Clip tilt = r.clip(new AnimationClip("tilt", List.of(new Track("root", 0, Property.ROTATION,
            Interpolation.STEP, new float[] {0}, new float[] {h, 0, 0, h}, 0))));
        Clip flip = r.clip(new AnimationClip("flip", List.of(new Track("root", 0, Property.ROTATION,
            Interpolation.STEP, new float[] {0}, new float[] {1, 0, 0, 0}, 0))));
        Rig.Layer base = r.layer();
        r.rootMotion(r.joint("root"), RootMotion.XZ_YAW);
        Rig rig = r.build();
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        a.layer(base).play(tilt);
        animators.update(0.1);
        // A tilt about x has no turn: the rest pose's turn is put back after it.
        assertThat(scene.local(nodes[0]).rotation().y()).isCloseTo(0.5, within(1e-6));
        a.layer(base).play(flip); // a half turn about x: no turn about y to take out
        animators.update(0.1);
        assertThat(Math.abs(scene.local(nodes[0]).rotation().x())).isCloseTo(1, within(1e-6));
        assertThat(a.rootDelta().yaw()).isZero();
    }

    @Test
    void aRestPoseWithoutATurnLeavesNone() {
        double[] rest = new double[30];
        for (int j = 0; j < 3; j++) {
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        rest[3] = 1; // the root at rest a half turn about x: no turn about y
        Skeleton flipped = new Skeleton(new String[] {"root", "arm", "hand"}, new int[] {-1, 0, 1}, rest);
        Rig.Builder r = Rig.builder(flipped);
        Clip turn = r.clip(walking(0, true));
        Rig.Layer base = r.layer();
        r.rootMotion(r.joint("root"), RootMotion.XZ_YAW);
        Rig rig = r.build();
        long[] nodes = spawn();
        animators.add(rig, nodes).layer(base).play(turn).time(0.5);
        animators.update(0);
        assertThat(scene.local(nodes[0]).rotation().y()).isCloseTo(0, within(1e-6));
    }

    @Test
    void rootMotionOfAJointNoClipMovesIsCountedButNotWritten() {
        Rig.Builder r = Rig.builder(RigTest.body());
        Clip arm = r.clip(new AnimationClip("arm", List.of(RigTest.at("a", 1, 1, "arm").tracks().get(0),
            new Track("root", 0, Property.SCALE, Interpolation.STEP, new float[] {0}, new float[] {1, 1, 1}, 0))));
        Rig.Layer base = r.layer();
        r.rootMotion(r.joint("root"), RootMotion.XYZ);
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn());
        ClipState state = a.layer(base).play(arm);
        animators.update(0.5);
        assertThat(a.rootDelta().dz()).isZero();
        // A root joint no clip moves: counted, not written.
        Rig.Builder bare = Rig.builder(RigTest.body());
        Clip armOnly = bare.clip(RigTest.at("arm", 1, 1, "arm"));
        Rig.Layer bareBase = bare.layer();
        bare.rootMotion(bare.joint("root"), RootMotion.XZ);
        Animator unmoved = animators.add(bare.build(), spawn());
        unmoved.layer(bareBase).play(armOnly);
        animators.update(0.5);
        assertThat(unmoved.rootDelta().dz()).isZero();
        // A layer or a motion weighing nothing moves nothing.
        a.layer(base).weight(0);
        animators.update(0.5);
        a.layer(base).weight(1);
        state.weight(0);
        animators.update(0.5);
        assertThat(a.rootDelta().dz()).isZero();
        // Without root motion, none.
        Animator plain = animators.add(RigTest.body(), spawn());
        plain.play(walking(2, false));
        animators.update(0.5);
        assertThat(plain.rootDelta().dz()).isZero();
        assertThat(RootMotion.XZ.height()).isFalse();
        assertThat(RootMotion.XYZ_YAW.yaw()).isTrue();
    }
}
