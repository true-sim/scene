package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class NodeHandleTest {

    @Test
    void handlesPackSlotAndGeneration() {
        long handle = NodeHandle.of(5, 1);
        assertThat(NodeHandle.index(handle)).isEqualTo(5);
        assertThat(NodeHandle.generation(handle)).isEqualTo(1);
        assertThat(handle).isNotEqualTo(NodeHandle.NONE).isNotEqualTo(NodeHandle.of(5, 2));
        long last = NodeHandle.of(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(NodeHandle.index(last)).isEqualTo(Integer.MAX_VALUE);
        assertThat(NodeHandle.generation(last)).isEqualTo(Integer.MAX_VALUE);
        assertThat(NodeHandle.toString(handle)).isEqualTo("node 5#1");
        assertThat(NodeHandle.toString(NodeHandle.NONE)).isEqualTo("no node");
    }

    @Test
    void invalidHandlesAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> NodeHandle.of(-1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> NodeHandle.of(0, 0))
            .withMessage("Not a node: index 0, generation 0");
    }
}
