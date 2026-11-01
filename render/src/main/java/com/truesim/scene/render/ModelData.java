package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.animation.AnimationClip;
import com.truesim.scene.animation.Skeleton;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A model as data: a tree of nodes, each with a name, a local transform, whether it shows, and optionally a mesh with
 * its materials (skinned by one of its skins) and a light, as a glTF scene or a prefab is. The meshes and materials are references, loaded as assets
 * of their own, so two models sharing a mesh share its upload. {@link #spawn} places the model's nodes in a scene.
 *
 * <pre>{@code
 * ModelData car = cache.get(paths.ref(RenderAssets.MODEL, "game://models/car.gltf"));
 * long[] nodes = car.spawn(scene, renderables, scene.create());     // the model's nodes under a node of the game's
 * }</pre>
 *
 * @param nodes the nodes, each parent before its children
 * @param lights the lights nodes name ({@link Node#light()})
 * @param skins the skins nodes' meshes are deformed by ({@link Node#skin()})
 * @param cameras the cameras nodes name ({@link Node#camera()}), looking along their node's -Z
 * @param animations the clips made for it, by name: play them on an animator of its {@link #skeleton()}
 */
@AllowAllocation("models are made when assets load and spawned when placed")
public record ModelData(List<Node> nodes, List<Light> lights, List<Skin> skins, List<Camera> cameras,
    List<Animation> animations) {

    /**
     * A clip made for the model, as a glTF animation: loaded as an asset of its own when played.
     *
     * @param name its name in the source; may be empty
     * @param clip the clip
     */
    public record Animation(String name, AssetRef<AnimationClip> clip) {

        public Animation {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(clip, "clip");
        }
    }

    /**
     * A camera of the model, as glTF's: it looks along its node's -Z, +Y up. Perspective with a vertical field of view,
     * or orthographic with half extents.
     *
     * @param name its name in the source; may be empty
     * @param orthographic orthographic rather than perspective
     * @param yfov a perspective camera's vertical field of view, radians
     * @param aspectRatio a perspective camera's width over height, or 0 to follow the view's
     * @param xmag an orthographic camera's half width
     * @param ymag an orthographic camera's half height
     * @param znear the near plane's distance
     * @param zfar the far plane's distance, or 0 for none (a perspective camera's infinite projection)
     */
    public record Camera(String name, boolean orthographic, double yfov, double aspectRatio, double xmag, double ymag,
        double znear, double zfar) {

        public Camera {
            Objects.requireNonNull(name, "name");
            if (orthographic ? !(xmag != 0 && ymag != 0 && znear >= 0 && zfar > znear)
                : !(yfov > 0 && aspectRatio >= 0 && znear > 0 && (zfar == 0 || zfar > znear))) {
                throw new IllegalArgumentException("Not a camera glTF allows: " + this);
            }
        }

        /** A perspective camera. */
        public static Camera perspective(String name, double yfov, double aspectRatio, double znear, double zfar) {
            return new Camera(name, false, yfov, aspectRatio, 0, 0, znear, zfar);
        }

        /** An orthographic camera. */
        public static Camera orthographic(String name, double xmag, double ymag, double znear, double zfar) {
            return new Camera(name, true, 0, 0, xmag, ymag, znear, zfar);
        }
    }

    /**
     * How a skinned mesh follows its joints, as glTF's skins: its vertices name joints (through their mesh's
     * {@link MeshData#paletteJoint palette}), each a node of the model, and each joint's inverse bind matrix takes a
     * vertex from the mesh's space into the joint's at rest.
     *
     * @param name its name in the source; may be empty
     * @param joints the joints' node indices in {@link ModelData#nodes()}
     * @param inverseBindMatrices 16 floats per joint, column-major (identity where the source gives none)
     * @param skeleton the node all joints descend from, or -1 where the source names none
     */
    public record Skin(String name, int[] joints, float[] inverseBindMatrices, int skeleton) {

        public Skin {
            Objects.requireNonNull(name, "name");
            joints = joints.clone();
            inverseBindMatrices = inverseBindMatrices.clone();
            if (joints.length < 1 || inverseBindMatrices.length != 16 * joints.length) {
                throw new IllegalArgumentException("A skin has joints and 16 floats of inverse bind matrix for each: "
                    + joints.length + " joints, " + inverseBindMatrices.length + " floats");
            }
        }

        /** The joints' node indices: a copy. */
        @Override
        public int[] joints() {
            return joints.clone();
        }

        /** The inverse bind matrices: a copy. */
        @Override
        public float[] inverseBindMatrices() {
            return inverseBindMatrices.clone();
        }

        public int jointCount() {
            return joints.length;
        }

        /** Joint {@code i}'s node index. */
        public int joint(int i) {
            return joints[i];
        }

        /** Element {@code element} (0 to 15, column-major) of joint {@code i}'s inverse bind matrix. */
        public float inverseBind(int i, int element) {
            return inverseBindMatrices[16 * i + Objects.checkIndex(element, 16)];
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Skin s && name.equals(s.name) && java.util.Arrays.equals(joints, s.joints)
                && java.util.Arrays.equals(inverseBindMatrices, s.inverseBindMatrices) && skeleton == s.skeleton;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, java.util.Arrays.hashCode(joints), java.util.Arrays.hashCode(inverseBindMatrices),
                skeleton);
        }

        @Override
        public String toString() {
            return "Skin[" + name + ", " + joints.length + " joints, skeleton " + skeleton + "]";
        }
    }

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
     * @param lines its points and lines, beside its mesh; drawn once the renderer draws them
     * @param skin the index in {@link #skins()} of the skin its mesh is deformed by, or -1
     * @param morphWeights its mesh's morph target weights at rest ({@link MeshData#morphTargetCount()}); empty for none
     * @param camera the index of its camera in {@link #cameras()}, or -1
     */
    public record Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
        double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh, List<AssetRef<MaterialData>> materials,
        boolean visible, int light, List<AssetRef<LineData>> lines, int skin, List<Double> morphWeights,
        int camera) {

        public Node {
            Objects.requireNonNull(name, "name");
            materials = List.copyOf(materials);
            lines = List.copyOf(lines);
            morphWeights = List.copyOf(morphWeights);
        }

        /** A node of no camera. */
        public Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
            double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh,
            List<AssetRef<MaterialData>> materials, boolean visible, int light, List<AssetRef<LineData>> lines,
            int skin, List<Double> morphWeights) {
            this(name, parent, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz, mesh, materials, visible, light, lines, skin,
                morphWeights, -1);
        }

        /** A node of no morph weights. */
        public Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
            double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh,
            List<AssetRef<MaterialData>> materials, boolean visible, int light, List<AssetRef<LineData>> lines,
            int skin) {
            this(name, parent, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz, mesh, materials, visible, light, lines, skin,
                List.of());
        }

        /** A node of no skin. */
        public Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
            double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh,
            List<AssetRef<MaterialData>> materials, boolean visible, int light, List<AssetRef<LineData>> lines) {
            this(name, parent, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz, mesh, materials, visible, light, lines, -1);
        }

        /** A node without points or lines. */
        public Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
            double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh,
            List<AssetRef<MaterialData>> materials, boolean visible, int light) {
            this(name, parent, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz, mesh, materials, visible, light, List.of());
        }

        /** A visible node without a light. */
        public Node(String name, int parent, double tx, double ty, double tz, double rx, double ry, double rz,
            double rw, double sx, double sy, double sz, AssetRef<MeshData> mesh,
            List<AssetRef<MaterialData>> materials) {
            this(name, parent, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz, mesh, materials, true, -1);
        }

        /** This node, of no skin: its mesh drawn as it is. */
        public Node withoutSkin() {
            return new Node(name, parent, tx, ty, tz, rx, ry, rz, rw, sx, sy, sz, mesh, materials, visible, light, lines,
                -1, morphWeights, camera);
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
        skins = List.copyOf(skins);
        cameras = List.copyOf(cameras);
        animations = List.copyOf(animations);
        for (int i = 0; i < nodes.size(); i++) {
            int parent = nodes.get(i).parent();
            if (parent < -1 || parent >= i) {
                throw new IllegalArgumentException("Node " + i + "'s parent " + parent + " is not a node before it");
            }
            int light = nodes.get(i).light();
            if (light < -1 || light >= lights.size()) {
                throw new IllegalArgumentException("Node " + i + "'s light " + light + " is not one of the model's");
            }
            int camera = nodes.get(i).camera();
            if (camera < -1 || camera >= cameras.size()) {
                throw new IllegalArgumentException("Node " + i + "'s camera " + camera + " is not one of the model's");
            }
            int skin = nodes.get(i).skin();
            if (skin < -1 || skin >= skins.size()) {
                throw new IllegalArgumentException("Node " + i + "'s skin " + skin + " is not one of the model's");
            }
        }
        for (Skin skin : skins) {
            for (int j = 0; j < skin.jointCount(); j++) {
                if (skin.joint(j) < 0 || skin.joint(j) >= nodes.size()) {
                    throw new IllegalArgumentException("Skin " + skin.name() + "'s joint " + skin.joint(j)
                        + " is not a node");
                }
            }
            if (skin.skeleton() < -1 || skin.skeleton() >= nodes.size()) {
                throw new IllegalArgumentException("Skin " + skin.name() + "'s skeleton " + skin.skeleton()
                    + " is not a node");
            }
        }
    }

    /** A model of no animations. */
    public ModelData(List<Node> nodes, List<Light> lights, List<Skin> skins, List<Camera> cameras) {
        this(nodes, lights, skins, cameras, List.of());
    }

    /** A model of no cameras. */
    public ModelData(List<Node> nodes, List<Light> lights, List<Skin> skins) {
        this(nodes, lights, skins, List.of());
    }

    /** A model of no skins. */
    public ModelData(List<Node> nodes, List<Light> lights) {
        this(nodes, lights, List.of());
    }

    /** A model without lights. */
    public ModelData(List<Node> nodes) {
        this(nodes, List.of());
    }

    /** The clip called {@code name}, the first if several are; throws if none is. */
    public AssetRef<AnimationClip> animation(String name) {
        for (Animation animation : animations) {
            if (animation.name().equals(name)) {
                return animation.clip();
            }
        }
        throw new IllegalArgumentException("No animation " + name + " among " + animations.size());
    }

    /**
     * Its nodes as a skeleton to animate: their names, parents and transforms at rest. Pass it with {@link #spawn}'s
     * nodes to {@code Animators.add}.
     */
    public Skeleton skeleton() {
        String[] names = new String[nodes.size()];
        int[] parents = new int[names.length];
        double[] rest = new double[Skeleton.TRANSFORM * names.length];
        for (int i = 0; i < names.length; i++) {
            Node node = nodes.get(i);
            names[i] = node.name();
            parents[i] = node.parent();
            double[] transform = {node.tx(), node.ty(), node.tz(), node.rx(), node.ry(), node.rz(), node.rw(),
                node.sx(), node.sy(), node.sz()};
            System.arraycopy(transform, 0, rest, Skeleton.TRANSFORM * i, Skeleton.TRANSFORM);
        }
        return new Skeleton(names, parents, rest);
    }

    /** The meshes, materials, points and lines its nodes use, each once, in the order the nodes name them. */
    public Set<AssetRef<?>> references() {
        Set<AssetRef<?>> references = new LinkedHashSet<>();
        for (Node node : nodes) {
            if (node.mesh() != null) {
                references.add(node.mesh());
            }
            references.addAll(node.materials());
            references.addAll(node.lines());
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
        // Skins last: their joints may come after the nodes they deform.
        for (int i = 0; i < placed.length; i++) {
            Node node = nodes.get(i);
            if (node.mesh() != null && node.skin() >= 0) {
                Skin skin = skins.get(node.skin());
                long[] joints = new long[skin.jointCount()];
                for (int j = 0; j < joints.length; j++) {
                    joints[j] = placed[skin.joint(j)];
                }
                renderables.setSkin(placed[i], new SkinBinding(joints, skin.inverseBindMatrices()));
            }
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

    /** About what it takes in memory, for the asset cache's budget: its nodes and skins, not their meshes. */
    public long bytes() {
        long skinBytes = 0;
        for (Skin skin : skins) {
            skinBytes += 64L + 68L * skin.jointCount();
        }
        return 64L + 160L * nodes.size() + skinBytes;
    }
}
