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
 */
public final class SchemaDrift {

    private SchemaDrift() {}

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
        List<String> declared = new ArrayList<>();
        for (Map<String, Object> f : spec.fields()) declared.add(String.valueOf(f.get("name")));

        Set<String> obs = lower(observed);
        Set<String> dec = lower(declared);
        boolean namesCompared = false;
        for (String d : declared) if (obs.contains(d.toLowerCase(Locale.ROOT))) { namesCompared = true; break; }

        List<String> added = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (namesCompared) {
            for (String o : observed) if (!dec.contains(o.toLowerCase(Locale.ROOT))) added.add(o);
            for (String d : declared) if (!obs.contains(d.toLowerCase(Locale.ROOT))) missing.add(d);
        }
        return new Report(file.getName(), spec.physicalCols(), Math.max(0, c.skipTailCols()), observed.size(),
                List.copyOf(observed), List.copyOf(added), List.copyOf(missing), namesCompared);
    }

    private static Set<String> lower(List<String> names) {
        Set<String> s = new HashSet<>();
        for (String n : names) s.add(n.toLowerCase(Locale.ROOT));
        return s;
    }
}
