package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.List;

/**
 * The segments a state plays, by name: in order and round again, shuffled (from the music's seed, never the same
 * twice running), or once through, then silence.
 *
 * @param order    how the segments follow
 * @param segments the segments' names
 */
@AllowAllocation("set up once")
public record Playlist(Order order, List<String> segments) {

    /** How the segments follow. */
    public enum Order {
        SEQUENCE,
        SHUFFLE,
        ONCE
    }

    public Playlist {
        segments = List.copyOf(segments);
    }

    /** {@code segments} in order, and round again. */
    public static Playlist of(String... segments) {
        return new Playlist(Order.SEQUENCE, List.of(segments));
    }

    /** {@code segments} shuffled. */
    public static Playlist shuffle(String... segments) {
        return new Playlist(Order.SHUFFLE, List.of(segments));
    }

    /** {@code segments} once through, in order. */
    public static Playlist once(String... segments) {
        return new Playlist(Order.ONCE, List.of(segments));
    }
}
