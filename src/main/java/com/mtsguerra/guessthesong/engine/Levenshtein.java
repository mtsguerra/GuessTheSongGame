package com.mtsguerra.guessthesong.engine;

/**
 * Classic Levenshtein edit distance (insert / delete / substitute, cost 1 each),
 * using two rolling rows: O(n·m) time, O(min(n, m)) memory.
 */
public final class Levenshtein {

    private Levenshtein() {
    }

    public static int distance(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isEmpty()) {
            return b.length();
        }
        if (b.isEmpty()) {
            return a.length();
        }
        // keep the shorter string in the inner loop
        if (a.length() < b.length()) {
            String tmp = a;
            a = b;
            b = tmp;
        }

        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** 1.0 = identical, 0.0 = nothing in common. */
    public static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (double) distance(a, b) / max;
    }
}
