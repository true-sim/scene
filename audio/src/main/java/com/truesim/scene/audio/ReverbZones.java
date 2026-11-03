package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.common.state.ChangeClock;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.util.Arrays;
import java.util.Objects;

/**
 * The reverb zones of a world: boxes or spheres on nodes, each with a reverb (an effect record, a preset or its
 * parameters), a fade distance outside its shape, and a priority. The listener's zone sets the aux reverb, blended
 * across fades; each emitter sends to its own zone's. Cell loaders add zones in {@code attach} and remove them in
 * {@code detach}, so zones stream with cells. Used from the scene's thread; reading allocates nothing.
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
    private int slots;
    private int size;
    private int version;
    private boolean[] used = new boolean[4];
    private int[] generations = new int[4];
    private long[] nodes = new long[4];
    /** Half extents (box) or the radius in the first (sphere, the others negative). */
    private double[] extents = new double[12];
    private double[] fades = new double[4];
    private int[] priorities = new int[4];
    private AudioEffect[] reverbs = new AudioEffect[4];
    private double[] positions = new double[12];
    private double[] rotations = new double[16];

    ReverbZones(SceneGraph scene, ChangeClock clock) {
        this.scene = scene;
        this.clock = clock;
    }

    /**
     * A box zone on {@code node}, {@code hx} by {@code hy} by {@code hz} metres from its centre in the node's frame,
     * fading out over {@code fade} metres outside, with {@code reverb}; higher {@code priority} wins where zones
     * overlap. Returns its id.
     */
    public long box(long node, double hx, double hy, double hz, double fade, AudioEffect reverb, int priority) {
        checkOwner();
        if (!(hx > 0 && hy > 0 && hz > 0)) {
            throw new IllegalArgumentException("Not a box: " + hx + " by " + hy + " by " + hz);
        }
        return add(node, hx, hy, hz, fade, reverb, priority);
    }

    /** A sphere zone on {@code node}, {@code radius} metres, as {@link #box} otherwise. */
    public long sphere(long node, double radius, double fade, AudioEffect reverb, int priority) {
        checkOwner();
        if (!(radius > 0)) {
            throw new IllegalArgumentException("Not a radius: " + radius);
        }
        return add(node, radius, -1, -1, fade, reverb, priority);
    }

    private long add(long node, double a, double b, double c, double fade, AudioEffect reverb, int priority) {
        if (scene == null) {
            throw new IllegalStateException("The UI store has no reverb zones: its sounds are not placed");
        }
        if (!scene.contains(node)) {
            throw new IllegalArgumentException("Not a node of the scene: " + NodeHandle.toString(node));
        }
        if (!(fade >= 0) || Double.isInfinite(fade)) {
            throw new IllegalArgumentException("Not a fade distance: " + fade);
        }
        int slot = 0;
        while (slot < slots && used[slot]) {
            slot++;
        }
        if (slot == slots) {
            if (slots == used.length) {
                grow();
            }
            slots++;
        }
        used[slot] = true;
        generations[slot]++;
        nodes[slot] = node;
        extents[3 * slot] = a;
        extents[3 * slot + 1] = b;
        extents[3 * slot + 2] = c;
        fades[slot] = fade;
        reverbs[slot] = Objects.requireNonNull(reverb, "reverb");
        priorities[slot] = priority;
        Placement.place(scene, node, positions, rotations, slot);
        size++;
        version++;
        return (long) generations[slot] << 32 | slot;
    }

    @AllowAllocation("growing with the zones")
    private void grow() {
        int n = 2 * used.length;
        used = Arrays.copyOf(used, n);
        generations = Arrays.copyOf(generations, n);
        nodes = Arrays.copyOf(nodes, n);
        extents = Arrays.copyOf(extents, 3 * n);
        fades = Arrays.copyOf(fades, n);
        priorities = Arrays.copyOf(priorities, n);
        reverbs = Arrays.copyOf(reverbs, n);
        positions = Arrays.copyOf(positions, 3 * n);
        rotations = Arrays.copyOf(rotations, 4 * n);
    }

    private int slot(long zone) {
        int slot = (int) zone;
        return slot >= 0 && slot < slots && used[slot] && generations[slot] == (int) (zone >>> 32) ? slot : -1;
    }

    /** Removes {@code zone}; nothing for one removed already. */
    public void remove(long zone) {
        checkOwner();
        int slot = slot(zone);
        if (slot >= 0) {
            used[slot] = false;
            reverbs[slot] = null;
            size--;
            version++;
        }
    }

    public boolean alive(long zone) {
        return slot(zone) >= 0;
    }

    /** The zone's reverb. */
    public AudioEffect reverb(long zone) {
        int slot = slot(zone);
        if (slot < 0) {
            throw new IllegalArgumentException("No reverb zone is " + Long.toHexString(zone));
        }
        return reverbs[slot];
    }

    public int size() {
        return size;
    }

    /** A count that changes whenever a zone is added or removed. */
    public int version() {
        return version;
    }

    /** Visits every zone in slot order. */
    public void forEach(Visitor visitor) {
        for (int slot = 0; slot < slots; slot++) {
            if (used[slot]) {
                visitor.visit((long) generations[slot] << 32 | slot);
            }
        }
    }

    /** How much of {@code zone} is at ({@code x}, {@code y}, {@code z}): 1 inside, fading to 0 over its fade. */
    public float weight(long zone, double x, double y, double z) {
        int slot = slot(zone);
        return slot < 0 ? 0 : weightAt(slot, x, y, z);
    }

    private float weightAt(int slot, double x, double y, double z) {
        double dx = x - positions[3 * slot];
        double dy = y - positions[3 * slot + 1];
        double dz = z - positions[3 * slot + 2];
        double outside;
        if (extents[3 * slot + 1] < 0) {
            outside = Maths.sqrt(dx * dx + dy * dy + dz * dz) - extents[3 * slot];
        } else {
            // into the node's frame: the inverse rotation, v + 2w (q x v) + 2 q x (q x v) with q conjugated
            double qx = -rotations[4 * slot];
            double qy = -rotations[4 * slot + 1];
            double qz = -rotations[4 * slot + 2];
            double qw = rotations[4 * slot + 3];
            double tx = 2 * (qy * dz - qz * dy);
            double ty = 2 * (qz * dx - qx * dz);
            double tz = 2 * (qx * dy - qy * dx);
            double lx = Math.abs(dx + qw * tx + qy * tz - qz * ty) - extents[3 * slot];
            double ly = Math.abs(dy + qw * ty + qz * tx - qx * tz) - extents[3 * slot + 1];
            double lz = Math.abs(dz + qw * tz + qx * ty - qy * tx) - extents[3 * slot + 2];
            double ox = Math.max(lx, 0);
            double oy = Math.max(ly, 0);
            double oz = Math.max(lz, 0);
            outside = Maths.sqrt(ox * ox + oy * oy + oz * oz);
        }
        if (outside <= 0) {
            return 1;
        }
        return outside >= fades[slot] ? 0 : (float) (1 - outside / fades[slot]);
    }

    /**
     * The zone that rules at ({@code x}, {@code y}, {@code z}): of those with weight there, the highest priority, then
     * the greatest weight, then the lowest slot; 0 outside every zone.
     */
    public long strongest(double x, double y, double z) {
        int best = -1;
        float bestWeight = 0;
        for (int slot = 0; slot < slots; slot++) {
            if (!used[slot]) {
                continue;
            }
            float w = weightAt(slot, x, y, z);
            if (w > 0 && (best < 0 || priorities[slot] > priorities[best]
                || priorities[slot] == priorities[best] && w > bestWeight)) {
                best = slot;
                bestWeight = w;
            }
        }
        return best < 0 ? 0 : (long) generations[best] << 32 | best;
    }

    /** Takes the zones' places from their nodes; a zone whose node was removed is removed. */
    void update() {
        for (int slot = 0; slot < slots; slot++) {
            if (used[slot]) {
                if (scene.contains(nodes[slot])) {
                    Placement.place(scene, nodes[slot], positions, rotations, slot);
                } else {
                    remove((long) generations[slot] << 32 | slot);
                }
            }
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
