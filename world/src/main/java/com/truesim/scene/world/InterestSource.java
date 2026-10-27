package com.truesim.scene.world;

/**
 * One observer of the world, e.g. a camera, a viewport or a player on a server: a position, an
 * {@link InterestProfile} saying what it needs, and a priority for ordering loads. Sources are cheap: create one per
 * observer with {@link World#addInterest}, move it every frame, and close it when the observer goes; the world loads
 * what any source needs and unloads what none needs any more on {@link World#update()}.
 *
 * <pre>{@code
 * InterestSource camera = world.addInterest(PLAYER_VIEW, Priority.HIGH);
 * camera.moveTo(x, y, z);   // every frame, allocation-free
 * world.update();
 * }</pre>
 */
public final class InterestSource implements AutoCloseable {

    private final World world;
    private final InterestProfile profile;
    private final Priority priority;
    final LongIntMap[][] wanted;
    final LongIntMap[][] next;
    /** Cells no longer wanted but held until what replaces them has loaded. */
    final LongIntMap[][] retained;
    double x;
    double y;
    double z;
    boolean moved = true;
    boolean closed;

    InterestSource(World world, InterestProfile profile, Priority priority) {
        this.world = world;
        this.profile = profile;
        this.priority = priority;
        int layers = profile.entries().size();
        this.wanted = new LongIntMap[layers][];
        this.next = new LongIntMap[layers][];
        this.retained = new LongIntMap[layers][];
        for (int i = 0; i < layers; i++) {
            int levels = profile.entries().get(i).layer().levels().count();
            wanted[i] = new LongIntMap[levels];
            next[i] = new LongIntMap[levels];
            retained[i] = new LongIntMap[levels];
            for (int level = 0; level < levels; level++) {
                wanted[i][level] = new LongIntMap();
                next[i][level] = new LongIntMap();
                retained[i][level] = new LongIntMap();
            }
        }
    }

    public InterestProfile profile() {
        return profile;
    }

    public Priority priority() {
        return priority;
    }

    /** Moves the source; its cells follow on the next {@link World#update()}. */
    public void moveTo(double newX, double newY, double newZ) {
        if (newX != x || newY != y || newZ != z) {
            x = newX;
            y = newY;
            z = newZ;
            moved = true;
        }
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    /** How many cells of {@code layer} at {@code level} this source holds. */
    public int cellCount(Layer<?> layer, int level) {
        for (int i = 0; i < wanted.length; i++) {
            if (profile.entries().get(i).layer() == layer) {
                return wanted[i][level].size();
            }
        }
        return 0;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Releases everything this source holds; cells nobody else needs unload. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            world.closed(this);
        }
    }
}
