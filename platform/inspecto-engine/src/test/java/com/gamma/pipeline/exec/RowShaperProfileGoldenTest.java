package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden output of the {@code transform.profile} Step (ANALYTICS-CORE-1 M4): the exact output columns, in order, and
 * every cell for numbers, text, dates, booleans, NULLs and an empty relation. Written against the hand-built profile
 * SQL BEFORE it moved onto the Analytics Kernel's {@code ColumnProfiler}, and kept green after, so the Step's output
 * contract is proven unchanged by the move.
 */
class RowShaperProfileGoldenTest {

    private static final List<String> COLUMNS =
            List.of("column_name", "row_count", "null_count", "distinct_count", "min_value", "max_value");

    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("rs_profile_golden_");
        conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE g AS SELECT * FROM (VALUES "
                    + "(1, 'b', 10.5, DATE '2024-03-01', TRUE),"
                    + "(2, 'a', NULL, DATE '2024-01-15', FALSE),"
                    + "(3, NULL, -2.25, NULL, TRUE),"
                    + "(4, 'b', 10.5, DATE '2023-12-31', NULL)) t(id, name, amount, day, flag)");
            st.execute("CREATE TABLE e AS SELECT * FROM g WHERE false");
        }
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    @Test
    void everyColumnOfAMixedRelation() throws Exception {
        String t = profile("g", Map.of());
        assertEquals(COLUMNS, columns(t));
        assertEquals(List.of(
                "amount|4|1|2|-2.25|10.50",
                "day|4|1|3|2023-12-31|2024-03-01",
                "flag|4|1|2|false|true",
                "id|4|0|4|1|4",
                "name|4|1|2|a|b"), rows(t));
    }

    @Test
    void anEmptyRelationCountsZeroAndHasNoBounds() throws Exception {
        String t = profile("e", Map.of());
        assertEquals(COLUMNS, columns(t));
        assertEquals(List.of(
                "amount|0|0|0|null|null",
                "day|0|0|0|null|null",
                "flag|0|0|0|null|null",
                "id|0|0|0|null|null",
                "name|0|0|0|null|null"), rows(t));
    }

    /** A declared column keeps the spelling it was declared with, in declaration order. */
    @Test
    void declaredColumnsKeepTheirAuthoredNames() throws Exception {
        String t = profile("g", Map.of("columns", List.of("NAME", "Amount")));
        assertEquals(COLUMNS, columns(t));
        assertEquals(List.of("Amount|4|1|2|-2.25|10.50", "NAME|4|1|2|a|b"), rows(t));
    }

    private String profile(String input, Map<String, Object> config) throws SQLException {
        return RowShaper.shape(conn, PipelineNode.of("pf", "transform.profile", config), input, "pf").stream()
                .filter(r -> r.rel().equals(PipelineRel.DATA)).map(RowShaper.Relation::table).findFirst().orElseThrow();
    }

    private List<String> columns(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0")) {
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) out.add(md.getColumnName(i));
        }
        return out;
    }

    /** Every row as {@code a|b|c}, sorted, so the assertion does not depend on UNION ALL's row order. */
    private List<String> rows(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM \"" + table + "\"")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= n; i++) sb.append(i > 1 ? "|" : "").append(rs.getString(i));
                out.add(sb.toString());
            }
        }
        out.sort(null);
        return out;
    }
}
