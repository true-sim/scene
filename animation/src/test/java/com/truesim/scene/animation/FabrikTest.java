package com.truesim.scene.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.math.Quat;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** FABRIK chains with limits, and the pose modifiers see. */
class FabrikTest {

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Animators animators = new Animators(scene);
    private long[] nodes;

    /** A base with a tail of five joints, four bones of 0.5 straight back along +z, from (0, 1, 0). */
    static Skeleton tailed() {
        String[] names = {"base", "t0", "t1", "t2", "t3", "t4", "side"};
        int[] parents = {-1, 0, 1, 2, 3, 4, 0};
        double[] rest = new double[10 * names.length];
        for (int j = 0; j < names.length; j++) {
            rest[10 * j + 6] = 1;
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        rest[10 + 1] = 1;
        for (int j = 2; j <= 5; j++) {
            rest[10 * j + 2] = 0.5;
        }
        return new Skeleton(names, parents, rest);
    }

    private Animator spawn(Rig rig) {
        Skeleton s = rig.skeleton();
        nodes = new long[s.size()];
        for (int j = 0; j < s.size(); j++) {
            nodes[j] = s.parent(j) < 0 ? scene.create() : scene.create(nodes[s.parent(j)]);
            scene.setTranslation(nodes[j], s.rest(j, 0), s.rest(j, 1), s.rest(j, 2));
        }
        return animators.add(rig, nodes);
    }

    private Vec3 at(int joint) {
        return scene.world(nodes[joint]).translation();
    }

    private void step() {
        animators.update(0);
        scene.update();
    }

    private void bonesKeepTheirLengths() {
        for (int j = 2; j <= 5; j++) {
            assertThat(at(j).sub(at(j - 1)).length()).isCloseTo(0.5, within(1e-9));
        }
    }

    @Test
    void chainsReachTheirTargetsKeepingTheirBones() {
        Rig.Builder r = Rig.builder(tailed());
        FabrikChain tail = r.fabrik(r.joint("t0"), r.joint("t4"));
        assertThat(tail.size()).isEqualTo(5);
        assertThat(tail.iterations()).isEqualTo(10);
        assertThat(tail.tolerance()).isEqualTo(1e-4);
        Rig rig = r.build();
        Animator a = spawn(rig);
        Animator.FabrikTarget target = a.fabrik(tail);
        assertThat(target.weight()).isZero();
        // Curling round to a point beside and below.
        target.target(1, 0.5, 1).weight(1);
        step();
        assertThat(at(5).sub(Vec3.of(1, 0.5, 1)).length()).isLessThan(1e-4);
        bonesKeepTheirLengths();
        assertThat(at(1)).as("the root stays").isEqualTo(Vec3.of(0, 1, 0));
        // Out of reach: straight toward it.
        target.target(0, 1, 10);
        step();
        assertThat(at(5).z()).isCloseTo(2, within(1e-6));
        // Half the weight: between.
        target.target(2, 1, 0).weight(0.5);
        step();
        assertThat(at(5).x()).isBetween(0.1, 1.9);
        // Right on the joint before the tip, as the chain lies straight: still solved, every bone its length.
        target.weight(1).target(0, 1, 10);
        step();
        target.target(0, 1, 1.5);
        step();
        bonesKeepTheirLengths();
        assertThatIllegalArgumentException().isThrownBy(() -> target.weight(2));
        assertThatIllegalArgumentException().isThrownBy(() -> target.target(Double.NaN, 0, 0));
    }

    @Test
    void limitsKeepEachBoneNearTheOneBefore() {
        Rig.Builder r = Rig.builder(tailed());
        FabrikChain tail = r.fabrik(r.joint("t0"), r.joint("t4")).iterations(20).tolerance(0);
        for (String joint : new String[] {"t0", "t1", "t2", "t3"}) {
            tail.limit(r.joint(joint), 0.3);
        }
        assertThat(tail.limit(r.joint("t2"))).isEqualTo(0.3);
        assertThat(tail.limit(r.joint("t4"))).as("the tip has no bone: no limit").isEqualTo(Math.PI);
        Rig rig = r.build();
        Animator a = spawn(rig);
        // Far round to the side: no bone turns more than its limit.
        a.fabrik(tail).target(2, 1, -1).weight(1);
        step();
        Vec3 before = Vec3.of(0, 0, 1); // the root's bone as the animation points it
        for (int j = 1; j <= 4; j++) {
            Vec3 bone = at(j + 1).sub(at(j)).normalize();
            assertThat(Math.acos(Math.min(1, bone.dot(before)))).isLessThanOrEqualTo(0.3 + 1e-6);
            before = bone;
        }
        bonesKeepTheirLengths();
        // Right behind the root: turned as far as the limits let it, any way round.
        a.fabrik(tail).target(0, 1, -3);
        step();
        bonesKeepTheirLengths();
    }

    @Test
    void chainsAreSetUpOnce() {
        Rig.Builder r = Rig.builder(tailed());
        Joint t0 = r.joint("t0");
        Joint t4 = r.joint("t4");
        Joint side = r.joint("side");
        assertThatIllegalArgumentException().isThrownBy(() -> r.fabrik(t4, t0));
        assertThatIllegalArgumentException().isThrownBy(() -> r.fabrik(side, t4));
        FabrikChain tail = r.fabrik(t0, t4);
        assertThatIllegalArgumentException().isThrownBy(() -> tail.limit(t4, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> tail.limit(side, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> tail.limit(t0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> tail.limit(t0, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> tail.limit(side));
        assertThatIllegalArgumentException().isThrownBy(() -> tail.iterations(0));
        assertThatIllegalArgumentException().isThrownBy(() -> tail.tolerance(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> tail.tolerance(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> tail.tolerance(Double.POSITIVE_INFINITY));
        Rig rig = r.build();
        assertThatIllegalStateException().isThrownBy(() -> tail.iterations(2));
        assertThatIllegalStateException().isThrownBy(() -> tail.tolerance(1));
        assertThatIllegalStateException().isThrownBy(() -> tail.limit(t0, 1));
        assertThatIllegalStateException().isThrownBy(() -> r.fabrik(t0, t4));
        Rig.Builder other = Rig.builder(tailed());
        FabrikChain foreign = other.fabrik(other.joint("t0"), other.joint("t4"));
        Animator a = spawn(rig);
        assertThatIllegalArgumentException().isThrownBy(() -> a.fabrik(foreign));
    }

    @Test
    void modifiersReadAndSetRotationsAndLocalTransforms() {
        Rig.Builder r = Rig.builder(tailed());
        FabrikChain tail = r.fabrik(r.joint("t0"), r.joint("t4"));
        Joint side = r.joint("side");
        Joint t1 = r.joint("t1");
        Quat quarter = Quat.fromAxisAngle(Vec3.of(0, 1, 0), Math.PI / 2);
        double[] rotation = new double[4];
        double[] local = new double[10];
        r.modifier(pose -> {
            pose.setWorldRotation(side, quarter.x(), quarter.y(), quarter.z(), quarter.w());
            pose.worldRotation(side, rotation);
            pose.local(t1, local);
            local[2] = 0.75; // a longer bone
            pose.setLocal(t1, local);
            pose.reach(tail, 0, 1, 2.25, 1);
        });
        Rig rig = r.build();
        long holder = scene.create();
        scene.setRotation(holder, quarter.x(), quarter.y(), quarter.z(), quarter.w());
        Animator a = animators.add(rig, place(rig, holder));
        step();
        // Turned with the holder, the side joint's world rotation is the quarter turn asked for.
        assertThat(Math.abs(Quat.of(rotation[0], rotation[1], rotation[2], rotation[3]).dot(quarter)))
            .isCloseTo(1, within(1e-9));
        assertThat(Math.abs(scene.world(nodes[6]).rotation().dot(quarter))).isCloseTo(1, within(1e-9));
        assertThat(scene.local(nodes[2]).translation().z()).isCloseTo(0.75, within(1e-9));
        assertThat(a.rig()).isSameAs(rig);
        Rig.Builder other = Rig.builder(tailed());
        FabrikChain foreign = other.fabrik(other.joint("t0"), other.joint("t4"));
        assertThat(foreign.rig()).isNotSameAs(rig);
    }

    @Test
    void aModifierReachingWithAnotherRigsChainIsRefused() {
        Rig.Builder other = Rig.builder(tailed());
        FabrikChain foreign = other.fabrik(other.joint("t0"), other.joint("t4"));
        Rig.Builder r = Rig.builder(tailed());
        r.modifier(pose -> pose.reach(foreign, 0, 0, 0, 1));
        Rig rig = r.build();
        spawn(rig);
        assertThatIllegalArgumentException().isThrownBy(this::step);
    }

    private long[] place(Rig rig, long holder) {
        Skeleton s = rig.skeleton();
        nodes = new long[s.size()];
        for (int j = 0; j < s.size(); j++) {
            nodes[j] = scene.create(s.parent(j) < 0 ? holder : nodes[s.parent(j)]);
            scene.setTranslation(nodes[j], s.rest(j, 0), s.rest(j, 1), s.rest(j, 2));
        }
        return nodes;
    }
}
