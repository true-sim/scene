package com.truesim.scene.effects;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The PCG hash, as unsigned 32-bit integers, and floats from it. */
class HashTest {

    @Test
    void theHashIsPcgOnUnsignedIntegers() {
        assertThat(Hash.pcg(0)).isEqualTo(129708002);
        assertThat(Hash.pcg(1)).isEqualTo(0xa8beea3c);
        assertThat(Hash.pcg(42)).isEqualTo(0x48f432ff);
        assertThat(Hash.pcg(0xFFFFFFFF)).isEqualTo(0xe62a4902);
        assertThat(Hash.combine(1, 2)).isEqualTo(0x7deda5d8);
        assertThat(Hash.combine(7, 3)).isEqualTo(0x47a42b00);
    }

    @Test
    void floatsAreTheTop24BitsInZeroToOne() {
        assertThat(Hash.unit(0)).isZero();
        assertThat(Hash.unit(0xFFFFFFFF)).isEqualTo(1 - 0x1p-24f).isLessThan(1);
        assertThat(Hash.unit(0x000000FF)).isZero();
        assertThat(Hash.random(7, 3)).isEqualTo((0x47a42b00 >>> 8) * 0x1p-24f);
    }
}
