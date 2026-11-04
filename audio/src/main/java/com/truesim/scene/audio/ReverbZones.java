package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;

/**
 * The reverb zones of a world: boxes or spheres on nodes, each with a reverb (an effect record, a preset or its
 * parameters), a fade distance outside its shape, and a priority. The listener's zone sets the aux reverb, blended
 * across fades; each emitter sends to its own zone's. Zones are {@link ReverbZone} components on their nodes
 * (application-api.md D46), so they are saved, stream with their cells and go with their nodes; a zone's id is its
 * node's handle, and a node has one zone at most (adding another replaces it). Used from the scene's thread; reading
 * allocates nothing.
 *
 * <pre>{@code
 * long hall = sounds.reverbZones().box(hallNode, 20, 6, 30, 2, AudioEffect.named("reverb").set("size", 0.9), 0);
 * long zone = sounds.reverbZones().strongest(x, y, z);       // 0 outside every zone
 * }</pre>
 */
public final class ReverbZones {

    /** Receives zones. */
    @FunctionalInterface
    public interface Visitor {
        void visit(long zone);
    }

    private final SceneGraph scene;
    private final ChangeClock clock;
    private final ComponentStore<ReverbZone> store; // null for the UI store, which places nothing

    ReverbZones(SceneGraph scene, ChangeClock clock) {
        this.scene = scene;
        this.clock = clock;
        this.store = scene == null ? null : scene.components().store(ReverbZone.TYPE);
    }

    /**
     * A box zone on {@code node}, {@code hx} by {@code hy} by {@code hz} metres from its centre in the node's frame,
     * fading out over {@code fade} metres outside, with {@code reverb}; higher {@code priority} wins where zones
     * overlap. Returns its id: the node's handle.
     */
    public long box(long node, double hx, double hy, double hz, double fade, AudioEffect reverb, int priority) {
        checkOwner();
        if (!(hx > 0 && hy > 0 && hz > 0)) {
            throw new IllegalArgumentException("Not a box: " + hx + " by " + hy + " by " + hz);
        }
        checkNode(node);
        return add(node, ReverbZone.box(hx, hy, hz, fade, reverb, priority));
    }

    /** A sphere zone on {@code node}, {@code radius} metres, as {@link #box} otherwise. */
    public long sphere(long node, double radius, double fade, AudioEffect reverb, int priority) {
        checkOwner();
        if (!(radius > 0)) {
            throw new IllegalArgumentException("Not a radius: " + radius);
        }
        checkNode(node);
        return add(node, ReverbZone.sphere(radius, fade, reverb, priority));
    }

    private void checkNode(long node) {
        if (scene == null) {
            throw new IllegalStateException("The UI store has no reverb zones: its sounds are not placed");
        }
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
    }

    private long add(long node, ReverbZone zone) {
        Placement.place(scene, node, zone.place);
        store.set(node, zone);
        return node;
    }

    /** Removes {@code zone}; nothing for one removed already. */
    public void remove(long zone) {
        checkOwner();
        if (store != null) {
            store.remove(zone);
        }
    }

    public boolean alive(long zone) {
        return store != null && store.has(zone);
    }

    /** The zone's reverb. */
    public AudioEffect reverb(long zone) {
        ReverbZone z = store == null ? null : store.get(zone);
        if (z == null) {
            throw new IllegalArgumentException("No reverb zone is " + Long.toHexString(zone));
        }
        return z.reverb;
    }

    public int size() {
        return store == null ? 0 : store.size();
    }

    /** A count that changes whenever a zone is added, replaced or removed. */
    public int version() {
        return store == null ? 0 : (int) store.version();
    }

    /** Visits every zone, in the store's packed order. */
    public void forEach(Visitor visitor) {
        for (int i = 0; i < size(); i++) {
            visitor.visit(store.node(i));
        }
    }

    /** How much of {@code zone} is at ({@code x}, {@code y}, {@code z}): 1 inside, fading to 0 over its fade. */
    public float weight(long zone, double x, double y, double z) {
        ReverbZone value = store == null ? null : store.get(zone);
        return value == null ? 0 : weightOf(value, x, y, z);
    }

    private static float weightOf(ReverbZone zone, double x, double y, double z) {
        double[] p = zone.place;
        double dx = x - p[0];
        double dy = y - p[1];
        double dz = z - p[2];
        double outside;
        if (zone.ey < 0) {
            outside = Maths.sqrt(dx * dx + dy * dy + dz * dz) - zone.ex;
        } else {
            // into the node's frame: the inverse rotation, v + 2w (q x v) + 2 q x (q x v) with q conjugated
            double qx = -p[3];
            double qy = -p[4];
            double qz = -p[5];
            double qw = p[6];
            double tx = 2 * (qy * dz - qz * dy);
            double ty = 2 * (qz * dx - qx * dz);
            double tz = 2 * (qx * dy - qy * dx);
            double lx = Math.abs(dx + qw * tx + qy * tz - qz * ty) - zone.ex;
            double ly = Math.abs(dy + qw * ty + qz * tx - qx * tz) - zone.ey;
            double lz = Math.abs(dz + qw * tz + qx * ty - qy * tx) - zone.ez;
            double ox = Math.max(lx, 0);
            double oy = Math.max(ly, 0);
            double oz = Math.max(lz, 0);
            outside = Maths.sqrt(ox * ox + oy * oy + oz * oz);
        }
        if (outside <= 0) {
            return 1;
        }
        return outside >= zone.fade ? 0 : (float) (1 - outside / zone.fade);
    }

    /**
     * The zone that rules at ({@code x}, {@code y}, {@code z}): of those with weight there, the highest priority, then
     * the greatest weight, then the first in the store's order; 0 outside every zone.
     */
    public long strongest(double x, double y, double z) {
        int best = -1;
        float bestWeight = 0;
        int bestPriority = 0;
        for (int i = 0; i < size(); i++) {
            ReverbZone zone = store.value(i);
            float w = weightOf(zone, x, y, z);
            if (w > 0 && (best < 0 || zone.priority > bestPriority || zone.priority == bestPriority && w > bestWeight)) {
                best = i;
                bestWeight = w;
                bestPriority = zone.priority;
            }
        }
        return best < 0 ? 0 : store.node(best);
    }

    /** Takes the zones' places from their nodes (a removed node took its zone with it). */
    void update() {
        for (int i = 0; i < size(); i++) {
            Placement.place(scene, store.node(i), store.value(i).place);
        }
    }

    /** Throws unless the calling thread may change the zones: any, or the clock's owner (ChangeClock.ownedBy). */
    private void checkOwner() {
        Thread owner = clock.owner();
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException(notOwner(owner));
        }
    }

    @AllowAllocation("diagnostics")
    private static String notOwner(Thread owner) {
        return "The reverb zones change on " + owner.getName() + " only, not on " + Thread.currentThread().getName();
    }
}
