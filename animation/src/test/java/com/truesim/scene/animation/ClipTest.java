package com.truesim.scene.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.scene.animation.Track.Interpolation;
import com.truesim.scene.animation.Track.Property;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Clips, their playing state, and skeletons. */
class ClipTest {

    static Track moving(String node, int index, float end) {
        return new Track(node, index, Property.TRANSLATION, Interpolation.LINEAR, new float[] {0, end},
            new float[] {0, 0, 0, end, 0, 0}, 0);
    }

    @Test
    void aClipLastsUntilItsLatestKey() {
        AnimationClip clip = new AnimationClip("walk", List.of(moving("a", 0, 1), moving("b", 1, 2.5f)));
        assertThat(clip.name()).isEqualTo("walk");
        assertThat(clip.tracks()).hasSize(2);
        assertThat(clip.duration()).isEqualTo(2.5);
        assertThat(clip.bytes()).isEqualTo(64 + 2 * (64 + 8 + 24));
        assertThat(clip).hasToString("AnimationClip[walk, 2 tracks, 2.5 s]");
        assertThat(new AnimationClip("", List.of()).duration()).isZero();
        assertThat(AnimationAssets.CLIP.type()).isEqualTo(AnimationClip.class);
    }

    /** A state playing {@code clip} on an animator of one node "a". */
    static ClipState playing(AnimationClip clip) {
        Skeleton one = new Skeleton(new String[] {"a"}, new int[] {-1}, new double[] {0, 0, 0, 0, 0, 0, 1, 1, 1, 1});
        SceneGraph scene = new SceneGraph(new ChangeClock());
        return new Animators(scene).add(one, new long[] {scene.create()}).play(clip);
    }

    @Test
    void loopingTimeWrapsAndPlayingOnceStopsAtTheEnds() {
        ClipState state = playing(new AnimationClip("", List.of(moving("a", 0, 2))));
        assertThat(state.duration()).isEqualTo(2);
        assertThat(state.motion()).isInstanceOf(Clip.class);
        assertThat(state.looping()).isTrue();
        assertThat(state.speed()).isEqualTo(1);
        state.advance(2.5, false);
        assertThat(state.time()).isCloseTo(0.5, within(1e-12));
        assertThat(state.phase()).isCloseTo(0.25, within(1e-12));
        assertThat(state.speed(-1).speed()).isEqualTo(-1);
        state.advance(1, false);
        assertThat(state.time()).isCloseTo(1.5, within(1e-12)); // backwards past the start: wrapped to the end
        assertThat(state.finished()).isFalse();
        state.loop(false).speed(2).advance(5, false);
        assertThat(state.time()).isEqualTo(2);
        assertThat(state.finished()).isTrue();
        state.speed(-1);
        assertThat(state.finished()).isFalse();
        state.advance(10, false);
        assertThat(state.time()).isZero();
        assertThat(state.finished()).isTrue();
        assertThat(state.time(1.25).time()).isEqualTo(1.25);
        assertThat(state.time(-3).time()).isZero();
        state.speed(1).time(1.5);
        assertThat(state.finished()).isFalse();
        // Paused, time stays.
        assertThat(state.pause().paused()).isTrue();
        state.advance(0.25, false);
        assertThat(state.time()).isEqualTo(1.5);
        assertThat(state.resume().paused()).isFalse();
        state.advance(0.25, false);
        assertThat(state.time()).isEqualTo(1.75);
        assertThat(state.weight(0.5).weight()).isEqualTo(0.5);
        assertThat(state.fade()).isOne();
        assertThat(state.fadingOut()).isFalse();
        assertThat(state.toString()).contains("phase", "weight 0.5");
        assertThatIllegalArgumentException().isThrownBy(() -> state.time(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> state.speed(Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> state.weight(1.5));
        assertThatIllegalArgumentException().isThrownBy(() -> state.weight(Double.NaN));
    }

    @Test
    void anInstantClipStaysAtItsStart() {
        ClipState state = playing(new AnimationClip("", List.of(new Track("a", 0, Property.SCALE,
            Interpolation.STEP, new float[] {0}, new float[] {1, 1, 1}, 0))));
        state.advance(3, false);
        assertThat(state.time()).isZero();
        assertThat(state.time(2).time()).isZero();
    }

    @Test
    void skeletonsAreNamedNodesAtRest() {
        double[] rest = new double[20];
        rest[0] = 5;
        rest[16] = 7;
        Skeleton skeleton = new Skeleton(new String[] {"hip", "knee"}, new int[] {-1, 0}, rest);
        rest[0] = 9; // copied
        assertThat(skeleton.size()).isEqualTo(2);
        assertThat(skeleton.name(1)).isEqualTo("knee");
        assertThat(skeleton.parent(0)).isEqualTo(-1);
        assertThat(skeleton.parent(1)).isZero();
        assertThat(skeleton.rest(0, 0)).isEqualTo(5);
        assertThat(skeleton.rest(1, 6)).isEqualTo(7);
        assertThat(skeleton.find("knee")).isEqualTo(1);
        assertThat(skeleton.find("toe")).isEqualTo(-1);
        double[] pose = new double[20];
        skeleton.restPose(pose);
        assertThat(pose[0]).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> skeleton.rest(0, 10))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Skeleton(new String[] {"a"}, new int[] {-1, 0}, new double[10]));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Skeleton(new String[] {"a"}, new int[] {-1}, new double[9]));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Skeleton(new String[] {"a"}, new int[] {-2}, new double[10]));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new Skeleton(new String[] {"a"}, new int[] {0}, new double[10]));
        assertThatNullPointerException()
            .isThrownBy(() -> new Skeleton(new String[] {null}, new int[] {-1}, new double[10]));
    }
}
