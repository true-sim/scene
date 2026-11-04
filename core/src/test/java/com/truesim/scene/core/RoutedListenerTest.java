package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.state.ChangeClock;
import org.junit.jupiter.api.Test;

class RoutedListenerTest {

    @Test
    void routedListenersSayWhatTheyHearInEachPhase() {
        RoutedListener.Listener l = e -> { };
        RoutedListener r = RoutedListener.of(SceneEventType.CLICK.bit(), SceneEventType.PRESS.bit(), l);
        assertThat(r.mask()).isEqualTo(SceneEventType.CLICK.bit());
        assertThat(r.captureMask()).isEqualTo(SceneEventType.PRESS.bit());
        assertThat(r.listener()).isSameAs(l);
        assertThat(r.hears(SceneEventType.PRESS, NodeEvent.Phase.CAPTURE)).isTrue();
        assertThat(r.hears(SceneEventType.CLICK, NodeEvent.Phase.CAPTURE)).isFalse();
        assertThat(r.hears(SceneEventType.CLICK, NodeEvent.Phase.TARGET)).isTrue();
        assertThat(r.hears(SceneEventType.PRESS, NodeEvent.Phase.TARGET)).isTrue();
        assertThat(r.hears(SceneEventType.MOVE, NodeEvent.Phase.TARGET)).isFalse();
        assertThat(r.hears(SceneEventType.CLICK, NodeEvent.Phase.BUBBLE)).isTrue();
        assertThat(r.hears(SceneEventType.PRESS, NodeEvent.Phase.BUBBLE)).isFalse();
        assertThatNullPointerException().isThrownBy(() -> RoutedListener.of(1, 0, null));
        SceneGraph scene = new SceneGraph(new ChangeClock());
        long node = scene.create();
        scene.components().set(node, RoutedListener.TYPE, r);
        assertThat(RoutedListener.TYPE.saved()).isFalse();
        scene.remove(node);
        assertThat(scene.components().store(RoutedListener.TYPE).size()).isZero();
    }

    @Test
    void eventTypesKnowTheirBitsAndWhetherTheyBubble() {
        assertThat(SceneEventType.of(0)).isEqualTo(SceneEventType.ENTER);
        assertThat(SceneEventType.CLICK.bubbles()).isTrue();
        assertThat(SceneEventType.EXIT.bubbles()).isFalse();
        assertThat(SceneEventType.MOVE.bit()).isEqualTo(4);
        assertThat(SceneEventType.count()).isEqualTo(19);
        assertThat(SceneEventType.ALL_MASK).isEqualTo((1 << 19) - 1);
        assertThat(NodeEvent.Phase.values()).hasSize(3);
    }
}
