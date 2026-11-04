package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;

/**
 * Makes a node pickable by pointer rays (a component, saved as {@code truesim.pickable}): how precisely it is hit, on
 * which pick layers, and whether it is on at all. {@link ScenePicker} tests only nodes with one, so the rest of a scene
 * costs picking nothing beyond the spatial index's box tests; the engine's scene interaction and the UI's scene bridge
 * route the hits to listeners.
 *
 * <pre>{@code
 * components.set(door, Pickable.TYPE, Pickable.bounds());             // its box: free
 * components.set(statue, Pickable.TYPE, Pickable.mesh());             // its renderable's triangles
 * components.set(orb, Pickable.TYPE, Pickable.custom(sphereTest));    // a test of its own
 * pickable.enabled(false);                                            // off for now, kept
 * }</pre>
 *
 * <p>A {@link Shape#MESH} pickable given no geometry is hit by its node's renderable's mesh, which the picker's
 * {@linkplain ScenePicker#resolver resolver} finds (scene-render's {@code RenderablePicking}: posed on the CPU when the
 * mesh is skinned); until the mesh is loaded it is hit by its box. Models mark nodes pickable by data
 * ({@code ModelData.Node.pick}, glTF's {@code "truesim.pickable"} node extra), so spawning them makes them pickable.
 *
 * <p>Changed in place by its setters, each counting {@link #version()}; reading allocates nothing.
 */
public final class Pickable {

    /** The pickable component type. */
    public static final ComponentType<Pickable> TYPE = ComponentType.of("truesim.pickable", Pickable.class);

    /** The pick layer pickables are on unless set: bit 0. */
    public static final int DEFAULT_LAYERS = 1;

    /** How precisely a node is hit. */
    public enum Shape {
        /** Its world box: free, the spatial index already tested it. */
        BOUNDS,
        /** Triangles: its own {@link PickGeometry}, or its renderable's mesh. */
        MESH,
        /** A test of the game's own ({@link Test}): a sphere, a capsule, a GPU result. */
        CUSTOM;

        /** The shape named {@code name} as glTF extras and saves write it ("bounds", "mesh"), or null. */
        @AllowAllocation("reading models and saves")
        public static Shape named(String name) {
            for (Shape s : values()) {
                if (s.name().equalsIgnoreCase(name)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** A node's own precise test: where a world ray hits it. */
    @FunctionalInterface
    public interface Test {

        /**
         * How far along the world ray (in units of its direction's length) it hits {@code node}, whose box it enters at
         * {@code boxDistance}; {@link Double#POSITIVE_INFINITY} for a miss. Must not allocate.
         */
        double test(long node, double boxDistance, double ox, double oy, double oz, double dx, double dy, double dz,
            double max);
    }

    private Shape shape;
    private PickGeometry geometry;
    private Test test;
    private boolean enabled = true;
    private int layers = DEFAULT_LAYERS;
    private long version;

    private Pickable(Shape shape) {
        this.shape = shape;
    }

    /** Hit by its world box. */
    @AllowAllocation("components are made when a scene is built")
    public static Pickable bounds() {
        return new Pickable(Shape.BOUNDS);
    }

    /** Hit by its renderable's mesh (resolved by the picker), its box until the mesh is there. */
    @AllowAllocation("components are made when a scene is built")
    public static Pickable mesh() {
        return new Pickable(Shape.MESH);
    }

    /** Hit by {@code geometry} in the node's space. */
    @AllowAllocation("components are made when a scene is built")
    public static Pickable mesh(PickGeometry geometry) {
        Pickable p = new Pickable(Shape.MESH);
        p.geometry = Objects.requireNonNull(geometry, "geometry");
        return p;
    }

    /** Hit by a test of its own. */
    @AllowAllocation("components are made when a scene is built")
    public static Pickable custom(Test test) {
        Pickable p = new Pickable(Shape.CUSTOM);
        p.test = Objects.requireNonNull(test, "test");
        return p;
    }

    /** A pickable of {@code shape}: {@link Shape#CUSTOM} needs a test, so it is refused here. */
    @AllowAllocation("components are made when a scene is built")
    public static Pickable of(Shape shape) {
        if (shape == Shape.CUSTOM) {
            throw new IllegalArgumentException("A custom pickable needs its test: Pickable.custom(test)");
        }
        return new Pickable(Objects.requireNonNull(shape, "shape"));
    }

    /** Switches picking it on or off, keeping the rest; returns it. */
    public Pickable enabled(boolean on) {
        enabled = on;
        version++;
        return this;
    }

    /** Puts it on the pick layers of {@code mask} (queries pick the layers they ask for); returns it. */
    public Pickable layers(int mask) {
        layers = mask;
        version++;
        return this;
    }

    /** Makes it hit by {@code value} (null: its renderable's mesh); returns it. */
    public Pickable geometry(PickGeometry value) {
        shape = Shape.MESH;
        geometry = value;
        test = null;
        version++;
        return this;
    }

    /** How it is hit. */
    public Shape shape() {
        return shape;
    }

    /** Its own geometry, or null (bounds, custom, or its renderable's mesh). */
    public PickGeometry geometry() {
        return geometry;
    }

    /** Its custom test, or null. */
    public Test test() {
        return test;
    }

    /** Whether picking it is on. */
    public boolean enabled() {
        return enabled;
    }

    /** Its pick layers. */
    public int layers() {
        return layers;
    }

    /** A count its setters raise. */
    public long version() {
        return version;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return "pickable by " + shape.name().toLowerCase(java.util.Locale.ROOT) + (enabled ? "" : ", off")
            + ", layers " + Integer.toHexString(layers);
    }
}
