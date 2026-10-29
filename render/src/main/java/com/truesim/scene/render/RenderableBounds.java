package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounds every renderable's node by its mesh: the mesh's box, from the moment the mesh has loaded, so spatial queries
 * (picking, gameplay, culling on the CPU) find renderables without anyone setting their bounds. Holds each mesh in the
 * asset cache while a renderable uses it, so it loads even where nothing draws (a server).
 *
 * <pre>{@code
 * RenderableBounds bounds = new RenderableBounds(renderables, assets);
 *
 * // every frame, after the frame boundary (tasks.drainCompleted(): meshes arrive):
 * bounds.update();         // follows renderables added, changed and removed
 * scene.update();          // the bounds count from here on
 * renderables.update();
 * }</pre>
 *
 * <p>A node's bounds are its mesh's while it has a renderable: bounds set on it otherwise are replaced when the mesh
 * arrives, and cleared when the renderable goes. Use it on the main thread, the asset cache's. Updating allocates
 * nothing when no renderable changed.
 */
public final class RenderableBounds implements AutoCloseable {

    private final Renderables renderables;
    private final SceneGraph scene;
    private final ChangeClock clock;
    private final AssetCache assets;
    private final Renderables.Visitor onChange = (node, changes) -> follow(node);
    private final AssetCache.Listener onLoad = new AssetCache.Listener() {
        @Override
        public void loaded(AssetRef<?> ref, Object data) {
            arrived(ref, data);
        }

        @Override
        public void evicted(AssetRef<?> ref, Object data) {
            // Held while a renderable uses it: only meshes nobody draws leave, and their nodes keep their bounds.
        }
    };
    // By node index: the node whose mesh is held there (NONE: none), and the mesh.
    private long[] nodes = new long[64];
    private AssetRef<?>[] meshes = new AssetRef<?>[64];
    // Meshes still loading, and the nodes waiting for them.
    private final Map<AssetRef<?>, List<Long>> waiting = new HashMap<>();
    private long seen;
    private boolean started;
    private boolean closed;

    /** Bounds the nodes of {@code renderables} by their meshes, loaded through {@code assets}. */
    @AllowAllocation("created rarely")
    public RenderableBounds(Renderables renderables, AssetCache assets) {
        this.renderables = Objects.requireNonNull(renderables, "renderables");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.scene = renderables.scene();
        this.clock = scene.clock();
        assets.addListener(onLoad);
    }

    /**
     * Follows the renderables added, changed and removed since the last update: their nodes take their meshes' bounds,
     * at once for meshes loaded already, else when they arrive. Allocates nothing when no renderable changed.
     */
    public void update() {
        if (closed) {
            throw new IllegalStateException("The renderable bounds are closed");
        }
        long since = seen;
        seen = clock.now();
        if (!started || !renderables.changesSince(since, onChange)) {
            started = true;
            rebuild(); // the first update, or fell behind the renderables' log
        }
    }

    /** Follows every renderable as it is now, and lets go of what renderables gone held. */
    @AllowAllocation("rebuilding, rarely")
    private void rebuild() {
        for (int index = 0; index < nodes.length; index++) {
            if (nodes[index] != NodeHandle.NONE) {
                follow(nodes[index]);
            }
        }
        renderables.forEach(this::follow);
    }

    /** Makes what is held for {@code node}'s slot match its renderable now. */
    @AllowAllocation("renderables changing")
    private void follow(long node) {
        int index = NodeHandle.index(node);
        if (index >= nodes.length) {
            nodes = Arrays.copyOf(nodes, Math.max(index + 1, 2 * nodes.length));
            meshes = Arrays.copyOf(meshes, nodes.length);
        }
        AssetRef<MeshData> mesh = renderables.has(node) && scene.contains(node) ? renderables.mesh(node) : null;
        if (nodes[index] == node && Objects.equals(meshes[index], mesh)) {
            return;
        }
        if (nodes[index] != NodeHandle.NONE) {
            let(index, mesh == null);
        }
        if (mesh == null) {
            return;
        }
        nodes[index] = node;
        meshes[index] = mesh;
        assets.acquire(mesh);
        MeshData data = assets.get(mesh);
        if (data != null) {
            bound(node, data);
        } else {
            waiting.computeIfAbsent(mesh, ref -> new ArrayList<>()).add(node);
        }
    }

    /** Lets go of the mesh held at {@code index}; with {@code clear}, its node (if still there) loses its bounds. */
    @AllowAllocation("renderables changing")
    private void let(int index, boolean clear) {
        long node = nodes[index];
        AssetRef<?> mesh = meshes[index];
        List<Long> nodesWaiting = waiting.get(mesh);
        if (nodesWaiting != null) {
            nodesWaiting.remove((Long) node);
            if (nodesWaiting.isEmpty()) {
                waiting.remove(mesh);
            }
        }
        assets.release(mesh);
        nodes[index] = NodeHandle.NONE;
        meshes[index] = null;
        if (clear && scene.contains(node)) {
            scene.clearBounds(node);
        }
    }

    private void arrived(AssetRef<?> ref, Object data) {
        List<Long> nodesWaiting = waiting.remove(ref);
        if (nodesWaiting != null) {
            for (long node : nodesWaiting) {
                if (scene.contains(node)) {
                    bound(node, (MeshData) data);
                }
            }
        }
    }

    private void bound(long node, MeshData mesh) {
        scene.setLocalBounds(node, mesh.boxMin(0), mesh.boxMin(1), mesh.boxMin(2), mesh.boxMin(0) + mesh.boxExtent(0),
            mesh.boxMin(1) + mesh.boxExtent(1), mesh.boxMin(2) + mesh.boxExtent(2));
    }

    /** How many meshes are held for renderables, loaded or loading. */
    public int heldCount() {
        int count = 0;
        for (long node : nodes) {
            count += node != NodeHandle.NONE ? 1 : 0;
        }
        return count;
    }

    /** Lets go of every mesh held and stops listening to the cache; nodes keep the bounds they have. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            assets.removeListener(onLoad);
            for (int index = 0; index < nodes.length; index++) {
                if (nodes[index] != NodeHandle.NONE) {
                    assets.release(meshes[index]);
                    nodes[index] = NodeHandle.NONE;
                    meshes[index] = null;
                }
            }
            waiting.clear();
        }
    }
}
