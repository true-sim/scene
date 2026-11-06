package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class ShadowSpecTest {

    @Test
    void specsSayHowALightCastsShadows() {
        assertThat(ShadowSpec.NONE.casts()).isFalse();
        ShadowSpec maps = ShadowSpec.maps(1024);
        assertThat(maps.method()).isEqualTo(ShadowMethod.MAPS);
        assertThat(maps.resolution()).isEqualTo(1024);
        assertThat(maps.casts()).isTrue();
        assertThat(maps.bias()).isNaN();
        ShadowSpec sun = ShadowSpec.cascades(4, ShadowSpec.AUTO, 300);
        assertThat(sun.method()).isEqualTo(ShadowMethod.CASCADES);
        assertThat(sun.cascades()).isEqualTo(4);
        assertThat(sun.distance()).isEqualTo(300);
        assertThat(sun.resolution()).isEqualTo(ShadowSpec.AUTO);
        assertThat(ShadowSpec.rayTraced().method()).isEqualTo(ShadowMethod.RAY_TRACED);
        ShadowSpec soft = ShadowSpec.softRayTraced(0.0047);
        assertThat(soft.method()).isEqualTo(ShadowMethod.SOFT_RAY_TRACED);
        assertThat(soft.softness()).isEqualTo(0.0047);
        ShadowSpec tuned = maps.auto(128, 1024).cascades(2).distance(50).bias(2).softness(0.1).importance(3)
            .method(ShadowMethod.CASCADES).resolution(512);
        assertThat(tuned).isEqualTo(new ShadowSpec(ShadowMethod.CASCADES, 512, 128, 1024, 2, 50, 2, 0.1, 3));
        assertThat(maps.auto(64, 64).resolution()).isEqualTo(ShadowSpec.AUTO);
        assertThat(maps.bias(Float.NaN).bias()).isNaN();
    }

    @Test
    void specsRefuseWhatIsNoSpec() {
        ShadowSpec s = ShadowSpec.maps(256);
        assertThatNullPointerException().isThrownBy(() -> s.method(null));
        for (int bad : new int[] {8, 100, 32768, -1}) {
            assertThatIllegalArgumentException().isThrownBy(() -> s.resolution(bad));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> s.auto(8, 64));
        assertThatIllegalArgumentException().isThrownBy(() -> s.auto(64, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> s.auto(128, 64));
        assertThatIllegalArgumentException().isThrownBy(() -> s.cascades(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> s.cascades(9));
        for (double bad : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> s.distance(bad));
            assertThatIllegalArgumentException().isThrownBy(() -> s.softness(bad));
        }
        for (float bad : new float[] {-1, 65, Float.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> s.bias(bad));
        }
        for (float bad : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThatIllegalArgumentException().isThrownBy(() -> s.importance(bad));
        }
    }
}
