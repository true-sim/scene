package com.truesim.scene.audio;

/**
 * How much a sound matters when voices are short, five levels as FMOD keeps (priority is not for fine distinctions).
 * Audibility is weighted by it when choosing real voices; {@link #HIGHEST} is never made virtual by the budget
 * (dialogue, the player's own weapon).
 */
public enum Priority {
    LOWEST,
    LOW,
    NORMAL,
    HIGH,
    HIGHEST;

    private static final Priority[] ALL = values();

    /** The priority of {@code ordinal}. */
    public static Priority of(int ordinal) {
        return ALL[ordinal];
    }

    /** The factor audibility is weighted by when choosing real voices: 0.25 for the lowest to 4 for the highest. */
    public float weight() {
        return (float) (1 << 2 * ordinal()) / 16;
    }
}
