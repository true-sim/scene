package com.truesim.scene.animation;

import com.truesim.annotations.AllowAllocation;
import java.util.List;
import java.util.Objects;

/**
 * An animation as data: tracks moving nodes and morph weights over its duration, as a glTF animation is. Immutable,
 * shared by every animator playing it; an asset of its own ({@link AnimationAssets#CLIP}).
 *
 * <pre>{@code
 * AnimationClip walk = cache.get(model.animation("Walk"));
 * animator.play(walk).speed(1.2f);
 * }</pre>
 */
public final class AnimationClip {

    private final String name;
    private final List<Track> tracks;
    private final double duration;

    /** A clip called {@code name} (may be empty) of {@code tracks}: as long as its latest key. */
    @AllowAllocation("clips are made when they load")
    public AnimationClip(String name, List<Track> tracks) {
        this.name = Objects.requireNonNull(name, "name");
        this.tracks = List.copyOf(tracks);
        double end = 0;
        for (Track track : this.tracks) {
            end = Math.max(end, track.end());
        }
        this.duration = end;
    }

    public String name() {
        return name;
    }

    public List<Track> tracks() {
        return tracks;
    }

    /** Seconds from its start to its latest key. */
    public double duration() {
        return duration;
    }

    /** About what it takes in memory, for the asset cache's budget. */
    public long bytes() {
        long bytes = 64;
        for (Track track : tracks) {
            bytes += track.bytes();
        }
        return bytes;
    }

    @Override
    public String toString() {
        return "AnimationClip[" + name + ", " + tracks.size() + " tracks, " + duration + " s]";
    }
}
