package com.truesim.scene.animation;

import com.truesim.scene.assets.AssetType;

/**
 * The asset types of animation data: clips, which models name ({@code ModelData.animations()}). Use these, so
 * references from any loader or tool are equal, and register loaders for them with the asset cache.
 */
public final class AnimationAssets {

    /** Animation clips: tracks of keyframes moving nodes and morph weights, as glTF's animations. */
    public static final AssetType<AnimationClip> CLIP = AssetType.of("clip", AnimationClip.class, AnimationClip::bytes);

    private AnimationAssets() {
    }
}
