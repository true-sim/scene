package com.truesim.scene.animation;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.animation.AnimationPointer.Target;
import com.truesim.scene.animation.Track.Interpolation;
import com.truesim.scene.animation.Track.Property;
import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.AssetType;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pointer tracks: what glTF's KHR_animation_pointer animates besides nodes, blended and handed to a sink. */
class PointerTest {

    private static final AssetRef<String> MATERIAL = AssetRef.of(AssetType.of("material", String.class, s -> 0),
        new AssetId(1, 2));
    private static final AnimationPointer COLOR = new AnimationPointer(
        "/materials/0/pbrMetallicRoughness/baseColorFactor", Target.MATERIAL, 0, "baseColor", MATERIAL, 1, 1, 1, 1);
    private static final AnimationPointer INTENSITY = new AnimationPointer(
        "/extensions/KHR_lights_punctual/lights/1/intensity", Target.LIGHT, 1, "intensity", null, 2);
    private static final AnimationPointer SHOWN = new AnimationPointer(
        "/nodes/1/extensions/KHR_node_visibility/visible", Target.VISIBILITY, 1, "visible", null, 1);

    private final SceneGraph scene = new SceneGraph(new ChangeClock());

    private static Skeleton pair() {
        double[] rest = {
            0, 0, 0, 0, 0, 0, 1, 1, 1, 1,
            0, 1, 0, 0, 0, 0, 1, 1, 1, 1};
        return new Skeleton(new String[] {"root", "lamp"}, new int[] {-1, 0}, rest);
    }

    private long[] spawn() {
        long root = scene.create();
        return new long[] {root, scene.create(root)};
    }

    /** The color from black to red over 1 s, and the intensity from 0 to 10. */
    private static AnimationClip glow() {
        return new AnimationClip("glow", List.of(
            new Track(COLOR, Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 0, 0, 1, 1, 0, 0, 1}),
            new Track(INTENSITY, Interpolation.LINEAR, new float[] {0, 1}, new float[] {0, 10})));
    }

    /** The lamp hidden from 0.5 s on, over 1 s; the intensity 4. */
    private static AnimationClip blink() {
        return new AnimationClip("blink", List.of(
            new Track(SHOWN, Interpolation.STEP, new float[] {0, 0.5f, 1}, new float[] {1, 0, 0}),
            new Track(INTENSITY, Interpolation.STEP, new float[] {0}, new float[] {4})));
    }

    @Test
    void pointersNameWhatTheyAnimate() {
        assertThat(COLOR.path()).isEqualTo("/materials/0/pbrMetallicRoughness/baseColorFactor");
        assertThat(COLOR.target()).isEqualTo(Target.MATERIAL);
        assertThat(COLOR.index()).isZero();
        assertThat(COLOR.property()).isEqualTo("baseColor");
        assertThat(COLOR.asset()).isEqualTo(MATERIAL);
        assertThat(COLOR.components()).isEqualTo(4);
        assertThat(COLOR.rest(3)).isEqualTo(1);
        assertThat(INTENSITY.asset()).isNull();
        assertThat(COLOR).hasToString("AnimationPointer[/materials/0/pbrMetallicRoughness/baseColorFactor: MATERIAL 0 "
            + "baseColor]");
        // Equal by what they name, whatever their rest
        AnimationPointer again = new AnimationPointer(INTENSITY.path(), Target.LIGHT, 1, "intensity", null, 7);
        assertThat(again).isEqualTo(INTENSITY).hasSameHashCodeAs(INTENSITY).isNotEqualTo(COLOR).isNotEqualTo("x");
        assertThat(again).isNotEqualTo(new AnimationPointer("/other", Target.LIGHT, 1, "intensity", null, 7))
            .isNotEqualTo(new AnimationPointer(INTENSITY.path(), Target.CAMERA, 1, "intensity", null, 7))
            .isNotEqualTo(new AnimationPointer(INTENSITY.path(), Target.LIGHT, 2, "intensity", null, 7))
            .isNotEqualTo(new AnimationPointer(INTENSITY.path(), Target.LIGHT, 1, "range", null, 7))
            .isNotEqualTo(new AnimationPointer(INTENSITY.path(), Target.LIGHT, 1, "intensity", MATERIAL, 7))
            .isNotEqualTo(new AnimationPointer(INTENSITY.path(), Target.LIGHT, 1, "intensity", null, 7, 7));
        assertThatIllegalArgumentException().isThrownBy(() -> new AnimationPointer("/a", Target.LIGHT, -1, "x", null, 1))
            .withMessageContaining("Not an index");
        assertThatIllegalArgumentException().isThrownBy(() -> new AnimationPointer("/a", Target.MATERIAL, 0, "x", null,
            1)).withMessageContaining("names its material");
        assertThatIllegalArgumentException().isThrownBy(() -> new AnimationPointer("/a", Target.LIGHT, 0, "x", null))
            .withMessageContaining("1 to 4 values");
        assertThatIllegalArgumentException().isThrownBy(() -> new AnimationPointer("/a", Target.LIGHT, 0, "x", null,
            1, 2, 3, 4, 5)).withMessageContaining("1 to 4 values");
    }

    @Test
    void pointerTracksSampleTheirPointersComponents() {
        Track track = glow().tracks().get(0);
        assertThat(track.property()).isEqualTo(Property.POINTER);
        assertThat(track.pointer()).isSameAs(COLOR);
        assertThat(track.components()).isEqualTo(4);
        assertThat(track.node()).isEqualTo(-1);
        assertThat(track.nodeName()).isEmpty();
        double[] out = new double[4];
        track.sample(0.5, new int[1], 0, out);
        assertThat(out).containsExactly(0.5, 0, 0, 1);
        Track cubic = new Track(INTENSITY, Interpolation.CUBIC_SPLINE, new float[] {0, 1},
            new float[] {0, 0, 0, 0, 10, 0});
        cubic.sample(0.5, new int[1], 0, out);
        assertThat(out[0]).isCloseTo(5, within(1e-9));
        assertThat(new Track("hip", 0, Property.SCALE, Interpolation.LINEAR, new float[] {0}, new float[] {1, 1, 1}, 0)
            .pointer()).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> new Track("hip", 0, Property.POINTER,
            Interpolation.LINEAR, new float[] {0}, new float[] {1}, 1)).withMessageContaining("with its pointer");
        assertThatThrownBy(() -> new Track(null, Interpolation.LINEAR, new float[] {0}, new float[] {1}))
            .isInstanceOf(NullPointerException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new Track(COLOR, Interpolation.LINEAR, new float[] {0},
            new float[] {1})).withMessageContaining("take 4 values");
    }

    @Test
    void animatorsBlendPointersFromTheirRestAndHandThemToTheSink() {
        Animators animators = new Animators(scene);
        long[] nodes = spawn();
        Animator a = animators.add(pair(), nodes);
        List<String> got = new ArrayList<>();
        Animators.PointerSink sink = (animator, pointer, values, offset, count) -> {
            assertThat(animator).isSameAs(a);
            got.add(pointer.property() + Arrays.toString(Arrays.copyOfRange(values, offset, offset + count)));
        };
        List<String> second = new ArrayList<>();
        animators.pointerSink(sink.andThen((animator, pointer, values, offset, count) -> second.add(pointer.property())));
        assertThat(a.value(COLOR, 0)).as("no clip keys it: its rest").isEqualTo(1);
        AnimationClip glow = glow();
        a.play(glow).time(0.5);
        assertThat(a.value(INTENSITY, 0)).as("bound, not yet updated: at rest").isEqualTo(2);
        assertThat(a.rig().pointerCount()).isEqualTo(2);
        assertThat(a.rig().pointer(1)).isEqualTo(INTENSITY);
        animators.update(0);
        assertThat(got).containsExactly("baseColor[0.5, 0.0, 0.0, 1.0]", "intensity[5.0]");
        assertThat(second).containsExactly("baseColor", "intensity");
        assertThat(a.value(COLOR, 0)).isEqualTo(0.5);
        assertThat(a.value(SHOWN, 0)).as("keyed by no clip of the rig").isEqualTo(1);
        assertThatThrownBy(() -> a.value(INTENSITY, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        got.clear();
        animators.pointerSink(null);
        animators.update(0.1);
        assertThat(got).as("dropped").isEmpty();
    }

    @Test
    void crossFadesBlendPointersAsCurves() {
        Rig.Builder r = Rig.builder(pair());
        Clip glow = r.clip(glow());
        Clip blink = r.clip(blink());
        Rig rig = r.build();
        assertThat(rig.pointerCount()).as("the intensity shared").isEqualTo(3);
        Animators animators = new Animators(scene);
        Animator a = animators.add(rig, spawn());
        Rig.Layer base = rig.layers().get(0);
        a.layer(base).play(glow).loop(false).time(0.5);
        animators.update(0);
        a.layer(base).play(blink, 1.0);
        animators.update(0.5);
        // Halfway: glow at its end, blink at 0.5 s; the color keyed by glow alone blends with its rest
        assertThat(a.value(INTENSITY, 0)).isCloseTo(0.5 * 10 + 0.5 * 4, within(1e-9));
        assertThat(a.value(COLOR, 1)).isCloseTo(0.5 * 0 + 0.5 * 1, within(1e-9));
        assertThat(a.value(SHOWN, 0)).isCloseTo(0.5 * 1 + 0.5 * 0, within(1e-9));
    }

    @Test
    void additivePointersAddTheirMovesFromTheFirstFrameOrTheRest() {
        Rig.Builder r = Rig.builder(pair());
        Clip glow = r.clip(glow());
        Clip fromFirst = r.additive(glow(), Clip.Reference.FIRST_FRAME);
        Clip fromRest = r.additive(glow(), Clip.Reference.REST_POSE);
        Rig.Layer base = r.layer();
        Rig.Layer adds = r.layer().additive();
        Rig rig = r.build();
        Animators animators = new Animators(scene);
        Animator a = animators.add(rig, spawn());
        a.layer(base).play(glow).loop(false).time(0.5);
        a.layer(adds).play(fromFirst).loop(false).time(1);
        animators.update(0);
        assertThat(a.value(INTENSITY, 0)).as("5, plus 10 from the first frame's 0").isCloseTo(15, within(1e-9));
        a.layer(adds).play(fromRest).loop(false).time(1);
        animators.update(0);
        assertThat(a.value(INTENSITY, 0)).as("5, plus 8 from the rest's 2").isCloseTo(13, within(1e-9));
        assertThat(a.value(COLOR, 3)).as("alpha 1, plus 0 from the rest's 1").isCloseTo(1, within(1e-9));
    }

    @Test
    void writingPointersAllocatesNothing() {
        Animators animators = new Animators(scene);
        Animator a = animators.add(pair(), spawn());
        double[] kept = new double[4];
        animators.pointerSink((animator, pointer, values, offset, count) -> System.arraycopy(values, offset, kept, 0,
            count));
        a.play(glow());
        animators.update(0.01);
        assertAllocationFree(() -> animators.update(1 / 60.0));
        assertAllocationFree(() -> kept[0] = a.value(COLOR, 0));
    }
}
