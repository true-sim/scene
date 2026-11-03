package com.truesim.scene.audio;

import com.truesim.annotations.AllowAllocation;
import java.util.Collection;

/** "Did you mean ..." for names that are not known: the closest known name by edit distance, if close enough. */
final class Suggestions {

    private Suggestions() {
    }

    /**
     * {@code " (did you mean x?)"} for the known name {@code x} closest to {@code name}, if at most a third of its
     * letters (and at least two) differ; else nothing.
     */
    @AllowAllocation("diagnostics")
    static String didYouMean(String name, Collection<String> known) {
        String best = null;
        int bestDistance = Math.max(2, name.length() / 3) + 1;
        for (String candidate : known) {
            int distance = distance(name, candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? "" : " (did you mean " + best + "?)";
    }

    /** The Levenshtein distance between {@code a} and {@code b}. */
    @AllowAllocation("diagnostics")
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
