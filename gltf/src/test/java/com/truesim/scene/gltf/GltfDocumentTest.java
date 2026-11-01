package com.truesim.scene.gltf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.truesim.scene.assets.AssetId;
import com.truesim.scene.assets.AssetInput;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Documents read from text or binaries, their buffers from wherever they are, accessors of every type. */
class GltfDocumentTest {

    /** An input holding {@code main}, and side resources by name. */
    static AssetInput input(byte[] main, Map<String, byte[]> side) {
        return new AssetInput() {
            @Override
            public AssetId id() {
                return new AssetId(1, 1);
            }

            @Override
            public int part() {
                return 0;
            }

            @Override
            public InputStream open(String resource) throws IOException {
                byte[] bytes = resource.isEmpty() ? main : side.get(resource);
                if (bytes == null) {
                    throw new FileNotFoundException(resource);
                }
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    static AssetInput input(String json, Map<String, byte[]> side) {
        return input(json.getBytes(StandardCharsets.UTF_8), side);
    }

    static String base64(byte[] bytes) {
        return "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /** A GLB of {@code json} and {@code bin} chunks (null: left out), version {@code version}. */
    static byte[] glb(String json, byte[] bin, int version) {
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        int jsonLength = (text.length + 3) & ~3;
        int length = 12 + 8 + jsonLength + (bin == null ? 0 : 8 + bin.length);
        ByteBuffer out = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67).putInt(version).putInt(length);
        out.putInt(jsonLength).putInt(0x4E4F534A).put(text);
        for (int i = text.length; i < jsonLength; i++) {
            out.put((byte) ' ');
        }
        if (bin != null) {
            out.putInt(bin.length).putInt(0x004E4942).put(bin);
        }
        return out.array();
    }

    @Test
    void accessorsOfEveryComponentType() throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(0, (byte) -127).put(1, (byte) 255).putShort(2, (short) -32767).putShort(4, (short) 65535)
            .putInt(8, 70_000_000).putFloat(12, 1.5f).putFloat(20, 2.5f) // floats with a stride of 8: 12, 20
            .put(40, (byte) 1).putFloat(44, 9.5f).putInt(48, -1); // a sparse index and value; an index below 0
        String json = """
            {"asset": {"version": "2.0"}, "buffers": [{"byteLength": 64, "uri": "%s"}],
             "bufferViews": [{"buffer": 0, "byteLength": 64}, {"buffer": 0, "byteOffset": 12, "byteLength": 16,
               "byteStride": 8}, {"buffer": 3, "byteLength": 4}, {"buffer": 0, "byteOffset": 8, "byteLength": 8,
               "byteStride": 4}, {"buffer": -1, "byteLength": 4}],
             "accessors": [
               {"bufferView": 0, "componentType": 5120, "count": 1, "type": "SCALAR", "normalized": true},
               {"bufferView": 0, "componentType": 5120, "count": 1, "type": "SCALAR"},
               {"bufferView": 0, "byteOffset": 1, "componentType": 5121, "count": 1, "type": "SCALAR",
                "normalized": true},
               {"bufferView": 0, "byteOffset": 1, "componentType": 5121, "count": 1, "type": "SCALAR"},
               {"bufferView": 0, "byteOffset": 2, "componentType": 5122, "count": 1, "type": "SCALAR",
                "normalized": true},
               {"bufferView": 0, "byteOffset": 2, "componentType": 5122, "count": 1, "type": "SCALAR"},
               {"bufferView": 0, "byteOffset": 4, "componentType": 5123, "count": 1, "type": "SCALAR",
                "normalized": true},
               {"bufferView": 0, "byteOffset": 4, "componentType": 5123, "count": 1, "type": "SCALAR"},
               {"bufferView": 0, "byteOffset": 8, "componentType": 5125, "count": 1, "type": "SCALAR"},
               {"bufferView": 1, "componentType": 5126, "count": 2, "type": "SCALAR"},
               {"componentType": 5126, "count": 2, "type": "VEC2"},
               {"bufferView": 0, "componentType": 5124, "count": 1, "type": "SCALAR"},
               {"bufferView": 0, "componentType": 5126, "count": 1, "type": "VEC5"},
               {"bufferView": 2, "componentType": 5126, "count": 1, "type": "SCALAR"},
               {"componentType": 5126, "count": 3, "type": "SCALAR", "sparse": {"count": 1,
                "indices": {"bufferView": 0, "byteOffset": 40, "componentType": 5121},
                "values": {"bufferView": 0, "byteOffset": 44}}},
               {"bufferView": 3, "componentType": 5125, "count": 2, "type": "SCALAR"},
               {"componentType": 5125, "count": 2, "type": "SCALAR"},
               {"bufferView": 0, "byteOffset": 1, "componentType": 5121, "count": 1, "type": "SCALAR"},
               {"bufferView": 0, "componentType": 5126, "count": 1, "type": "MAT4"},
               {"bufferView": 4, "componentType": 5126, "count": 1, "type": "SCALAR"},
               {"bufferView": 0, "byteOffset": 8, "componentType": 5125, "count": 1, "type": "SCALAR"},
               {"componentType": 5126, "count": 3, "type": "SCALAR", "sparse": {"count": 1,
                "indices": {"bufferView": 0, "byteOffset": 1, "componentType": 5121},
                "values": {"bufferView": 0, "byteOffset": 44}}},
               {"bufferView": 1, "componentType": 5126, "count": 2, "type": "SCALAR", "sparse": {"count": 1,
                "indices": {"bufferView": 0, "byteOffset": 48, "componentType": 5125},
                "values": {"bufferView": 0, "byteOffset": 44}}}
             ]}
            """.formatted(base64(bytes.array()));
        GltfDocument document = GltfDocument.read(input(json, Map.of()));
        assertThat(document.floats(0)).containsExactly(-1f);
        assertThat(document.floats(1)).containsExactly(-127f);
        assertThat(document.floats(2)).containsExactly(1f);
        assertThat(document.floats(3)).containsExactly(255f);
        assertThat(document.floats(4)).containsExactly(-1f);
        assertThat(document.floats(5)).containsExactly(-32767f);
        assertThat(document.floats(6)).containsExactly(1f);
        assertThat(document.floats(7)).containsExactly(65535f);
        assertThat(document.floats(8)).containsExactly(70_000_000f);
        assertThat(document.floats(9)).as("strided").containsExactly(1.5f, 2.5f);
        assertThat(document.floats(10)).as("no buffer view: zeros").containsExactly(0, 0, 0, 0);
        assertThat(document.count(10)).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> document.floats(11)).withMessageContaining("5124");
        assertThatIllegalArgumentException().isThrownBy(() -> document.floats(12)).withMessageContaining("VEC5");
        assertThatIllegalArgumentException().isThrownBy(() -> document.floats(13)).withMessageContaining("buffer 3");
        assertThat(document.floats(14)).as("sparse, over zeros").containsExactly(0, 9.5f, 0);
        assertThatIllegalArgumentException().isThrownBy(() -> document.floats(21)).withMessageContaining("past");
        assertThatIllegalArgumentException().isThrownBy(() -> document.floats(22)).withMessageContaining("past");
        assertThat(document.indices(15)).as("32-bit, strided").containsExactly(70_000_000, 0x3FC00000);
        assertThat(document.indices(16)).containsExactly(0, 0);
        assertThat(document.indices(17)).containsExactly(255);
        assertThat(document.floats(18)).hasSize(16);
        assertThatIllegalArgumentException().isThrownBy(() -> document.floats(19)).withMessageContaining("buffer -1");
        assertThat(document.indices(20)).as("32-bit, packed").containsExactly(70_000_000);
        assertThat(GltfDocument.components("VEC4")).isEqualTo(4);
        assertThat(GltfDocument.components("MAT2")).isEqualTo(4);
        assertThat(GltfDocument.components("MAT3")).isEqualTo(9);
        assertThatIllegalArgumentException().isThrownBy(() -> document.get("accessors", 99))
            .withMessage("The document has no accessors 99");
        assertThatIllegalArgumentException().isThrownBy(() -> document.get("accessors", -1));
    }

    @Test
    void buffersFromFilesDataUrisAndBinaryChunks() throws IOException {
        byte[] four = {1, 2, 3, 4};
        String external = """
            {"asset": {"version": "2.0"}, "buffers": [{"uri": "my%20data.bin", "byteLength": 4}],
             "bufferViews": [{"buffer": 0, "byteLength": 4}],
             "accessors": [{"bufferView": 0, "componentType": 5121, "count": 4, "type": "SCALAR"}]}
            """;
        assertThat(GltfDocument.read(input(external, Map.of("my data.bin", four))).indices(0))
            .containsExactly(1, 2, 3, 4);
        String chunked = """
            {"asset": {"version": "2.0"}, "buffers": [{"byteLength": 4}],
             "bufferViews": [{"buffer": 0, "byteLength": 4}],
             "accessors": [{"bufferView": 0, "componentType": 5121, "count": 4, "type": "SCALAR"}]}
            """;
        assertThat(GltfDocument.read(input(glb(chunked, four, 2), Map.of())).indices(0)).containsExactly(1, 2, 3, 4);
        byte[] withOtherChunk = glb(chunked, four, 2);
        ByteBuffer extra = ByteBuffer.allocate(withOtherChunk.length + 12).order(ByteOrder.LITTLE_ENDIAN)
            .put(withOtherChunk).putInt(4).putInt(0x12345678).putInt(0); // an unknown chunk: skipped
        assertThat(GltfDocument.read(input(extra.array(), Map.of())).indices(0)).containsExactly(1, 2, 3, 4);
        assertThat(GltfDocument.decode("a%2Fb%20c")).isEqualTo("a/b c");
        assertThat(GltfDocument.decode("100%")).isEqualTo("100%");
    }

    @Test
    void whatCannotBeReadIsRefused() throws IOException {
        String chunked = "{\"asset\": {\"version\": \"2.0\"}, \"buffers\": [{\"byteLength\": 4}]}";
        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> GltfDocument.read(input(glb(chunked, null, 1), Map.of())))
            .withMessageContaining("version 1");
        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> GltfDocument.read(input(glb(chunked, null, 2), Map.of())))
            .withMessageContaining("without a URI");
        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> GltfDocument.read(input(chunked, Map.of())))
            .withMessageContaining("without a URI");
        byte[] cut = glb(chunked, new byte[] {1, 2, 3, 4}, 2);
        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> GltfDocument.read(input(java.util.Arrays.copyOf(cut, cut.length - 2), Map.of())))
            .withMessageContaining("past its end");
        ByteBuffer noJson = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546C67).putInt(2)
            .putInt(24).putInt(4).putInt(0x004E4942).putInt(0);
        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> GltfDocument.read(input(noJson.array(), Map.of())))
            .withMessageContaining("without its JSON chunk");
        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> GltfDocument.read(input("{\"asset\": {\"version\": \"1.0\"}}", Map.of())))
            .withMessageContaining("Only glTF 2");
        assertThatExceptionOfType(IOException.class).isThrownBy(() -> GltfDocument.read(input(
            "{\"asset\": {\"version\": \"2.0\"}, \"extensionsRequired\": [\"KHR_texture_transform\"]}", Map.of())))
            .withMessageContaining("requires extensions this importer does not read: [KHR_texture_transform]");
        assertThat(GltfDocument.read(input("{\"asset\": {\"version\": \"2.0\"}, \"extensionsRequired\": []}",
            Map.of())).list("meshes")).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> GltfDocument.read(input("[1]", Map.of())))
            .withMessageContaining("JSON object");
        String plainData = "{\"asset\": {\"version\": \"2\"}, \"buffers\": [{\"uri\": \"data:text/plain,abc\"}]}";
        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> GltfDocument.read(input(plainData, Map.of())))
            .withMessageContaining("base64");
        String noComma = "{\"asset\": {\"version\": \"2\"}, \"buffers\": [{\"uri\": \"data:abc\"}]}";
        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> GltfDocument.read(input(noComma, Map.of())));
        String missing = "{\"asset\": {\"version\": \"2\"}, \"buffers\": [{\"uri\": \"gone.bin\"}]}";
        assertThatExceptionOfType(FileNotFoundException.class)
            .isThrownBy(() -> GltfDocument.read(input(missing, Map.of())));
    }
}
