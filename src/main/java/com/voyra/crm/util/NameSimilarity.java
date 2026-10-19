package com.voyra.crm.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Second pass over the pg_trgm candidates of the duplicate-client check. Trigrams score
 * "CRB2 Credit Client" close to "CRB2 Lane Client" because of the shared words; two names are
 * only likely the same party when every word one has and the other lacks is a typo or a short
 * form of one the other has, or one name's words are all in the other (a middle name added).
 */
public final class NameSimilarity {

    private NameSimilarity() {
    }

    public static boolean likelySame(String a, String b) {
        Set<String> wordsA = words(a);
        Set<String> wordsB = words(b);
        Set<String> onlyA = new HashSet<>(wordsA);
        onlyA.removeAll(wordsB);
        Set<String> onlyB = new HashSet<>(wordsB);
        onlyB.removeAll(wordsA);
        if (onlyA.isEmpty() || onlyB.isEmpty()) {
            return true;
        }
        Set<String> fewer = onlyA.size() <= onlyB.size() ? onlyA : onlyB;
        Set<String> more = fewer == onlyA ? onlyB : onlyA;
        return fewer.stream().allMatch(w -> more.stream().anyMatch(o -> close(w, o)));
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>(Arrays.asList(s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
        out.remove("");
        return out;
    }

    /** A short form ("corp" / "corporation") or a typo: one edit, two for longer words. */
    private static boolean close(String x, String y) {
        if (Math.min(x.length(), y.length()) >= 3 && (x.startsWith(y) || y.startsWith(x))) {
            return true;
        }
        return distance(x, y) <= (Math.max(x.length(), y.length()) > 6 ? 2 : 1);
    }

    private static int distance(String x, String y) {
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= x.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int sub = prev[j - 1] + (x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j], cur[j - 1]) + 1);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[y.length()];
    }
}
