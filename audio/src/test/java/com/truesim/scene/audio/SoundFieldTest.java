package com.truesim.scene.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** Sound fields (§2.18): points, lines and areas, their nearest places and samples, played by a store. */
class SoundFieldTest {

    @Test
    void pointsAreTheirOwnSamplesAndTheNearestIsFound() {
        SoundField crowd = SoundField.points(0, 0, 0, 10, 0, 0, 0, 0, 10);
        assertThat(crowd.kind()).isEqualTo(SoundField.Kind.POINTS);
        assertThat(crowd.nearest()).isEqualTo(SoundField.NEAREST);
        assertThat(crowd.count()).isEqualTo(3);
        assertThat(crowd.samples()).isEqualTo(3);
        assertThat(crowd.sample(1, 0)).isEqualTo(10);
        assertThat(crowd.place(2, 2)).isEqualTo(10);
        assertThat(crowd.centre(0)).isCloseTo(10 / 3.0, within(1e-12));
        assertThat(crowd.nearest(2).nearest()).isEqualTo(2);
        double[] out = new double[3];
        crowd.nearestTo(9, 1, 1, out);
        assertThat(out).containsExactly(10, 0, 0);
    }

    @Test
    void aLineIsSampledAlongAndItsNearestPointIsOnIt() {
        SoundField river = SoundField.line(0, 0, 0, 10, 0, 0, 10, 0, 10).spacing(2.5);
        assertThat(river.spacing()).isEqualTo(2.5);
        assertThat(river.nearest()).isEqualTo(1);
        assertThat(river.samples()).isEqualTo(8); // 4 a segment of 10 m
        assertThat(river.sample(0, 0)).isCloseTo(1.25, within(1e-12));
        double[] out = new double[3];
        river.nearestTo(4, 3, -5, out);
        assertThat(out).containsExactly(4, 0, 0);
        river.nearestTo(13, 0, 7, out);
        assertThat(out).containsExactly(10, 0, 7);
        river.nearestTo(-5, 0, -5, out); // before the start: the start
        assertThat(out).containsExactly(0, 0, 0);
        SoundField still = SoundField.line(1, 1, 1, 1, 1, 1); // a line of no length
        still.nearestTo(0, 0, 0, out);
        assertThat(out).containsExactly(1, 1, 1);
    }

    @Test
    void anAreaHasItsInsideAndItsEdges() {
        SoundField lake = SoundField.area(0, 1, 0, 10, 1, 0, 10, 3, 10, 0, 3, 10); // height 2 on average
        assertThat(lake.kind()).isEqualTo(SoundField.Kind.AREA);
        assertThat(lake.samples()).isEqualTo(25); // a 2 m grid inside 10 by 10
        assertThat(lake.inside(5, 5)).isTrue();
        assertThat(lake.inside(-1, 5)).isFalse();
        double[] out = new double[3];
        lake.nearestTo(5, 10, 5, out);
        assertThat(out).containsExactly(5, 2, 5); // over it: straight down to its height
        lake.nearestTo(5, 2, -4, out);
        assertThat(out).containsExactly(5, 2, 0);
        assertThat(SoundField.area(0, 0, 0, 10, 0, 0, 0, 0, 10).samples()).isEqualTo(10); // the grid inside it
        SoundField tiny = SoundField.area(0, 0, 0, 0.5, 0, 0, 0, 0, 0.5);
        assertThat(tiny.samples()).isEqualTo(1);
        assertThat(tiny.sample(0, 0)).isCloseTo(1 / 6.0, within(1e-12));
    }

    @Test
    void badFieldsSayWhy() {
        assertThatThrownBy(() -> SoundField.points()).hasMessageContaining("at least 1");
        assertThatThrownBy(() -> SoundField.points(1, 2)).hasMessageContaining("2 numbers given");
        assertThatThrownBy(() -> SoundField.line(1, 2, 3)).hasMessageContaining("line field needs at least 2");
        assertThatThrownBy(() -> SoundField.area(1, 2, 3, 4, 5, 6)).hasMessageContaining("at least 3");
        assertThatThrownBy(() -> SoundField.points(0, 0, 0).nearest(0)).hasMessageContaining("1 to 8");
        assertThatThrownBy(() -> SoundField.points(0, 0, 0).nearest(9)).hasMessageContaining("1 to 8");
        assertThatThrownBy(() -> SoundField.line(0, 0, 0, 1, 0, 0).nearest(2)).hasMessageContaining("LINE");
        assertThatThrownBy(() -> SoundField.line(0, 0, 0, 1, 0, 0).spacing(0)).hasMessageContaining("positive");
    }

    @Test
    void aStorePlaysAFieldAsOneInstanceAtItsCentre() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        Sounds sounds = new Sounds(scene);
        SoundField crowd = SoundField.points(0, 0, 0, 4, 0, 0);
        Sound murmur = Sound.builder().root(Play.clip(SoundsTest.clip(1))).loop()
            .concurrency(Concurrency.max(1).steal(Concurrency.Steal.REJECT_NEW)).build();
        SoundInstance one = sounds.field(murmur, crowd);
        assertThat(sounds.field(one.id())).isSameAs(crowd);
        assertThat(sounds.position(one.id(), 0)).isEqualTo(2);
        assertThat(sounds.fireField(murmur, crowd)).isZero(); // rejected: the group is full
        long single = sounds.fire(Sound.of(SoundsTest.clip(2)), 1, 1, 1);
        assertThat(sounds.field(single)).isNull();
        assertThatThrownBy(() -> sounds.fireField(murmur, null)).isInstanceOf(NullPointerException.class);
    }
}
