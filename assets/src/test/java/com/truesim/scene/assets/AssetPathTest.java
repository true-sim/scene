package com.truesim.scene.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/** Paths are normalised when parsed, resolve side resources from their folder, and give stable ids. */
class AssetPathTest {

    @Test
    void pathsAreNormalisedWhenParsed() {
        AssetPath rock = AssetPath.parse("game://models\\.//rocks/../rock.gltf");
        assertThat(rock.mount()).isEqualTo("game");
        assertThat(rock.path()).isEqualTo("models/rock.gltf");
        assertThat(rock).hasToString("game://models/rock.gltf");
        assertThat(rock).isEqualTo(AssetPath.parse("game:///models/rock.gltf"));
        assertThat(rock).as("case-sensitive").isNotEqualTo(AssetPath.parse("game://Models/rock.gltf"));
        assertThatIllegalArgumentException().isThrownBy(() -> AssetPath.parse("models/rock.gltf"))
            .withMessageContaining("mount://path");
        assertThatIllegalArgumentException().isThrownBy(() -> AssetPath.parse("C:/models/rock.gltf"));
        assertThatIllegalArgumentException().isThrownBy(() -> AssetPath.parse("Game://rock.gltf"))
            .withMessageContaining("A mount is");
        assertThatIllegalArgumentException().isThrownBy(() -> AssetPath.parse("game://../rock.gltf"))
            .withMessageContaining("above its mount");
        assertThatIllegalArgumentException().isThrownBy(() -> AssetPath.parse("game://models/.."))
            .withMessageContaining("names no file");
        assertThatIllegalArgumentException().isThrownBy(() -> new AssetPath("game", "models//rock.gltf"))
            .withMessageContaining("Not a normalised path");
    }

    @Test
    void sideResourcesResolveFromTheFolder() {
        AssetPath rock = AssetPath.parse("game://models/rock.gltf");
        assertThat(rock.resolve("rock.bin")).hasToString("game://models/rock.bin");
        assertThat(rock.resolve("..\\textures/rock.png")).hasToString("game://textures/rock.png");
        assertThat(rock.resolve("/shared/noise.png")).hasToString("game://shared/noise.png");
        assertThat(AssetPath.parse("game://rock.gltf").resolve("rock.bin")).hasToString("game://rock.bin");
        assertThatIllegalArgumentException().isThrownBy(() -> rock.resolve("../../outside.bin"));
    }

    @Test
    void extensionsAndIds() {
        assertThat(AssetPath.parse("game://models/rock.GLTF").extension()).isEqualTo("gltf");
        assertThat(AssetPath.parse("game://models.v2/README").extension()).isEmpty();
        assertThat(AssetPath.parse("game://models/.hidden").extension()).isEmpty();
        AssetId id = AssetPath.parse("game://models/rock.gltf").id();
        assertThat(id).as("derived from the path: the same every time").isEqualTo(new AssetPath("game",
            "models/rock.gltf").id());
        assertThat(id).hasToString("19e5b8db6ee637d0a3f73afa97e04125");
        assertThat(AssetPath.parse("mod://models/rock.gltf").id()).isNotEqualTo(id);
    }

    @Test
    void fragmentsNameAssetsInAFile() {
        AssetPath body = AssetPath.parse("game://models/./car.gltf#mesh/0");
        assertThat(body.path()).isEqualTo("models/car.gltf");
        assertThat(body.fragment()).isEqualTo("mesh/0");
        assertThat(body).hasToString("game://models/car.gltf#mesh/0");
        assertThat(body.file()).isEqualTo(AssetPath.parse("game://models/car.gltf"));
        AssetPath file = body.file();
        assertThat(file.file()).as("no fragment: itself").isSameAs(file);
        assertThat(body.file().fragment("mesh/0")).isEqualTo(body);
        assertThat(body.id()).as("another asset than the file").isNotEqualTo(body.file().id());
        assertThat(body.extension()).isEqualTo("gltf");
        assertThat(body.resolve("textures/paint.png#linear")).hasToString("game://models/textures/paint.png#linear");
        assertThat(body.resolve("/paint.png")).hasToString("game://paint.png");
        assertThat(body.resolve("/paint.png#linear").fragment()).isEqualTo("linear");
        assertThat(new AssetPath("game", "car.gltf").fragment()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> AssetPath.parse("game://car.gltf#a#b"))
            .withMessageContaining("holds no #");
    }
}
