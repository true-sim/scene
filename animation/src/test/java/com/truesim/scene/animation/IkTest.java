package com.truesim.scene.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.Quat;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Two-bone inverse kinematics, look-ats, overrides, released joints and pose modifiers. */
class IkTest {

    private static final String[] NAMES = {"root", "pelvis", "thigh_l", "knee_l", "foot_l", "thigh_r", "knee_r",
        "foot_r", "spine", "neck", "head"};
    private static final int[] PARENTS = {-1, 0, 1, 2, 3, 1, 5, 6, 1, 8, 9};
    private static final double[][] AT = {{0, 0, 0}, {0, 1, 0}, {-0.2, 0, 0}, {0, -0.5, 0}, {0, -0.5, 0}, {0.2, 0, 0},
        {0, -0.5, 0}, {0, -0.5, 0}, {0, 0.3, 0}, {0, 0.2, 0}, {0, 0.1, 0}};

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Animators animators = new Animators(scene);
    private long[] nodes;

    /** A figure standing at rest: a pelvis 1 up, straight legs of two halves, a spine, neck and head above. */
    static Skeleton figure() {
        double[] rest = new double[10 * NAMES.length];
        for (int j = 0; j < NAMES.length; j++) {
            System.arraycopy(AT[j], 0, rest, 10 * j, 3);
            rest[10 * j + 6] = 1;
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        return new Skeleton(NAMES, PARENTS, rest);
    }

    /** The figure's nodes, under {@code parent} (NONE: a root), at rest. */
    private long[] spawn(long parent) {
        long[] placed = new long[NAMES.length];
        for (int j = 0; j < NAMES.length; j++) {
            long up = PARENTS[j] < 0 ? parent : placed[PARENTS[j]];
            placed[j] = up == com.truesim.scene.core.NodeHandle.NONE ? scene.create() : scene.create(up);
            scene.setTranslation(placed[j], AT[j][0], AT[j][1], AT[j][2]);
        }
        nodes = placed;
        return placed;
    }

    private Vec3 at(int joint) {
        return scene.world(nodes[joint]).translation();
    }

    private void step() {
        animators.update(0);
        scene.update();
    }

    private static void close(Vec3 actual, double x, double y, double z, double tolerance) {
        assertThat(actual.x()).isCloseTo(x, within(tolerance));
        assertThat(actual.y()).isCloseTo(y, within(tolerance));
        assertThat(actual.z()).isCloseTo(z, within(tolerance));
    }

    @Test
    void twoBoneChainsReachTheirTargetsBendingTowardThePole() {
        Rig.Builder r = Rig.builder(figure());
        TwoBoneIk left = r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"), r.joint("foot_l"));
        assertThat(left.end()).isEqualTo(new Joint(4, "foot_l"));
        assertThat(left.stretch()).isEqualTo(1);
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn(com.truesim.scene.core.NodeHandle.NONE));
        Animator.IkTarget leg = a.ik(left);
        assertThat(leg.weight()).as("off until given").isZero();
        step();
        close(at(4), -0.2, 0, 0, 1e-9);
        // A straight leg reaching up and forward, the knee bending toward a pole in front.
        leg.target(-0.2, 0.3, 0.2).pole(-0.2, 0.5, 2).weight(1);
        step();
        close(at(4), -0.2, 0.3, 0.2, 1e-6);
        assertThat(at(3).z()).as("the knee forward, toward the pole").isGreaterThan(0.2);
        assertThat(at(3).x()).isCloseTo(-0.2, within(1e-6));
        // The pole behind: the knee bends back.
        leg.pole(-0.2, 0.5, -2);
        step();
        close(at(4), -0.2, 0.3, 0.2, 1e-6);
        assertThat(at(3).z()).isLessThan(0);
        // Bending as the leg already bends, without a pole.
        leg.noPole().target(-0.2, 0.2, 0.1);
        step();
        close(at(4), -0.2, 0.2, 0.1, 1e-6);
        // Out of reach: as far as the leg goes, toward it.
        leg.target(-0.2, 1, 5);
        step();
        double reach = at(4).sub(at(2)).length();
        assertThat(reach).isCloseTo(1, within(1e-5));
        assertThat(at(4).z()).isGreaterThan(0.9);
        // Half the weight: halfway in rotation, the foot between.
        leg.target(-0.2, 0.5, 0.5).weight(0.5);
        step();
        assertThat(at(4).z()).isBetween(0.05, 0.5);
        // The end's rotation, given.
        Quat turned = Quat.fromAxisAngle(Vec3.of(0, 1, 0), 0.7);
        leg.weight(1).rotation(turned.x(), turned.y(), turned.z(), turned.w());
        step();
        Quat foot = scene.world(nodes[4]).rotation();
        assertThat(Math.abs(foot.dot(turned))).isCloseTo(1, within(1e-6));
        leg.noRotation().weight(0);
        assertThatIllegalArgumentException().isThrownBy(() -> leg.weight(1.5));
        assertThatIllegalArgumentException().isThrownBy(() -> leg.target(Double.NaN, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> leg.pole(0, Double.POSITIVE_INFINITY, 0));
    }

    @Test
    void chainsStretchToReachAndWorkInTheInstancesFrame() {
        Rig.Builder r = Rig.builder(figure());
        TwoBoneIk left = r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"), r.joint("foot_l")).stretch(1.5);
        assertThatIllegalArgumentException().isThrownBy(() -> left.stretch(0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> left.stretch(Double.POSITIVE_INFINITY));
        Rig rig = r.build();
        assertThatIllegalStateException().isThrownBy(() -> left.stretch(2));
        // The figure under a moved, turned and scaled node of the game's.
        long holder = scene.create();
        scene.setTranslation(holder, 10, 2, -3);
        scene.setRotation(holder, 0, Math.sin(0.4), 0, Math.cos(0.4));
        scene.setScale(holder, 2, 2, 2);
        Animator a = animators.add(rig, spawn(holder));
        step();
        Vec3 hip = at(2);
        Vec3 target = hip.add(Vec3.of(0, -2.4, 0)); // 1.2 legs away at scale 2: stretched to 1.2
        a.ik(left).target(target.x(), target.y(), target.z()).weight(1);
        step();
        close(at(4), target.x(), target.y(), target.z(), 1e-5);
        // Further than the stretch: as far as it goes.
        Vec3 far = hip.add(Vec3.of(0, -6, 0));
        a.ik(left).target(far.x(), far.y(), far.z());
        step();
        assertThat(at(4).sub(at(2)).length()).isCloseTo(3, within(1e-4));
    }

    @Test
    void lookAtsTurnTheirChainEachItsShareWithinTheLimit() {
        Rig.Builder r = Rig.builder(figure());
        Joint head = r.joint("head");
        LookAt look = r.lookAt(head, 0, 0, 1).chain(r.joint("neck"), 0.5).chain(r.joint("spine"), 0.25);
        LookAt limited = r.lookAt(head, 0, 0, 2).limit(Math.PI / 4);
        assertThat(look.limit()).isEqualTo(Math.PI);
        assertThat(limited.limit()).isEqualTo(Math.PI / 4);
        assertThatIllegalArgumentException().isThrownBy(() -> look.chain(r.joint("knee_l"), 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> look.chain(r.joint("pelvis"), 2));
        assertThatIllegalArgumentException().isThrownBy(() -> limited.limit(4));
        assertThatIllegalArgumentException().isThrownBy(() -> limited.limit(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> r.lookAt(head, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> r.lookAt(head, Double.NaN, 0, 1));
        Rig rig = r.build();
        assertThatIllegalStateException().isThrownBy(() -> look.chain(r.joint("pelvis"), 0.5));
        assertThatIllegalStateException().isThrownBy(() -> limited.limit(1));
        Animator a = animators.add(rig, spawn(com.truesim.scene.core.NodeHandle.NONE));
        step();
        Vec3 eyes = at(10);
        // To the left (+x), level with the head: the head ends facing it; the spine and neck turned some of the way.
        a.lookAt(look).target(eyes.x() + 5, eyes.y(), eyes.z()).weight(1);
        step();
        Vec3 facing = scene.world(nodes[10]).rotation().rotate(Vec3.of(0, 0, 1));
        Vec3 wanted = Vec3.of(eyes.x() + 5, eyes.y(), eyes.z()).sub(at(10)).normalize();
        assertThat(facing.dot(wanted)).isCloseTo(1, within(1e-6));
        assertThat(scene.local(nodes[8]).rotation().w()).as("the spine took a share").isLessThan(0.9999);
        assertThat(scene.local(nodes[9]).rotation().w()).as("the neck took a share").isLessThan(0.9999);
        // Behind, past the limit: no more than 45 degrees.
        a.lookAt(look).weight(0);
        a.lookAt(limited).target(eyes.x(), eyes.y(), eyes.z() - 5).weight(1);
        step();
        Vec3 limitedFacing = scene.world(nodes[10]).rotation().rotate(Vec3.of(0, 0, 1));
        assertThat(limitedFacing.z()).isCloseTo(Math.cos(Math.PI / 4), within(1e-6));
        // Straight ahead already: no turn.
        a.lookAt(limited).target(eyes.x(), eyes.y(), eyes.z() + 5);
        step();
        assertThat(scene.world(nodes[10]).rotation().rotate(Vec3.of(0, 0, 1)).z()).isCloseTo(1, within(1e-6));
        assertThatIllegalArgumentException().isThrownBy(() -> a.lookAt(look).target(0, Double.NaN, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> a.lookAt(look).weight(-1));
        assertThat(a.lookAt(look).weight()).isZero();
    }

    @Test
    void overridesSetJointsAndReleasedJointsAreTheGames() {
        Rig.Builder r = Rig.builder(figure());
        Joint neck = r.joint("neck");
        Joint head = r.joint("head");
        Clip nod = r.clip(new AnimationClip("nod", List.of(new Track("head", 10, Track.Property.TRANSLATION,
            Track.Interpolation.STEP, new float[] {0}, new float[] {0, 0.2f, 0}, 0))));
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn(com.truesim.scene.core.NodeHandle.NONE));
        Quat quarter = Quat.fromAxisAngle(Vec3.of(0, 1, 0), Math.PI / 2);
        Animator.JointOverride local = a.override(neck, Space.LOCAL).rotation(quarter.x(), quarter.y(), quarter.z(),
            quarter.w()).weight(1);
        assertThat(local.space()).isEqualTo(Space.LOCAL);
        assertThat(local.weight()).isOne();
        step();
        assertThat(Math.abs(scene.local(nodes[9]).rotation().dot(quarter))).isCloseTo(1, within(1e-9));
        // In the model frame, under the neck's quarter turn: the head's own rotation undoes it.
        a.override(head, Space.MODEL).rotation(0, 0, 0, 1).weight(1);
        step();
        assertThat(scene.world(nodes[10]).rotation().w()).isCloseTo(1, within(1e-9));
        assertThat(Math.abs(scene.local(nodes[10]).rotation().dot(quarter.conjugate()))).isCloseTo(1, within(1e-9));
        // Released, a joint is the game's: neither posed by clips nor by overrides.
        a.layer(base).play(nod);
        a.release(head);
        assertThat(a.released(head)).isTrue();
        scene.setTranslation(nodes[10], 7, 7, 7);
        step();
        assertThat(scene.local(nodes[10]).translation()).isEqualTo(Vec3.of(7, 7, 7));
        a.hold(head);
        assertThat(a.released(head)).isFalse();
        step();
        assertThat(scene.local(nodes[10]).translation().y()).isCloseTo(0.2, within(1e-6));
        assertThatIllegalArgumentException().isThrownBy(() -> local.weight(2));
        assertThatNullPointerException().isThrownBy(() -> a.override(neck, null));
        assertThatIllegalArgumentException().isThrownBy(() -> a.override(new Joint(3, "neck"), Space.LOCAL));
    }

    @Test
    void footPlacementPutsFeetOnTheGroundLoweringThePelvis() {
        Rig.Builder r = Rig.builder(figure());
        TwoBoneIk left = r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"), r.joint("foot_l"));
        TwoBoneIk right = r.twoBoneIk(r.joint("thigh_r"), r.joint("knee_r"), r.joint("foot_r"));
        Joint pelvis = r.joint("pelvis");
        // Lower ground on the left, higher on the right.
        FootPlacement feet = new FootPlacement(left, right, pelvis, (x, z) -> x < 0 ? -0.2 : 0.1);
        r.modifier(feet);
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn(com.truesim.scene.core.NodeHandle.NONE));
        step();
        assertThat(at(4).y()).as("a leg just straight: a hair short").isCloseTo(-0.2, within(1e-5));
        assertThat(at(7).y()).isCloseTo(0.1, within(1e-6));
        assertThat(at(1).y()).as("the pelvis down to the lower foot's ground").isCloseTo(0.8, within(1e-6));
        // Half the weight: halfway.
        a.weight(feet, 0.5);
        step();
        assertThat(at(1).y()).isCloseTo(0.9, within(1e-6));
        a.weight(feet, 0);
        step();
        assertThat(at(1).y()).as("off").isCloseTo(1, within(1e-6));
        assertThatIllegalArgumentException().isThrownBy(() -> a.weight(feet, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> a.weight(pose -> { }, 1));
        assertThatNullPointerException().isThrownBy(() -> new FootPlacement(null, right, pelvis,
            (x, z) -> 0));
        assertThatNullPointerException().isThrownBy(() -> new FootPlacement(left, null, pelvis,
            (x, z) -> 0));
        assertThatNullPointerException().isThrownBy(() -> new FootPlacement(left, right, null, (x, z) -> 0));
        assertThatNullPointerException().isThrownBy(() -> new FootPlacement(left, right, pelvis, null));
    }

    @Test
    void feetWithoutGroundAndHigherGroundLeaveThePelvis() {
        Rig.Builder r = Rig.builder(figure());
        TwoBoneIk left = r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"), r.joint("foot_l"));
        TwoBoneIk right = r.twoBoneIk(r.joint("thigh_r"), r.joint("knee_r"), r.joint("foot_r"));
        r.modifier(new FootPlacement(left, right, r.joint("pelvis"), (x, z) -> x < 0 ? Double.NaN : 0.1));
        Rig rig = r.build();
        animators.add(rig, spawn(com.truesim.scene.core.NodeHandle.NONE));
        step();
        assertThat(at(1).y()).isCloseTo(1, within(1e-9));
        assertThat(at(4).y()).as("no ground: as it was").isCloseTo(0, within(1e-9));
        assertThat(at(7).y()).isCloseTo(0.1, within(1e-6));
        // And the other way round.
        Rig.Builder o = Rig.builder(figure());
        TwoBoneIk l2 = o.twoBoneIk(o.joint("thigh_l"), o.joint("knee_l"), o.joint("foot_l"));
        TwoBoneIk r2 = o.twoBoneIk(o.joint("thigh_r"), o.joint("knee_r"), o.joint("foot_r"));
        o.modifier(new FootPlacement(l2, r2, o.joint("pelvis"), (x, z) -> x < 0 ? -0.1 : Double.NaN));
        animators.add(o.build(), spawn(com.truesim.scene.core.NodeHandle.NONE));
        step();
        assertThat(at(1).y()).isCloseTo(0.9, within(1e-6));
    }

    @Test
    void edgesOfTheSolvers() {
        Rig.Builder r = Rig.builder(figure());
        Joint root = r.joint("root");
        Joint thigh = r.joint("thigh_l");
        TwoBoneIk left = r.twoBoneIk(thigh, r.joint("knee_l"), r.joint("foot_l")).stretch(1.5);
        TwoBoneIk right = r.twoBoneIk(r.joint("thigh_r"), r.joint("knee_r"), r.joint("foot_r"));
        Joint head = r.joint("head");
        LookAt look = r.lookAt(head, 0, 0, 1).chain(r.joint("neck"), 0).chain(r.joint("spine"), 0.5).limit(1);
        assertThatIllegalArgumentException().isThrownBy(() -> look.chain(r.joint("pelvis"), -1));
        assertThatIllegalArgumentException().isThrownBy(() -> look.chain(r.joint("pelvis"), Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> r.lookAt(head, Double.POSITIVE_INFINITY, 0, 1));
        float bend = (float) Math.sin(0.2);
        Clip kick = r.clip(new AnimationClip("kick", List.of(new Track("knee_r", 6, Track.Property.ROTATION,
            Track.Interpolation.STEP, new float[] {0}, new float[] {bend, 0, 0, (float) Math.cos(0.2)}, 0))));
        PoseModifier lift = pose -> pose.moveWorld(root, 0, 0, 0);
        r.modifier(lift);
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn(com.truesim.scene.core.NodeHandle.NONE));
        a.weight(lift, 0);
        // An override of no rotation is none; one the other way round blends the short way; twice is the same one.
        Animator.JointOverride turn = a.override(root, Space.MODEL).rotation(0, 0, 0, 0).weight(1);
        assertThat(a.override(root, Space.MODEL)).isSameAs(turn);
        step();
        assertThat(scene.local(nodes[0]).rotation().w()).isCloseTo(1, within(1e-9));
        turn.rotation(0, 0, 0, -1).weight(0.5);
        step();
        assertThat(Math.abs(scene.local(nodes[0]).rotation().w())).isCloseTo(1, within(1e-9));
        // Kept but off, an override alone changes nothing.
        turn.weight(0);
        step();
        // Of a released joint: not applied.
        turn.weight(1).rotation(0, 1, 0, 0);
        a.release(root);
        step();
        assertThat(scene.local(nodes[0]).rotation().w()).isCloseTo(1, within(1e-9));
        a.hold(root);
        turn.weight(0);
        // A straight leg reaching straight down, short of its length: it bends toward the pole's side, either side.
        a.ik(left).target(-0.2, 0.4, 0).pole(-5, 0.5, 0).weight(1);
        step();
        assertThat(at(3).x()).isLessThan(-0.2);
        a.ik(left).pole(5, 0.5, 0);
        step();
        assertThat(at(3).x()).isGreaterThan(-0.2);
        // A pole on the line to the target: no plane to turn into.
        a.ik(left).pole(-0.2, 0.7, 0);
        step();
        close(at(4), -0.2, 0.4, 0, 1e-6);
        // Within reach, a stretching chain does not stretch.
        a.ik(left).noPole().target(-0.2, 0.2, 0.3);
        step();
        assertThat(at(3).sub(at(2)).length()).isCloseTo(0.5, within(1e-9));
        // A chain whose joints a clip moves, and one with a released joint, solve too; the released one stays.
        a.layer(base).play(kick);
        a.ik(right).target(0.4, 0.3, 0.2).weight(1);
        step();
        close(at(7), 0.4, 0.3, 0.2, 1e-6);
        a.release(thigh);
        step();
        a.hold(thigh);
        // The look's chain: a share of 0 turns nothing; the limit to one side.
        Vec3 eyes = at(10);
        a.lookAt(look).target(eyes.x() + 5, eyes.y(), eyes.z()).weight(1);
        step();
        assertThat(scene.local(nodes[9]).rotation().w()).as("the neck's share is 0").isCloseTo(1, within(1e-9));
        assertThat(scene.world(nodes[10]).rotation().rotate(Vec3.of(0, 0, 1)).z()).isCloseTo(Math.cos(1),
            within(1e-6));
        // Modifiers off while solvers run: skipped; moving the root, which has no parent.
        a.weight(lift, 1);
        step();
    }

    @Test
    void solvingAllocatesNothing() {
        Rig.Builder r = Rig.builder(figure());
        TwoBoneIk left = r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"), r.joint("foot_l"));
        TwoBoneIk right = r.twoBoneIk(r.joint("thigh_r"), r.joint("knee_r"), r.joint("foot_r"));
        LookAt look = r.lookAt(r.joint("head"), 0, 0, 1).chain(r.joint("neck"), 0.5);
        r.modifier(new FootPlacement(left, right, r.joint("pelvis"), (x, z) -> 0.05 * x));
        Joint spine = r.joint("spine");
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn(com.truesim.scene.core.NodeHandle.NONE));
        a.ik(left).target(-0.2, 0.3, 0.2).pole(-0.2, 0.5, 2).rotation(0, 0, 0, 1).weight(1);
        a.lookAt(look).target(3, 1.5, 2).weight(1);
        a.override(spine, Space.MODEL).rotation(0, 0.1, 0, 1).weight(0.5);
        step();
        com.truesim.testing.AllocationAssertions.assertAllocationFree(() -> {
            a.ik(left).target(-0.2, 0.25, 0.2);
            animators.update(1 / 60.0);
        });
    }

    @Test
    void directionsAcrossAnyVector() {
        double[] out = new double[3];
        Kinematics.across(new double[] {1, 0, 0}, out);
        assertThat(Math.abs(out[0])).isLessThan(1e-12);
        assertThat(Kinematics.dot(out, out)).isCloseTo(1, within(1e-12));
        Kinematics.across(new double[] {0, 1, 0}, out);
        assertThat(Math.abs(out[1])).isLessThan(1e-12);
        double[] q = {0, 0, 0, 0};
        Kinematics.normalizeQuaternion(q, 0);
        assertThat(q).containsExactly(0, 0, 0, 1);
    }

    @Test
    void modifiersSeeAPoseInWorldCoordinates() {
        Rig.Builder r = Rig.builder(figure());
        TwoBoneIk left = r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"), r.joint("foot_l"));
        Joint head = r.joint("head");
        double[] seen = new double[4];
        r.modifier(pose -> {
            double[] p = new double[3];
            pose.worldPosition(head, p);
            seen[0] = p[1];
            seen[1] = pose.groundHeight();
            seen[2] = pose.weight();
            assertThat(pose.animator()).isNotNull();
            pose.moveWorld(head, 0, 1, 0);
            pose.reach(left, -0.2, 3.5, 0.3, 1);
        });
        assertThatIllegalArgumentException().isThrownBy(() -> r.twoBoneIk(r.joint("knee_l"), r.joint("thigh_l"),
            r.joint("foot_l")));
        assertThatIllegalArgumentException().isThrownBy(() -> r.twoBoneIk(r.joint("thigh_l"), r.joint("foot_l"),
            r.joint("knee_l")));
        assertThatNullPointerException().isThrownBy(() -> r.modifier(null));
        Rig rig = r.build();
        assertThatIllegalStateException().isThrownBy(() -> r.twoBoneIk(r.joint("thigh_l"), r.joint("knee_l"),
            r.joint("foot_l")));
        long holder = scene.create();
        scene.setTranslation(holder, 0, 3, 0);
        animators.add(rig, spawn(holder));
        step();
        assertThat(seen[0]).isCloseTo(4.6, within(1e-9));
        assertThat(seen[1]).isEqualTo(3);
        assertThat(seen[2]).isEqualTo(1);
        assertThat(at(10).y()).isCloseTo(5.6, within(1e-9));
        close(at(4), -0.2, 3.5, 0.3, 1e-6);
        // Of another rig, refused.
        Rig.Builder other = Rig.builder(figure());
        TwoBoneIk foreign = other.twoBoneIk(other.joint("thigh_l"), other.joint("knee_l"), other.joint("foot_l"));
        LookAt foreignLook = other.lookAt(other.joint("head"), 0, 0, 1);
        Animator a = animators.add(rig, spawn(com.truesim.scene.core.NodeHandle.NONE));
        assertThatIllegalArgumentException().isThrownBy(() -> a.ik(foreign));
        assertThatIllegalArgumentException().isThrownBy(() -> a.lookAt(foreignLook));
    }
}
