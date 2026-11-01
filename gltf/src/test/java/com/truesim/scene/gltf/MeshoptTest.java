package com.truesim.scene.gltf;

import static com.truesim.scene.gltf.GltfDocumentTest.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.meshoptimizer.MeshOptimizer;

/** Buffer views compressed with meshopt decode, every mode and filter: data encoded here with meshoptimizer. */
class MeshoptTest {

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] bytes(ByteBuffer buffer, long length) {
        byte[] out = new byte[(int) length];
        buffer.get(0, out);
        return out;
    }

    /** {@code count} vertices of {@code stride} bytes, encoded. */
    private static byte[] vertices(ByteBuffer vertices, int count, int stride) {
        ByteBuffer out = direct((int) MeshOptimizer.meshopt_encodeVertexBufferBound(count, stride));
        return bytes(out, MeshOptimizer.meshopt_encodeVertexBuffer(out, vertices, count, stride));
    }

    private static FloatBuffer floats(float... values) {
        FloatBuffer out = ByteBuffer.allocateDirect(4 * values.length).order(ByteOrder.nativeOrder()).asFloatBuffer();
        return out.put(values).flip();
    }

    private static IntBuffer ints(int... values) {
        IntBuffer out = ByteBuffer.allocateDirect(4 * values.length).order(ByteOrder.nativeOrder()).asIntBuffer();
        return out.put(values).flip();
    }

    @Test
    void everyModeAndFilterDecodes() throws IOException {
        // Plain floats: two vec3 positions.
        ByteBuffer plain = direct(24);
        plain.asFloatBuffer().put(new float[] {1, 2, 3, 4, 5, 6});
        byte[] plainEncoded = vertices(plain, 2, 12);
        // Triangles and an index sequence, 16-bit.
        IntBuffer triangles = ints(0, 1, 2, 2, 1, 3);
        ByteBuffer triangleOut = direct((int) MeshOptimizer.meshopt_encodeIndexBufferBound(6, 4));
        byte[] trianglesEncoded = bytes(triangleOut, MeshOptimizer.meshopt_encodeIndexBuffer(triangleOut, triangles));
        IntBuffer sequence = ints(0, 1, 2, 3);
        ByteBuffer sequenceOut = direct((int) MeshOptimizer.meshopt_encodeIndexSequenceBound(4, 4));
        byte[] sequenceEncoded = bytes(sequenceOut, MeshOptimizer.meshopt_encodeIndexSequence(sequenceOut, sequence));
        // Filtered: an octahedral normal (4 x int8), a quaternion and a color (4 x int16), an exponential float.
        ByteBuffer oct = direct(4);
        MeshOptimizer.meshopt_encodeFilterOct(oct, 1, 4, 8, floats(0, 0, 1, 0));
        ByteBuffer quat = direct(8);
        MeshOptimizer.meshopt_encodeFilterQuat(quat, 1, 8, 12, floats(0, 0, 0, 1));
        ByteBuffer color = direct(8);
        MeshOptimizer.meshopt_encodeFilterColor(color, 1, 8, 12, floats(1, 0.5f, 0.25f, 1));
        ByteBuffer exp = direct(4);
        MeshOptimizer.meshopt_encodeFilterExp(exp, 1, 4, 15, floats(2.5f), MeshOptimizer.meshopt_EncodeExpSeparate);

        byte[][] parts = {plainEncoded, trianglesEncoded, sequenceEncoded, vertices(oct, 1, 4), vertices(quat, 1, 8),
            vertices(color, 1, 8), vertices(exp, 1, 4)};
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        int[] offsets = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            offsets[i] = buffer.position();
            buffer.put(parts[i]);
        }
        byte[] data = java.util.Arrays.copyOf(buffer.array(), buffer.position());
        String json = """
            {"asset": {"version": "2.0"}, "extensionsRequired": ["EXT_meshopt_compression"],
             "buffers": [{"uri": "data.bin", "byteLength": %d}, {"byteLength": 4096,
               "extensions": {"KHR_meshopt_compression": {"fallback": true}}}],
             "bufferViews": [%s, %s, %s, %s, %s, %s, %s, %s, %s, %s],
             "accessors": [
               {"bufferView": 0, "componentType": 5126, "count": 2, "type": "VEC3"},
               {"bufferView": 1, "componentType": 5123, "count": 6, "type": "SCALAR"},
               {"bufferView": 2, "componentType": 5123, "count": 4, "type": "SCALAR"},
               {"bufferView": 3, "componentType": 5120, "normalized": true, "count": 1, "type": "VEC4"},
               {"bufferView": 4, "componentType": 5122, "normalized": true, "count": 1, "type": "VEC4"},
               {"bufferView": 5, "componentType": 5123, "normalized": true, "count": 1, "type": "VEC4"},
               {"bufferView": 6, "componentType": 5126, "count": 1, "type": "SCALAR"}]}
            """.formatted(data.length,
                view("KHR", offsets[0], parts[0].length, 12, 2, "ATTRIBUTES", null),
                view("EXT", offsets[1], parts[1].length, 2, 6, "TRIANGLES", null),
                view("KHR", offsets[2], parts[2].length, 2, 4, "INDICES", null),
                view("KHR", offsets[3], parts[3].length, 4, 1, "ATTRIBUTES", "OCTAHEDRAL"),
                view("KHR", offsets[4], parts[4].length, 8, 1, "ATTRIBUTES", "QUATERNION"),
                view("KHR", offsets[5], parts[5].length, 8, 1, "ATTRIBUTES", "COLOR"),
                view("KHR", offsets[6], parts[6].length, 4, 1, "ATTRIBUTES", "EXPONENTIAL"),
                view("KHR", offsets[0], parts[0].length, 12, 2, "POINTS", null),
                view("KHR", offsets[0], parts[0].length, 12, 2, "ATTRIBUTES", "BLUR"),
                view("KHR", offsets[0], 3, 12, 2, "ATTRIBUTES", null));
        GltfDocument document = GltfDocument.read(input(json, Map.of("data.bin", data)));
        assertThat(document.floats(0)).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(document.view(0)).as("decoded once, then kept").isEqualTo(document.view(0));
        assertThat(document.indices(1)).containsExactly(0, 1, 2, 2, 1, 3);
        assertThat(document.indices(2)).containsExactly(0, 1, 2, 3);
        float[] normal = document.floats(3);
        assertThat(normal[2]).isCloseTo(1, within(0.02f));
        assertThat(document.floats(4)[3]).as("the quaternion's w").isCloseTo(1, within(0.01f));
        assertThat(document.floats(5)[1]).as("the color's green").isCloseTo(0.5f, within(0.01f));
        assertThat(document.floats(6)[0]).isCloseTo(2.5f, within(0.001f));
        assertThatIllegalArgumentException().isThrownBy(() -> document.view(7)).withMessageContaining("mode");
        assertThatIllegalArgumentException().isThrownBy(() -> document.view(8)).withMessageContaining("filter");
        assertThatIllegalArgumentException().isThrownBy(() -> document.view(9)).withMessageContaining("does not decode");
    }

    private static String view(String prefix, int offset, int length, int stride, int count, String mode,
        String filter) {
        return """
            {"buffer": 1, "byteLength": %d, "extensions": {"%s_meshopt_compression": {"buffer": 0, "byteOffset": %d,
              "byteLength": %d, "byteStride": %d, "count": %d, "mode": "%s"%s}}}"""
            .formatted(stride * count, prefix, offset, length, stride, count, mode,
                filter == null ? "" : ", \"filter\": \"" + filter + "\"");
    }
}
