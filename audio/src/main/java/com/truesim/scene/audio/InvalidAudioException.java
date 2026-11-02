package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.io.Serial;
import java.util.List;

/**
 * Thrown when a sound, bus layout or snapshot is built from data that cannot work: every problem found, not only the
 * first, each saying where and suggesting a name where one was misspelt.
 */
public final class InvalidAudioException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The problems found, as sentences. */
    private final transient List<String> problems;

    @AllowAllocation("diagnostics")
    InvalidAudioException(String what, List<String> problems) {
        super("The " + what + " has " + problems.size() + (problems.size() == 1 ? " problem:\n  " : " problems:\n  ")
            + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    /** Throws for {@code problems} unless there are none. */
    static void throwIf(String what, List<String> problems) {
        if (!problems.isEmpty()) {
            throw new InvalidAudioException(what, problems);
        }
    }

    /** The problems found, as sentences. */
    public List<String> problems() {
        return problems;
    }
}
