package com.truesim.scene.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.truesim.common.state.ChangeClock;
import org.junit.jupiter.api.Test;

class CameraAndLightTest {

    @Test
    void lensesArePerspectiveOrOrthographicAndCountTheirChanges() {
        CameraLens lens = CameraLens.perspective(1.2, 0.05);
        assertThat(lens.orthographic()).isFalse();
        assertThat(lens.fovY()).isEqualTo(1.2);
        assertThat(lens.near()).isEqualTo(0.05);
        assertThat(lens).hasToString("perspective lens, fov 1.2, near 0.05");
        long v = lens.version();
        assertThat(lens.fovY(0.5).fovY()).isEqualTo(0.5);
        assertThat(lens.version()).isGreaterThan(v);
        lens.orthographicLens(4, 100);
        assertThat(lens.orthographic()).isTrue();
        assertThat(lens.halfHeight()).isEqualTo(4);
        assertThat(lens.depth()).isEqualTo(100);
        assertThat(lens.fovY()).as("kept").isEqualTo(0.5);
        assertThat(lens).hasToString("orthographic lens, half height 4.0, depth 100.0");
        assertThat(CameraLens.orthographic(2, 10).halfHeight()).isEqualTo(2);
        for (double[] bad : new double[][] {{0, 1}, {Math.PI, 1}, {Double.NaN, 1}, {1, 0}, {1, Double.NaN},
            {1, Double.POSITIVE_INFINITY}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> CameraLens.perspective(bad[0], bad[1]));
        }
        for (double[] bad : new double[][] {{0, 1}, {Double.NaN, 1}, {1, 0}, {1, Double.NaN},
            {Double.POSITIVE_INFINITY, 1}, {1, Double.POSITIVE_INFINITY}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> CameraLens.orthographic(bad[0], bad[1]));
        }
        assertThat(CameraLens.TYPE.name()).isEqualTo("truesim.camera");
    }

    @Test
    void lightsArePointSpotOrDirectionalAndCountTheirChanges() {
        LightSource bulb = LightSource.point(1, 0.5f, 0.25f, 40);
        assertThat(bulb.kind()).isEqualTo(LightSource.Kind.POINT);
        assertThat(bulb.red()).isEqualTo(1);
        assertThat(bulb.green()).isEqualTo(0.5f);
        assertThat(bulb.blue()).isEqualTo(0.25f);
        assertThat(bulb.intensity()).isEqualTo(40);
        assertThat(bulb.range()).isEqualTo(LightSource.NO_RANGE);
        assertThat(bulb.castsShadows()).isFalse();
        assertThat(bulb).hasToString("POINT light 1.0, 0.5, 0.25 x 40.0");
        long v = bulb.version();
        bulb.range(12).castsShadows(true).intensity(0);
        assertThat(bulb.version()).isEqualTo(v + 3);
        assertThat(bulb.range()).isEqualTo(12);
        assertThat(bulb.castsShadows()).isTrue();
        LightSource spot = LightSource.spot(1, 1, 1, 100, 0.1, 0.4);
        assertThat(spot.innerCone()).isEqualTo(0.1);
        assertThat(spot.outerCone()).isEqualTo(0.4);
        assertThat(LightSource.directional(1, 1, 1, 3).kind()).isEqualTo(LightSource.Kind.DIRECTIONAL);
        assertThat(spot.kind(LightSource.Kind.POINT).kind()).isEqualTo(LightSource.Kind.POINT);
        assertThatNullPointerException().isThrownBy(() -> spot.kind(null));
        for (float[] bad : new float[][] {{-1, 0, 0}, {0, -1, 0}, {0, 0, -1}, {Float.NaN, 0, 0},
            {Float.POSITIVE_INFINITY, 0, 0}}) {
            assertThatIllegalArgumentException().isThrownBy(() -> bulb.color(bad[0], bad[1], bad[2]));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> bulb.intensity(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> bulb.intensity(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> bulb.intensity(Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException().isThrownBy(() -> bulb.range(0));
        assertThatIllegalArgumentException().isThrownBy(() -> bulb.range(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> bulb.cone(-0.1, 0.2));
        assertThatIllegalArgumentException().isThrownBy(() -> bulb.cone(0.3, 0.2));
        assertThatIllegalArgumentException().isThrownBy(() -> bulb.cone(0.1, 2));
        assertThat(LightSource.TYPE.name()).isEqualTo("truesim.light");
    }

    @Test
    void camerasAndLightsAreComponentsOfNodes() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        long eye = scene.create();
        long lamp = scene.create(eye);
        scene.components().set(eye, CameraLens.TYPE, CameraLens.perspective(1, 0.1));
        scene.components().set(lamp, LightSource.TYPE, LightSource.point(1, 1, 1, 10));
        assertThat(scene.components().store(LightSource.TYPE).node(0)).isEqualTo(lamp);
        assertThat(scene.components().get(eye, CameraLens.TYPE).fovY()).isEqualTo(1);
        scene.remove(eye);
        assertThat(scene.components().store(CameraLens.TYPE).size()).isZero();
        assertThat(scene.components().store(LightSource.TYPE).size()).isZero();
    }
}
