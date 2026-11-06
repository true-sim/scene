package com.truesim.scene.effects;

import com.truesim.scene.assets.AssetType;

/**
 * The asset types of effects. Use them, so references from any loader or tool are equal, and register loaders for it with
 * the asset cache; {@link EffectData#ref()} makes an asset of data built in code.
 */
public final class EffectAssets {

    /** Particle effects: emitters, user parameters and an effect type. */
    public static final AssetType<EffectData> EFFECT = AssetType.of("effect", EffectData.class, EffectData::bytes);
    /**
     * Vector fields: accelerations on a 3D grid that particles' vector field modules ({@link Forces#field}) name; FGA
     * files import through {@link FgaImporter}.
     */
    public static final AssetType<VectorFieldData> VECTOR_FIELD =
        AssetType.of("vectorField", VectorFieldData.class, VectorFieldData::bytes);

    private EffectAssets() {
    }
}
