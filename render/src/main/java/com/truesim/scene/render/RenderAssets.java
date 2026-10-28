package com.truesim.scene.render;

import com.truesim.scene.assets.AssetType;
import com.truesim.scene.assets.mesh.MeshData;

/**
 * The asset types render data refers to: {@link Renderables} name meshes and materials, materials name textures. Use
 * these, so references from any loader or tool are equal, and register loaders for them with the asset cache.
 *
 * <pre>{@code
 * assets.register(RenderAssets.MESH, gltfMeshes);
 * AssetRef<MeshData> rock = AssetRef.of(RenderAssets.MESH, AssetId.parse("4f1c09d2a7e35b6c81f0e2d4c6a8b9e1"));
 * }</pre>
 */
public final class RenderAssets {

    public static final AssetType<MeshData> MESH = AssetType.of("mesh", MeshData.class, MeshData::bytes);
    public static final AssetType<MaterialData> MATERIAL = AssetType.of("material", MaterialData.class,
        MaterialData::bytes);
    public static final AssetType<TextureData> TEXTURE = AssetType.of("texture", TextureData.class, TextureData::bytes);

    private RenderAssets() {
    }
}
