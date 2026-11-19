package com.gamma.etl;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema drift &amp; new-field detection for header-bearing delimited files ({@code quality.schema.drift},
 * board {@code SP-DQ-06}).
 *
 * <p>The native read hands DuckDB the DECLARED shape ({@code columns={'c0'…}}, {@code header=false},
 * {@code skip=header}) — so the header line is skipped, never read, an added column is silently dropped
 * (or tolerated by {@code skip_tail_columns}) and a column inserted mid-row silently misaligns every
 * positional selector to its right. Nothing recorded "columns seen vs columns declared" anywhere. This
 * reads the header once, the way DuckDB itself names it, and diffs it against {@code raw.fields[]}.
 *
 * <p>⚠ Two comparisons, because selectors are POSITIONAL. Width is always compared: fewer columns than
 * {@code maxSelector + 1} means rows are rejected, more than declared + {@code skip_tail_columns} means a
 * new field. Names are compared ONLY when the schema was evidently authored from this header — at least
 * one declared name occurs in it (case-insensitive). A positional lane may legitimately name
 * {@code customer_id} for the header "Customer ID"; diffing those names would flag every file forever.
 *
 * <p>Detection only. The parse still runs; the report is a {@code quality.schema_drift} Signal per batch,
 * emitted above this foundation layer (the etl package imports no event/signal types — same seam as
 * {@code PipelineConsignmentSignal}).
 *
 * <p><b>The one comparison</b> ({@code BASIC-ELT-PROFILE-1} B5): {@link #diff} is how the product compares the
 * columns a schema declares with the columns a file or sample shows, and {@link #settle} the one type a new column
 * settles to across files. Four callers that each compared on their own now ask it: this run-time detector, schema
 * suggest's drift for the editor ({@code SchemaSuggest.drift}), and Add data's later-file check and new-column
 * typing ({@code OnboardImport.checkColumns}, {@code DatasetNewColumns}). What each does with the answer stays its
 * own: a Standard Pipeline reports drift and reads on; Basic refuses a file with a missing or moved column and may
 * keep a new one.
 */
public final class SchemaDrift {

    private SchemaDrift() {}

    // ── the one comparison ────────────────────────────────────────────────────────────────────────────────

    /** A column a schema declares ({@code raw.fields[]}): its selector, name and type ({@code null} when none is declared). */
    public record Declared(String selector, String name, String type) {
        /** What finds the column: the selector, else the name. */
        String key() {
            return selector == null || selector.isBlank() ? name : selector.trim();
        }
    }

    /**
     * A column a file or sample shows, in its order. {@code name} is what a declared name is compared with,
     * {@code selector} what a by-name selector is compared with (a flattened JSON field's dotted path; else the name);
     * {@code type} and {@code format} are what the sample profiler answered ({@code null} when not asked).
     */
    public record Observed(String name, String selector, String type, String format) {}

    /** How a declared field finds its column. */
    public enum By {
        /**
         * A selector of digits is the column's position, and the name there must be the declared one; a declared name
         * found at another position has moved (delimited text read by position, with a header).
         */
        POSITION,
        /** A selector of digits is the column's position; names are not compared (a positional lane, a parsed sample). */
        WIDTH,
        /** Every selector is a name or a path. */
        NAME
    }

    /**
     * One declared field and where it was found: {@code at} is the observed column's position ({@code -1}: missing),
     * {@code moved} that it was found by name at another position than its selector says ({@link By#POSITION}).
     */
    public record Placed(Declared field, int at, Observed seen, boolean moved) {
        public boolean missing() {
            return at < 0;
        }
    }

    /**
     * The comparison: every declared field in declared order ({@code fields}); the observed columns no declared field
     * reads ({@code added}, in file order); of those, the ones a reader can take on ({@code keepable}: by position only
     * those right after the declared width, in a row, since anything further is read by position too; by name every
     * one); {@code declaredWidth} is the widest position selector plus one ({@code 0} when none is a position).
     */
    public record Diff(List<Placed> fields, List<Observed> added, List<Observed> keepable, int declaredWidth,
                       int observedWidth) {

        public List<Declared> missing() {
            return fields.stream().filter(Placed::missing).map(Placed::field).toList();
        }

        /** The fields found whose declared type differs from the observed one (a field with no declared type never does). */
        public List<Placed> typeChanged() {
            return fields.stream().filter(p -> !p.missing() && p.field().type() != null && !p.field().type().isBlank()
                    && p.seen().type() != null && !p.field().type().trim().equalsIgnoreCase(p.seen().type())).toList();
        }
    }

    private static final java.util.regex.Pattern POSITION_SELECTOR = java.util.regex.Pattern.compile("\\d{1,9}");

    /**
     * Compare {@code declared} with {@code observed}, addressed {@code by}. Exact names and selectors: a caller that
     * compares without case passes both sides lower-cased. Pure.
     */
    public static Diff diff(List<Declared> declared, List<Observed> observed, By by) {
        List<Placed> fields = new ArrayList<>(declared.size());
        Set<Integer> claimed = new HashSet<>();
        Set<String> declaredKeys = new HashSet<>();
        int width = 0;
        for (Declared d : declared) {
            String key = d.key();
            boolean positional = by != By.NAME && key != null && POSITION_SELECTOR.matcher(key).matches();
            int at = -1;
            boolean moved = false;
            if (positional) {
                int sel = Integer.parseInt(key);
                width = Math.max(width, sel + 1);
                if (by == By.WIDTH) {
                    if (sel < observed.size()) at = sel;
                } else {
                    declaredKeys.add(d.name());
                    if (sel < observed.size() && observed.get(sel).name().equals(d.name())) at = sel;
                    else {
                        at = indexOf(observed, d.name(), false);
                        moved = at >= 0;
                    }
                }
            } else {
                declaredKeys.add(key);
                at = indexOf(observed, key, true);
            }
            if (at >= 0) claimed.add(at);
            fields.add(new Placed(d, at, at < 0 ? null : observed.get(at), moved));
        }
        List<Observed> added = new ArrayList<>();
        for (int i = 0; i < observed.size(); i++) {
            Observed o = observed.get(i);
            boolean read = switch (by) {
                case WIDTH -> claimed.contains(i);
                case POSITION -> declaredKeys.contains(o.name());
                case NAME -> declaredKeys.contains(o.selector());
            };
            if (!read) added.add(o);
        }
        List<Observed> keepable = new ArrayList<>();
        if (by == By.NAME) keepable.addAll(added);
        else
            for (int i = width; i < observed.size(); i++) {
                if (!added.contains(observed.get(i))) break;
                keepable.add(observed.get(i));
            }
        return new Diff(List.copyOf(fields), List.copyOf(added), List.copyOf(keepable), width, observed.size());
    }

    private static int indexOf(List<Observed> observed, String key, boolean bySelector) {
        for (int i = 0; i < observed.size(); i++)
            if (key != null && key.equals(bySelector ? observed.get(i).selector() : observed.get(i).name())) return i;
        return -1;
    }

    /** The types a column settles to; any other answer (text, an unknown type) settles to text. */
    static final Set<String> SETTLED = Set.of("BIGINT", "DOUBLE", "DATE", "TIMESTAMP", "TIME", "BOOLEAN");
    private static final Set<String> NUMBERS = Set.of("BIGINT", "DOUBLE");

    /**
     * The one type a column settles to across files: {@code perFile} is each file's sample-profiler answer for it
     * ({@code null} for a file without it). The same type and format in every file; whole and decimal numbers together
     * are {@code DOUBLE}; anything else - a file without it, two types, two formats, or a type outside
     * {@link #SETTLED} - is text ({@code VARCHAR}, no format). Answers the first file's column with that type.
     */
    public static Observed settle(List<Observed> perFile) {
        if (perFile.isEmpty() || perFile.get(0) == null) throw new IllegalArgumentException("the first file must carry the column");
        Observed first = perFile.get(0);
        String type = first.type(), format = first.format();
        for (Observed x : perFile.subList(1, perFile.size())) {
            if (x == null || type == null || x.type() == null) return asText(first);
            if (NUMBERS.contains(type) && NUMBERS.contains(x.type())) {
                if (!type.equals(x.type())) type = "DOUBLE";
            } else if (!type.equals(x.type()) || !java.util.Objects.equals(format, x.format())) {
                return asText(first);
            }
        }
        if (type == null || !SETTLED.contains(type)) return asText(first);
        return new Observed(first.name(), first.selector(), type, format);
    }

    private static Observed asText(Observed o) {
        return new Observed(o.name(), o.selector(), "VARCHAR", null);
    }

    /**
     * One file's observed-vs-declared shape.
     *
     * @param declaredWidth  {@code maxSelector + 1} — fewer observed columns means rejected rows
     * @param toleratedExtra {@code skip_tail_columns} — trailing columns the reader drops by design
     * @param namesCompared  false on a positional lane (no declared name occurs in the header)
     */
    public record Report(String file, int declaredWidth, int toleratedExtra, int observedWidth,
                         List<String> observed, List<String> added, List<String> missing,
                         boolean namesCompared) {

        public boolean drifted() {
            return observedWidth < declaredWidth
                    || observedWidth > declaredWidth + toleratedExtra
                    || !added.isEmpty() || !missing.isEmpty();
        }
    }

    /**
     * Compare {@code file}'s header with the schema's {@code raw.fields[]}. Returns {@code null} when
     * the frontend carries no header ({@code has_header: false}) or the header could not be read — a
     * detector that cannot see must say nothing, never guess.
     */
    public static Report detect(File file, Map<String, Object> schemaConfig, PipelineConfig cfg,
                                Connection conn) {
        PipelineConfig.CsvSettings c = cfg.csv();
        if (!c.hasHeader()) return null;
        List<String> observed;
        try {
            observed = DuckDbCsvIngester.observedHeader(file, cfg, conn);
        } catch (SQLException e) {
            return null;
        }
        if (observed.isEmpty()) return null;

        ParserSpec spec = ParserSpec.fromSchema(schemaConfig);
        // names are compared without case, so both sides go to the diff lower-cased
        List<Declared> declared = new ArrayList<>();
        for (Map<String, Object> f : spec.fields())
            declared.add(new Declared(String.valueOf(f.get("selector")), String.valueOf(f.get("name")).toLowerCase(Locale.ROOT), null));
        List<Observed> seen = new ArrayList<>();
        for (String o : observed) seen.add(new Observed(o.toLowerCase(Locale.ROOT), o, null, null));

        Set<String> obs = new HashSet<>();
        for (Observed o : seen) obs.add(o.name());
        boolean namesCompared = declared.stream().anyMatch(d -> obs.contains(d.name()));

        List<String> added = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (namesCompared) {
            Diff d = diff(declared, seen, By.POSITION);
            for (Observed o : d.added()) added.add(o.selector());   // the header's own spelling
            for (int i = 0; i < d.fields().size(); i++)
                if (d.fields().get(i).missing()) missing.add(String.valueOf(spec.fields().get(i).get("name")));
        }
        return new Report(file.getName(), spec.physicalCols(), Math.max(0, c.skipTailCols()), observed.size(),
                List.copyOf(observed), List.copyOf(added), List.copyOf(missing), namesCompared);
    }
}
