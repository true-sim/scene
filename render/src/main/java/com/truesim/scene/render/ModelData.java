package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A model as data: a tree of nodes, each with a name, a local transform, whether it shows, and optionally a mesh with
 * its materials and a light, as a glTF scene or a prefab is. The meshes and materials are references, loaded as assets
 * of their own, so two models sharing a mesh share its upload. {@link #spawn} places the model's nodes in a scene.
 *
 * <pre>{@code
 * ModelData car = cache.get(paths.ref(RenderAssets.MODEL, "game://models/car.gltf"));
 * long[] nodes = car.spawn(scene, renderables, scene.create());     // the model's nodes under a node of the game's
 * }</pre>
 *
 * @param nodes the nodes, each parent before its children
 * @param lights the lights nodes name ({@link Node#light()})
 */
@AllowAllocation("models are made when assets load and spawned when placed")
public record ModelData(List<Node> nodes, List<Light> lights) {

    /** What kind of light a {@link Light} is, as glTF's punctual lights. */
    public enum LightKind {
        /** Parallel rays along the node's -Z: the sun. Intensity in lux. */
        DIRECTIONAL,
        /** From the node's origin in every direction. Intensity in candela. */
        POINT,
        /** From the node's origin along its -Z, within a cone. Intensity in candela. */
        SPOT
    }

    /**
     * A light a node carries.
     *
     * @param name its name in the source; may be empty
     * @param kind directional, point or spot
     * @param r color red, linear
     * @param g color green
     * @param b color blue
     * @param intensity lux for directional lights, candela for the others
     * @param range how far it reaches, in meters; 0 for no limit (falling off with the square of the distance)
     * @param innerCone a spot's angle from its axis where it starts to fade, in radians
     * @param outerCone a spot's angle from its axis where it has faded out, in radians
     */
    public record Light(String name, LightKind kind, double r, double g, double b, double intensity, double range,
        double innerCone, double outerCone) {

        public Light {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * One node of a model.
     *
     * @param name its name in the source, for finding it (a wheel, a door); may be empty
     * @param parent the index of its parent in {@link #nodes()}, below its own; -1 for a root
     * @param tx translation x
     * @param ty translation y
     * @param tz translation z
     * @param rx rotation, a unit quaternion: x
     * @param ry rotation y
     * @param rz rotation z
     * @param rw rotation w
     * @param sx scale x
     * @param sy scale y
     * @param sz scale z
     * @param mesh its mesh, or null for a node that only groups
     * @param materials the materials of its mesh's submeshes ({@link Renderables#set}); empty without a mesh
     * @param visible whether it shows, with its children; hidden nodes are spawned but drawn on no layer
     * @param light the index of its light in {@link #lights()}, or -1
     */
    public record Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
        double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh, List<AssetRef<MaterialData>> materials,
        boolean visible, int light) {

        public Node {
            Objects.requireNonNull(name, "name");
            materials = List.copyOf(materials);
        }

        /** A visible node without a light. */
        public Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
            double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh,
            List<AssetRef<MaterialData>> materials) {
            this(name, parent, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz, mesh, materials, true, -1);
        }

        /** A node at the origin, unrotated and unscaled. */
        public static Node of(String name, int parent, AssetRef<MeshData> mesh,
            List<AssetRef<MaterialData>> materials) {
            return new Node(name, parent, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, mesh, materials);
        }
    }

    public ModelData {
        nodes = List.copyOf(nodes);
        lights = List.copyOf(lights);
        for (int i = 0; i < nodes.size(); i++) {
            int parent = nodes.get(i).parent();
            if (parent < -1 || parent >= i) {
                throw new IllegalArgumentException("Node " + i + "'s parent " + parent + " is not a node before it");
            }
            int light = nodes.get(i).light();
            if (light < -1 || light >= lights.size()) {
                throw new IllegalArgumentException("Node " + i + "'s light " + light + " is not one of the model's");
            }
        }
    }

    /** A model without lights. */
    public ModelData(List<Node> nodes) {
        this(nodes, List.of());
    }

    /** The meshes and materials its nodes use, each once, in the order the nodes name them. */
    public Set<AssetRef<?>> references() {
        Set<AssetRef<?>> references = new LinkedHashSet<>();
        for (Node node : nodes) {
            if (node.mesh() != null) {
                references.add(node.mesh());
            }
            references.addAll(node.materials());
        }
        return references;
    }

    /**
     * Places the model's nodes in {@code scene}, its roots under {@code parent} ({@link SceneGraph#create(long)}'s
     * parent: a node of the game's), with their transforms, and their meshes in {@code renderables}. Hidden nodes, and
     * their children, are put on no layer. Lights are the engine's to place ({@link #lights()}). Returns the scene
     * nodes, by the model's node index.
     */
    public long[] spawn(SceneGraph scene, Renderables renderables, long parent) {
        long[] placed = new long[nodes.size()];
        boolean[] shown = new boolean[nodes.size()];
        for (int i = 0; i < placed.length; i++) {
            Node node = nodes.get(i);
            long created = scene.create(node.parent() < 0 ? parent : placed[node.parent()]);
            scene.setTranslation(created, node.tx(), node.ty(), node.tz());
            scene.setRotation(created, node.rx(), node.ry(), node.rz(), node.rw());
            scene.setScale(created, node.sx(), node.sy(), node.sz());
            shown[i] = node.visible() && (node.parent() < 0 || shown[node.parent()]);
            if (!shown[i]) {
                scene.setLayers(created, 0);
            }
            if (node.mesh() != null) {
                renderables.set(created, node.mesh(), node.materials());
            }
            placed[i] = created;
        }
        return placed;
    }

    /** The index of the first node called {@code name}, or -1. */
    public int find(String name) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** About what it takes in memory, for the asset cache's budget: its nodes, not their meshes. */
    public long bytes() {
        return 64L + 160L * nodes.size();
    }
}
