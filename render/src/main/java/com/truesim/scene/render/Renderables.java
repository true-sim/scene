package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.NodeChange;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What draws on which node: a mesh with a material per submesh, placed by the node's world transform, drawn where the
 * node's layers say. A node has at most one renderable; a node drawing several meshes has child nodes for them.
 * Changes are logged on the scene's clock, so a renderer uploads what changed, like the scene graph's own changes.
 *
 * <pre>{@code
 * Renderables renderables = new Renderables(scene);
 * long rock = scene.create();
 * renderables.set(rock, ROCK_MESH, List.of(ROCK_MATERIAL));     // submesh 0 takes ROCK_MATERIAL
 *
 * scene.update();
 * renderables.update();                                       // drops renderables of removed nodes
 * renderables.changesSince(since, (node, changes) -> ...);    // ADDED, REMOVED, MESH, MATERIALS, SHADOWS
 * }</pre>
 *
 * <p>Submesh {@code s} takes material {@code s} of the list, and submeshes past its end the last one; with no
 * materials every submesh is drawn with {@link MaterialData#DEFAULT}. Reading, updating and reading changes allocate
 * nothing; setting a renderable copies its material list. Used from the scene's thread.
 */
public final class Renderables {

    /** The node got a renderable. */
    public static final int ADDED = 1;
    /** The node's renderable was removed, or the node itself; its handle may be stale. Reported alone. */
    public static final int REMOVED = 1 << 1;
    /** The renderable's mesh changed. */
    public static final int MESH = 1 << 2;
    /** The renderable's materials changed. */
    public static final int MATERIALS = 1 << 3;
    /** Whether the renderable casts shadows changed. */
    public static final int SHADOWS = 1 << 4;

    /** Receives changed renderables. */
    @FunctionalInterface
    public interface Visitor {

        /** {@code node}'s renderable changed in the ways {@code changes} lists. */
        void changed(long node, int changes);
    }

    private final SceneGraph scene;
    private final ChangeClock clock;
    // By node index: the node holding a renderable there (NONE: none), and the renderable.
    private long[] nodes = new long[64];
    private AssetRef<MeshData>[] meshes = newMeshes(64);
    private List<AssetRef<MaterialData>>[] materials = newMaterials(64);
    private boolean[] castsShadows = new boolean[64];
    private int size;
    /** Scene changes seen by {@link #update()}: up to this stamp. */
    private long sceneSeen;
    private final NodeChange.Visitor onSceneChange = this::sceneChanged;

    // The change log: a ring of (stamp, node, changes).
    private final long[] logStamps;
    private final long[] logNodes;
    private final int[] logChanges;
    private int logStart;
    private int logSize;
    private long logIncompleteThrough = -1;

    /** Renderables on the nodes of {@code scene}, logging their changes on its clock. */
    public Renderables(SceneGraph scene) {
        this(scene, SceneGraph.DEFAULT_LOG_CAPACITY);
    }

    /** Renderables keeping the last {@code logCapacity} changes for consumers. */
    @AllowAllocation("created rarely")
    public Renderables(SceneGraph scene, int logCapacity) {
        if (logCapacity < 1) {
            throw new IllegalArgumentException("The change log needs room: " + logCapacity);
        }
        this.scene = Objects.requireNonNull(scene, "scene");
        this.clock = scene.clock();
        this.sceneSeen = clock.now();
        logStamps = new long[logCapacity];
        logNodes = new long[logCapacity];
        logChanges = new int[logCapacity];
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AssetRef<MeshData>[] newMeshes(int length) {
        return new AssetRef[length];
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<AssetRef<MaterialData>>[] newMaterials(int length) {
        return new List[length];
    }

    public SceneGraph scene() {
        return scene;
    }

    /**
     * Draws {@code mesh} on {@code node} with {@code materials}, one per submesh (see above), casting shadows; replaces
     * the node's renderable if it had one.
     */
    @AllowAllocation("copies the material list")
    public void set(long node, AssetRef<MeshData> mesh, List<AssetRef<MaterialData>> materials) {
        Objects.requireNonNull(mesh, "mesh");
        List<AssetRef<MaterialData>> copied = List.copyOf(materials);
        int index = slot(node);
        if (nodes[index] == node) {
            int changes = (meshes[index].equals(mesh) ? 0 : MESH) | (same(this.materials[index], copied) ? 0 : MATERIALS);
            meshes[index] = mesh;
            this.materials[index] = copied;
            if (changes != 0) {
                log(node, changes);
            }
            return;
        }
        dropStale(index);
        nodes[index] = node;
        meshes[index] = mesh;
        this.materials[index] = copied;
        castsShadows[index] = true;
        size++;
        log(node, ADDED);
    }

    /** Changes the mesh of {@code node}'s renderable. */
    public void setMesh(long node, AssetRef<MeshData> mesh) {
        Objects.requireNonNull(mesh, "mesh");
        int index = held(node);
        if (!meshes[index].equals(mesh)) {
            meshes[index] = mesh;
            log(node, MESH);
        }
    }

    /** Changes the materials of {@code node}'s renderable. */
    @AllowAllocation("copies the material list")
    public void setMaterials(long node, List<AssetRef<MaterialData>> materials) {
        List<AssetRef<MaterialData>> copied = List.copyOf(materials);
        int index = held(node);
        if (!same(this.materials[index], copied)) {
            this.materials[index] = copied;
            log(node, MATERIALS);
        }
    }

    /** Whether two material lists are equal, by index: {@code List.equals} would allocate an iterator. */
    private static boolean same(List<AssetRef<MaterialData>> a, List<AssetRef<MaterialData>> b) {
        if (a == b) {
            return true;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code node}'s renderable casts shadows; it does by default. */
    public void setCastsShadows(long node, boolean casts) {
        int index = held(node);
        if (castsShadows[index] != casts) {
            castsShadows[index] = casts;
            log(node, SHADOWS);
        }
    }

    /** Removes {@code node}'s renderable, if it has one. */
    public void remove(long node) {
        if (has(node)) {
            clear(NodeHandle.index(node));
            log(node, REMOVED);
        }
    }

    /** Whether {@code node} has a renderable. */
    public boolean has(long node) {
        int index = NodeHandle.index(node);
        return node != NodeHandle.NONE && index < nodes.length && nodes[index] == node;
    }

    public AssetRef<MeshData> mesh(long node) {
        return meshes[held(node)];
    }

    /** The materials of {@code node}'s renderable, one per submesh. */
    public List<AssetRef<MaterialData>> materials(long node) {
        return materials[held(node)];
    }

    /**
     * The material submesh {@code submesh} of {@code node}'s mesh is drawn with: its own, the last one past the end of
     * the list, or {@code null} for {@link MaterialData#DEFAULT} with no materials.
     */
    public AssetRef<MaterialData> material(long node, int submesh) {
        if (submesh < 0) {
            throw new IllegalArgumentException("No submesh " + submesh);
        }
        List<AssetRef<MaterialData>> list = materials[held(node)];
        return list.isEmpty() ? null : list.get(Math.min(submesh, list.size() - 1));
    }

    public boolean castsShadows(long node) {
        return castsShadows[held(node)];
    }

    /** How many nodes have a renderable. */
    public int size() {
        return size;
    }

    /** Visits every node with a renderable, by node index. */
    public void forEach(SceneGraph.NodeVisitor visitor) {
        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i] != NodeHandle.NONE) {
                visitor.visit(nodes[i]);
            }
        }
    }

    /**
     * Drops the renderables of nodes removed from the scene since the last update, logging them {@link #REMOVED}: call
     * after {@code scene.update()}. Allocates nothing.
     */
    public void update() {
        if (!scene.changesSince(sceneSeen, onSceneChange)) {
            for (int i = 0; i < nodes.length; i++) { // fell behind the scene's log: check every renderable
                if (nodes[i] != NodeHandle.NONE && !scene.contains(nodes[i])) {
                    removeStale(i);
                }
            }
        }
        sceneSeen = clock.now();
    }

    private void sceneChanged(long node, int changes) {
        if (NodeChange.has(changes, NodeChange.REMOVED) && has(node)) {
            removeStale(NodeHandle.index(node));
        }
    }

    /**
     * Reports every renderable that changed after {@code since} (a {@link com.truesim.common.state.ChangeCursor} mark
     * on the scene's clock), in the order of the changes; a node may be reported more than once. Returns false,
     * reporting nothing, when changes that old are no longer kept: rebuild from {@link #forEach} then.
     */
    public boolean changesSince(long since, Visitor visitor) {
        if (since < logIncompleteThrough) {
            return false;
        }
        for (int i = firstAfter(since); i < logSize; i++) {
            int entry = (logStart + i) % logStamps.length;
            visitor.changed(logNodes[entry], logChanges[entry]);
        }
        return true;
    }

    /** The position in the log of the first entry stamped after {@code since}: stamps only grow, so a binary search. */
    private int firstAfter(long since) {
        int low = 0;
        int high = logSize;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (logStamps[(logStart + middle) % logStamps.length] > since) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    private void log(long node, int changes) {
        if (logSize == logStamps.length) {
            logIncompleteThrough = logStamps[logStart];
            logStart = (logStart + 1) % logStamps.length;
            logSize--;
        }
        int entry = (logStart + logSize) % logStamps.length;
        logStamps[entry] = clock.stamp();
        logNodes[entry] = node;
        logChanges[entry] = changes;
        logSize++;
    }

    /** The index of a live node of the scene, with room for it. */
    private int slot(long node) {
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        int index = NodeHandle.index(node);
        if (index >= nodes.length) {
            grow(Math.max(index + 1, nodes.length * 2));
        }
        return index;
    }

    /** The index of {@code node}'s renderable. */
    private int held(long node) {
        if (!has(node)) {
            throw new IllegalArgumentException("No renderable on " + NodeHandle.toString(node));
        }
        return NodeHandle.index(node);
    }

    /** A renderable left at {@code index} by a removed node whose slot the scene reused: removed now. */
    private void dropStale(int index) {
        if (nodes[index] != NodeHandle.NONE) {
            removeStale(index);
        }
    }

    private void removeStale(int index) {
        long node = nodes[index];
        clear(index);
        log(node, REMOVED);
    }

    private void clear(int index) {
        nodes[index] = NodeHandle.NONE;
        meshes[index] = null;
        materials[index] = null;
        castsShadows[index] = false;
        size--;
    }

    @AllowAllocation("growing with the scene")
    private void grow(int length) {
        nodes = Arrays.copyOf(nodes, length);
        meshes = Arrays.copyOf(meshes, length);
        materials = Arrays.copyOf(materials, length);
        castsShadows = Arrays.copyOf(castsShadows, length);
    }
}
