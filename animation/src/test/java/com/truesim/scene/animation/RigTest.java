package com.truesim.scene.animation;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.animation.Track.Interpolation;
import com.truesim.scene.animation.Track.Property;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rigs: layers, masks, cross-fades, additive clips, blend spaces, events and curves. */
class RigTest {

    static final AnimationEvent STEP = AnimationEvent.of("step");
    static final AnimationEvent HIT = AnimationEvent.of("hit");
    static final AnimationCurve BLINK = AnimationCurve.of("blink");

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Animators animators = new Animators(scene);

    /** A root with an arm under it and a hand under that, all at rest at the origin. */
    static Skeleton body() {
        double[] rest = new double[30];
        for (int j = 0; j < 3; j++) {
            rest[10 * j + 6] = 1;
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        return new Skeleton(new String[] {"root", "arm", "hand"}, new int[] {-1, 0, 1}, rest);
    }

    /** {@code joints} held at x = {@code x} for {@code duration} seconds. */
    static AnimationClip at(String name, double x, float duration, String... joints) {
        List<Track> tracks = new ArrayList<>();
        for (String joint : joints) {
            tracks.add(new Track(joint, List.of("root", "arm", "hand").indexOf(joint), Property.TRANSLATION,
                Interpolation.LINEAR, new float[] {0, duration}, new float[] {(float) x, 0, 0, (float) x, 0, 0}, 0));
        }
        return new AnimationClip(name, tracks);
    }

    private long[] spawn() {
        long root = scene.create();
        long arm = scene.create(root);
        return new long[] {root, arm, scene.create(arm)};
    }

    private double x(long node) {
        return scene.local(node).translation().x();
    }

    @Test
    void crossFadesBlendWhatPlayedIntoWhatPlaysNext() {
        Rig.Builder r = Rig.builder(body());
        Clip one = r.clip(at("one", 1, 1, "root", "arm"));
        Clip three = r.clip(at("three", 3, 1, "root", "arm"));
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        assertThat(a.rig()).isSameAs(rig);
        Animator.Playback playback = a.layer(base);
        assertThat(playback.layer()).isSameAs(base);
        ClipState first = playback.play(one);
        animators.update(0.1);
        assertThat(x(nodes[0])).isCloseTo(1, within(1e-9));
        ClipState next = playback.play(three, 1);
        assertThat(next.fade()).isZero();
        assertThat(first.fadingOut()).isTrue();
        assertThat(playback.size()).isEqualTo(2);
        assertThat(playback.state(0)).isSameAs(first);
        assertThat(playback.playing()).isSameAs(next);
        animators.update(0.5);
        assertThat(x(nodes[0])).as("halfway").isCloseTo(2, within(1e-9));
        animators.update(0.5);
        assertThat(x(nodes[0])).isCloseTo(3, within(1e-9));
        assertThat(playback.size()).as("the first faded out, gone").isOne();
        assertThatThrownBy(() -> playback.state(1)).isInstanceOf(IndexOutOfBoundsException.class);
        // A weight under 1 shows what is below: the rest pose, at 0.
        next.weight(0.25);
        animators.update(0.1);
        assertThat(x(nodes[0])).isCloseTo(0.75, within(1e-9));
        // Stopped over a fade, it fades out and leaves.
        playback.stop(0.5);
        animators.update(0.25);
        assertThat(playback.size()).isOne();
        animators.update(0.5);
        assertThat(playback.size()).isZero();
        assertThat(playback.playing()).isNull();
        assertThat(a.playing()).isNull();
    }

    @Test
    void layersBlendOverTheOnesBelowByWeightAndMask() {
        Rig.Builder r = Rig.builder(body());
        Joint arm = r.joint("arm");
        Joint hand = r.joint("hand");
        Clip one = r.clip(at("one", 1, 1, "root", "arm", "hand"));
        Clip three = r.clip(at("three", 3, 1, "root", "arm", "hand"));
        Mask upper = r.mask().include(arm).weight(hand, 0.5).build();
        assertThat(upper.weight(arm)).isEqualTo(1);
        assertThat(upper.weight(hand)).isEqualTo(0.5f);
        assertThat(upper.weight(r.joint("root"))).isZero();
        Mask armOnly = r.mask().include(arm).exclude(hand).build();
        assertThat(armOnly.weight(hand)).isZero();
        assertThat(armOnly.rig()).isNotNull();
        Rig.Layer base = r.layer();
        Rig.Layer top = r.layer().mask(upper);
        assertThat(top.mask()).isSameAs(upper);
        assertThat(top.index()).isOne();
        assertThat(top.isAdditive()).isFalse();
        assertThat(top).hasToString("Layer[1, masked]");
        Rig rig = r.build();
        assertThat(rig.layers()).containsExactly(base, top);
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        a.layer(base).play(one);
        a.layer(top).play(three);
        Animator.Playback overlay = a.layer(top).weight(0.5);
        assertThat(overlay.weight()).isEqualTo(0.5);
        animators.update(0.1);
        assertThat(x(nodes[0])).as("root: the base only").isCloseTo(1, within(1e-9));
        assertThat(x(nodes[1])).as("arm: halfway").isCloseTo(2, within(1e-9));
        assertThat(x(nodes[2])).as("hand: a quarter of the way").isCloseTo(1.5, within(1e-9));
        overlay.weight(0);
        animators.update(0.1);
        assertThat(x(nodes[1])).as("a layer of weight 0 is skipped").isCloseTo(1, within(1e-9));
        assertThatIllegalArgumentException().isThrownBy(() -> overlay.weight(2));
        assertThatIllegalArgumentException().isThrownBy(() -> overlay.weight(Double.NaN));
    }

    @Test
    void additiveLayersAddTheirClipsMovesFromAReference() {
        Skeleton body = body();
        Rig.Builder r = Rig.builder(body);
        Clip one = r.clip(at("one", 1, 1, "root"));
        float h = (float) Math.sqrt(0.5);
        AnimationClip nod = new AnimationClip("nod", List.of(
            new Track("root", 0, Property.ROTATION, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {0, 0, 0, 1, 0, h, 0, h}, 0),
            new Track("root", 0, Property.TRANSLATION, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {5, 0, 0, 6, 0, 0}, 0),
            new Track("root", 0, Property.SCALE, Interpolation.LINEAR, new float[] {0, 1},
                new float[] {1, 1, 1, 2, 2, 2}, 0)));
        Clip fromFirst = r.additive(nod, Clip.Reference.FIRST_FRAME);
        Clip fromRest = r.additive(nod, Clip.Reference.REST_POSE);
        assertThat(fromFirst.additive()).isTrue();
        assertThat(fromFirst.reference()).isEqualTo(Clip.Reference.FIRST_FRAME);
        assertThat(one.reference()).isNull();
        assertThat(fromRest).hasToString("Clip[nod, additive from REST_POSE]");
        assertThat(one).hasToString("Clip[one]");
        Rig.Layer base = r.layer();
        Rig.Layer adds = r.layer().additive();
        assertThat(adds).hasToString("Layer[1, additive]");
        Rig rig = r.build();
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        a.layer(base).play(one);
        a.layer(adds).play(fromFirst).loop(false).time(1);
        animators.update(0);
        // From the first frame: +1 along x, a quarter turn about y, twice as big, over the base's x = 1.
        assertThat(x(nodes[0])).isCloseTo(2, within(1e-9));
        assertThat(scene.local(nodes[0]).rotation().y()).isCloseTo(h, within(1e-6));
        assertThat(scene.local(nodes[0]).scale().x()).isCloseTo(2, within(1e-9));
        // Half the layer: half the move.
        a.layer(adds).weight(0.5);
        animators.update(0);
        assertThat(x(nodes[0])).isCloseTo(1.5, within(1e-9));
        assertThat(scene.local(nodes[0]).scale().x()).isCloseTo(1.5, within(1e-9));
        // From the rest pose: the whole translation, 6.
        a.layer(adds).weight(1).play(fromRest).loop(false).time(1);
        animators.update(0);
        assertThat(x(nodes[0])).isCloseTo(7, within(1e-9));
        // Additive clips go on additive layers, and only they.
        assertThatIllegalArgumentException().isThrownBy(() -> a.layer(base).play(fromFirst));
        assertThatIllegalArgumentException().isThrownBy(() -> a.layer(adds).play(one));
    }

    @Test
    void additiveRotationsTakeTheShortWay() {
        Rig.Builder r = Rig.builder(body());
        // A clip whose reference is a half turn the other way round: its move is still the short one.
        AnimationClip turn = new AnimationClip("turn", List.of(new Track("arm", 1, Property.ROTATION,
            Interpolation.STEP, new float[] {0}, new float[] {0, 0, 0, -1}, 0)));
        Clip add = r.additive(turn, Clip.Reference.REST_POSE);
        Rig.Layer adds = r.layer().additive();
        Rig rig = r.build();
        long[] nodes = spawn();
        animators.add(rig, nodes).layer(adds).play(add);
        animators.update(0);
        assertThat(Math.abs(scene.local(nodes[1]).rotation().w())).as("no turn").isCloseTo(1, within(1e-9));
    }

    @Test
    void aRotationKeyedToNothingPosesUnturned() {
        Rig.Builder r = Rig.builder(body());
        Clip none = r.clip(new AnimationClip("none", List.of(new Track("root", 0, Property.ROTATION,
            Interpolation.STEP, new float[] {0}, new float[] {0, 0, 0, 0}, 0))));
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        long[] nodes = spawn();
        animators.add(rig, nodes).layer(base).play(none);
        animators.update(0);
        assertThat(scene.local(nodes[0]).rotation().w()).isEqualTo(1);
    }

    @Test
    void playedOnceAMotionFadesOutAtItsEndAndLeaves() {
        Rig.Builder r = Rig.builder(body());
        Clip one = r.clip(at("one", 1, 1, "root"));
        Clip slash = r.clip(at("slash", 3, 1, "root"));
        Rig.Layer base = r.layer();
        Rig.Layer top = r.layer();
        Rig rig = r.build();
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        a.layer(base).play(one);
        ClipState once = a.layer(top).playOnce(slash, 0, 0.5);
        assertThat(once.looping()).isFalse();
        animators.update(0.4);
        assertThat(x(nodes[0])).isCloseTo(3, within(1e-9));
        animators.update(0.1); // half a second left: fading out from now
        assertThat(once.fadingOut()).isTrue();
        animators.update(0.25);
        assertThat(x(nodes[0])).as("half faded: halfway back to the base").isCloseTo(2, within(1e-6));
        animators.update(0.25);
        assertThat(a.layer(top).size()).isZero();
        animators.update(0.1);
        assertThat(x(nodes[0])).isCloseTo(1, within(1e-9));
        // Played once with no fade out, it leaves at its end; backwards, at its start.
        ClipState quick = a.layer(top).playOnce(slash, 0, 0);
        animators.update(2);
        assertThat(a.layer(top).size()).isZero();
        a.layer(top).playOnce(slash, 0, 0.25).time(1).speed(-1);
        animators.update(0.75); // a quarter of a second from its start: fading out
        assertThat(a.layer(top).size()).isOne();
        animators.update(0.25);
        assertThat(a.layer(top).size()).isZero();
        assertThat(quick.looping()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> a.layer(top).playOnce(slash, 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> a.layer(top).playOnce(slash, 0,
            Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> a.layer(top).play(slash, Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> a.layer(top).stop(-1));
    }

    @Test
    void aLayerBlendsAtMostAFewMotionsTheLightestMakingRoom() {
        Rig.Builder r = Rig.builder(body());
        Clip[] clips = new Clip[Animator.STATES + 1];
        for (int i = 0; i < clips.length; i++) {
            clips[i] = r.clip(at("c" + i, i, 1, "root"));
        }
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        long[] nodes = spawn();
        Animator.Playback playback = animators.add(rig, nodes).layer(base);
        for (int i = 0; i < Animator.STATES; i++) {
            playback.play(clips[i], 10);
            animators.update(0.1);
        }
        assertThat(playback.size()).isEqualTo(Animator.STATES);
        ClipState newest = playback.play(clips[Animator.STATES], 10);
        assertThat(playback.size()).isEqualTo(Animator.STATES);
        assertThat(playback.playing()).isSameAs(newest);
        // Several weighing more than 1 in all are normalized.
        playback.play(clips[1]);
        ClipState two = playback.play(clips[2], 10);
        two.fade = 1;
        playback.state(0).fade = 1;
        playback.state(0).fadeTarget = 1;
        animators.update(0);
        assertThat(x(nodes[0])).isCloseTo(1.5, within(1e-9));
    }

    @Test
    void blendSpacesBlendTheirClipsAtOnePhase() {
        Rig.Builder r = Rig.builder(body());
        Clip idle = r.clip(at("idle", 0, 1, "root"));
        Clip run = r.clip(at("run", 2, 2, "root")).event(1, STEP);
        Clip sprint = r.clip(at("sprint", 4, 1, "root"));
        idle.event(0.5, STEP);
        BlendSpace1 move = r.blendSpace1().at(2, run).at(0, idle).at(4, sprint);
        assertThat(move.size()).isEqualTo(3);
        assertThat(move.clip(0)).isSameAs(idle);
        assertThat(move.parameter(2)).isEqualTo(4);
        assertThat(move).hasToString("BlendSpace1[0.0, 2.0, 4.0]");
        assertThatIllegalArgumentException().isThrownBy(() -> move.at(2, idle));
        assertThatIllegalArgumentException().isThrownBy(() -> move.at(Double.NaN, idle));
        assertThatIllegalArgumentException().isThrownBy(() -> move.at(1, r.additive(at("a", 0, 1, "root"),
            Clip.Reference.FIRST_FRAME)));
        assertThatNullPointerException().isThrownBy(() -> move.at(1, null));
        assertThatIllegalArgumentException().isThrownBy(() -> move.at(5, Rig.builder(body()).clip(at("x", 0, 1))));
        BlendSpace1 lone = r.blendSpace1().at(0, idle);
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        List<Float> steps = new ArrayList<>();
        animators.listen(STEP, (node, event, weight) -> steps.add(weight));
        ClipState state = a.layer(base).play(move);
        a.input(move, 1); // halfway from idle to run
        assertThat(a.parameter(move)).isEqualTo(1);
        assertThat(state.duration()).as("between 1 s and 2 s").isCloseTo(1.5, within(1e-9));
        animators.update(0.75); // half the phase: idle's event at 0.5, run's at 1
        assertThat(x(nodes[0])).isCloseTo(1, within(1e-9));
        assertThat(steps).containsExactly(0.5f, 0.5f);
        a.input(move, -3); // clamped: idle alone
        animators.update(0);
        assertThat(x(nodes[0])).isCloseTo(0, within(1e-9));
        assertThat(state.duration()).isEqualTo(1);
        a.input(move, 9);
        animators.update(0);
        assertThat(x(nodes[0])).isCloseTo(4, within(1e-9));
        a.input(move, 3); // between run and sprint
        animators.update(0);
        assertThat(x(nodes[0])).isCloseTo(3, within(1e-9));
        a.layer(base).play(lone);
        a.input(lone, 5);
        animators.update(0.1);
        assertThat(x(nodes[0])).isCloseTo(0, within(1e-9));
        assertThatIllegalArgumentException().isThrownBy(() -> a.input(move, Double.POSITIVE_INFINITY));
        Rig other = Rig.builder(body()).build();
        Rig.Builder elsewhere = Rig.builder(body());
        BlendSpace1 foreign = elsewhere.blendSpace1();
        assertThatIllegalArgumentException().isThrownBy(() -> a.input(foreign, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> a.parameter(foreign));
        assertThatIllegalArgumentException().isThrownBy(() -> a.layer(other.layers().get(0)));
        assertThatIllegalArgumentException().isThrownBy(() -> a.layer(base).play(foreign));
    }

    @Test
    void eventsFireAsPlaybackPassesThemEvenAcrossTheLoopsEnd() {
        Rig.Builder r = Rig.builder(body());
        Clip walk = r.clip(at("walk", 1, 1, "root")).event(0.5, STEP).event(0, STEP).event(0.75, HIT);
        assertThat(walk.duration()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() -> walk.event(Double.NaN, STEP));
        assertThatIllegalArgumentException().isThrownBy(() -> walk.event(-1, STEP));
        assertThatIllegalArgumentException().isThrownBy(() -> walk.event(Double.POSITIVE_INFINITY, STEP));
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        long[] nodes = spawn();
        Animator a = animators.add(rig, nodes);
        List<String> heard = new ArrayList<>();
        List<Long> where = new ArrayList<>();
        animators.listen(STEP, (node, event, weight) -> {
            where.add(node);
            heard.add(event.name() + "@" + weight);
        });
        animators.listen(STEP, (node, event, weight) -> heard.add("again"));
        ClipState state = a.layer(base).play(walk);
        animators.update(0.25);
        assertThat(heard).as("the one at its start too").containsExactly("step@1.0", "again");
        assertThat(where).as("on the animator's first node").containsExactly(nodes[0]);
        heard.clear();
        animators.update(0.5); // to 0.75: the step at 0.5 (the hit has no listener)
        assertThat(heard).containsExactly("step@1.0", "again");
        heard.clear();
        animators.update(0.5); // past the end, to 0.25: the one at 0
        assertThat(heard).containsExactly("step@1.0", "again");
        heard.clear();
        state.speed(-1);
        animators.update(0.5); // back past the start, to 0.75: 0, then nothing until 0.75's HIT
        assertThat(heard).containsExactly("step@1.0", "again");
        heard.clear();
        animators.update(0.5); // back to 0.25: the step at 0.5
        assertThat(heard).containsExactly("step@1.0", "again");
        heard.clear();
        state.weight(0); // weighing nothing: silent
        animators.update(0.5);
        assertThat(heard).isEmpty();
        // Many in one update: the queue grows.
        state.weight(1).speed(1);
        for (int i = 0; i < 6; i++) {
            animators.add(rig, spawn()).layer(base).play(walk);
        }
        animators.add(rig, spawn()).layer(base).play(walk);
        animators.update(0.9);
        assertThat(heard).hasSizeGreaterThan(8);
        assertThatNullPointerException().isThrownBy(() -> animators.listen(null, (node, event, weight) -> { }));
        assertThatNullPointerException().isThrownBy(() -> animators.listen(STEP, null));
        assertThat(STEP).hasToString("AnimationEvent[step]");
        assertThatNullPointerException().isThrownBy(() -> AnimationEvent.of(null));
    }

    @Test
    void manyEventsInOneAnimatorsUpdateAreAllHeard() {
        Rig.Builder r = Rig.builder(body());
        Clip busy = r.clip(at("busy", 1, 1, "root"));
        for (int i = 0; i < 12; i++) {
            busy.event(i / 20.0, STEP);
        }
        Rig.Layer base = r.layer();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn());
        int[] heard = new int[1];
        animators.listen(STEP, (node, event, weight) -> heard[0]++);
        a.layer(base).play(busy);
        animators.update(0.9);
        assertThat(heard[0]).isEqualTo(12);
    }

    @Test
    void curvesBlendAsTheirClipsDo() {
        Rig.Builder r = Rig.builder(body());
        Clip open = r.clip(at("open", 0, 1, "root")).curve(BLINK, new float[] {0, 1}, new float[] {0, 1});
        Clip shut = r.clip(at("shut", 0, 1, "root")).curve(BLINK, new float[] {0, 0.5f, 2}, new float[] {1, 1, 3});
        assertThat(shut.duration()).as("to its last curve key").isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> open.curve(BLINK, new float[] {1, 0}, new float[2]));
        Clip wink = r.additive(at("wink", 0, 1, "root"), Clip.Reference.FIRST_FRAME)
            .curve(BLINK, new float[] {0, 1}, new float[] {0.5f, 1});
        Clip still = r.additive(at("still", 0, 1, "root"), Clip.Reference.REST_POSE)
            .curve(BLINK, new float[] {0}, new float[] {0.25f});
        Rig.Layer base = r.layer();
        Rig.Layer adds = r.layer().additive();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn());
        assertThat(a.curve(BLINK)).isZero();
        a.layer(base).play(open).time(0.5);
        animators.update(0);
        assertThat(a.curve(BLINK)).isCloseTo(0.5, within(1e-9));
        a.layer(base).play(shut).time(1.25);
        animators.update(0);
        assertThat(a.curve(BLINK)).as("between keys after the first segment").isCloseTo(2, within(1e-9));
        a.layer(base).play(shut).time(0);
        a.layer(adds).play(wink).loop(false).time(1);
        animators.update(0);
        assertThat(a.curve(BLINK)).as("1, plus the wink's 0.5 from its first frame").isCloseTo(1.5, within(1e-9));
        a.layer(adds).play(still);
        animators.update(0);
        assertThat(a.curve(BLINK)).as("from the rest pose's 0").isCloseTo(1.25, within(1e-9));
        a.layer(base).play(shut).loop(false).time(2);
        animators.update(0);
        assertThat(a.curve(BLINK)).as("held past the last key").isCloseTo(3.25, within(1e-9));
        assertThat(a.curve(AnimationCurve.of("other"))).as("keyed by none").isZero();
        assertThat(BLINK).hasToString("AnimationCurve[blink]");
        assertThatNullPointerException().isThrownBy(() -> AnimationCurve.of(null));
    }

    @Test
    void morphWeightsBlendAndAreAddedOnAdditiveLayers() {
        Rig.Builder r = Rig.builder(body());
        Clip smile = r.clip(new AnimationClip("smile", List.of(new Track("hand", 2, Property.WEIGHTS,
            Interpolation.STEP, new float[] {0}, new float[] {1, 0.5f}, 2))));
        Clip more = r.additive(new AnimationClip("more", List.of(new Track("hand", 2, Property.WEIGHTS,
            Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 0, 0.5f, 0}, 2))), Clip.Reference.REST_POSE);
        Rig.Layer base = r.layer();
        Rig.Layer adds = r.layer().additive();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn());
        assertThat(a.weightCount(2)).isEqualTo(2);
        a.layer(base).play(smile);
        a.layer(adds).play(more).loop(false).time(1);
        animators.update(0);
        assertThat(a.weight(2, 0)).isCloseTo(1.5, within(1e-6));
        assertThat(a.weight(2, 1)).isCloseTo(0.5, within(1e-6));
    }

    @Test
    void aRigsSetupIsFinalOnceBuilt() {
        Rig.Builder r = Rig.builder(body());
        Joint arm = r.joint("arm");
        assertThat(arm).isEqualTo(new Joint(1, "arm"));
        assertThatIllegalArgumentException().isThrownBy(() -> r.joint("tail"));
        Clip one = r.clip(at("one", 1, 1, "root"));
        BlendSpace1 space = r.blendSpace1();
        Mask.Builder masking = r.mask();
        assertThatIllegalArgumentException().isThrownBy(() -> masking.include(new Joint(7, "tail")));
        assertThatIllegalArgumentException().isThrownBy(() -> masking.include(new Joint(-1, "root")));
        assertThatIllegalArgumentException().isThrownBy(() -> masking.include(new Joint(1, "hand")));
        assertThatNullPointerException().isThrownBy(() -> masking.include(null));
        assertThatIllegalArgumentException().isThrownBy(() -> masking.weight(arm, -0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> masking.weight(arm, 1.5));
        Mask mask = masking.build();
        Rig.Builder other = Rig.builder(body());
        Mask foreign = other.mask().build();
        Rig rig = r.build(); // no layer made: one of its own
        assertThat(rig.layers()).hasSize(1);
        assertThat(rig.skeleton().size()).isEqualTo(3);
        assertThatIllegalStateException().isThrownBy(() -> r.joint("arm"));
        assertThatIllegalStateException().isThrownBy(() -> r.clip(at("two", 2, 1, "root")));
        assertThatIllegalStateException().isThrownBy(() -> r.additive(at("two", 2, 1, "root"),
            Clip.Reference.FIRST_FRAME));
        assertThatIllegalStateException().isThrownBy(r::blendSpace1);
        assertThatIllegalStateException().isThrownBy(r::mask);
        assertThatIllegalStateException().isThrownBy(r::layer);
        assertThatIllegalStateException().isThrownBy(r::build);
        assertThatIllegalStateException().isThrownBy(() -> one.event(0, STEP));
        assertThatIllegalStateException().isThrownBy(() -> one.curve(BLINK, new float[] {0}, new float[] {0}));
        assertThatIllegalStateException().isThrownBy(() -> space.at(0, one));
        assertThatIllegalStateException().isThrownBy(() -> rig.layers().get(0).mask(mask));
        assertThatIllegalStateException().isThrownBy(() -> rig.layers().get(0).additive());
        assertThatIllegalArgumentException().isThrownBy(() -> other.layer().mask(mask));
        assertThat(foreign.rig()).isNotSameAs(mask.rig());
        assertThatNullPointerException().isThrownBy(() -> other.additive(at("x", 0, 1), null));
        Clip loose = other.clip(at("y", 0, 1));
        assertThatNullPointerException().isThrownBy(() -> loose.event(0, null));
        // A built rig plays its own clips only.
        Animator a = animators.add(rig, spawn());
        assertThatIllegalStateException().isThrownBy(() -> a.play(at("loose", 0, 1, "root")));
        assertThatNullPointerException().isThrownBy(() -> a.layer(rig.layers().get(0)).play(null));
        assertThat(one.clip().name()).isEqualTo("one");
        assertThat(one.rig()).isSameAs(rig);
        assertThat(space.rig()).isSameAs(rig);
    }

    @Test
    void anOpenRigBindsEachClipOnceAsItFirstPlays() {
        AnimationClip one = at("one", 1, 1, "root");
        Animator a = animators.add(body(), spawn());
        ClipState first = a.play(one);
        Clip bound = (Clip) first.motion();
        assertThat(((Clip) a.play(one).motion())).as("kept").isSameAs(bound);
        a.play(at("two", 2, 1, "arm"));
        animators.update(0);
        assertThat(a.rig().layers()).hasSize(1);
    }

    @Test
    void edgesOfPlaybackAndSetup() {
        // A fourth joint beside the arm: a mask of the arm leaves it out.
        double[] rest = new double[40];
        for (int j = 0; j < 4; j++) {
            rest[10 * j + 6] = 1;
            rest[10 * j + 7] = 1;
            rest[10 * j + 8] = 1;
            rest[10 * j + 9] = 1;
        }
        Skeleton four = new Skeleton(new String[] {"root", "arm", "hand", "leg"}, new int[] {-1, 0, 1, 0}, rest);
        Rig.Builder r = Rig.builder(four);
        Mask arm = r.mask().include(r.joint("arm")).build();
        assertThat(arm.weight(r.joint("leg"))).isZero();
        assertThat(arm.weight(r.joint("hand"))).isEqualTo(1);
        // An event just before the loop's end, passed as it wraps.
        Clip walk = r.clip(at("walk", 1, 1, "root")).event(0.9, STEP);
        Clip instant = r.clip(new AnimationClip("instant", List.of(new Track("root", 0, Property.SCALE,
            Interpolation.STEP, new float[] {0}, new float[] {2, 2, 2}, 0))));
        // Additive from the rest pose with a track of no joint; from a first frame of no scale.
        Clip loose = r.additive(new AnimationClip("loose", List.of(new Track("tail", 9, Property.TRANSLATION,
            Interpolation.STEP, new float[] {0}, new float[] {1, 0, 0}, 0))), Clip.Reference.REST_POSE);
        Clip grow = r.additive(new AnimationClip("grow", List.of(new Track("leg", 3, Property.SCALE,
            Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 0, 0, 3, 3, 3}, 0))),
            Clip.Reference.FIRST_FRAME);
        Rig.Layer base = r.layer();
        Rig.Layer adds = r.layer().additive();
        Rig rig = r.build();
        long root = scene.create();
        long armNode = scene.create(root);
        long[] nodes = {root, armNode, scene.create(armNode), scene.create(root)};
        Animator a = animators.add(rig, nodes);
        List<Float> heard = new ArrayList<>();
        animators.listen(STEP, (node, event, weight) -> heard.add(weight));
        a.layer(base).play(walk).time(0.8);
        animators.update(0.3);
        assertThat(heard).hasSize(1);
        // Played once, an instant clip fades out at once.
        a.layer(base).playOnce(instant, 0, 0.5);
        animators.update(0.1);
        assertThat(a.layer(base).playing().fadingOut()).isTrue();
        animators.update(0.5);
        assertThat(a.layer(base).size()).isZero();
        // A time a hair before the start wraps to the start, not to the end.
        assertThat(a.layer(base).play(walk).time(-1e-17).phase()).isZero();
        a.layer(adds).play(loose);
        a.layer(adds).play(grow).loop(false).time(1);
        animators.update(0);
        assertThat(scene.local(nodes[3]).scale().x()).as("a scale from none: kept").isCloseTo(1, within(1e-9));
        assertThat(BLINK.name()).isEqualTo("blink");
    }

    @Test
    void layeredUpdatesAllocateNothing() {
        Rig.Builder r = Rig.builder(body());
        Clip one = r.clip(at("one", 1, 1, "root", "arm")).event(0.5, STEP).curve(BLINK, new float[] {0, 1},
            new float[] {0, 1});
        Clip three = r.clip(at("three", 3, 1, "root", "arm"));
        BlendSpace1 move = r.blendSpace1().at(0, one).at(1, three);
        Clip nod = r.additive(at("nod", 1, 1, "root"), Clip.Reference.FIRST_FRAME);
        Rig.Layer base = r.layer();
        Rig.Layer top = r.layer().mask(r.mask().include(r.joint("arm")).build());
        Rig.Layer adds = r.layer().additive();
        Rig rig = r.build();
        Animator a = animators.add(rig, spawn());
        animators.listen(STEP, (node, event, weight) -> { });
        a.layer(base).play(move);
        a.layer(top).play(one);
        a.layer(adds).play(nod);
        animators.update(0.1);
        assertAllocationFree(() -> {
            a.input(move, 0.5);
            a.layer(top).play(three, 0.2);
            animators.update(1 / 60.0);
            a.curve(BLINK);
        });
    }
}
