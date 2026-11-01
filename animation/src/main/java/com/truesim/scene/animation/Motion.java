package com.truesim.scene.animation;

/**
 * What a layer plays: a {@link Clip}, or a {@link BlendSpace1} of clips. Set up once per rig; animators play them.
 */
public sealed interface Motion permits Clip, BlendSpace1 {

    /** The rig it belongs to. */
    Rig rig();
}
