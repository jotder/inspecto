package com.gamma.acquire;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Windowed two-token sequence-gap analysis</b> — "how many files are missing for this pipeline over this
 * period?" (completeness-kpi-plan K2). Pure and side-effect-free, like {@link GapDetector}: it is handed the
 * observed file names and answers about them, so it can be unit tested directly and carries no opinion about
 * where the names came from.
 *
 * <h3>Why this is not {@link GapDetector}</h3>
 * {@code GapDetector} takes <b>one</b> {@code {…}} token holding a date pattern, between a literal prefix and
 * suffix, and enumerates the series <b>between the lowest and highest observed key</b>. Two consequences make
 * it the wrong instrument for this question:
 * <ul>
 *   <li>Names of the shape {@code CDR_{yyyyMMddHH}_{seq}_*} carry a <b>second, numeric</b> token and an
 *       arbitrary suffix — neither expressible in that grammar.</li>
 *   <li>🔴 Bounding the series by what was <em>observed</em> hides the largest holes. If the last three hours
 *       of a day produced nothing at all, the observed maximum simply moves earlier and the detector reports
 *       no gap. A question about a <b>period</b> must enumerate the period, not the data.</li>
 * </ul>
 *
 * <h3>Template grammar</h3>
 * A literal prefix, a {@code {datePattern}} token, a literal separator, a {@code {seq}} token, then an
 * optional trailing {@code *} matching any suffix — e.g. {@code "CDR_{yyyyMMddHH}_{seq}_*"}. The date token
 * follows {@code GapDetector}'s rules (the finest field present sets the bucket size). {@code {seq}} matches a
 * run of digits; zero padding is <b>not</b> significant, so {@code _007_} and {@code _7_} are the same
 * sequence number and are never reported as two.
 *
 * <h3>🔴 What this can and cannot know</h3>
 * Two limits, stated rather than papered over, because a completeness number that overstates its own certainty
 * is worse than no number:
 * <ul>
 *   <li><b>Only interior holes are counted.</b> Within a bucket holding sequences 1, 2 and 4, number 3 is
 *       missing. But 1, 2, 3 is <b>not</b> reported complete — the highest sequence received is not knowably
 *       the highest sent, so a <b>truncated tail is undetectable from file names alone</b>. This is a property
 *       of the naming, not of the implementation; no amount of care recovers it.</li>
 *   <li><b>An entirely empty bucket yields no file count.</b> If an hour produced nothing, how many files it
 *       <em>should</em> have produced is unknowable from the names — there is no sequence to find holes in.
 *       Empty buckets are therefore reported <b>separately and counted as buckets</b>, never folded into the
 *       missing-file total. Estimating them from neighbouring hours is a statistical question (K3's rolling
 *       baseline), and mixing an estimate into an exact count would make the exact half untrustworthy.</li>
 * </ul>
 */
public final class FileSequenceGaps {

    private FileSequenceGaps() {}

    /** Safety cap on enumerated buckets — a malformed template or absurd window cannot run away. */
    public static final int MAX_BUCKETS = 1_000_000;

    /**
     * Whether {@code {seq}} restarts inside each time bucket or runs on across them.
     *
     * <p>⚠ There is no safe way to infer this from the names: a continuous counter and a per-bucket one look
     * identical for any single bucket, and guessing wrong turns every bucket boundary into a false gap (or
     * hides every real one). The caller states it.
     */
    public enum SeqScope {
        /** The counter restarts in every bucket — holes are sought within each bucket independently. */
        PER_BUCKET,
        /** One counter spans the whole window — holes are sought across the window as a single series. */
        CONTINUOUS
    }

    /** One time bucket's finding. {@code missing} holds interior holes only; see the class note. */
    public record Bucket(String key, List<Long> observed, List<Long> missing, boolean empty) {
        public Bucket {
            observed = List.copyOf(observed);
            missing = List.copyOf(missing);
        }
    }

    /**
     * The answer to "what is missing over this window".
     *
     * @param missingFiles  🔴 <b>an exact count of interior holes</b>, never an estimate. Empty buckets
     *                      contribute nothing to it.
     * @param emptyBuckets  buckets in the window with no matching file at all — reported apart from
     *                      {@code missingFiles} because their file count is unknowable, not zero.
     * @param unmatched     names that did not match the template. Non-zero here usually means the template is
     *                      wrong, and a silently-ignored majority would otherwise read as "nothing missing".
     */
    public record Report(String template, SeqScope scope, String from, String to,
                         long observedFiles, long missingFiles, List<Bucket> buckets,
                         List<String> emptyBuckets, long unmatched) {
        public Report {
            buckets = List.copyOf(buckets);
            emptyBuckets = List.copyOf(emptyBuckets);
        }
        public boolean hasGaps() { return missingFiles > 0 || !emptyBuckets.isEmpty(); }
    }

    /**
     * Analyse {@code names} against {@code template} over the closed window {@code [from, to]}.
     *
     * <p>The window is enumerated at the template's bucket granularity, so a bucket that produced nothing is
     * found even at the edges of the period — the case an observed-bounded scan misses.
     *
     * @throws IllegalArgumentException on a malformed template, or a window whose end precedes its start
     */
    public static Report analyze(String template, Collection<String> names, LocalDateTime from,
                                 LocalDateTime to, SeqScope scope) {
        Parsed t = parse(template);
        if (from == null || to == null) throw new IllegalArgumentException("window bounds are required — "
                + "an unbounded scan cannot tell an empty period from a missing one");
        if (to.isBefore(from))
            throw new IllegalArgumentException("window end " + to + " precedes its start " + from);

        // bucket → the sequence numbers seen in it. TreeMap/TreeSet keep both ordered and de-duplicated,
        // which is also what makes zero-padding insignificant: 007 and 7 parse to the same Long.
        TreeMap<LocalDateTime, TreeSet<Long>> seen = new TreeMap<>();
        long matched = 0, unmatched = 0;
        for (String name : names) {
            Matcher m = t.matcher.matcher(name);
            if (!m.matches()) { unmatched++; continue; }
            LocalDateTime bucket;
            try {
                bucket = LocalDateTime.parse(m.group(1), t.formatter);
            } catch (RuntimeException notADate) {
                unmatched++;   // matched the shape but not a real date (month 13) — not part of the series
                continue;
            }
            long seq;
            try {
                seq = Long.parseLong(m.group(2));
            } catch (NumberFormatException tooBig) {
                unmatched++;
                continue;
            }
            if (bucket.isBefore(from) || bucket.isAfter(to)) continue;   // outside the asked-about window
            seen.computeIfAbsent(bucket, k -> new TreeSet<>()).add(seq);
            matched++;
        }

        List<Bucket> buckets = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        long missingTotal = 0;

        if (scope == SeqScope.CONTINUOUS) {
            // One series across the window: pool every sequence number, find interior holes once. Bucket
            // boundaries are descriptive here, so a number missing at a boundary is still just missing.
            TreeSet<Long> all = new TreeSet<>();
            seen.values().forEach(all::addAll);
            List<Long> holes = interiorHoles(all);
            missingTotal = holes.size();
            buckets.add(new Bucket("(window)", new ArrayList<>(all), holes, all.isEmpty()));
        } else {
            for (Map.Entry<LocalDateTime, TreeSet<Long>> e : seen.entrySet()) {
                List<Long> holes = interiorHoles(e.getValue());
                missingTotal += holes.size();
                buckets.add(new Bucket(t.formatter.format(e.getKey()),
                        new ArrayList<>(e.getValue()), holes, false));
            }
        }

        // Enumerate the WINDOW, not the observations, so an edge bucket that produced nothing is still found.
        LocalDateTime cursor = truncate(from, t.unit);
        int guard = 0;
        while (!cursor.isAfter(to) && guard++ < MAX_BUCKETS) {
            if (!seen.containsKey(cursor)) empty.add(t.formatter.format(cursor));
            cursor = cursor.plus(1, t.unit);
        }

        return new Report(template, scope, t.formatter.format(from), t.formatter.format(to),
                matched, missingTotal, buckets, empty, unmatched);
    }

    /**
     * The numbers absent between the lowest and highest present.
     *
     * <p>⛔ Deliberately bounded by the observed maximum rather than an assumed one: nothing in a file name
     * says how many files the sender meant to send, so extending past the highest observed value would invent
     * missing files. See the class note on the undetectable tail.
     */
    private static List<Long> interiorHoles(TreeSet<Long> present) {
        List<Long> holes = new ArrayList<>();
        if (present.size() < 2) return holes;   // no interior to speak of
        long lo = present.first(), hi = present.last();
        if (hi - lo > MAX_BUCKETS) return holes; // absurd span (a mis-parsed token) — report nothing, not millions
        for (long i = lo + 1; i < hi; i++)
            if (!present.contains(i)) holes.add(i);
        return holes;
    }

    /** Floor {@code t} to the bucket granularity so window enumeration lands on bucket starts. */
    private static LocalDateTime truncate(LocalDateTime t, ChronoUnit unit) {
        return switch (unit) {
            case SECONDS, MINUTES, HOURS, DAYS -> t.truncatedTo(unit);
            case MONTHS -> t.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
            default -> t.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
        };
    }

    // ── template parsing ─────────────────────────────────────────────────────

    private record Parsed(Pattern matcher, DateTimeFormatter formatter, ChronoUnit unit) {}

    private static Parsed parse(String template) {
        if (template == null || template.isBlank())
            throw new IllegalArgumentException("file sequence template is blank");
        int dOpen = template.indexOf('{');
        int dClose = template.indexOf('}', dOpen + 1);
        int sOpen = dClose < 0 ? -1 : template.indexOf('{', dClose + 1);
        int sClose = sOpen < 0 ? -1 : template.indexOf('}', sOpen + 1);
        if (dOpen < 0 || dClose < 0 || sOpen < 0 || sClose < 0)
            throw new IllegalArgumentException("file sequence template needs a {datePattern} token and a "
                    + "{seq} token, e.g. \"CDR_{yyyyMMddHH}_{seq}_*\": " + template);

        String datePattern = template.substring(dOpen + 1, dClose);
        String seqToken = template.substring(sOpen + 1, sClose);
        if (!"seq".equals(seqToken))
            throw new IllegalArgumentException("the second token must be {seq}, not {" + seqToken + "}: "
                    + template);
        if (datePattern.isBlank())
            throw new IllegalArgumentException("the {…} date token is empty: " + template);

        String prefix = template.substring(0, dOpen);
        String between = template.substring(dClose + 1, sOpen);
        String suffix = template.substring(sClose + 1);

        // A trailing '*' means "any suffix"; anything else is literal. Only the trailing position is a
        // wildcard — a '*' elsewhere is a literal character, because guessing at glob semantics here would
        // silently change which names participate.
        String suffixRegex;
        if (suffix.endsWith("*"))
            suffixRegex = Pattern.quote(suffix.substring(0, suffix.length() - 1)) + ".*";
        else suffixRegex = Pattern.quote(suffix);

        String regex = "^" + Pattern.quote(prefix) + "(" + dateRegex(datePattern) + ")"
                + Pattern.quote(between) + "(\\d+)" + suffixRegex + "$";

        DateTimeFormatterBuilder fb = new DateTimeFormatterBuilder().appendPattern(datePattern);
        if (!has(datePattern, "yu")) fb.parseDefaulting(ChronoField.YEAR, 2000);
        if (!has(datePattern, "ML")) fb.parseDefaulting(ChronoField.MONTH_OF_YEAR, 1);
        if (!has(datePattern, "dD")) fb.parseDefaulting(ChronoField.DAY_OF_MONTH, 1);
        if (!has(datePattern, "HhKk")) fb.parseDefaulting(ChronoField.HOUR_OF_DAY, 0);
        if (!has(datePattern, "m")) fb.parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0);
        if (!has(datePattern, "s")) fb.parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0);

        return new Parsed(Pattern.compile(regex), fb.toFormatter(), stepUnit(datePattern));
    }

    /** Each run of pattern letters → {@code \d{len}}; literals quoted. Same rule {@link GapDetector} uses. */
    private static String dateRegex(String pattern) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = pattern.length();
        while (i < n) {
            char c = pattern.charAt(i);
            if (Character.isLetter(c)) {
                int j = i;
                while (j < n && pattern.charAt(j) == c) j++;
                sb.append("\\d{").append(j - i).append('}');
                i = j;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean has(String pattern, String letters) {
        for (int i = 0; i < letters.length(); i++)
            if (pattern.indexOf(letters.charAt(i)) >= 0) return true;
        return false;
    }

    private static ChronoUnit stepUnit(String pattern) {
        if (pattern.indexOf('s') >= 0) return ChronoUnit.SECONDS;
        if (pattern.indexOf('m') >= 0) return ChronoUnit.MINUTES;
        if (pattern.indexOf('H') >= 0 || pattern.indexOf('h') >= 0) return ChronoUnit.HOURS;
        if (pattern.indexOf('d') >= 0 || pattern.indexOf('D') >= 0) return ChronoUnit.DAYS;
        if (pattern.indexOf('M') >= 0 || pattern.indexOf('L') >= 0) return ChronoUnit.MONTHS;
        return ChronoUnit.YEARS;
    }
}
