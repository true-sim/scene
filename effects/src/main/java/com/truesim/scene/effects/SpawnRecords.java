package com.truesim.scene.effects;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * A frame's spawns, as {@link EffectRuntime} computes them: records of particles of one emitter of one effect, born at
 * evenly spaced times within the frame, each with its spawn index (its seed and order follow from it). The engine
 * copies them into its spawn ring; {@code CpuSimulation} spawns CPU emitters' particles from them. Structure of
 * arrays; reading allocates nothing.
 *
 * <p>Record {@code i} holds {@link #count(int)} particles with spawn indices from {@link #firstIndex(int)} on; the
 * first is {@link #age(int)} seconds old at the end of the frame (in its effect's time), each next one
 * {@link #interval(int)} seconds younger. A record of a burst into a shared instance names the burst call
 * ({@link #burst(int)}), whose position, normal and payload {@link Effects} keeps until the next update.
 */
public final class SpawnRecords {

    /** The record's particles were born before the effect started, prewarming it: no events, no depth collision. */
    public static final int PREWARM = 1;

    private int size;
    private long[] effects;
    private int[] emitters;
    private int[] counts;
    private long[] firsts;
    private float[] ages;
    private float[] intervals;
    private int[] flags;
    private int[] bursts;

    /** No records, with room for {@code capacity} before growing. */
    @AllowAllocation("made once")
    SpawnRecords(int capacity) {
        effects = new long[capacity];
        emitters = new int[capacity];
        counts = new int[capacity];
        firsts = new long[capacity];
        ages = new float[capacity];
        intervals = new float[capacity];
        flags = new int[capacity];
        bursts = new int[capacity];
    }

    /** How many records there are. */
    public int size() {
        return size;
    }

    /** The effect record {@code i} spawns into. */
    public long effect(int i) {
        return effects[Objects.checkIndex(i, size)];
    }

    /** The index of the emitter, in its effect's data, that record {@code i} spawns. */
    public int emitter(int i) {
        return emitters[Objects.checkIndex(i, size)];
    }

    /** How many particles record {@code i} spawns. */
    public int count(int i) {
        return counts[Objects.checkIndex(i, size)];
    }

    /** The spawn index of record {@code i}'s first particle; the others follow on. */
    public long firstIndex(int i) {
        return firsts[Objects.checkIndex(i, size)];
    }

    /** How old record {@code i}'s first particle is at the end of the frame, in seconds of its effect's time. */
    public float age(int i) {
        return ages[Objects.checkIndex(i, size)];
    }

    /** How much younger each next particle of record {@code i} is, in seconds. */
    public float interval(int i) {
        return intervals[Objects.checkIndex(i, size)];
    }

    /** Record {@code i}'s flags: {@link #PREWARM}. */
    public int flags(int i) {
        return flags[Objects.checkIndex(i, size)];
    }

    /** The {@link Effects} burst call record {@code i} spawns for, or -1 for a record of the effect's own timing. */
    public int burst(int i) {
        return bursts[Objects.checkIndex(i, size)];
    }

    void clear() {
        size = 0;
    }

    void add(long effect, int emitter, int count, long first, float age, float interval, int flag, int burst) {
        if (size == counts.length) {
            grow();
        }
        effects[size] = effect;
        emitters[size] = emitter;
        counts[size] = count;
        firsts[size] = first;
        ages[size] = age;
        intervals[size] = interval;
        flags[size] = flag;
        bursts[size] = burst;
        size++;
    }

    /** Appends record {@code i} of {@code other}. */
    void add(SpawnRecords other, int i) {
        add(other.effects[i], other.emitters[i], other.counts[i], other.firsts[i], other.ages[i], other.intervals[i],
            other.flags[i], other.bursts[i]);
    }

    @AllowAllocation("room for more records")
    private void grow() {
        int length = 2 * counts.length;
        effects = Arrays.copyOf(effects, length);
        emitters = Arrays.copyOf(emitters, length);
        counts = Arrays.copyOf(counts, length);
        firsts = Arrays.copyOf(firsts, length);
        ages = Arrays.copyOf(ages, length);
        intervals = Arrays.copyOf(intervals, length);
        flags = Arrays.copyOf(flags, length);
        bursts = Arrays.copyOf(bursts, length);
    }
}
