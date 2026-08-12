package com.gamma.pipeline.exec;

import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>G1 ({@code consignment-chain-plan.md} S4) — draft-schema inference over already-parsed sample
 * rows.</b> TRY_CAST voting on a throwaway DuckDB (the {@link ComponentPreview} scratch idiom): per
 * column, the most specific candidate type every non-blank value accepts wins — {@code BIGINT} →
 * {@code DOUBLE} → {@code TIMESTAMP} (demoted to {@code DATE} when every value is midnight, i.e. the
 * strings carried no time part) → {@code BOOLEAN} — else {@code VARCHAR}. {@code BIGINT} is checked
 * before {@code BOOLEAN} so a {@code 0/1} column stays numeric.
 *
 * <p><b>A draft, never applied.</b> The caller returns it for a human to edit (the
 * {@code ParserPlugin.suggest} posture: "never auto-applied"), and real ingest keeps
 * {@code auto_detect=false} ({@code DuckDbCsvIngester}) — inference belongs at authoring time,
 * determinism at run time. An all-null/blank column is {@code VARCHAR}: unknown is not evidence.
 */
public final class SchemaSuggest {

    private SchemaSuggest() {}

    /** One inferred field of the draft {@code raw.fields} list. */
    public record Field(String name, String type) {}

    /** Candidates most→least specific; see the class doc for the ordering rationale. */
    private static final List<String> CANDIDATES = List.of("BIGINT", "DOUBLE", "TIMESTAMP", "BOOLEAN");

    /**
     * Infer a draft field list from {@code sampleRows} (the parsing preview's own output shape:
     * string-valued maps). Throws {@link IllegalArgumentException} on an empty or column-less sample.
     */
    public static List<Field> infer(List<Map<String, Object>> sampleRows)
            throws SQLException, java.io.IOException {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        List<String> columns = ScratchTables.columnsOf(sampleRows);
        if (columns.isEmpty()) throw new IllegalArgumentException("sample rows have no columns");

        File db = DuckDbUtil.tempDbFile("suggest_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, "suggest_input", columns, sampleRows);
            List<Field> out = new ArrayList<>(columns.size());
            for (String c : columns) out.add(new Field(c, inferColumn(conn, c)));
            return out;
        } finally {
            DuckDbUtil.deleteTempDb(db);   // throwaway scratch DB
        }
    }

    private static String inferColumn(Connection conn, String column) throws SQLException {
        String col = "\"" + column.replace("\"", "\"\"") + "\"";
        String nonBlank = col + " IS NOT NULL AND trim(" + col + ") <> ''";
        if (count(conn, "SELECT count(*) FROM suggest_input WHERE " + nonBlank) == 0)
            return "VARCHAR";   // nothing to vote with — unknown is not evidence
        for (String candidate : CANDIDATES) {
            // ⚠ DuckDB's TRY_CAST('1.5' AS BIGINT) SUCCEEDS by rounding, so a bare cast check lets
            // BIGINT swallow every decimal column. The round-trip guard: BIGINT only wins a value
            // whose DOUBLE cast equals its BIGINT cast (no fractional part was lost).
            String accepts = "BIGINT".equals(candidate)
                    ? "(TRY_CAST(" + col + " AS BIGINT) IS NOT NULL"
                            + " AND TRY_CAST(" + col + " AS DOUBLE) = TRY_CAST(" + col + " AS BIGINT))"
                    : "TRY_CAST(" + col + " AS " + candidate + ") IS NOT NULL";
            long failures = count(conn, "SELECT count(*) FROM suggest_input WHERE " + nonBlank
                    + " AND NOT " + accepts);
            if (failures > 0) continue;
            if ("TIMESTAMP".equals(candidate)) {
                long timed = count(conn, "SELECT count(*) FROM suggest_input WHERE " + nonBlank
                        + " AND strftime(TRY_CAST(" + col + " AS TIMESTAMP), '%H:%M:%S') <> '00:00:00'");
                return timed == 0 ? "DATE" : "TIMESTAMP";
            }
            return candidate;
        }
        return "VARCHAR";
    }

    private static long count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
