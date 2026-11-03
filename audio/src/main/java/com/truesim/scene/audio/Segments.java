package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.Objects;

/**
 * An intro played once, a loop repeated with a sample-exact seam, and an outro when the instance stops, as its
 * {@link Release} says.
 *
 * <pre>{@code
 * Segments.intro(SPIN_UP).loop(FIRE_LOOP).outro(SPIN_DOWN).release(Release.crossfade(0.03))
 * }</pre>
 *
 * @param intro   played once first; {@code null} for none
 * @param loop    repeated until stopped
 * @param outro   played after the loop; {@code null} for none
 * @param release when the outro starts
 * @param shaping volume, pitch, delay and curves
 */
@AllowAllocation("set up once")
public record Segments(SoundNode intro, SoundNode loop, SoundNode outro, Release release, Shaping shaping)
        implements SoundNode {

    public Segments {
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(shaping, "shaping");
    }

    /** Segments starting with {@code intro}, to give a loop. */
    public static Starter intro(AssetRef<AudioClip> intro) {
        return intro(Play.clip(intro));
    }

    /** Segments starting with {@code intro}, to give a loop. */
    public static Starter intro(SoundNode intro) {
        return new Starter(Objects.requireNonNull(intro, "intro"));
    }

    /** Segments without an intro, looping {@code loop}. */
    public static Segments loop(AssetRef<AudioClip> loop) {
        return new Segments(null, Play.clip(loop), null, Release.AT_LOOP_END, Shaping.NONE);
    }

    /** A copy ending with {@code outro}. */
    public Segments outro(AssetRef<AudioClip> outro) {
        return outro(Play.clip(outro));
    }

    /** A copy ending with {@code outro}. */
    public Segments outro(SoundNode outro) {
        return new Segments(intro, loop, Objects.requireNonNull(outro, "outro"), release, shaping);
    }

    /** A copy releasing as {@code release} says. */
    public Segments release(Release release) {
        return new Segments(intro, loop, outro, release, shaping);
    }

    @Override
    public Segments with(Shaping shaping) {
        return new Segments(intro, loop, outro, release, shaping);
    }

    /**
     * Segments with an intro, waiting for their loop.
     *
     * @param intro the intro
     */
    public record Starter(SoundNode intro) {

        /** The segments with {@code loop} after the intro. */
        public Segments loop(AssetRef<AudioClip> loop) {
            return loop(Play.clip(loop));
        }

        /** The segments with {@code loop} after the intro. */
        public Segments loop(SoundNode loop) {
            return new Segments(intro, loop, null, Release.AT_LOOP_END, Shaping.NONE);
        }
    }
}
