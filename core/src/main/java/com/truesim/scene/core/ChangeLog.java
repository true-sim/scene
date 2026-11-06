package com.truesim.scene.core;

import com.truesim.annotations.AllowAllocation;

/**
 * The change log of a scene graph or a component store: entries of (stamp, nodes changed), oldest first. An entry is one
 * node, or a batch of nodes that changed together ({@link ChangeBatch}): a subtree removed, a {@link SceneBatch}
 * attached, many nodes made at once. Two rings: the entries (stamp, where their records start, how many) and the
 * records (node, changes), both by absolute position, so an entry's records stay where they are as the rings wrap.
 *
 * <p>When full, the oldest entry goes (and a consumer that late rebuilds), unless the rings are under their
 * {@code max} or the oldest entry is newer than the stamp the caller must keep: then they grow. Appending allocates
 * only to grow; reading allocates nothing. Used on the owner's thread.
 */
final class ChangeLog {

    // entries, by position & entryMask
    private long[] stamps;
    private long[] starts;
    private int[] counts;
    private int entryHead; // the oldest entry's position
    private int entrySize;
    // records, by absolute position & recordMask
    private long[] nodes;
    private int[] changes;
    private long recordHead; // the oldest record kept
    private long recordTail; // one past the newest
    private final int max;
    private long incompleteThrough = -1;
    private final ChangeBatch batch = new ChangeBatch();

    /** A log with room for {@code capacity} records at first, growing up to {@code max} before it drops any. */
    @AllowAllocation("set up once")
    ChangeLog(int capacity, int max) {
        int length = powerOfTwo(capacity);
        stamps = new long[length];
        starts = new long[length];
        counts = new int[length];
        nodes = new long[length];
        changes = new int[length];
        this.max = max;
    }

    private static int powerOfTwo(int n) {
        return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    /** Logs {@code node}'s {@code change} at {@code stamp}, keeping every entry stamped after {@code keepAfter}. */
    void add(long stamp, long keepAfter, long node, int change) {
        room(1, keepAfter);
        int at = (int) (recordTail & (nodes.length - 1));
        nodes[at] = node;
        changes[at] = change;
        entry(stamp, 1);
    }

    /**
     * Logs nodes {@code from} to {@code from + count - 1} of {@code changed} as one entry at {@code stamp}: each with its
     * own changes from {@code with} or, null, all with {@code change}. Records with no changes (0) are left out; nothing
     * is logged without any. Keeps every entry stamped after {@code keepAfter}.
     */
    void addAll(long stamp, long keepAfter, long[] changed, int[] with, int change, int from, int count) {
        if (count == 0) {
            return;
        }
        room(count, keepAfter);
        int mask = nodes.length - 1;
        int kept = 0;
        for (int i = from; i < from + count; i++) {
            int bits = with == null ? change : with[i];
            if (bits != 0) {
                int at = (int) ((recordTail + kept) & mask);
                nodes[at] = changed[i];
                changes[at] = bits;
                kept++;
            }
        }
        if (kept > 0) {
            entry(stamp, kept);
        }
    }

    private void entry(long stamp, int count) {
        int at = (entryHead + entrySize) & (stamps.length - 1);
        stamps[at] = stamp;
        starts[at] = recordTail;
        counts[at] = count;
        entrySize++;
        recordTail += count;
    }

    /** Makes room for an entry of {@code count} records: drops the oldest entries, or grows. */
    private void room(int count, long keepAfter) {
        while (entrySize == stamps.length || recordTail - recordHead + count > nodes.length) {
            boolean droppable = entrySize > 0 && nodes.length >= max && stamps[entryHead] <= keepAfter;
            if (droppable) {
                incompleteThrough = stamps[entryHead];
                recordHead += counts[entryHead];
                entryHead = (entryHead + 1) & (stamps.length - 1);
                entrySize--;
            } else {
                grow(count);
            }
        }
    }

    @AllowAllocation("growing the change log")
    private void grow(int count) {
        if (entrySize == stamps.length) {
            int length = 2 * stamps.length;
            long[] newStamps = new long[length];
            long[] newStarts = new long[length];
            int[] newCounts = new int[length];
            for (int i = 0; i < entrySize; i++) {
                int from = (entryHead + i) & (stamps.length - 1);
                newStamps[i] = stamps[from];
                newStarts[i] = starts[from];
                newCounts[i] = counts[from];
            }
            stamps = newStamps;
            starts = newStarts;
            counts = newCounts;
            entryHead = 0;
        }
        long used = recordTail - recordHead;
        if (used + count > nodes.length) {
            int length = powerOfTwo((int) Math.max(2L * nodes.length, used + count));
            long[] newNodes = new long[length];
            int[] newChanges = new int[length];
            for (long p = recordHead; p < recordTail; p++) {
                newNodes[(int) (p & (length - 1))] = nodes[(int) (p & (nodes.length - 1))];
                newChanges[(int) (p & (length - 1))] = changes[(int) (p & (nodes.length - 1))];
            }
            nodes = newNodes;
            changes = newChanges;
        }
    }

    /**
     * Reports the entries stamped after {@code since}, oldest first: single nodes to
     * {@link NodeChange.Visitor#changed(long, int)}, batches to {@link NodeChange.Visitor#changed(ChangeBatch)}. False,
     * reporting nothing, when entries that old were dropped.
     */
    boolean since(long since, NodeChange.Visitor visitor) {
        if (since < incompleteThrough) {
            return false;
        }
        int mask = stamps.length - 1;
        for (int i = firstAfter(since); i < entrySize; i++) {
            int entry = (entryHead + i) & mask;
            int recordMask = nodes.length - 1;
            int at = (int) (starts[entry] & recordMask);
            if (counts[entry] == 1) {
                visitor.changed(nodes[at], changes[at]);
            } else {
                batch.set(nodes, changes, at, counts[entry]);
                visitor.changed(batch);
            }
        }
        return true;
    }

    /** How many node records {@link #since} would report, or -1 when entries that old were dropped. */
    int countSince(long since) {
        if (since < incompleteThrough) {
            return -1;
        }
        int first = firstAfter(since);
        return first == entrySize ? 0 : (int) (recordTail - starts[(entryHead + first) & (stamps.length - 1)]);
    }

    /** How many entries {@link #since} would report (a batch is one): -1 when entries that old were dropped. */
    int entriesSince(long since) {
        return since < incompleteThrough ? -1 : entrySize - firstAfter(since);
    }

    private int firstAfter(long since) {
        int low = 0;
        int high = entrySize;
        int mask = stamps.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (stamps[(entryHead + middle) & mask] > since) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }
}
