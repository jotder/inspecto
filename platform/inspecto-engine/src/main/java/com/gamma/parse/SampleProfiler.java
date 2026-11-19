package com.gamma.parse;

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
 * ({@link #types}, slice 2): DuckDB's own type for each column beside the vote over its values as text.
 *
 * <p>The answer is DuckDB's, unshaped: callers keep their own defaults, fallbacks, evidence and confidence. In
 * particular DuckDB spells "none found" as {@code (empty)}, which is passed through as it is.
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
     * One column's type, answered two ways: {@code duckdb} is DuckDB's own — the type its reader gives the column
     * ({@code DESCRIBE}), unshaped: for an auto-detecting {@code read_csv} that is DuckDB's type sniff, for Parquet the
     * file's declared type, for an all-text read {@code VARCHAR}; {@code type} is the <b>vote</b> over the column's
     * values read as text, with {@code format} the strptime pattern a {@code DATE} was proven with, else {@code null}.
     * Callers pick the answer they give (the parse preview DuckDB's, schema suggest and Add data the vote), so where the
     * two differ the difference is visible here.
     */
    public record ColumnType(String name, String duckdb, String type, String format) {}

    /** Vote candidates, most to least specific; see {@link #vote} for the ordering rationale. */
    private static final List<String> CANDIDATES = List.of("BIGINT", "DOUBLE", "TIMESTAMP", "BOOLEAN");

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

    /** The scratch table {@link #types} votes over; dropped before it returns. */
    private static final String SAMPLE = "sample_profiler_values";

    /**
     * The column types of {@code relation} — anything that may follow {@code FROM} on {@code conn}: a table, or a reader
     * such as {@code read_csv(…)} — from its first {@code limit} rows ({@code limit <= 0}: every row), in the relation's
     * column order. Empty when the relation has no columns. The vote reads each value as text
     * ({@code CAST … AS VARCHAR}) in a temporary table on {@code conn}, so the relation is read twice: once to
     * describe it, once for the values. A typed relation is voted on as DuckDB renders its values (a {@code DOUBLE} 1
     * is {@code 1.0}); the vote is meant for an all-text read, where the text is the file's.
     */
    public static List<ColumnType> types(Connection conn, String relation, int limit) throws SQLException {
        List<String> names = new ArrayList<>();
        List<String> duckdb = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) {
                names.add(rs.getString(1));
                duckdb.add(rs.getString(2));
            }
        }
        if (names.isEmpty()) return List.of();
        StringBuilder proj = new StringBuilder();
        for (String n : names)
            proj.append(proj.isEmpty() ? "" : ", ").append("CAST(").append(SqlIdent.q(n)).append(" AS VARCHAR) AS ").append(SqlIdent.q(n));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP TABLE " + SAMPLE + " AS SELECT " + proj + " FROM " + relation
                    + (limit > 0 ? " LIMIT " + limit : ""));
        }
        try {
            List<ColumnType> out = new ArrayList<>(names.size());
            for (int i = 0; i < names.size(); i++) {
                String[] v = vote(conn, names.get(i));
                out.add(new ColumnType(names.get(i), duckdb.get(i), v[0], v[1]));
            }
            return out;
        } finally {
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + SAMPLE);
            }
        }
    }

    /**
     * The vote over one column's values as text ({@code G1}, formerly {@code SchemaSuggest.inferColumn}): the most
     * specific candidate every non-blank value accepts wins — {@code BIGINT} → {@code DOUBLE} → {@code TIMESTAMP}
     * (demoted to {@code DATE} when every value is midnight, i.e. the strings carried no time part) → {@code BOOLEAN} —
     * else {@code VARCHAR}. Text written with a decimal point or an exponent is never {@code BIGINT}, even {@code 47.00}.
     * {@code BIGINT} is checked before {@code BOOLEAN} so a {@code 0/1} column stays numeric. A column no bare cast
     * accepts is then tried against {@link #DATE_FORMATS}; a unique fit votes {@code DATE} with that format. An
     * all-null/blank column is {@code VARCHAR}: unknown is not evidence. Returns {@code {type, format}}.
     */
    private static String[] vote(Connection conn, String column) throws SQLException {
        String col = SqlIdent.q(column);
        String nonBlank = col + " IS NOT NULL AND trim(" + col + ") <> ''";
        if (count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank) == 0)
            return new String[] {"VARCHAR", null};   // nothing to vote with — unknown is not evidence
        for (String candidate : CANDIDATES) {
            if ("BOOLEAN".equals(candidate)) {
                // Not a castable TIMESTAMP — but it may still be a human-written date.
                String fmt = dateFormat(conn, col, nonBlank);
                if (fmt != null) return new String[] {"DATE", fmt};
            }
            // ⚠ DuckDB's TRY_CAST('1.5' AS BIGINT) SUCCEEDS by rounding, so a bare cast check lets
            // BIGINT swallow every decimal column. The round-trip guard: BIGINT only wins a value
            // whose DOUBLE cast equals its BIGINT cast (no fractional part was lost). And text written
            // with a decimal point or an exponent ('47.00', '1e3') is a decimal even when its fraction
            // is zero (DECIMAL-TEXT-NOT-BIGINT-1): a later '12.25' in that column must not be cut to 12.
            String accepts = "BIGINT".equals(candidate)
                    ? "(TRY_CAST(" + col + " AS BIGINT) IS NOT NULL"
                            + " AND TRY_CAST(" + col + " AS DOUBLE) = TRY_CAST(" + col + " AS BIGINT)"
                            + " AND NOT regexp_matches(" + col + ", '[.eE]'))"
                    : "TRY_CAST(" + col + " AS " + candidate + ") IS NOT NULL";
            long failures = count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank
                    + " AND NOT " + accepts);
            if (failures > 0) continue;
            if ("TIMESTAMP".equals(candidate)) {
                long timed = count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank
                        + " AND strftime(TRY_CAST(" + col + " AS TIMESTAMP), '%H:%M:%S') <> '00:00:00'");
                // A DATE carries the format it was proven with when one fits, so it lands as DATE
                // whatever the pipeline's own date_formats list says.
                return timed == 0 ? new String[] {"DATE", dateFormat(conn, col, nonBlank)} : new String[] {"TIMESTAMP", null};
            }
            return new String[] {candidate, null};
        }
        return new String[] {"VARCHAR", null};
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
        String first = winners.get(0);
        for (String other : winners.subList(1, winners.size())) {
            if (count(conn, "SELECT count(*) FROM " + SAMPLE + " WHERE " + nonBlank
                    + " AND TRY_STRPTIME(" + col + ", '" + first + "') <> TRY_STRPTIME(" + col + ", '"
                    + other + "')") > 0) return null;
        }
        return first;
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
