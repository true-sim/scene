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
 * A model as data: a tree of nodes, each with a name, a local transform and optionally a mesh with its materials, as a
 * glTF scene or a prefab is. The meshes and materials are references, loaded as assets of their own, so two models
 * sharing a mesh share its upload. {@link #spawn} places the model's nodes in a scene.
 *
 * <pre>{@code
 * ModelData car = cache.get(paths.ref(RenderAssets.MODEL, "game://models/car.gltf"));
 * long[] nodes = car.spawn(scene, renderables, scene.create());     // the model's nodes under a node of the game's
 * }</pre>
 *
 * @param nodes the nodes, each parent before its children
 */
@AllowAllocation("models are made when assets load and spawned when placed")
public record ModelData(List<Node> nodes) {

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
     */
    public record Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
        double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh, List<AssetRef<MaterialData>> materials) {

        public Node {
            Objects.requireNonNull(name, "name");
            materials = List.copyOf(materials);
        }

        /** A node at the origin, unrotated and unscaled. */
        public static Node of(String name, int parent, AssetRef<MeshData> mesh,
            List<AssetRef<MaterialData>> materials) {
            return new Node(name, parent, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, mesh, materials);
        }
    }

    public ModelData {
        nodes = List.copyOf(nodes);
        for (int i = 0; i < nodes.size(); i++) {
            int parent = nodes.get(i).parent();
            if (parent < -1 || parent >= i) {
                throw new IllegalArgumentException("Node " + i + "'s parent " + parent + " is not a node before it");
            }
        }
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
     * parent: a node of the game's), with their transforms, and their meshes in {@code renderables}. Returns the scene
     * nodes, by the model's node index.
     */
    public long[] spawn(SceneGraph scene, Renderables renderables, long parent) {
        long[] placed = new long[nodes.size()];
        for (int i = 0; i < placed.length; i++) {
            Node node = nodes.get(i);
            long created = scene.create(node.parent() < 0 ? parent : placed[node.parent()]);
            scene.setTranslation(created, node.tx(), node.ty(), node.tz());
            scene.setRotation(created, node.rx(), node.ry(), node.rz(), node.rw());
            scene.setScale(created, node.sx(), node.sy(), node.sz());
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
