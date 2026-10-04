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
 * <p>⚠ Heuristic, deliberately: it catches digit-form values, also split by single dashes or spaces into groups of
 * under {@value #MIN_RUN} ({@code 4111-1111-1111-1111}). Not caught: a value split by underscores or dots (those
 * join ordinals and versions), a value spelled in letters, and a digit run that happens to read as a date-time.
 * ⚠ Losing the salt re-keys every value-bearing name, so those files read as NEW once.
 */
public final class FileNames {

    private FileNames() {}

    static final int MIN_RUN = 8;
    /** A contiguous digit run, or an already-fingerprinted span (skipped, so {@link #safe} is idempotent: a 16-hex
     *  fingerprint can itself hold 8 decimal digits in a row). */
    private static final Pattern RUN = Pattern.compile("<fp:[0-9a-f]{16}>|\\d{" + MIN_RUN + ",}");
    private static final Pattern FP_SPAN = Pattern.compile("<fp:([0-9a-f]{16})>");
    /** Short digit groups joined by single dashes or spaces ({@code 4111-1111-1111-1111}, {@code 91 98765 43210}). */
    private static final Pattern SPLIT = Pattern.compile("(?<!\\d)\\d{1,7}(?!\\d)(?:[ -]\\d{1,7}(?!\\d))+");
    /** A file-name-shaped token (an extension) that holds a long digit run, inside free text. */
    private static final Pattern PATHISH = Pattern.compile("[^\\s'\"<>()]*\\d{" + MIN_RUN + ",}[^\\s'\"<>()]*\\.[A-Za-z0-9]{1,8}\\b");

    /** {@code name} with every non-timestamp digit run of {@value #MIN_RUN}+ (also one split by dashes or spaces)
     *  replaced by {@code <fp:…>}. */
    public static String safe(PipelineConfig cfg, String name) {
        if (name == null || cfg == null) return name;
        return split(cfg, contiguous(cfg, name));
    }

    /** The OUTPUT-file stem of an inbox file ({@code INGEST-OUTPUT-NAME-EMBEDS-SOURCE-STEM-1}): the name without its
     *  extensions, {@link #safe}, with each {@code <fp:X>} span spelled {@code fp-X} because {@code <}, {@code >} and
     *  {@code :} are not legal in a file name. Stable per name and salt, so an {@code OVERWRITE_OR_IGNORE} re-run
     *  lands on the same file; a name with no value run is unchanged. */
    public static String outputStem(PipelineConfig cfg, String fileName) {
        return FP_SPAN.matcher(safe(cfg, CsvIngester.stripExtensions(fileName))).replaceAll("fp-$1");
    }

    /** {@code text} with {@link #safe} applied to every file-name-shaped token that holds a long digit run. */
    public static String safeText(PipelineConfig cfg, String text) {
        if (text == null || cfg == null || text.isEmpty()) return text;
        Matcher m = PATHISH.matcher(text);
        StringBuilder sb = null;
        while (m.find()) {
            String s = safe(cfg, m.group());
            if (s.equals(m.group())) continue;
            if (sb == null) sb = new StringBuilder();
            m.appendReplacement(sb, Matcher.quoteReplacement(s));
        }
        if (sb == null) return text;
        m.appendTail(sb);
        return sb.toString();
    }

    private static String contiguous(PipelineConfig cfg, String name) {
        Matcher m = RUN.matcher(name);
        StringBuilder sb = null;
        while (m.find()) {
            if (m.group().startsWith("<") || isTimestamp(m.group())) continue;
            if (sb == null) sb = new StringBuilder();
            m.appendReplacement(sb, Matcher.quoteReplacement("<" + FailureText.fingerprint(cfg, m.group()) + ">"));
        }
        if (sb == null) return name;
        m.appendTail(sb);
        return sb.toString();
    }

    /** Second pass: groups joined by a separator. Fingerprinted over the COMPACT digits, so the dashed and the plain
     *  spelling of one value agree. A group chain that starts with a valid date ({@code 2026-10-03-1}) is a stamp. */
    private static String split(PipelineConfig cfg, String name) {
        Matcher m = SPLIT.matcher(name);
        StringBuilder sb = null;
        while (m.find()) {
            String d = m.group().replaceAll("\\D", "");
            if (d.length() < MIN_RUN || isTimestamp(d.substring(0, MIN_RUN)) || isTimestamp(d)) continue;
            if (sb == null) sb = new StringBuilder();
            m.appendReplacement(sb, Matcher.quoteReplacement("<" + FailureText.fingerprint(cfg, d) + ">"));
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
