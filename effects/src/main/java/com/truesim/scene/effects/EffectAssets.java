package com.truesim.scene.effects;

import com.truesim.scene.assets.AssetType;

/**
 * The asset type of effects. Use it, so references from any loader or tool are equal, and register loaders for it with
 * the asset cache; {@link EffectData#ref()} makes an asset of data built in code.
 */
public final class EffectAssets {

    /** Particle effects: emitters, user parameters and an effect type. */
    public static final AssetType<EffectData> EFFECT = AssetType.of("effect", EffectData.class, EffectData::bytes);

    private EffectAssets() {
    }
}
