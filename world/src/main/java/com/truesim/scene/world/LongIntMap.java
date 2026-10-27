package com.truesim.scene.world;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A map from non-negative {@code long} keys (packed cells) to {@code int} values, with open addressing and linear
 * probing: lookups, inserts and removals allocate nothing once grown.
 */
final class LongIntMap {

    private static final long EMPTY = -1;

    private long[] keys;
    private int[] values;
    private int size;

    LongIntMap() {
        keys = new long[16];
        values = new int[16];
        Arrays.fill(keys, EMPTY);
    }

    int size() {
        return size;
    }

    /** Removes every entry, keeping the capacity. */
    void clear() {
        if (size > 0) {
            Arrays.fill(keys, EMPTY);
            size = 0;
        }
    }

    /** The number of slots, for walking the entries with {@link #keyAt}. */
    int capacity() {
        return keys.length;
    }

    /** The key in slot {@code slot}, or -1 for an empty slot. */
    long keyAt(int slot) {
        return keys[slot];
    }

    /** The value of {@code key}, or -1. */
    int get(long key) {
        int mask = keys.length - 1;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) {
                return values[i];
            }
            if (k == EMPTY) {
                return -1;
            }
        }
    }

    void put(long key, int value) {
        if ((size + 1) * 4 > keys.length * 3) {
            grow();
        }
        int mask = keys.length - 1;
        int i = slot(key, mask);
        while (keys[i] != EMPTY && keys[i] != key) {
            i = (i + 1) & mask;
        }
        if (keys[i] == EMPTY) {
            size++;
        }
        keys[i] = key;
        values[i] = value;
    }

    /** Removes {@code key} if present, shifting later entries of its probe run back so lookups still find them. */
    void remove(long key) {
        int mask = keys.length - 1;
        int i = slot(key, mask);
        while (keys[i] != key) {
            if (keys[i] == EMPTY) {
                return;
            }
            i = (i + 1) & mask;
        }
        size--;
        int hole = i;
        for (int j = (hole + 1) & mask; keys[j] != EMPTY; j = (j + 1) & mask) {
            int home = slot(keys[j], mask);
            // Move the entry into the hole if its home is not between the hole and its slot (cyclically).
            boolean between = hole <= j ? hole < home && home <= j : hole < home || home <= j;
            if (!between) {
                keys[hole] = keys[j];
                values[hole] = values[j];
                hole = j;
            }
        }
        keys[hole] = EMPTY;
    }

    @AllowAllocation("growing")
    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new int[keys.length];
        Arrays.fill(keys, EMPTY);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }
}
