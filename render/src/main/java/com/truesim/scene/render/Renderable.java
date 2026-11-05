package com.truesim.scene.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.ComponentType;
import java.util.List;
import java.util.Objects;

/**
 * What draws on a node, as a component (application-api.md D46): a mesh with a material per submesh, placed by the
 * node's world transform, drawn where the node's layers say, casting shadows or not, deformed by a skin or not. A node
 * has at most one; a node drawing several meshes has child nodes for them. Immutable: a change sets a new value, and
 * the store logs which fields changed ({@link #MESH}, {@link #MATERIALS}, {@link #SHADOWS}, {@link #SKIN}), so a
 * renderer uploads only what changed (a material swap, not the mesh).
 *
 * <pre>{@code
 * ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
 * long rock = scene.create();
 * renderables.set(rock, Renderable.of(ROCK_MESH, List.of(STONE, MOSS)));   // submesh 0 stone, 1 and on moss
 * renderables.set(rock, renderables.get(rock).withCastsShadows(false));    // logged CHANGED | SHADOWS
 * renderables.changesSince(since, (node, changes) -> ...);   // ADDED, REMOVED, CHANGED with MESH, MATERIALS, ...
 * }</pre>
 *
 * <p>Submesh {@code s} takes material {@code s} of the list, and submeshes past its end the last one; with no materials
 * every submesh is drawn with {@link MaterialData#DEFAULT}. Morph target weights are a component of their own,
 * {@link MorphWeights}: animated every frame, changed in place. Saved as {@code truesim.renderable}.
 */
public final class Renderable {

    /** The mesh changed. */
    public static final int MESH = ComponentStore.FIELD;
    /** The materials changed. */
    public static final int MATERIALS = ComponentStore.FIELD << 1;
    /** Whether it casts shadows changed. */
    public static final int SHADOWS = ComponentStore.FIELD << 2;
    /** The skin changed. */
    public static final int SKIN = ComponentStore.FIELD << 3;

    /** The renderable component type, saved as {@code truesim.renderable}. */
    public static final ComponentType<Renderable> TYPE =
        ComponentType.of("truesim.renderable", Renderable.class, Renderable::changed);

    private final AssetRef<MeshData> mesh;
    private final List<AssetRef<MaterialData>> materials;
    private final boolean castsShadows;
    private final SkinBinding skin;

    private Renderable(AssetRef<MeshData> mesh, List<AssetRef<MaterialData>> materials, boolean castsShadows,
        SkinBinding skin) {
        this.mesh = mesh;
        this.materials = materials;
        this.castsShadows = castsShadows;
        this.skin = skin;
    }

    /** {@code mesh} drawn with {@code materials}, one per submesh (see above), casting shadows, not skinned. */
    @AllowAllocation("copies the material list")
    public static Renderable of(AssetRef<MeshData> mesh, List<AssetRef<MaterialData>> materials) {
        return new Renderable(Objects.requireNonNull(mesh, "mesh"), List.copyOf(materials), true, null);
    }

    /** This with {@code mesh} instead. */
    @AllowAllocation("a new value")
    public Renderable withMesh(AssetRef<MeshData> mesh) {
        return new Renderable(Objects.requireNonNull(mesh, "mesh"), materials, castsShadows, skin);
    }

    /** This with {@code materials} instead, one per submesh. */
    @AllowAllocation("a new value, the list copied")
    public Renderable withMaterials(List<AssetRef<MaterialData>> materials) {
        return new Renderable(mesh, List.copyOf(materials), castsShadows, skin);
    }

    /** This casting shadows or not; it does by default. */
    @AllowAllocation("a new value")
    public Renderable withCastsShadows(boolean casts) {
        return new Renderable(mesh, materials, casts, skin);
    }

    /**
     * This deformed by {@code skin}, its mesh's joint palette naming the skin's joints; null draws the mesh as it is.
     */
    @AllowAllocation("a new value")
    public Renderable withSkin(SkinBinding skin) {
        return new Renderable(mesh, materials, castsShadows, skin);
    }

    public AssetRef<MeshData> mesh() {
        return mesh;
    }

    /** Its materials, one per submesh. */
    public List<AssetRef<MaterialData>> materials() {
        return materials;
    }

    /**
     * The material submesh {@code submesh} of its mesh is drawn with: its own, the last one past the end of the list, or
     * {@code null} for {@link MaterialData#DEFAULT} with no materials.
     */
    public AssetRef<MaterialData> material(int submesh) {
        if (submesh < 0) {
            throw new IllegalArgumentException("No submesh " + submesh);
        }
        return materials.isEmpty() ? null : materials.get(Math.min(submesh, materials.size() - 1));
    }

    public boolean castsShadows() {
        return castsShadows;
    }

    /** The skin deforming it, or null. */
    public SkinBinding skin() {
        return skin;
    }

    /** The fields that differ from {@code before} to {@code after}: {@link #MESH}, {@link #MATERIALS}, ... */
    static int changed(Renderable before, Renderable after) {
        if (before == after) {
            return 0;
        }
        return (before.mesh.equals(after.mesh) ? 0 : MESH)
            | (sameMaterials(before.materials, after.materials) ? 0 : MATERIALS)
            | (before.castsShadows == after.castsShadows ? 0 : SHADOWS)
            | (Objects.equals(before.skin, after.skin) ? 0 : SKIN);
    }

    /** Whether two material lists are equal, by index: {@code List.equals} would allocate an iterator. */
    private static boolean sameMaterials(List<AssetRef<MaterialData>> a, List<AssetRef<MaterialData>> b) {
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

    @Override
    public boolean equals(Object o) {
        return o instanceof Renderable other && changed(this, other) == 0;
    }

    @Override
    public int hashCode() {
        int hash = 31 * mesh.hashCode() + materials.hashCode();
        return 31 * (31 * hash + (castsShadows ? 1 : 0)) + Objects.hashCode(skin);
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return "Renderable[" + mesh + ", " + materials + (castsShadows ? "" : ", no shadows")
            + (skin != null ? ", skinned" : "") + "]";
    }
}
