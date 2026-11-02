package com.truesim.scene.effects;

/**
 * The random numbers of effects: a PCG hash on 32-bit integers (Jarzynski and Olano, "Hash functions for GPU
 * rendering", 2020), written alike in Java and Slang so both draw the same values bit for bit (§2.3). A particle's
 * seed is fixed at its birth from its effect's seed, its emitter and its spawn index; each module draws with its own
 * salt. Nothing depends on slots, threads or order. Allocates nothing.
 */
public final class Hash {

    private Hash() {
    }

    /** The PCG hash of {@code value}, as unsigned 32-bit integers. */
    public static int pcg(int value) {
        int state = value * 747796405 + 0xAC564B05;
        int word = ((state >>> ((state >>> 28) + 4)) ^ state) * 277803737;
        return (word >>> 22) ^ word;
    }

    /** A hash of {@code seed} and {@code salt} together: seeds of children, values per module. */
    public static int combine(int seed, int salt) {
        return pcg(seed + pcg(salt));
    }

    /** {@code hash}'s top 24 bits as a float in [0, 1): exact in a float, so the same on every device. */
    public static float unit(int hash) {
        return (hash >>> 8) * 0x1p-24f;
    }

    /** A random float in [0, 1) for {@code seed} and {@code salt}. */
    public static float random(int seed, int salt) {
        return unit(combine(seed, salt));
    }
}
