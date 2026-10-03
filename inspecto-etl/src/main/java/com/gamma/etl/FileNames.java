package com.gamma.etl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Never store values, file-name half</b> ({@code INGEST-RAW-SOURCE-COPIES-RETENTION-1}): the one rendering of an
 * inbox file NAME or relative path for anything that reaches disk or a log — the acquisition ledger's key and name,
 * the discovery / dedup log lines, the acquisition events.
 *
 * <p>A name can embed a value (an MSISDN, a card number, an account id) and is recorded at discovery, BEFORE any
 * name check runs. {@link #safe} replaces every run of {@value #MIN_RUN}+ digits that is not a plausible timestamp
 * with its {@linkplain FailureText#fingerprint salted fingerprint}; every other character survives, so the prefix,
 * date, extension and directory stay readable.
 *
 * <p><b>Stable and unique</b>: the result is a pure function of the name and the Space's salt, so the same name always
 * yields the same key (dedup / replay identity holds) and two names differing in a value yield different keys
 * (64-bit HMAC). A name with no such run is returned UNCHANGED, so an existing ledger keeps matching.
 *
 * <p>⚠ Heuristic, deliberately: it catches digit-form values only. Not caught: a value split by separators into runs
 * under {@value #MIN_RUN} digits, a value spelled in letters, and a digit run that happens to read as a date-time.
 * ⚠ Losing the salt re-keys every value-bearing name, so those files read as NEW once.
 */
public final class FileNames {

    private FileNames() {}

    static final int MIN_RUN = 8;
    private static final Pattern RUN = Pattern.compile("\\d{" + MIN_RUN + ",}");

    /** {@code name} with every non-timestamp digit run of {@value #MIN_RUN}+ replaced by {@code <fp:…>}. */
    public static String safe(PipelineConfig cfg, String name) {
        if (name == null || cfg == null) return name;
        Matcher m = RUN.matcher(name);
        StringBuilder sb = null;
        while (m.find()) {
            if (isTimestamp(m.group())) continue;
            if (sb == null) sb = new StringBuilder();
            m.appendReplacement(sb, Matcher.quoteReplacement("<" + FailureText.fingerprint(cfg, m.group()) + ">"));
        }
        if (sb == null) return name;
        m.appendTail(sb);
        return sb.toString();
    }

    /** yyyyMMdd, +HH, +mm, +ss (8, 10, 12 or 14 digits) with in-range fields and a year 1990..2100. */
    static boolean isTimestamp(String d) {
        int n = d.length();
        if (n != 8 && n != 10 && n != 12 && n != 14) return false;
        int year = Integer.parseInt(d.substring(0, 4)), mon = Integer.parseInt(d.substring(4, 6)),
                day = Integer.parseInt(d.substring(6, 8));
        if (year < 1990 || year > 2100 || mon < 1 || mon > 12 || day < 1 || day > 31) return false;
        if (n >= 10 && Integer.parseInt(d.substring(8, 10)) > 23) return false;
        if (n >= 12 && Integer.parseInt(d.substring(10, 12)) > 59) return false;
        return n < 14 || Integer.parseInt(d.substring(12, 14)) <= 60;
    }
}
