package com.truesim.scene.gltf;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * A glTF 2.0 document, JSON ({@code .gltf}) or binary ({@code .glb}), with its buffers read: external ones relative to
 * the file, embedded ones from data URIs or the binary chunk. Reads accessors as floats or indices.
 */
@AllowAllocation("reading documents when assets load")
final class GltfDocument {

    private static final int MAGIC = 0x46546C67; // "glTF"
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;
    private static final String DATA = "data:";
    /** The extensions read, besides every {@code KHR_materials_*}: a file requiring others fails. */
    static final java.util.Set<String> READ = java.util.Set.of("KHR_texture_transform", "KHR_lights_punctual",
        "KHR_node_visibility", "KHR_mesh_quantization", "KHR_xmp_json_ld");

    private final Map<String, Object> json;
    private final List<byte[]> buffers = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private GltfDocument(Object json) {
        if (!(json instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("A glTF document is a JSON object");
        }
        this.json = (Map<String, Object>) json;
    }

    /** The document {@code input} holds, its buffers read. */
    static GltfDocument read(AssetInput input) throws IOException {
        byte[] main;
        try (InputStream in = input.open()) {
            main = in.readAllBytes();
        }
        ByteBuffer bytes = ByteBuffer.wrap(main).order(ByteOrder.LITTLE_ENDIAN);
        byte[] bin = null;
        GltfDocument document;
        if (main.length >= 12 && bytes.getInt(0) == MAGIC) {
            if (bytes.getInt(4) != 2) {
                throw new IOException("Only glTF 2 binaries are read: version " + bytes.getInt(4));
            }
            String text = null;
            int at = 12;
            while (at + 8 <= main.length) {
                int length = bytes.getInt(at);
                int type = bytes.getInt(at + 4);
                if (at + 8 + length > main.length) {
                    throw new IOException("A glTF binary's chunk runs past its end");
                }
                if (type == JSON_CHUNK) {
                    text = new String(main, at + 8, length, StandardCharsets.UTF_8);
                } else if (type == BIN_CHUNK) {
                    bin = new byte[length];
                    System.arraycopy(main, at + 8, bin, 0, length);
                }
                at += 8 + length;
            }
            if (text == null) {
                throw new IOException("A glTF binary without its JSON chunk");
            }
            document = new GltfDocument(Json.parse(text));
        } else {
            document = new GltfDocument(Json.parse(new String(main, StandardCharsets.UTF_8)));
        }
        Map<String, Object> asset = object(document.json, "asset");
        if (!String.valueOf(asset.get("version")).startsWith("2")) {
            throw new IOException("Only glTF 2 is read: version " + asset.get("version"));
        }
        Object required = document.json.get("extensionsRequired");
        if (required instanceof List<?> extensions) {
            List<Object> unread = new ArrayList<>(extensions);
            unread.removeIf(name -> READ.contains(name) || String.valueOf(name).startsWith("KHR_materials_"));
            if (!unread.isEmpty()) {
                throw new IOException("The file requires extensions this importer does not read yet: " + unread);
            }
        }
        for (Map<String, Object> buffer : document.list("buffers")) {
            Object uri = buffer.get("uri");
            byte[] data;
            if (uri == null) {
                if (bin == null) {
                    throw new IOException("A buffer without a URI in a file without a binary chunk");
                }
                data = bin;
            } else {
                data = document.bytes((String) uri, input);
            }
            document.buffers.add(data);
        }
        return document;
    }

    /** The bytes {@code uri} names: a data URI's, or a file's relative to the document. */
    byte[] bytes(String uri, AssetInput input) throws IOException {
        if (uri.startsWith(DATA)) {
            int comma = uri.indexOf(',');
            if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) {
                throw new IOException("Only base64 data URIs are read: " + uri.substring(0, Math.min(40, uri.length())));
            }
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        }
        try (InputStream in = input.open(decode(uri))) {
            return in.readAllBytes();
        }
    }

    /** A relative URI's path: {@code %20} and the like decoded. */
    static String decode(String uri) {
        if (uri.indexOf('%') < 0) {
            return uri;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '%' && i + 2 < uri.length()) {
                out.write(Integer.parseInt(uri, i + 1, i + 3, 16));
                i += 2;
            } else {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The document's JSON object. */
    Map<String, Object> json() {
        return json;
    }

    /** The objects of the top-level array {@code name}; empty if there is none. */
    List<Map<String, Object>> list(String name) {
        return list(json, name);
    }

    /** Object {@code index} of the top-level array {@code name}. */
    Map<String, Object> get(String name, int index) {
        List<Map<String, Object>> list = list(name);
        if (index < 0 || index >= list.size()) {
            throw new IllegalArgumentException("The document has no " + name + " " + index);
        }
        return list.get(index);
    }

    /** The objects of {@code owner}'s array {@code name}; empty if there is none. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> owner, String name) {
        return (List<Map<String, Object>>) owner.getOrDefault(name, List.of());
    }

    /** {@code owner}'s object {@code name}; empty if there is none. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> owner, String name) {
        return (Map<String, Object>) owner.getOrDefault(name, Map.of());
    }

    /** {@code owner}'s number {@code name}, or {@code fallback}. */
    static double number(Map<String, Object> owner, String name, double fallback) {
        Object value = owner.get(name);
        return value == null ? fallback : (Double) value;
    }

    /** {@code owner}'s integer {@code name}, or {@code fallback}. */
    static int integer(Map<String, Object> owner, String name, int fallback) {
        return (int) number(owner, name, fallback);
    }

    /** {@code owner}'s numbers {@code name}, or {@code fallback}. */
    @SuppressWarnings("unchecked")
    static double[] numbers(Map<String, Object> owner, String name, double... fallback) {
        List<Double> values = (List<Double>) owner.get(name);
        if (values == null) {
            return fallback;
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** The bytes of buffer view {@code index}. */
    ByteBuffer view(int index) {
        Map<String, Object> view = get("bufferViews", index);
        int buffer = integer(view, "buffer", 0);
        if (buffer < 0 || buffer >= buffers.size()) {
            throw new IllegalArgumentException("Buffer view " + index + " reads buffer " + buffer + ", which is not");
        }
        return ByteBuffer.wrap(buffers.get(buffer), integer(view, "byteOffset", 0), integer(view, "byteLength", 0))
            .slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** How many components a value of accessor type {@code type} has. */
    static int components(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT2" -> 4;
            case "MAT3" -> 9;
            case "MAT4" -> 16;
            default -> throw new IllegalArgumentException("Not an accessor type: " + type);
        };
    }

    private static int size(int componentType) {
        return switch (componentType) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IllegalArgumentException("Not a component type: " + componentType);
        };
    }

    /** How many values accessor {@code index} holds. */
    int count(int index) {
        return integer(get("accessors", index), "count", 0);
    }

    /** Accessor {@code index}'s values as floats, normalized integers mapped to 0..1 or -1..1. */
    float[] floats(int index) {
        Map<String, Object> accessor = get("accessors", index);
        int components = components((String) accessor.get("type"));
        int count = integer(accessor, "count", 0);
        int type = integer(accessor, "componentType", 5126);
        boolean normalized = Boolean.TRUE.equals(accessor.get("normalized"));
        float[] out = new float[count * components];
        if (accessor.containsKey("bufferView")) { // else all zeros, as glTF says
            int viewIndex = integer(accessor, "bufferView", 0);
            int stride = integer(get("bufferViews", viewIndex), "byteStride", 0);
            read(view(viewIndex), integer(accessor, "byteOffset", 0), stride, type, normalized, components, count, out,
                null);
        }
        if (accessor.containsKey("sparse")) {
            Map<String, Object> sparse = object(accessor, "sparse");
            int changed = integer(sparse, "count", 0);
            Map<String, Object> indices = object(sparse, "indices");
            float[] at = new float[changed];
            read(view(integer(indices, "bufferView", 0)), integer(indices, "byteOffset", 0), 0,
                integer(indices, "componentType", 5125), false, 1, changed, at, null);
            Map<String, Object> values = object(sparse, "values");
            read(view(integer(values, "bufferView", 0)), integer(values, "byteOffset", 0), 0, type, normalized,
                components, changed, out, at);
        }
        return out;
    }

    /**
     * Reads {@code count} values of {@code components} components of {@code type} from {@code view} at
     * {@code offset}, {@code stride} bytes apart (0: packed), into {@code out}: value {@code i} at index {@code i}, or
     * at index {@code targets[i]} where given (a sparse accessor's).
     */
    private static void read(ByteBuffer view, int offset, int stride, int type, boolean normalized, int components,
        int count, float[] out, float[] targets) {
        int size = size(type);
        int step = stride > 0 ? stride : size * components;
        for (int i = 0; i < count; i++) {
            int at = offset + i * step;
            int to = (targets == null ? i : (int) targets[i]) * components;
            if (to < 0 || to + components > out.length) {
                throw new IllegalArgumentException("A sparse index past the accessor's end: " + to / components);
            }
            for (int c = 0; c < components; c++) {
                out[to + c] = read(view, at + c * size, type, normalized);
            }
        }
    }

    private static float read(ByteBuffer view, int at, int type, boolean normalized) {
        return switch (type) {
            case 5120 -> normalized ? Math.max(view.get(at) / 127f, -1) : view.get(at);
            case 5121 -> normalized ? (view.get(at) & 0xFF) / 255f : view.get(at) & 0xFF;
            case 5122 -> normalized ? Math.max(view.getShort(at) / 32767f, -1) : view.getShort(at);
            case 5123 -> normalized ? (view.getShort(at) & 0xFFFF) / 65535f : view.getShort(at) & 0xFFFF;
            case 5125 -> view.getInt(at);
            default -> view.getFloat(at);
        };
    }

    /** Accessor {@code index}'s values as indices (unsigned bytes, shorts or ints). */
    int[] indices(int index) {
        Map<String, Object> accessor = get("accessors", index);
        if (integer(accessor, "componentType", 5125) != 5125 || !accessor.containsKey("bufferView")) {
            float[] values = floats(index); // bytes and shorts: exact as floats
            int[] out = new int[values.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (int) values[i];
            }
            return out;
        }
        int[] out = new int[integer(accessor, "count", 0)]; // ints may pass a float's 24 bits: read as ints
        int viewIndex = integer(accessor, "bufferView", 0);
        ByteBuffer view = view(viewIndex);
        int stride = integer(get("bufferViews", viewIndex), "byteStride", 0);
        int step = stride > 0 ? stride : 4;
        int offset = integer(accessor, "byteOffset", 0);
        for (int i = 0; i < out.length; i++) {
            out[i] = view.getInt(offset + i * step);
        }
        return out;
    }

    /** The bytes of image {@code index}: from its buffer view or data URI; null for an external file. */
    byte[] embeddedImage(int index, AssetInput input) throws IOException {
        Map<String, Object> image = get("images", index);
        if (image.containsKey("bufferView")) {
            ByteBuffer view = view(integer(image, "bufferView", 0));
            byte[] out = new byte[view.remaining()];
            view.get(out);
            return out;
        }
        Object uri = image.get("uri");
        if (uri == null) {
            throw new IllegalArgumentException("Image " + index + " has neither a URI nor a buffer view");
        }
        return ((String) uri).startsWith(DATA) ? bytes((String) uri, input) : null;
    }
}
