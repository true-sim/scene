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
 * read; points and lines, sparse accessors, skins and animations are not. A file is read once while its parts load
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
                .add(RenderAssets.TEXTURE, extension, this::image);
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
        return new ModelData(out);
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
        if (node.containsKey("mesh")) {
            int meshIndex = GltfDocument.integer(node, "mesh", 0);
            mesh = paths.ref(RenderAssets.MESH, file.fragment("mesh/" + meshIndex));
            for (Map<String, Object> primitive : GltfDocument.list(document.get("meshes", meshIndex), "primitives")) {
                materials.add(primitive.containsKey("material")
                    ? paths.ref(RenderAssets.MATERIAL, file.fragment("material/"
                        + GltfDocument.integer(primitive, "material", 0)))
                    : MaterialData.DEFAULT.ref());
            }
        }
        String name = String.valueOf(node.getOrDefault("name", ""));
        int at = out.size();
        out.add(new ModelData.Node(name, parent, t[0], t[1], t[2], r[0], r[1], r[2], r[3], s[0], s[1], s[2], mesh,
            materials));
        for (double child : GltfDocument.numbers(node, "children")) {
            add(document, file, (int) child, at, out, depth + 1);
        }
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
        List<Map<String, Object>> primitives = GltfDocument.list(document.get("meshes", meshIndex), "primitives");
        if (primitives.isEmpty()) {
            throw new IllegalArgumentException("Mesh " + meshIndex + " has no primitives: " + path(input));
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
        int[] indices;
        if (primitive.containsKey("indices")) {
            indices = document.indices(GltfDocument.integer(primitive, "indices", 0));
        } else {
            indices = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = i;
            }
        }
        int mode = GltfDocument.integer(primitive, "mode", 4);
        return switch (mode) {
            case 4 -> indices;
            case 5 -> strip(indices);
            case 6 -> fan(indices);
            default -> throw new IllegalArgumentException("Only triangles are read, not mode " + mode + ": " + path);
        };
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
        double[] base = GltfDocument.numbers(pbr, "baseColorFactor", 1, 1, 1, 1);
        double[] emissive = GltfDocument.numbers(material, "emissiveFactor", 0, 0, 0);
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
        MaterialData.AlphaMode alpha = MaterialData.AlphaMode.valueOf(String.valueOf(material.getOrDefault(
            "alphaMode", "OPAQUE")));
        return builder.alpha(alpha, (float) GltfDocument.number(material, "alphaCutoff", 0.5))
            .doubleSided(Boolean.TRUE.equals(material.get("doubleSided")))
            .build();
    }

    /**
     * Sets {@code field} to the texture {@code owner}'s texture info {@code name} names, if it has one; whether it did.
     */
    private boolean texture(GltfDocument document, AssetPath file, Map<String, Object> owner, String name,
        boolean linear, MaterialData.Builder builder, String field) {
        if (!owner.containsKey(name)) {
            return false;
        }
        Map<String, Object> info = GltfDocument.object(owner, name);
        Map<String, Object> texture = document.get("textures", GltfDocument.integer(info, "index", 0));
        if (!texture.containsKey("source")) {
            return false; // an extension's image (WebP, KTX2) only: not read
        }
        int image = GltfDocument.integer(texture, "source", 0);
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
