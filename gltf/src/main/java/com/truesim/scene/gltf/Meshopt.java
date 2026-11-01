package com.truesim.scene.gltf;

import com.truesim.annotations.AllowAllocation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import org.lwjgl.util.meshoptimizer.MeshOptimizer;

/**
 * Decodes buffer views compressed with {@code KHR_meshopt_compression} (or its draft, {@code EXT_meshopt_compression})
 * through meshoptimizer: vertex attributes, triangle indices and index sequences, with the octahedral, quaternion,
 * exponential and color filters undone.
 */
@AllowAllocation("decoding when assets load")
final class Meshopt {

    /** The names the extension goes by. */
    static final String KHR = "KHR_meshopt_compression";
    static final String EXT = "EXT_meshopt_compression";

    private Meshopt() {
    }

    /** {@code view}'s compression object, or an empty one where it is not compressed. */
    static Map<String, Object> compression(Map<String, Object> view) {
        Map<String, Object> khr = Gltf.extension(view, KHR);
        return khr.isEmpty() ? Gltf.extension(view, EXT) : khr;
    }

    /** The bytes {@code compression} (a buffer view's) decodes to, reading {@code source}, the compressed bytes. */
    static byte[] decode(Map<String, Object> compression, ByteBuffer source) {
        int count = GltfDocument.integer(compression, "count", 0);
        int stride = GltfDocument.integer(compression, "byteStride", 0);
        String mode = String.valueOf(compression.get("mode"));
        String filter = String.valueOf(compression.getOrDefault("filter", "NONE"));
        ByteBuffer in = ByteBuffer.allocateDirect(source.remaining()).put(source.duplicate()).flip();
        ByteBuffer out = ByteBuffer.allocateDirect(count * stride).order(ByteOrder.LITTLE_ENDIAN);
        int result = switch (mode) {
            case "ATTRIBUTES" -> MeshOptimizer.meshopt_decodeVertexBuffer(out, count, stride, in);
            case "TRIANGLES" -> MeshOptimizer.meshopt_decodeIndexBuffer(out, count, stride, in);
            case "INDICES" -> MeshOptimizer.meshopt_decodeIndexSequence(out, count, stride, in);
            default -> throw new IllegalArgumentException("Not a meshopt mode: " + mode);
        };
        if (result != 0) {
            throw new IllegalArgumentException("Meshopt data that does not decode (" + result + "), mode " + mode);
        }
        switch (filter) {
            case "NONE" -> {
                // as decoded
            }
            case "OCTAHEDRAL" -> MeshOptimizer.meshopt_decodeFilterOct(out, count, stride);
            case "QUATERNION" -> MeshOptimizer.meshopt_decodeFilterQuat(out, count, stride);
            case "EXPONENTIAL" -> MeshOptimizer.meshopt_decodeFilterExp(out, count, stride);
            case "COLOR" -> MeshOptimizer.meshopt_decodeFilterColor(out, count, stride);
            default -> throw new IllegalArgumentException("Not a meshopt filter: " + filter);
        }
        byte[] bytes = new byte[count * stride];
        out.get(0, bytes);
        return bytes;
    }
}
