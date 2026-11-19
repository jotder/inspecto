package com.gamma.parse;

import com.gamma.util.SqlIdent;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>The sample profiler</b> ({@code BASIC-ELT-PROFILE-1} BE-3): what a sample file is, read once for every screen that
 * asks — Add data's draft ({@code OnboardDraft}) and the parse preview ({@code ComponentPreview.parsing}). Slice 1 holds
 * the <b>dialect</b> only: DuckDB's {@code sniff_csv} over the file with no authored options, so the answer is the
 * FILE's, not an echo of a grammar. Column types join here in the next slice.
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
}
