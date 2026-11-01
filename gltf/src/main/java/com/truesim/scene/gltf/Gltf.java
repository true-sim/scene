package com.truesim.scene.gltf;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import com.truesim.scene.assets.AssetPath;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.render.ImageImporter;
import com.truesim.scene.render.LineData;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.ModelData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Imports glTF 2.0 files ({@code .gltf} with their buffers and images, and {@code .glb}) into the engine's asset types,
 * one asset per part, addressed by path fragments: the file itself is its default scene as a {@link ModelData}
 * ({@code car.gltf}), {@code #mesh/N} a mesh with a submesh per primitive, {@code #material/N} a material of the
 * metallic-roughness type, {@code #image/N} an embedded image ({@code #image/N/linear} read as data). External images
 * are textures at their own paths, read by the image importer ({@code wheel.png}, {@code wheel_normal.png#linear}).
 *
 * <pre>{@code
 * new Gltf(paths).register();                                     // gltf and glb, for models, meshes, materials, images
 * paths.importers().add(RenderAssets.TEXTURE, "png", ImageImporter.INSTANCE);
 * AssetRef<ModelData> car = paths.ref(RenderAssets.MODEL, "game://models/car.glb");
 * }</pre>
 *
 * <p>Meshes are built into cluster hierarchies; a mesh without normals gets smooth ones. Triangles, strips and fans are
 * read, sparse accessors too; points and lines are {@code #lines/M/P} assets of their own ({@link LineData}), named by
 * the model's nodes beside their meshes; skins and animations are not read yet. Extensions: every
 * {@code KHR_materials_*} as a feature of the material (clearcoat by the engine's names, the others by glTF's; emissive
 * strength scales the emissive color; spec-gloss also as a metallic-roughness approximation), {@code
 * KHR_texture_transform} per texture, {@code KHR_lights_punctual} and {@code KHR_node_visibility} on the model,
 * {@code KHR_mesh_quantization}. A file requiring another extension fails, as glTF asks. A file is read once while its parts load
 * close together (a soft cache). Imports run in the background and are thread-safe.
 */
@AllowAllocation("importing when assets load")
public final class Gltf {

    private static final List<String> EXTENSIONS = List.of("gltf", "glb");

    private final PathAssets paths;
    private final Map<AssetPath, SoftReference<GltfDocument>> documents = new ConcurrentHashMap<>();

    /** Imports for {@code paths}, which its references to meshes, materials and images go through. */
    public Gltf(PathAssets paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    /** Registers the importers for models, meshes, materials and embedded images, for {@code .gltf} and {@code .glb}. */
    public Gltf register() {
        for (String extension : EXTENSIONS) {
            paths.importers().add(RenderAssets.MODEL, extension, this::model)
                .add(RenderAssets.MESH, extension, this::mesh)
                .add(RenderAssets.MATERIAL, extension, this::material)
                .add(RenderAssets.TEXTURE, extension, this::image)
                .add(RenderAssets.LINES, extension, this::lines);
        }
        return this;
    }

    /** The document {@code input}'s file holds: read once while it is softly kept. */
    private GltfDocument document(AssetInput input) throws IOException {
        AssetPath file = path(input).file();
        SoftReference<GltfDocument> kept = documents.get(file);
        GltfDocument document = kept == null ? null : kept.get();
        if (document == null) {
            document = GltfDocument.read(input);
            documents.put(file, new SoftReference<>(document));
        }
        return document;
    }

    /** Where the part is: the importers run only for path-addressed assets, so it has a path. */
    private static AssetPath path(AssetInput input) {
        return Objects.requireNonNull(input.path(), "glTF parts are addressed by path");
    }

    /** The index a fragment {@code kind/N} names, checked against {@code kind}. */
    private static int index(AssetPath path, String kind) {
        String[] parts = path.fragment().split("/");
        if (parts.length < 2 || !parts[0].equals(kind)) {
            throw new IllegalArgumentException("Not a glTF " + kind + ": " + path + " (expected #" + kind + "/N)");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a glTF " + kind + ": " + path + " (expected #" + kind + "/N)");
        }
    }

    // ---------------------------------------------------------------- models

    private ModelData model(AssetInput input) throws IOException {
        AssetPath file = path(input).file();
        if (!path(input).fragment().isEmpty()) {
            throw new IllegalArgumentException("A glTF model is the file, without a fragment: " + path(input));
        }
        GltfDocument document = document(input);
        List<Map<String, Object>> nodes = document.list("nodes");
        List<Integer> roots = new ArrayList<>();
        List<Map<String, Object>> scenes = document.list("scenes");
        if (!scenes.isEmpty()) {
            Map<String, Object> scene = document.get("scenes", GltfDocument.integer(document.json(), "scene", 0));
            for (double root : GltfDocument.numbers(scene, "nodes")) {
                roots.add((int) root);
            }
        } else {
            boolean[] child = new boolean[nodes.size()];
            for (Map<String, Object> node : nodes) {
                for (double c : GltfDocument.numbers(node, "children")) {
                    child[(int) c] = true;
                }
            }
            for (int i = 0; i < nodes.size(); i++) {
                if (!child[i]) {
                    roots.add(i);
                }
            }
        }
        List<ModelData.Node> out = new ArrayList<>();
        for (int root : roots) {
            add(document, file, root, -1, out, 0);
        }
        List<ModelData.Light> lights = new ArrayList<>();
        for (Map<String, Object> light : GltfDocument.list(extension(document.json(), "KHR_lights_punctual"),
            "lights")) {
            double[] color = GltfDocument.numbers(light, "color", 1, 1, 1);
            Map<String, Object> spot = GltfDocument.object(light, "spot");
            lights.add(new ModelData.Light(String.valueOf(light.getOrDefault("name", "")),
                ModelData.LightKind.valueOf(String.valueOf(light.get("type")).toUpperCase(java.util.Locale.ROOT)),
                color[0], color[1], color[2], GltfDocument.number(light, "intensity", 1),
                GltfDocument.number(light, "range", 0), GltfDocument.number(spot, "innerConeAngle", 0),
                GltfDocument.number(spot, "outerConeAngle", Math.PI / 4)));
        }
        return new ModelData(out, lights);
    }

    private void add(GltfDocument document, AssetPath file, int index, int parent, List<ModelData.Node> out,
        int depth) {
        if (depth > 256) {
            throw new IllegalArgumentException("The node tree is deeper than 256 or has a cycle: node " + index);
        }
        Map<String, Object> node = document.get("nodes", index);
        double[] t = GltfDocument.numbers(node, "translation", 0, 0, 0);
        double[] r = GltfDocument.numbers(node, "rotation", 0, 0, 0, 1);
        double[] s = GltfDocument.numbers(node, "scale", 1, 1, 1);
        if (node.containsKey("matrix")) {
            double[][] trs = decompose(GltfDocument.numbers(node, "matrix"));
            t = trs[0];
            r = trs[1];
            s = trs[2];
        }
        AssetRef<MeshData> mesh = null;
        List<AssetRef<MaterialData>> materials = new ArrayList<>();
        List<AssetRef<LineData>> lines = new ArrayList<>();
        if (node.containsKey("mesh")) {
            int meshIndex = GltfDocument.integer(node, "mesh", 0);
            List<Map<String, Object>> primitives = GltfDocument.list(document.get("meshes", meshIndex), "primitives");
            for (int p = 0; p < primitives.size(); p++) {
                Map<String, Object> primitive = primitives.get(p);
                if (triangles(primitive)) {
                    materials.add(material(file, primitive));
                } else {
                    lines.add(paths.ref(RenderAssets.LINES, file.fragment("lines/" + meshIndex + "/" + p)));
                }
            }
            if (!materials.isEmpty()) {
                mesh = paths.ref(RenderAssets.MESH, file.fragment("mesh/" + meshIndex));
            }
        }
        String name = String.valueOf(node.getOrDefault("name", ""));
        int at = out.size();
        boolean visible = !Boolean.FALSE.equals(extension(node, "KHR_node_visibility").get("visible"));
        int light = GltfDocument.integer(extension(node, "KHR_lights_punctual"), "light", -1);
        out.add(new ModelData.Node(name, parent, t[0], t[1], t[2], r[0], r[1], r[2], r[3], s[0], s[1], s[2], mesh,
            materials, visible, light, lines));
        for (double child : GltfDocument.numbers(node, "children")) {
            add(document, file, (int) child, at, out, depth + 1);
        }
    }

    /** The material {@code primitive} names, or the default. */
    private AssetRef<MaterialData> material(AssetPath file, Map<String, Object> primitive) {
        return primitive.containsKey("material")
            ? paths.ref(RenderAssets.MATERIAL, file.fragment("material/" + GltfDocument.integer(primitive, "material",
                0)))
            : MaterialData.DEFAULT.ref();
    }

    /** Whether {@code primitive} holds triangles, a strip or a fan: a mesh's; else points or lines. */
    private static boolean triangles(Map<String, Object> primitive) {
        return GltfDocument.integer(primitive, "mode", 4) >= 4;
    }

    /** {@code owner}'s extension {@code name}'s object; empty if it has none. */
    static Map<String, Object> extension(Map<String, Object> owner, String name) {
        return GltfDocument.object(GltfDocument.object(owner, "extensions"), name);
    }

    /** A column-major 4x4 matrix as translation, rotation (a unit quaternion) and scale; no shear. */
    static double[][] decompose(double[] m) {
        double sx = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        double sy = Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]);
        double sz = Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);
        double determinant = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[4] * (m[1] * m[10] - m[2] * m[9])
            + m[8] * (m[1] * m[6] - m[2] * m[5]);
        if (determinant < 0) {
            sx = -sx; // a mirror: one axis flipped
        }
        double r00 = m[0] / sx;
        double r10 = m[1] / sx;
        double r20 = m[2] / sx;
        double r01 = m[4] / sy;
        double r11 = m[5] / sy;
        double r21 = m[6] / sy;
        double r02 = m[8] / sz;
        double r12 = m[9] / sz;
        double r22 = m[10] / sz;
        double[] q = new double[4];
        double trace = r00 + r11 + r22;
        if (trace > 0) {
            double k = 0.5 / Math.sqrt(trace + 1);
            q[3] = 0.25 / k;
            q[0] = (r21 - r12) * k;
            q[1] = (r02 - r20) * k;
            q[2] = (r10 - r01) * k;
        } else if (r00 > r11 && r00 > r22) {
            double k = 2 * Math.sqrt(1 + r00 - r11 - r22);
            q[3] = (r21 - r12) / k;
            q[0] = 0.25 * k;
            q[1] = (r01 + r10) / k;
            q[2] = (r02 + r20) / k;
        } else if (r11 > r22) {
            double k = 2 * Math.sqrt(1 + r11 - r00 - r22);
            q[3] = (r02 - r20) / k;
            q[0] = (r01 + r10) / k;
            q[1] = 0.25 * k;
            q[2] = (r12 + r21) / k;
        } else {
            double k = 2 * Math.sqrt(1 + r22 - r00 - r11);
            q[3] = (r10 - r01) / k;
            q[0] = (r02 + r20) / k;
            q[1] = (r12 + r21) / k;
            q[2] = 0.25 * k;
        }
        return new double[][] {{m[12], m[13], m[14]}, q, {sx, sy, sz}};
    }

    // ---------------------------------------------------------------- meshes

    private MeshData mesh(AssetInput input) throws IOException {
        GltfDocument document = document(input);
        int meshIndex = index(path(input), "mesh");
        List<Map<String, Object>> primitives = new ArrayList<>(GltfDocument.list(document.get("meshes", meshIndex),
            "primitives"));
        primitives.removeIf(primitive -> !triangles(primitive)); // points and lines are assets of their own
        if (primitives.isEmpty()) {
            throw new IllegalArgumentException("Mesh " + meshIndex + " has no triangles: " + path(input));
        }
        // Attributes every primitive has; positions are a must.
        List<VertexAttribute> attributes = new ArrayList<>(List.of(VertexAttribute.NORMAL, VertexAttribute.TANGENT,
            VertexAttribute.UV0, VertexAttribute.UV1, VertexAttribute.COLOR));
        for (Map<String, Object> primitive : primitives) {
            Map<String, Object> names = GltfDocument.object(primitive, "attributes");
            if (!names.containsKey("POSITION")) {
                throw new IllegalArgumentException("A primitive without positions: " + path(input));
            }
            attributes.removeIf(attribute -> !names.containsKey(source(attribute)));
        }
        attributes.add(0, VertexAttribute.NORMAL); // made smooth where missing
        List<VertexAttribute> format = attributes.stream().distinct().toList();
        int vertices = 0;
        for (Map<String, Object> primitive : primitives) {
            vertices += document.count(GltfDocument.integer(GltfDocument.object(primitive, "attributes"), "POSITION",
                0));
        }
        float[] positions = new float[3 * vertices];
        float[][] values = new float[format.size()][];
        for (int a = 0; a < values.length; a++) {
            values[a] = new float[width(format.get(a)) * vertices];
        }
        List<int[]> submeshes = new ArrayList<>();
        int base = 0;
        for (Map<String, Object> primitive : primitives) {
            Map<String, Object> names = GltfDocument.object(primitive, "attributes");
            float[] p = document.floats(GltfDocument.integer(names, "POSITION", 0));
            int count = p.length / 3;
            System.arraycopy(p, 0, positions, 3 * base, p.length);
            int[] triangles = triangles(document, primitive, count, path(input));
            for (int a = 0; a < values.length; a++) {
                VertexAttribute attribute = format.get(a);
                int width = width(attribute);
                float[] read = names.containsKey(source(attribute))
                    ? widen(document.floats(GltfDocument.integer(names, source(attribute), 0)), count, width)
                    : smoothNormals(p, triangles);
                System.arraycopy(read, 0, values[a], width * base, width * count);
            }
            int[] offset = new int[triangles.length];
            for (int i = 0; i < offset.length; i++) {
                offset[i] = triangles[i] + base;
            }
            submeshes.add(offset);
            base += count;
        }
        MeshBuilder builder = new MeshBuilder(VertexFormat.of(format.toArray(new VertexAttribute[0])), vertices)
            .positions(positions);
        for (int a = 0; a < values.length; a++) {
            builder.attribute(format.get(a), values[a]);
        }
        for (int[] submesh : submeshes) {
            builder.submesh(submesh);
        }
        return builder.buildHierarchy();
    }

    /** glTF's attribute name for {@code attribute}. */
    private static String source(VertexAttribute attribute) {
        return switch (attribute) {
            case NORMAL -> "NORMAL";
            case TANGENT -> "TANGENT";
            case UV0 -> "TEXCOORD_0";
            case UV1 -> "TEXCOORD_1";
            default -> "COLOR_0";
        };
    }

    /** How many floats per vertex the mesh builder takes for {@code attribute}. */
    private static int width(VertexAttribute attribute) {
        return switch (attribute) {
            case NORMAL -> 3;
            case UV0, UV1 -> 2;
            default -> 4; // tangents, colors
        };
    }

    /** {@code values} of {@code count} vertices as {@code width} floats each: RGB colors get alpha 1. */
    private static float[] widen(float[] values, int count, int width) {
        int has = values.length / Math.max(1, count);
        if (has == width) {
            return values;
        }
        float[] out = new float[count * width];
        for (int i = 0; i < count; i++) {
            System.arraycopy(values, i * has, out, i * width, has);
            out[i * width + width - 1] = 1;
        }
        return out;
    }

    /** The primitive's triangles: its indices or its vertices in order, strips and fans unrolled. */
    private static int[] triangles(GltfDocument document, Map<String, Object> primitive, int count, AssetPath path) {
        int[] indices = vertices(document, primitive, count);
        int mode = GltfDocument.integer(primitive, "mode", 4);
        return switch (mode) {
            case 5 -> strip(indices);
            case 6 -> fan(indices);
            default -> {
                if (mode != 4) {
                    throw new IllegalArgumentException("Not a mode of glTF: " + mode + ": " + path);
                }
                yield indices;
            }
        };
    }

    /** The primitive's vertices in order, or its indices. */
    private static int[] vertices(GltfDocument document, Map<String, Object> primitive, int count) {
        if (primitive.containsKey("indices")) {
            return document.indices(GltfDocument.integer(primitive, "indices", 0));
        }
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = i;
        }
        return indices;
    }

    // ---------------------------------------------------------------- points and lines

    private LineData lines(AssetInput input) throws IOException {
        GltfDocument document = document(input);
        AssetPath path = path(input);
        String[] parts = path.fragment().split("/");
        if (parts.length != 3 || !parts[0].equals("lines")) {
            throw new IllegalArgumentException("Not glTF points or lines: " + path + " (expected #lines/M/P)");
        }
        int meshIndex;
        int primitiveIndex;
        try {
            meshIndex = Integer.parseInt(parts[1]);
            primitiveIndex = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not glTF points or lines: " + path + " (expected #lines/M/P)");
        }
        List<Map<String, Object>> primitives = GltfDocument.list(document.get("meshes", meshIndex), "primitives");
        if (primitiveIndex < 0 || primitiveIndex >= primitives.size() || triangles(primitives.get(primitiveIndex))) {
            throw new IllegalArgumentException("Not a primitive of points or lines: " + path);
        }
        Map<String, Object> primitive = primitives.get(primitiveIndex);
        Map<String, Object> names = GltfDocument.object(primitive, "attributes");
        if (!names.containsKey("POSITION")) {
            throw new IllegalArgumentException("A primitive without positions: " + path);
        }
        float[] positions = document.floats(GltfDocument.integer(names, "POSITION", 0));
        int count = positions.length / 3;
        float[] colors = names.containsKey("COLOR_0")
            ? widen(document.floats(GltfDocument.integer(names, "COLOR_0", 0)), count, 4) : new float[0];
        int[] order = vertices(document, primitive, count);
        int mode = GltfDocument.integer(primitive, "mode", 4);
        int[] indices = switch (mode) {
            case 0, 1 -> order;
            case 2 -> lineLoop(order);
            default -> lineStrip(order);
        };
        return new LineData(mode == 0 ? LineData.Kind.POINTS : LineData.Kind.LINES, positions, colors,
            mode == 1 ? java.util.Arrays.copyOf(indices, indices.length & ~1) : indices,
            material(path.file(), primitive));
    }

    /** A loop's segments: each vertex to the next, the last back to the first. */
    private static int[] lineLoop(int[] order) {
        int[] out = new int[2 * order.length];
        for (int i = 0; i < order.length; i++) {
            out[2 * i] = order[i];
            out[2 * i + 1] = order[(i + 1) % order.length];
        }
        return out;
    }

    /** A strip's segments: each vertex to the next. */
    private static int[] lineStrip(int[] order) {
        int[] out = new int[2 * Math.max(0, order.length - 1)];
        for (int i = 0; i + 1 < order.length; i++) {
            out[2 * i] = order[i];
            out[2 * i + 1] = order[i + 1];
        }
        return out;
    }

    private static int[] strip(int[] indices) {
        int[] out = new int[3 * Math.max(0, indices.length - 2)];
        for (int i = 0; i + 2 < indices.length; i++) {
            boolean even = i % 2 == 0;
            out[3 * i] = indices[i];
            out[3 * i + 1] = indices[even ? i + 1 : i + 2];
            out[3 * i + 2] = indices[even ? i + 2 : i + 1];
        }
        return out;
    }

    private static int[] fan(int[] indices) {
        int[] out = new int[3 * Math.max(0, indices.length - 2)];
        for (int i = 1; i + 1 < indices.length; i++) {
            out[3 * (i - 1)] = indices[0];
            out[3 * (i - 1) + 1] = indices[i];
            out[3 * (i - 1) + 2] = indices[i + 1];
        }
        return out;
    }

    /** Smooth normals of {@code positions} over {@code triangles}: each vertex's faces, weighted by their area. */
    static float[] smoothNormals(float[] positions, int[] triangles) {
        float[] normals = new float[positions.length];
        for (int t = 0; t + 2 < triangles.length; t += 3) {
            int a = 3 * triangles[t];
            int b = 3 * triangles[t + 1];
            int c = 3 * triangles[t + 2];
            float ux = positions[b] - positions[a];
            float uy = positions[b + 1] - positions[a + 1];
            float uz = positions[b + 2] - positions[a + 2];
            float vx = positions[c] - positions[a];
            float vy = positions[c + 1] - positions[a + 1];
            float vz = positions[c + 2] - positions[a + 2];
            float nx = uy * vz - uz * vy;
            float ny = uz * vx - ux * vz;
            float nz = ux * vy - uy * vx;
            for (int v : new int[] {a, b, c}) {
                normals[v] += nx;
                normals[v + 1] += ny;
                normals[v + 2] += nz;
            }
        }
        for (int v = 0; v < normals.length; v += 3) {
            float length = (float) Math.sqrt(normals[v] * normals[v] + normals[v + 1] * normals[v + 1]
                + normals[v + 2] * normals[v + 2]);
            if (length > 0) {
                normals[v] /= length;
                normals[v + 1] /= length;
                normals[v + 2] /= length;
            } else {
                normals[v + 1] = 1; // a vertex no triangle uses: up
            }
        }
        return normals;
    }

    // ---------------------------------------------------------------- materials

    private MaterialData material(AssetInput input) throws IOException {
        GltfDocument document = document(input);
        AssetPath file = path(input).file();
        Map<String, Object> material = document.get("materials", index(path(input), "material"));
        Map<String, Object> pbr = GltfDocument.object(material, "pbrMetallicRoughness");
        Map<String, Object> specularGlossiness = extension(material, SPECULAR_GLOSSINESS);
        if (!specularGlossiness.isEmpty() && pbr.isEmpty()) {
            // Spec-gloss: its diffuse as the base color, rough where not glossy, not metallic; kept whole as a feature
            pbr = Map.of("baseColorFactor", specularGlossiness.getOrDefault("diffuseFactor", List.of(1.0, 1.0, 1.0,
                1.0)), "metallicFactor", 0.0, "roughnessFactor", 1 - GltfDocument.number(specularGlossiness,
                "glossinessFactor", 1));
            if (specularGlossiness.containsKey("diffuseTexture")) {
                pbr = new java.util.HashMap<>(pbr);
                pbr.put("baseColorTexture", specularGlossiness.get("diffuseTexture"));
            }
        }
        double[] base = GltfDocument.numbers(pbr, "baseColorFactor", 1, 1, 1, 1);
        double[] emissive = GltfDocument.numbers(material, "emissiveFactor", 0, 0, 0);
        double emissiveStrength = GltfDocument.number(extension(material, "KHR_materials_emissive_strength"),
            "emissiveStrength", 1);
        for (int i = 0; i < 3; i++) {
            emissive[i] *= emissiveStrength;
        }
        MaterialData.Builder builder = MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", (float) base[0], (float) base[1], (float) base[2], (float) base[3])
            .set("metallic", (float) GltfDocument.number(pbr, "metallicFactor", 1))
            .set("roughness", (float) GltfDocument.number(pbr, "roughnessFactor", 1))
            .set("emissive", (float) emissive[0], (float) emissive[1], (float) emissive[2]);
        texture(document, file, pbr, "baseColorTexture", false, builder, "baseColorTexture");
        texture(document, file, material, "emissiveTexture", false, builder, "emissiveTexture");
        Map<String, Object> normal = GltfDocument.object(material, "normalTexture");
        if (texture(document, file, material, "normalTexture", true, builder, "normalTexture")) {
            builder.set("normalScale", (float) GltfDocument.number(normal, "scale", 1));
        }
        if (texture(document, file, pbr, "metallicRoughnessTexture", true, builder, "ormTexture")) {
            // The engine reads occlusion from the same texture's red: only where glTF's occlusion texture is that one.
            Map<String, Object> occlusion = GltfDocument.object(material, "occlusionTexture");
            int metallicRoughness = GltfDocument.integer(GltfDocument.object(pbr, "metallicRoughnessTexture"), "index",
                -1);
            boolean packed = GltfDocument.integer(occlusion, "index", -2) == metallicRoughness;
            builder.set("occlusion", packed ? (float) GltfDocument.number(occlusion, "strength", 1) : 0);
        }
        Map<String, Object> extensions = GltfDocument.object(material, "extensions");
        for (Map.Entry<String, Object> entry : extensions.entrySet()) {
            String name = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> values = (Map<String, Object>) entry.getValue();
            if (name.equals("KHR_materials_clearcoat")) {
                builder.feature("clearcoat", coat -> clearcoat(document, file, values, coat));
            } else if (name.equals(SPECULAR_GLOSSINESS)) {
                builder.feature("specularGlossiness", feature -> feature(document, file, values, feature));
            } else if (name.startsWith("KHR_materials_") && !name.equals("KHR_materials_emissive_strength")) {
                builder.feature(name.substring("KHR_materials_".length()),
                    feature -> feature(document, file, values, feature));
            }
        }
        MaterialData.AlphaMode alpha = MaterialData.AlphaMode.valueOf(String.valueOf(material.getOrDefault(
            "alphaMode", "OPAQUE")));
        return builder.alpha(alpha, (float) GltfDocument.number(material, "alphaCutoff", 0.5))
            .doubleSided(Boolean.TRUE.equals(material.get("doubleSided")))
            .build();
    }

    /** The engine's clearcoat feature from glTF's {@code KHR_materials_clearcoat} {@code values}. */
    private void clearcoat(GltfDocument document, AssetPath file, Map<String, Object> values,
        MaterialData.FeatureBuilder coat) {
        coat.set("strength", (float) GltfDocument.number(values, "clearcoatFactor", 0))
            .set("roughness", (float) GltfDocument.number(values, "clearcoatRoughnessFactor", 0));
        texture(document, file, values, "clearcoatTexture", true, coat, "strengthTexture");
        texture(document, file, values, "clearcoatRoughnessTexture", true, coat, "roughnessTexture");
        if (texture(document, file, values, "clearcoatNormalTexture", true, coat, "normalTexture")) {
            coat.set("normalScale", (float) GltfDocument.number(GltfDocument.object(values, "clearcoatNormalTexture"),
                "scale", 1));
        }
    }

    /**
     * A material extension's {@code values} as a feature's, by glTF's names: numbers, vectors, booleans (1 or 0), and
     * texture infos as textures (colors sRGB, the rest linear), with their transforms.
     */
    @SuppressWarnings("unchecked")
    private void feature(GltfDocument document, AssetPath file, Map<String, Object> values,
        MaterialData.FeatureBuilder feature) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Double number) {
                feature.set(name, number.floatValue());
            } else if (value instanceof Boolean flag) {
                feature.set(name, flag ? 1 : 0);
            } else if (value instanceof List<?> list && list.size() >= 2 && list.size() <= 4) {
                float[] v = new float[list.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = ((Double) list.get(i)).floatValue();
                }
                feature.set(name, v[0], v[1], java.util.Arrays.copyOfRange(v, 2, v.length));
            } else if (value instanceof Map<?, ?> info && info.containsKey("index")) {
                texture(document, file, values, name, !SRGB_TEXTURES.contains(name), feature, name);
            }
        }
    }

    /** The feature textures holding colors: read as sRGB; every other is data. */
    private static final java.util.Set<String> SRGB_TEXTURES = java.util.Set.of("sheenColorTexture",
        "specularColorTexture", "diffuseTransmissionColorTexture", "diffuseTexture", "specularGlossinessTexture");
    private static final String SPECULAR_GLOSSINESS = "KHR_materials_pbrSpecularGlossiness";
    /** glTF's default wrap mode: repeating. */
    private static final int REPEAT = 10497;

    /**
     * Sets {@code field} to the texture {@code owner}'s texture info {@code name} names, if it has one, with its
     * texture coordinate set ({@code fieldTexCoord}) where not 0, its {@code KHR_texture_transform} where given
     * ({@code fieldOffset}, {@code fieldRotation} in radians, {@code fieldScale}) and its sampler where it differs from
     * glTF's default (repeating, filtered): {@code fieldWrapS}, {@code fieldWrapT} and {@code fieldMagFilter} as glTF's
     * codes (33071 clamped, 33648 mirrored; 9728 nearest); whether it did.
     */
    private <B extends MaterialData.ValuesBuilder<B>> boolean texture(GltfDocument document, AssetPath file,
        Map<String, Object> owner, String name, boolean linear, B builder, String field) {
        if (!owner.containsKey(name)) {
            return false;
        }
        Map<String, Object> info = GltfDocument.object(owner, name);
        Map<String, Object> texture = document.get("textures", GltfDocument.integer(info, "index", 0));
        int image = GltfDocument.integer(texture, "source", -1);
        for (String source : List.of("EXT_texture_webp", "KHR_texture_basisu")) {
            if (image < 0) {
                image = GltfDocument.integer(extension(texture, source), "source", -1);
            }
        }
        if (image < 0) {
            return false; // only a vendor extension's image, not required by the file: glTF lets it be left out
        }
        Map<String, Object> transform = extension(info, "KHR_texture_transform");
        int texCoord = GltfDocument.integer(transform, "texCoord", GltfDocument.integer(info, "texCoord", 0));
        if (texCoord != 0) {
            builder.set(field + "TexCoord", texCoord);
        }
        if (!transform.isEmpty()) {
            double[] offset = GltfDocument.numbers(transform, "offset", 0, 0);
            double[] scale = GltfDocument.numbers(transform, "scale", 1, 1);
            builder.set(field + "Offset", (float) offset[0], (float) offset[1])
                .set(field + "Rotation", (float) GltfDocument.number(transform, "rotation", 0))
                .set(field + "Scale", (float) scale[0], (float) scale[1]);
        }
        if (texture.containsKey("sampler")) {
            Map<String, Object> sampler = document.get("samplers", GltfDocument.integer(texture, "sampler", 0));
            for (String key : List.of("wrapS", "wrapT")) {
                int wrap = GltfDocument.integer(sampler, key, REPEAT);
                if (wrap != REPEAT) {
                    builder.set(field + Character.toUpperCase(key.charAt(0)) + key.substring(1), wrap);
                }
            }
            int magnified = GltfDocument.integer(sampler, "magFilter", -1);
            if (magnified >= 0) {
                builder.set(field + "MagFilter", magnified);
            }
        }
        Object uri = document.get("images", image).get("uri");
        AssetPath path = uri != null && !((String) uri).startsWith("data:")
            ? file.resolve(GltfDocument.decode((String) uri) + (linear ? "#" + ImageImporter.LINEAR : ""))
            : file.fragment("image/" + image + (linear ? "/" + ImageImporter.LINEAR : ""));
        builder.texture(field, paths.ref(RenderAssets.TEXTURE, path));
        return true;
    }

    // ---------------------------------------------------------------- images

    private TextureData image(AssetInput input) throws IOException {
        GltfDocument document = document(input);
        AssetPath path = path(input);
        int image = index(path, "image");
        byte[] bytes = document.embeddedImage(image, input);
        if (bytes == null) {
            throw new IllegalArgumentException("Image " + image + " is a file of its own: load it by its path: " + path);
        }
        TextureData.ColorSpace colorSpace = path.fragment().endsWith("/" + ImageImporter.LINEAR)
            ? TextureData.ColorSpace.LINEAR : TextureData.ColorSpace.SRGB;
        return ImageImporter.read(new ByteArrayInputStream(bytes), colorSpace, path.toString());
    }
}
