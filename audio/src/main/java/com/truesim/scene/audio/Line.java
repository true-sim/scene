package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetRef;
import java.util.List;
import java.util.Objects;

/**
 * A line of dialogue (§2.15): its clip (a path with a {@code {locale}} placeholder, resolved by the world's
 * {@link Dialogue}, or a clip given directly), its speaker, its subtitle's text key, its subtitle cues (from a WebVTT
 * sidecar, {@link WebVtt}; none: one cue for the whole line) and its viseme track (from Rhubarb, {@link Visemes}).
 *
 * <pre>{@code
 * static final Line HALT = Line.of("game://vo/{locale}/guard_halt.ogg").speaker("guard").subtitle("guard.halt");
 * }</pre>
 *
 * @param path     its clip's path, with {@code {locale}} where the language goes; null when {@code clip} is given
 * @param clip     its clip, for every language; null when {@code path} is given
 * @param speaker  who says it: one line per speaker at a time
 * @param subtitle its subtitle's text key; null for none
 * @param cues     its subtitle cues, in order
 * @param visemes  its viseme track; null for none
 */
@AllowAllocation("set up once")
public record Line(String path, AssetRef<AudioClip> clip, String speaker, String subtitle, List<Cue> cues,
        Visemes visemes) {

    public Line {
        if ((path == null) == (clip == null)) {
            throw new IllegalArgumentException("A line has a path or a clip, one of them");
        }
        Objects.requireNonNull(speaker, "speaker");
        cues = List.copyOf(cues);
    }

    /** The line whose clip is at {@code path} ({@code {locale}} replaced by the language). */
    public static Line of(String path) {
        return new Line(Objects.requireNonNull(path, "path"), null, "", null, List.of(), null);
    }

    /** The line playing {@code clip} in every language. */
    public static Line of(AssetRef<AudioClip> clip) {
        return new Line(null, Objects.requireNonNull(clip, "clip"), "", null, List.of(), null);
    }

    public Line speaker(String speaker) {
        return new Line(path, clip, speaker, subtitle, cues, visemes);
    }

    public Line subtitle(String key) {
        return new Line(path, clip, speaker, key, cues, visemes);
    }

    public Line cues(List<Cue> cues) {
        return new Line(path, clip, speaker, subtitle, cues, visemes);
    }

    public Line visemes(Visemes track) {
        return new Line(path, clip, speaker, subtitle, cues, track);
    }

    /** Its path for language {@code locale}; null for a line given its clip. */
    public String path(String locale) {
        return path == null ? null : path.replace("{locale}", locale);
    }

    /** What cooldowns key it by: its subtitle key, else its path, else its clip. */
    public Object key() {
        return subtitle != null ? subtitle : path != null ? path : clip;
    }
}
