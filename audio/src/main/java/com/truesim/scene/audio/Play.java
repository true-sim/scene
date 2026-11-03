package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.Objects;

/**
 * Plays one clip, or the input an instance is given ({@link #input()}: a listener's mix or a game ring).
 *
 * @param clip    the clip; {@code null} for an input
 * @param shaping volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Play(AssetRef<AudioClip> clip, Shaping shaping) implements SoundNode {

    public Play {
        Objects.requireNonNull(shaping, "shaping");
    }

    /** Plays {@code clip}. */
    public static Play clip(AssetRef<AudioClip> clip) {
        return new Play(Objects.requireNonNull(clip, "clip"), Shaping.NONE);
    }

    /** Plays the input the instance is given. */
    public static Play input() {
        return new Play(null, Shaping.NONE);
    }

    /** Whether it plays an input rather than a clip. */
    public boolean isInput() {
        return clip == null;
    }

    @Override
    public Play with(Shaping shaping) {
        return new Play(clip, shaping);
    }
}
