package com.truesim.scene.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import org.junit.jupiter.api.Test;

/** 3D textures and dynamic textures as data: sizes checked, changes kept for readers at their own place. */
class DynamicTextureTest {

    private static TextureData volume(int w, int h, int d) {
        return new TextureData(w, h, TextureData.ColorSpace.LINEAR, TextureData.Format.RGBA8,
            Arena.ofAuto().allocate(4L * w * h * d), d);
    }

    @Test
    void volumesAreSlicesOfTexels() {
        TextureData noise = volume(4, 2, 3);
        assertThat(noise.depth()).isEqualTo(3);
        assertThat(noise.volume()).isTrue();
        assertThat(noise.bytes()).isEqualTo(4 * 4 * 2 * 3);
        TextureData flat = new TextureData(2, 2, TextureData.ColorSpace.SRGB, Arena.ofAuto().allocate(16));
        assertThat(flat.depth()).isOne();
        assertThat(flat.volume()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new TextureData(4, 2, TextureData.ColorSpace.LINEAR,
            TextureData.Format.RGBA8, Arena.ofAuto().allocate(32), 3)).withMessageContaining("4 x 2 x 3");
        assertThatIllegalArgumentException().isThrownBy(() -> new TextureData(4, 2, TextureData.ColorSpace.LINEAR,
            TextureData.Format.RGBA8, Arena.ofAuto().allocate(0), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new TextureData(0, 2, TextureData.ColorSpace.LINEAR,
            TextureData.Format.RGBA8, Arena.ofAuto().allocate(0), 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new TextureData(2, 0, TextureData.ColorSpace.LINEAR,
            TextureData.Format.RGBA8, Arena.ofAuto().allocate(0), 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new TextureData(1, 1, TextureData.ColorSpace.LINEAR,
            TextureData.Format.RGBA8, Arena.ofAuto().allocate(8), 1)).withMessageContaining("1 x 1 texture");
    }

    @Test
    void changesAreKeptForReadersBehindUntilTheRingWrapsOverThem() {
        DynamicTexture painted = DynamicTexture.of(volume(8, 8, 2));
        assertThat(painted.gpuWritten()).isFalse();
        assertThat(painted.texels().width()).isEqualTo(8);
        assertThat(painted.bytes()).isEqualTo(4 * 8 * 8 * 2);
        int[] box = new int[6];
        assertThat(painted.change(0, box)).as("none yet").isFalse();
        painted.changed(1, 2, 1, 3, 4, 1);
        painted.changedAll();
        assertThat(painted.changes()).isEqualTo(2);
        assertThat(painted.change(0, box)).isTrue();
        assertThat(box).containsExactly(1, 2, 1, 3, 4, 1);
        assertThat(painted.change(1, box)).isTrue();
        assertThat(box).containsExactly(0, 0, 0, 8, 8, 2);
        assertThat(painted.change(2, box)).isFalse();
        assertThat(painted.change(-1, box)).isFalse();
        for (int i = 0; i < DynamicTexture.KEPT; i++) {
            painted.changed(0, 0, 0, 1, 1, 1);
        }
        assertThat(painted.change(1, box)).as("wrapped over: take it all again").isFalse();
        assertThat(painted.change(2, box)).isTrue();
        assertThat(DynamicTexture.gpuWritten(volume(2, 2, 1)).gpuWritten()).isTrue();
    }

    @Test
    void boxesOutsideAreRefused() {
        DynamicTexture painted = DynamicTexture.of(volume(4, 4, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(-1, 0, 0, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(0, -1, 0, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(0, 0, -1, 1, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(0, 0, 0, 0, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(0, 0, 0, 1, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(0, 0, 0, 1, 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(3, 0, 0, 2, 1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(0, 3, 0, 1, 2, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> painted.changed(0, 0, 1, 1, 1, 1))
            .withMessageContaining("outside");
        assertThatThrownBy(() -> DynamicTexture.of(null)).isInstanceOf(NullPointerException.class);
        assertThat(RenderAssets.DYNAMIC_TEXTURE.name()).isEqualTo("dynamicTexture");
    }
}
