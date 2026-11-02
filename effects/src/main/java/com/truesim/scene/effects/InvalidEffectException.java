package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.io.Serial;
import java.util.List;

/**
 * Thrown when an effect is built from data that cannot work: every problem found, not only the first, each naming its
 * emitter and suggesting a name where one was misspelt.
 */
public final class InvalidEffectException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The problems found, as sentences. */
    private final transient List<String> problems;

    @AllowAllocation("diagnostics")
    InvalidEffectException(List<String> problems) {
        super("The effect has " + problems.size() + (problems.size() == 1 ? " problem:\n  " : " problems:\n  ")
            + String.join("\n  ", problems));
        this.problems = List.copyOf(problems);
    }

    /** The problems found, as sentences. */
    public List<String> problems() {
        return problems;
    }
}
