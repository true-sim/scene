package com.truesim.scene.assets.mesh;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetCache;

/**
 * Meshes let go of, kept for meshes of the same sizes to be built into: their memory reused instead of freed and
 * allocated again. Streaming content that comes and goes in equal sizes (generated terrain tiles, a cell's rocks)
 * then builds each mesh into the buffers of one that left, and no shared arena is closed (closing one makes every
 * thread reach a safepoint, the frame's too).
 *
 * <pre>{@code
 * MeshPool pool = new MeshPool(64);
 * cache.recycler(RenderAssets.MESH, pool);                 // generated meshes evicted go here, not freed
 * Generator<Tile, MeshData> tiles = (tile, seed) -> MeshData.builder(format, count)...build(pool); // reuses them
 * }</pre>
 *
 * <p>A mesh offered is the pool's: nothing may read it afterwards (the cache offers only meshes no one holds). A mesh
 * with morph targets is never kept. Built into, a kept mesh's object is closed ({@link MeshData#isClosed()}) and its
 * memory belongs to the new mesh. Thread-safe: the cache offers on the frame's thread, generators take on workers.
 */
public final class MeshPool implements AssetCache.Recycler<MeshData> {

    private final Object lock = new Object();
    private MeshData[] kept = new MeshData[8];
    private int count;
    private int capacity;
    private long reused;

    /** A pool keeping at most {@code capacity} meshes (0: none, every offer refused). */
    public MeshPool(int capacity) {
        capacity(capacity);
    }

    /**
     * Keeps at most {@code capacity} meshes from now on; with fewer than it keeps, the oldest are freed now, on the
     * calling thread.
     */
    public void capacity(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Not a capacity: " + capacity);
        }
        synchronized (lock) {
            this.capacity = capacity;
            while (count > capacity) {
                MeshData oldest = kept[0];
                System.arraycopy(kept, 1, kept, 0, --count);
                kept[count] = null;
                oldest.close();
            }
        }
    }

    /** How many meshes it may keep. */
    public int capacity() {
        synchronized (lock) {
            return capacity;
        }
    }

    /** How many meshes it keeps now. */
    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    /** How many meshes were built into kept ones since it was made. */
    public long reusedCount() {
        synchronized (lock) {
            return reused;
        }
    }

    /**
     * Keeps {@code mesh} for a mesh of the same sizes, unless it has morph targets or the pool keeps none: returns
     * what the caller frees instead, null if nothing (the mesh is the pool's), the mesh itself if not kept, or the
     * oldest kept one when the pool was full (the newest are likeliest to fit what comes next). Allocates only to grow.
     */
    @Override
    public MeshData offer(MeshData mesh) {
        if (mesh.isClosed() || mesh.morphTargetCount() > 0) {
            return mesh;
        }
        synchronized (lock) {
            if (capacity == 0) {
                return mesh;
            }
            MeshData out = null;
            if (count == capacity) {
                out = kept[0];
                System.arraycopy(kept, 1, kept, 0, --count);
            }
            if (count == kept.length) {
                grow();
            }
            kept[count++] = mesh;
            return out;
        }
    }

    @AllowAllocation("keeping more meshes than before")
    private void grow() {
        kept = java.util.Arrays.copyOf(kept, 2 * kept.length);
    }

    /**
     * A kept mesh whose buffers have exactly {@code sizes} in bytes ({@link #sizes}), taken (the oldest of them), or
     * null: its object closed, its memory the caller's to build into. One closed meanwhile is never taken.
     */
    MeshData take(long[] sizes) {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                MeshData mesh = kept[i];
                if (!mesh.isClosed() && java.util.Arrays.equals(sizes(mesh), sizes)) {
                    System.arraycopy(kept, i + 1, kept, i, count - i - 1);
                    kept[--count] = null;
                    reused++;
                    mesh.handOver();
                    return mesh;
                }
            }
            return null;
        }
    }

    /** The sizes in bytes of {@code mesh}'s buffers that a build fills: positions to cluster levels. */
    @AllowAllocation("building a mesh")
    static long[] sizes(MeshData mesh) {
        return new long[] {mesh.positions().byteSize(), mesh.attributes().byteSize(),
            mesh.meshletVertices().byteSize(), mesh.meshletTriangles().byteSize(), mesh.meshlets().byteSize(),
            mesh.clusterLod().byteSize()};
    }

    /** Frees every kept mesh. The pool keeps none until meshes are offered again. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                kept[i].close();
                kept[i] = null;
            }
            count = 0;
        }
    }
}
