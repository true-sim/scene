package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * An effect a node plays, as a component of the node (application-api.md D46; saved as {@code truesim.effects.emitter}):
 * a torch's flame, a chimney's smoke. The scene's {@link Effects} starts it on the node when the component is added (or
 * replaced) and stops it (its particles living out their lives) when the component or its node goes. One-off effects
 * stay {@link Effects#play}'s and {@link Effects#burst}'s.
 *
 * <pre>{@code
 * scene.components().set(torch, EffectEmitter.TYPE, EffectEmitter.of(FLAME));
 * }</pre>
 */
public final class EffectEmitter {

    /** The effect emitter component type. */
    public static final ComponentType<EffectEmitter> TYPE = ComponentType.of("truesim.effects.emitter",
        EffectEmitter.class);

    private final EffectData effect;

    private EffectEmitter(EffectData effect) {
        this.effect = Objects.requireNonNull(effect, "effect");
    }

    /** An emitter of {@code effect}. */
    @AllowAllocation("components are made when a scene is built")
    public static EffectEmitter of(EffectData effect) {
        return new EffectEmitter(effect);
    }

    /** The effect it plays. */
    public EffectData effect() {
        return effect;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return "effect emitter";
    }
}
