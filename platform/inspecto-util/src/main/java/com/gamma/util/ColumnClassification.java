package com.gamma.util;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The ONE order of column classifications (ASSURE-CLASSIFICATION-PROPAGATION-1, operator decision 2026-10-04:
 * <b>strictest wins</b>). When several classified inputs feed one computed column (a {@code concat(msisdn, imsi)}),
 * the column takes the most restrictive class, so it is masked if ANY input is masked. Used by every consumer of
 * column lineage: {@code publish.postgres}, Risk Score evidence masking and Link Analysis entity masking.
 *
 * <p>The order: a class the consumer MASKS outranks one it does not (for publication and evidence that is
 * {@link #SENSITIVE}; for Link Analysis, a class a masked Entity Type claims). Ties break by {@link #RESTRICTIVENESS}
 * ({@code MSISDN > IMSI > ACCOUNT > PII}, the platform's sensitive classes, subscriber identifiers first), then by
 * name. Within {@link #SENSITIVE} the tie-break only chooses the class a refusal or response NAMES: all four mask
 * the same way.
 */
public final class ColumnClassification {

    private ColumnClassification() {}

    /** Column classifications publication refuses and Risk Score evidence masks. */
    public static final Set<String> SENSITIVE = Set.of("MSISDN", "IMSI", "ACCOUNT", "PII");

    /** Tie-break among masked (or among unmasked) classes, most restrictive first; others rank after, by name. */
    public static final List<String> RESTRICTIVENESS = List.of("MSISDN", "IMSI", "ACCOUNT", "PII");

    /** {@code raw} trimmed and upper-cased, or null when blank. */
    public static String normalise(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s.toUpperCase(Locale.ROOT);
    }

    /** The stricter of two classes under {@code masked}; either may be null (no class). */
    public static String stricter(String a, String b, Predicate<String> masked) {
        if (a == null) return b;
        if (b == null) return a;
        return compare(a, b, masked) <= 0 ? a : b;
    }

    /** The strictest of {@code classes} under {@code masked}, or null when there are none. */
    public static String strictest(Collection<String> classes, Predicate<String> masked) {
        String out = null;
        for (String c : classes) out = stricter(out, normalise(c), masked);
        return out;
    }

    /** {@link #stricter} under {@link #SENSITIVE}. */
    public static String stricter(String a, String b) {
        return stricter(a, b, SENSITIVE::contains);
    }

    /** Negative when {@code a} is MORE restrictive than {@code b}. */
    private static int compare(String a, String b, Predicate<String> masked) {
        boolean ma = masked.test(a), mb = masked.test(b);
        if (ma != mb) return ma ? -1 : 1;
        int ra = rank(a), rb = rank(b);
        return ra != rb ? Integer.compare(ra, rb) : a.compareTo(b);
    }

    private static int rank(String c) {
        int i = RESTRICTIVENESS.indexOf(c);
        return i < 0 ? RESTRICTIVENESS.size() : i;
    }
}
