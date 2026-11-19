package com.gamma.parse;

import com.gamma.etl.DecimalMarkText;
import com.gamma.util.SqlIdent;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The sample profiler</b> ({@code BASIC-ELT-PROFILE-1} BE-3): what a sample file is, read once for every screen that
 * asks — Add data's draft ({@code OnboardDraft}), the parse preview ({@code ComponentPreview.parsing}) and schema
 * suggest ({@code SchemaSuggest.infer}). The <b>dialect</b> ({@link #dialect}, slice 1): DuckDB's {@code sniff_csv} over
 * the file with no authored options, so the answer is the FILE's, not an echo of a grammar. The <b>column types</b>
 * ({@link #types}, slice 2 and the operator's rulings of 2026-11-19): the vote over each column's values as text, the
 * ONE type answer all three screens give (with DuckDB's own type beside it, for a file that declares its types).
 *
 * <p>The dialect is DuckDB's, unshaped: callers keep their own defaults, fallbacks, evidence and confidence. In
 * particular DuckDB spells "none found" as {@code (empty)}, which is passed through as it is. The type is the same in
 * every caller; a caller adds only its own words (Add data's evidence and confidence) and what only it can see (the
 * person's stated types, a fraction beyond the sample).
 */
public final class SampleProfiler {

    private SampleProfiler() {}

    /** {@code sniff_csv} column → the {@code parsing.delimited.*} option it describes. */
    private static final Map<String, String> OPTIONS = options();

    private static Map<String, String> options() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Delimiter", "delimiter");
        m.put("Quote", "quote");
        m.put("Escape", "escape");
        m.put("Comment", "comment");
        m.put("HasHeader", "has_header");
        m.put("SkipRows", "skip_header_lines");
        m.put("DateFormat", "date_format");
        m.put("TimestampFormat", "timestamp_format");
        return Collections.unmodifiableMap(m);
    }

    /**
     * A sniffed dialect: {@code options} maps each grammar option name DuckDB reported to its value, in
     * {@code sniff_csv}'s column order. A NUL character (DuckDB's "none", e.g. no escape) becomes {@code ""}; an option
     * DuckDB did not report is absent. Empty when the sniff returned no row.
     */
    public record Dialect(Map<String, String> options) {
        public Dialect {
            options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }

        public String delimiter() {
            return options.get("delimiter");
        }

        public String quote() {
            return options.get("quote");
        }

        /** Whether the sniff found a header line; {@code false} when it reported none. */
        public boolean header() {
            return Boolean.parseBoolean(options.get("has_header"));
        }

        /** The lines the sniff skips before the header (or the first record); {@code 0} when it reported none. */
        public int skip() {
            String s = options.get("skip_header_lines");
            return s == null || !s.matches("\\d{1,9}") ? 0 : Integer.parseInt(s);
        }
    }

    /**
     * Sniff the dialect of {@code file} on {@code conn} (which must be allowed to read it). {@code encoding} is a DuckDB
     * {@code read_csv} encoding name, or {@code null} for DuckDB's default (UTF-8). Throws {@link SQLException} when
     * DuckDB cannot settle the dialect (mixed line endings, for one): the caller decides whether that is fatal.
     */
    public static Dialect dialect(Connection conn, Path file, String encoding) throws SQLException {
        String encodingArg = encoding == null ? "" : ", encoding = " + SqlIdent.sqlStr(encoding);
        Map<String, String> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM sniff_csv(" + SqlIdent.pathStr(file) + encodingArg + ")")) {
            if (!rs.next()) return new Dialect(Map.of());
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String option = OPTIONS.get(md.getColumnLabel(i));
                Object v = option == null ? null : rs.getObject(i);
                if (v != null) out.put(option, v.toString().replace("\0", ""));
            }
        }
        return new Dialect(out);
    }

    // ── column types ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One column's type, as every screen that asks proposes it (operator rulings 2026-11-19, {@code BASIC-ELT-PROFILE-1}
     * U3/BE-3): {@code type} with {@code format}, the strptime pattern a {@code DATE} or {@code TIMESTAMP} is read with
     * (else {@code null}), and {@code decimal}, the decimal mark a number column's text needs (else {@code null}).
     * {@code fits} of {@code of} non-blank sampled values read as that type; when fewer than all do (almost all dates,
     * {@link #mostly}) {@code odd} is one that does not, and the import leaves such values blank. {@code duckdb} is
     * DuckDB's own type for the column ({@code DESCRIBE} of the relation): for Parquet the file's declared type, for an
     * all-text read {@code VARCHAR} — the caller that reads a file which declares its types takes that one instead.
     */
    public record ColumnType(String name, String duckdb, String type, String format, String decimal,
                             long fits, long of, String odd) {}

    /**
     * Date-with-a-time spellings, in preference order: the format a {@code TIMESTAMP} is proposed with (the first every
     * value parses with), and the formats a column no bare cast reads is tried against.
     */
    public static final List<String> TIMESTAMP_FORMATS = List.of("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S",
            "%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%dT%H:%M:%S.%f", "%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M",
            "%d/%m/%Y %H:%M:%S", "%m/%d/%Y %H:%M:%S", "%d/%m/%Y %H:%M", "%d.%m.%Y %H:%M:%S");

    /**
     * Human date spellings tried when a column is not a bare castable TIMESTAMP (and, for a DATE, to pin the ISO
     * spelling too), in preference order. ⚠ DuckDB's strptime matches literal whitespace and punctuation EXACTLY, so
     * {@code March 22,2025} and {@code March 22, 2025} need separate entries; {@code %d}/{@code %m} accept one or two
     * digits; month names match case-insensitively.
     */
    public static final List<String> DATE_FORMATS = List.of(
            "%Y-%m-%d", "%Y/%m/%d",
            "%B %d,%Y", "%B %d, %Y", "%B %d %Y", "%d %B %Y", "%d %B, %Y",
            "%b %d,%Y", "%b %d, %Y", "%b %d %Y", "%d %b %Y", "%d-%b-%Y",
            "%d/%m/%Y", "%m/%d/%Y", "%d-%m-%Y", "%m-%d-%Y", "%d.%m.%Y");

    /**
     * {@code LA-FULL-CYCLE-TEST-1} finding 3, now every screen's: a column whose sampled values are <i>almost all</i>
     * dates, or dates with a time, in one format is proposed as that type, and the values that do not fit are left blank
     * by the import. "Almost all" is at least nine in ten, and at least this many fitting values: below ten agreeing
     * values one stray value is already a tenth of the column, so the share says nothing. ⛔ Never for numbers (operator
     * ruling 2026-11-19): a column with one value that is not a number stays text.
     */
    public static final int MOSTLY_MIN = 10;

    /** Whether {@code fits} of {@code of} values is "almost all" ({@link #MOSTLY_MIN}). */
    public static boolean mostly(long fits, long of) {
        return fits >= MOSTLY_MIN && fits * 10 >= of * 9;
    }

    /** The scratch table {@link #types} votes over; dropped before it returns. */
    private static final String SAMPLE = "sample_profiler_values";

    /** The columns of {@code relation} (anything that may follow {@code FROM}) with DuckDB's type for each, in order. */
    public static Map<String, String> describe(Connection conn, String relation) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        }
        return out;
    }

    /** {@link #types(Connection, String, int, String)} with no decimal mark. */
    public static List<ColumnType> types(Connection conn, String relation, int limit) throws SQLException {
        return types(conn, relation, limit, null);
    }

    /**
     * The column types of {@code relation} — anything that may follow {@code FROM} on {@code conn}: a table, or a reader
     * such as {@code read_csv(…)} — from its first {@code limit} rows ({@code limit <= 0}: every row), in the relation's
     * column order. Empty when the relation has no columns. {@code decimalMark} ({@code "."} or {@code ","}, else
     * {@code null}) is the mark number columns are written with — the Grammar's {@code decimal_separator}, or the one
     * Add data detected. The vote reads each value as text ({@code CAST … AS VARCHAR}) in a temporary table on
     * {@code conn}; it is meant for an all-text read, where the text is the file's (a typed relation is voted on as
     * DuckDB renders its values: a {@code DOUBLE} 1 is {@code 1.0}).
     */
    public static List<ColumnType> types(Connection conn, String relation, int limit, String decimalMark) throws SQLException {
        Map<String, String> described = describe(conn, relation);
        if (described.isEmpty()) return List.of();
        List<String> names = new ArrayList<>(described.keySet());
        StringBuilder proj = new StringBuilder();
        for (String n : names)
            proj.append(proj.isEmpty() ? "" : ", ").append("CAST(").append(SqlIdent.q(n)).append(" AS VARCHAR) AS ").append(SqlIdent.q(n));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP TABLE " + SAMPLE + " AS SELECT " + proj + " FROM " + relation
                    + (limit > 0 ? " LIMIT " + limit : ""));
        }
        try {
            List<ColumnType> out = new ArrayList<>(names.size());
            for (String n : names) out.add(vote(conn, n, described.get(n), decimalMark));
            return out;
        } finally {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + SAMPLE);
            }
        }
    }

    /**
     * The vote over one column's values as text ({@code G1}, formerly {@code SchemaSuggest.inferColumn}, now every
     * screen's): the first that every non-blank value accepts wins —
     * <ol>
     *   <li>a number written with {@code mark} that a plain cast cannot read ({@code 3,50}, {@code 1.234,50}):
     *       {@code DOUBLE} carrying the mark ({@link DecimalMarkText#numberType}). Under a {@code ,} mark a column with a
     *       value the mark does not read ({@code 3.5}, which the import would read as 35) is never a number;</li>
     *   <li>{@code BIGINT} (text written with a decimal point or an exponent is never one, even {@code 47.00}, and it is
     *       checked before {@code BOOLEAN} so a {@code 0/1} column stays numeric), then {@code DOUBLE};</li>
     *   <li>a bare {@code TIMESTAMP} cast: {@code DATE} when every value is midnight (the text carried no time), else
     *       {@code TIMESTAMP} with the first {@link #TIMESTAMP_FORMATS} entry every value parses with
     *       ({@code 2025-03-22T09:30:00} → {@code %Y-%m-%dT%H:%M:%S});</li>
     *   <li>one {@link #DATE_FORMATS} entry ({@code March 22, 2025} → {@code DATE %B %d, %Y}), ambiguity refused; then one
     *       {@link #TIMESTAMP_FORMATS} entry a bare cast does not read ({@code 22/03/2025 09:30:00});</li>
     *   <li>{@code TIME} ({@code 09:30:00}), then {@code BOOLEAN};</li>
     *   <li>almost all dates with a time, then almost all dates, in one format ({@link #mostly});</li>
     * </ol>
     * else {@code VARCHAR} — including a column that is almost all numbers. An all-null/blank column is {@code VARCHAR}:
     * unknown is not evidence.
     */
    private static ColumnType vote(Connection conn, String column, String duckdb, String mark) throws SQLException {
        String col = SqlIdent.q(column);
        String nonBlank = col + " IS NOT NULL AND trim(" + col + ") <> ''";
        long n = count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank);
        if (n == 0) return all(column, duckdb, "VARCHAR", null, null, 0);   // nothing to vote with
        boolean numbers = true;   // whether a plain number cast may answer
        if (mark != null) {
            List<String> values = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + col + " FROM " + SAMPLE + " WHERE " + nonBlank)) {
                while (rs.next()) values.add(rs.getString(1));
            }
            if (DecimalMarkText.numberType(values, mark) != null) return all(column, duckdb, "DOUBLE", null, mark, n);
            // under a comma mark the engine reads '3.5' as 35 (the point groups digits): a column the mark does not read
            // value by value is no number, or the type answer and the import would disagree
            if (",".equals(mark))
                for (String v : values) numbers &= DecimalMarkText.reads(v, mark);
        }
        // ⚠ DuckDB's TRY_CAST('1.5' AS BIGINT) SUCCEEDS by rounding, so a bare cast check lets BIGINT swallow every
        // decimal column. The round-trip guard: BIGINT only wins a value whose DOUBLE cast equals its BIGINT cast (no
        // fractional part was lost). And text written with a decimal point or an exponent ('47.00', '1e3') is a decimal
        // even when its fraction is zero (DECIMAL-TEXT-NOT-BIGINT-1): a later '12.25' in that column must not be cut to 12.
        if (numbers && every(conn, nonBlank, "(TRY_CAST(" + col + " AS BIGINT) IS NOT NULL"
                + " AND TRY_CAST(" + col + " AS DOUBLE) = TRY_CAST(" + col + " AS BIGINT)"
                + " AND NOT regexp_matches(" + col + ", '[.eE]'))"))
            return all(column, duckdb, "BIGINT", null, null, n);
        if (numbers && every(conn, nonBlank, "TRY_CAST(" + col + " AS DOUBLE) IS NOT NULL")) return all(column, duckdb, "DOUBLE", null, null, n);
        if (every(conn, nonBlank, "TRY_CAST(" + col + " AS TIMESTAMP) IS NOT NULL")) {
            long timed = count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank
                    + " AND strftime(TRY_CAST(" + col + " AS TIMESTAMP), '%H:%M:%S') <> '00:00:00'");
            // A DATE carries the format it was proven with when one fits, so it lands as DATE whatever the pipeline's
            // own date_formats list says; a TIMESTAMP likewise names its spelling (the T form included)
            return timed == 0 ? all(column, duckdb, "DATE", dateFormat(conn, col, nonBlank), null, n)
                    : all(column, duckdb, "TIMESTAMP", firstFormat(conn, col, nonBlank, TIMESTAMP_FORMATS), null, n);
        }
        String date = dateFormat(conn, col, nonBlank);
        if (date != null) return all(column, duckdb, "DATE", date, null, n);
        ColumnType time = bestFormat(conn, column, duckdb, nonBlank, "TIMESTAMP", TIMESTAMP_FORMATS, n);
        if (time != null && time.fits() == n) return time;
        if (every(conn, nonBlank, "TRY_CAST(" + col + " AS TIME) IS NOT NULL")) return all(column, duckdb, "TIME", null, null, n);
        if (every(conn, nonBlank, "TRY_CAST(" + col + " AS BOOLEAN) IS NOT NULL")) return all(column, duckdb, "BOOLEAN", null, null, n);
        // finding 3: almost all dates with a time, then almost all dates - the odd values are left blank by the import
        if (time != null && mostly(time.fits(), n)) return time;
        ColumnType day = bestFormat(conn, column, duckdb, nonBlank, "DATE", DATE_FORMATS, n);
        if (day != null && mostly(day.fits(), n)) return day;
        return all(column, duckdb, "VARCHAR", null, null, n);
    }

    private static ColumnType all(String column, String duckdb, String type, String format, String decimal, long n) {
        return new ColumnType(column, duckdb, type, format, decimal, n, n, null);
    }

    /** Whether every non-blank sampled value meets {@code accepts}. */
    private static boolean every(Connection conn, String nonBlank, String accepts) throws SQLException {
        return count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank + " AND NOT " + accepts) == 0;
    }

    /** The first of {@code formats} every non-blank sampled value parses with, else {@code null}. */
    private static String firstFormat(Connection conn, String col, String nonBlank, List<String> formats) throws SQLException {
        for (String f : formats)
            if (every(conn, nonBlank, "TRY_STRPTIME(" + col + ", " + SqlIdent.sqlStr(f) + ") IS NOT NULL")) return f;
        return null;
    }

    /**
     * The one {@link #DATE_FORMATS} entry every non-blank value parses with, or {@code null}.
     *
     * <p>⛔ <b>Ambiguity is refused, not guessed.</b> Where several formats accept the whole sample and
     * any value parses to a DIFFERENT date under two of them — {@code 03/04/2025} under
     * {@code %d/%m/%Y} vs {@code %m/%d/%Y} — the column gets no format (and so stays {@code VARCHAR}
     * unless a bare cast proved otherwise). One value such as {@code 13/04/2025} settles it. Formats
     * that agree on every value ({@code %B} and {@code %b} over {@code May}) are not ambiguous; the
     * first in preference order wins. The value is tested exactly as the engine parses it
     * ({@code TRY_STRPTIME} over the untrimmed text), so a suggested format cannot NULL a sample value.
     */
    private static String dateFormat(Connection conn, String col, String nonBlank) throws SQLException {
        List<String> winners = new ArrayList<>();
        for (String f : DATE_FORMATS) {
            if (count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank
                    + " AND TRY_STRPTIME(" + col + ", '" + f + "') IS NULL") == 0) winners.add(f);
        }
        if (winners.isEmpty()) return null;
        return ambiguous(conn, col, nonBlank, winners.get(0), winners.subList(1, winners.size())) ? null : winners.get(0);
    }

    /** Whether any value parses to a different date under {@code first} than under one of {@code others}. */
    private static boolean ambiguous(Connection conn, String col, String nonBlank, String first, List<String> others)
            throws SQLException {
        for (String other : others) {
            if (count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank
                    + " AND TRY_STRPTIME(" + col + ", '" + first + "') <> TRY_STRPTIME(" + col + ", '"
                    + other + "')") > 0) return true;
        }
        return false;
    }

    /**
     * The one of {@code formats} that reads the most values, as a {@code type} answer with its count and one value it
     * does not read; {@code null} when none reads any, or when another that reads as many values reads one of them as a
     * different date (day first or month first: never guessed between).
     */
    private static ColumnType bestFormat(Connection conn, String column, String duckdb, String nonBlank, String type,
                                         List<String> formats, long n) throws SQLException {
        String col = SqlIdent.q(column);
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int k = 0; k < formats.size(); k++)
            sql.append(k == 0 ? "" : ", ").append("count(*) FILTER (WHERE TRY_STRPTIME(").append(col).append(", ")
                    .append(SqlIdent.sqlStr(formats.get(k))).append(") IS NOT NULL)");
        long[] counts = new long[formats.size()];
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql + " FROM " + SAMPLE + " WHERE " + nonBlank)) {
            rs.next();
            for (int k = 0; k < formats.size(); k++) counts[k] = rs.getLong(k + 1);
        }
        int best = -1;
        for (int k = 0; k < formats.size(); k++) if (counts[k] > 0 && (best < 0 || counts[k] > counts[best])) best = k;
        if (best < 0) return null;
        List<String> tied = new ArrayList<>();
        for (int k = best + 1; k < formats.size(); k++) if (counts[k] == counts[best]) tied.add(formats.get(k));
        String f = formats.get(best);
        if (ambiguous(conn, col, nonBlank, f, tied)) return null;
        String odd = null;
        if (counts[best] < n)
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT " + col + " FROM " + SAMPLE
                    + " WHERE " + nonBlank + " AND TRY_STRPTIME(" + col + ", " + SqlIdent.sqlStr(f) + ") IS NULL LIMIT 1")) {
                if (rs.next()) odd = rs.getString(1);
            }
        return new ColumnType(column, duckdb, type, f, null, counts[best], n, odd);
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
