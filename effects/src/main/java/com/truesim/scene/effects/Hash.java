package com.truesim.scene.effects;

/**
 * The random numbers of effects: common-math's PCG hash ({@link com.truesim.common.math.random.Hash}), written alike
 * in Java and Slang so both draw the same values bit for bit (§2.3). A particle's seed is fixed at its birth from its
 * effect's seed, its emitter and its spawn index; each module draws with its own salt. Nothing depends on slots,
 * threads or order. Allocates nothing. Kept here, delegating, so the effects code and the vectors beside it keep
 * their names.
 */
public final class Hash {

    private Hash() {
    }

    /** The PCG hash of {@code value}, as unsigned 32-bit integers. */
    public static int pcg(int value) {
        return com.truesim.common.math.random.Hash.pcg(value);
    }

    /** A hash of {@code seed} and {@code salt} together: seeds of children, values per module. */
    public static int combine(int seed, int salt) {
        return com.truesim.common.math.random.Hash.combine(seed, salt);
    }

    /** {@code hash}'s top 24 bits as a float in [0, 1): exact in a float, so the same on every device. */
    public static float unit(int hash) {
        return com.truesim.common.math.random.Hash.unit(hash);
    }

    /** A random float in [0, 1) for {@code seed} and {@code salt}. */
    public static float random(int seed, int salt) {
        return com.truesim.common.math.random.Hash.random(seed, salt);
    }
}
