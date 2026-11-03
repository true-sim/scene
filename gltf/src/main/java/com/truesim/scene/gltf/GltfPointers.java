package com.truesim.scene.gltf;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.animation.AnimationPointer;
import com.truesim.scene.assets.AssetRef;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Reads {@code KHR_animation_pointer}'s JSON pointers as what the engine animates: a material's value by the name the
 * importer gives it in the material's data ({@code baseColor}, {@code emissive}, {@code baseColorTextureOffset},
 * {@code clearcoat:strength}), a punctual light's or a camera's value, or a node's visibility. Pointers at a node's
 * translation, rotation, scale or weights are a node's tracks instead ({@link #nodeTarget}). Others are not read:
 * values the engine has no field for (a material's {@code alphaCutoff}, its emissive strength, spec-gloss, the
 * transform of an occlusion texture packed with the metallic-roughness one, which follows that one's), and anything
 * not listed here.
 */
@AllowAllocation("importing when assets load")
final class GltfPointers {

    /** glTF's values where a file leaves them out, by their name, or by their parent's and theirs where it matters. */
    private static final Map<String, double[]> DEFAULTS = Map.ofEntries(
        Map.entry("baseColorFactor", new double[] {1, 1, 1, 1}),
        Map.entry("metallicFactor", new double[] {1}),
        Map.entry("roughnessFactor", new double[] {1}),
        Map.entry("emissiveFactor", new double[] {0, 0, 0}),
        Map.entry("normalTexture/scale", new double[] {1}),
        Map.entry("clearcoatNormalTexture/scale", new double[] {1}),
        Map.entry("strength", new double[] {1}),
        Map.entry("KHR_texture_transform/offset", new double[] {0, 0}),
        Map.entry("KHR_texture_transform/rotation", new double[] {0}),
        Map.entry("KHR_texture_transform/scale", new double[] {1, 1}),
        Map.entry("clearcoatFactor", new double[] {0}),
        Map.entry("clearcoatRoughnessFactor", new double[] {0}),
        Map.entry("specularFactor", new double[] {1}),
        Map.entry("specularColorFactor", new double[] {1, 1, 1}),
        Map.entry("sheenColorFactor", new double[] {0, 0, 0}),
        Map.entry("sheenRoughnessFactor", new double[] {0}),
        Map.entry("transmissionFactor", new double[] {0}),
        Map.entry("thicknessFactor", new double[] {0}),
        Map.entry("attenuationColor", new double[] {1, 1, 1}),
        Map.entry("attenuationDistance", new double[] {Double.POSITIVE_INFINITY}),
        Map.entry("ior", new double[] {1.5}),
        Map.entry("iridescenceFactor", new double[] {0}),
        Map.entry("iridescenceIor", new double[] {1.3}),
        Map.entry("iridescenceThicknessMinimum", new double[] {100}),
        Map.entry("iridescenceThicknessMaximum", new double[] {400}),
        Map.entry("anisotropyStrength", new double[] {0}),
        Map.entry("anisotropyRotation", new double[] {0}),
        Map.entry("dispersion", new double[] {0}),
        Map.entry("diffuseTransmissionFactor", new double[] {0}),
        Map.entry("diffuseTransmissionColorFactor", new double[] {1, 1, 1}),
        Map.entry("color", new double[] {1, 1, 1}),
        Map.entry("intensity", new double[] {1}),
        Map.entry("range", new double[] {0}),
        Map.entry("innerConeAngle", new double[] {0}),
        Map.entry("outerConeAngle", new double[] {Math.PI / 4}),
        Map.entry("visible", new double[] {1}));

    /** The clearcoat feature's names for glTF's clearcoat values: the engine's own, as the importer gives them. */
    private static final Map<String, String> CLEARCOAT = Map.of("clearcoatFactor", "strength",
        "clearcoatRoughnessFactor", "roughness", "clearcoatTexture", "strengthTexture", "clearcoatRoughnessTexture",
        "roughnessTexture", "clearcoatNormalTexture", "normalTexture");

    private static final List<String> NODE_PATHS = List.of("translation", "rotation", "scale", "weights");
    private static final List<String> LIGHT_VALUES = List.of("color", "intensity", "range");
    private static final List<String> CONE_ANGLES = List.of("innerConeAngle", "outerConeAngle");
    private static final List<String> CAMERA_VALUES = List.of("perspective/yfov", "perspective/aspectRatio",
        "perspective/znear", "perspective/zfar", "orthographic/xmag", "orthographic/ymag", "orthographic/znear",
        "orthographic/zfar");

    private GltfPointers() {
    }

    /** {@code pointer}'s parts: {@code /a/b~1c} is {@code a}, {@code b/c}; null for no JSON pointer. */
    static String[] parts(Object pointer) {
        if (!(pointer instanceof String text) || !text.startsWith("/")) {
            return null;
        }
        String[] parts = text.substring(1).split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].replace("~1", "/").replace("~0", "~");
        }
        return parts;
    }

    /** The node path ({@code translation}, ...) {@code parts} point at, a channel's own; null for another pointer. */
    static String nodeTarget(String[] parts) {
        return parts.length == 3 && parts[0].equals("nodes") && index(parts[1]) >= 0 && NODE_PATHS.contains(parts[2])
            ? parts[2] : null;
    }

    /**
     * What {@code pointer} (in {@code parts}) animates in {@code document}, its values as wide as {@code firstKey}, the
     * rest value where the file gives none and glTF has no default; null where the engine does not animate it.
     * {@code modelIndex} takes glTF nodes to the model's, {@code material} makes the reference to a material by its
     * index. {@code scale} (one element) gets what the values are multiplied by: a material's emissive strength for its
     * emissive factor, else 1.
     */
    static AnimationPointer of(GltfDocument document, String pointer, String[] parts, double[] firstKey,
        int[] modelIndex, IntFunction<AssetRef<?>> material, double[] scale) {
        scale[0] = 1;
        int index = parts.length > 2 ? index(parts[1]) : -1;
        AnimationPointer.Target target;
        String property;
        AssetRef<?> asset = null;
        if (parts[0].equals("materials") && index >= 0 && index < document.list("materials").size()) {
            Map<String, Object> m = document.get("materials", index);
            property = materialField(m, parts);
            target = AnimationPointer.Target.MATERIAL;
            asset = material.apply(index);
            if ("emissive".equals(property)) {
                scale[0] = GltfDocument.number(Gltf.extension(m, "KHR_materials_emissive_strength"),
                    "emissiveStrength", 1);
            }
        } else if (parts[0].equals("cameras") && parts.length == 4 && index >= 0
            && CAMERA_VALUES.contains(parts[2] + "/" + parts[3])) {
            target = AnimationPointer.Target.CAMERA;
            property = parts[3];
        } else if (parts.length >= 5 && parts[0].equals("extensions") && parts[1].equals("KHR_lights_punctual")
            && parts[2].equals("lights") && (index = index(parts[3])) >= 0) {
            target = AnimationPointer.Target.LIGHT;
            // A spot's cone angles one deeper
            property = parts.length == 5 && LIGHT_VALUES.contains(parts[4]) ? parts[4]
                : parts.length == 6 && parts[4].equals("spot") && CONE_ANGLES.contains(parts[5]) ? parts[5] : null;
        } else if (parts.length == 5 && parts[0].equals("nodes") && parts[3].equals("KHR_node_visibility")
            && parts[4].equals("visible") && index >= 0 && index < modelIndex.length && modelIndex[index] >= 0) {
            target = AnimationPointer.Target.VISIBILITY;
            property = "visible";
            index = modelIndex[index];
        } else {
            return null;
        }
        if (property == null) {
            return null;
        }
        double[] rest = rest(document.json(), parts, firstKey.length);
        rest = rest != null ? rest : firstKey.clone();
        for (int c = 0; c < rest.length; c++) {
            rest[c] *= scale[0];
        }
        return new AnimationPointer(pointer, target, index, property, asset, rest);
    }

    /** A non-negative index's value, or -1 for anything else. */
    private static int index(String part) {
        return part.matches("\\d{1,9}") ? Integer.parseInt(part) : -1;
    }

    /**
     * The name the importer gives the material value {@code parts} (from the third on) points at, in
     * {@code material}'s data; null for one it does not read.
     */
    private static String materialField(Map<String, Object> material, String[] parts) {
        String path = String.join("/", List.of(parts).subList(2, parts.length));
        switch (path) {
            case "pbrMetallicRoughness/baseColorFactor":
                return "baseColor";
            case "pbrMetallicRoughness/metallicFactor":
                return "metallic";
            case "pbrMetallicRoughness/roughnessFactor":
                return "roughness";
            case "emissiveFactor":
                return "emissive";
            case "normalTexture/scale":
                return "normalScale";
            case "occlusionTexture/strength":
                return material.containsKey("occlusionTexture") ? "occlusion" : null;
            default:
                break;
        }
        int n = parts.length;
        if (n >= 6 && parts[n - 3].equals("extensions") && parts[n - 2].equals("KHR_texture_transform")) {
            String texture = textureField(material, parts, n - 3);
            String part = parts[n - 1];
            return texture == null || !List.of("offset", "rotation", "scale").contains(part) ? null
                : texture + Character.toUpperCase(part.charAt(0)) + part.substring(1);
        }
        if (n >= 5 && parts[2].equals("extensions") && parts[3].startsWith("KHR_materials_")) {
            String feature = feature(parts[3]);
            if (feature == null) {
                return null;
            }
            if (feature.equals("clearcoat")) {
                if (n == 6 && parts[4].equals("clearcoatNormalTexture") && parts[5].equals("scale")) {
                    return "clearcoat:normalScale";
                }
                String name = CLEARCOAT.get(parts[4]);
                return n == 5 && name != null && !name.endsWith("Texture") ? "clearcoat:" + name : null;
            }
            return n == 5 && !parts[4].endsWith("Texture") ? feature + ":" + parts[4] : null;
        }
        return null;
    }

    /**
     * The material data's texture field for the texture info at {@code parts[2..end)}: its own name, or a feature's
     * prefixed by it; null for one the importer does not read with its own transform.
     */
    private static String textureField(Map<String, Object> material, String[] parts, int end) {
        String path = String.join("/", List.of(parts).subList(2, end));
        switch (path) {
            case "pbrMetallicRoughness/baseColorTexture":
                return "baseColorTexture";
            case "pbrMetallicRoughness/metallicRoughnessTexture":
                return "ormTexture";
            case "emissiveTexture":
                return "emissiveTexture";
            case "normalTexture":
                return "normalTexture";
            case "occlusionTexture":
                return Gltf.packedOcclusion(material) ? null : "occlusionTexture"; // packed: the ORM's transform
            default:
                break;
        }
        if (end == 5 && parts[2].equals("extensions") && parts[3].startsWith("KHR_materials_")) {
            String feature = feature(parts[3]);
            if (feature == null) {
                return null;
            }
            return feature.equals("clearcoat") ? (CLEARCOAT.containsKey(parts[4]) ? "clearcoat:"
                + CLEARCOAT.get(parts[4]) : null) : feature + ":" + parts[4];
        }
        return null;
    }

    /** The importer's feature name for material extension {@code extension}; null for those it reads otherwise. */
    private static String feature(String extension) {
        String name = extension.substring("KHR_materials_".length());
        return name.equals("emissive_strength") || name.equals("pbrSpecularGlossiness") ? null : name;
    }

    /**
     * The value at {@code parts} in {@code json}, {@code components} wide (a boolean as 1 or 0), or glTF's default for
     * it where the file leaves it out; null where neither is known.
     */
    static double[] rest(Map<String, Object> json, String[] parts, int components) {
        Object at = json;
        for (String part : parts) {
            if (at instanceof Map<?, ?> map) {
                at = map.get(part);
            } else if (at instanceof List<?> list && index(part) >= 0 && index(part) < list.size()) {
                at = list.get(index(part));
            } else {
                at = null;
            }
        }
        double[] value = switch (at) {
            case Double number -> new double[] {number};
            case Boolean flag -> new double[] {flag ? 1 : 0};
            case List<?> list -> numbers(list);
            case null, default -> {
                String last = parts[parts.length - 1];
                double[] fallback = DEFAULTS.get(parts[parts.length - 2] + "/" + last);
                fallback = fallback != null ? fallback : DEFAULTS.get(last);
                yield fallback != null ? fallback.clone() : null;
            }
        };
        return value != null && value.length == components ? value : null;
    }

    /** {@code list}'s numbers; null if it holds anything else. */
    private static double[] numbers(List<?> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            if (!(list.get(i) instanceof Double number)) {
                return null;
            }
            out[i] = number;
        }
        return out;
    }
}
