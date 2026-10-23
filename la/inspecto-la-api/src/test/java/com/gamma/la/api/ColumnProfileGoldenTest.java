package com.gamma.la.api;

import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.SqlIdent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden figures of Link Analysis' column-profile SQL (ANALYTICS-CORE-1 M4): {@link ColumnProfile#stats} over numbers, text,
 * dates and NULLs (both distinct measures LA uses: the value as VARCHAR and a normalised key that NULLs an empty key), an
 * empty Dataset, and the discrete degree quantiles of {@link DatasetLinkStats#degreeSummarySql}. Written against the
 * hand-built SQL BEFORE it moved onto the Analytics Kernel's {@code ColumnProfiler}, and kept green after: LA figures do
 * not move (D-AN3 keeps {@code quantile_disc} as the explicit DISCRETE mode).
 */
class ColumnProfileGoldenTest {

    @TempDir
    static Path spill;
    private static Connection conn;

    /** Runs the request as the host does: the Dataset's relation bound under its name, then the statement over it. */
    private static final class DuckDbDatasets implements DatasetProvider {
        @Override public Optional<Map<String, Object>> dataset(Path writeRoot, String id) { return Optional.empty(); }

        @Override public List<Entry> datasets(Path writeRoot) { return List.of(); }

        @Override public List<Path> readRoots(Map<String, Object> dataset, Path dataRoot) { return List.of(); }

        @Override public String relationSql(Map<String, Object> dataset, Path dataRoot, Path writeRoot) { return null; }

        @Override public Result runPlanned(String name, String relationSql, SqlSandboxPolicy policy, Planner planner) {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        @Override public BoundFilter predicateBound(Object filter) { return BoundFilter.TRUE; }

        @Override public Result run(Request req) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE OR REPLACE TEMP VIEW " + SqlIdent.q(req.datasetName()) + " AS " + req.relationSql());
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet rs = st.executeQuery(req.sql())) {
                    var md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                        rows.add(row);
                    }
                }
                return new Result(List.of(), rows, rows.size(), false, 0);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public Result run(Request req, SqlSandboxPolicy policy) { return run(req); }

        @Override public Result run(Request req, SqlSandboxPolicy policy, ZoneId tz) { return run(req); }
    }

    @BeforeAll
    static void open() throws Exception {
        conn = DuckDbUtil.openInMemory(spill.resolve(DuckDbUtil.SPILL_DIR_NAME));
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE src AS SELECT * FROM (VALUES "
                    + "(1, 'A', DATE '2024-01-01', 10.5),"
                    + "(2, 'A', DATE '2024-01-01', NULL),"
                    + "(3, '', NULL, 7.25),"
                    + "(4, NULL, DATE '2024-02-01', 7.25),"
                    + "(5, 'b', DATE '2024-03-01', 1.0)) t(id, msisdn, day, amount)");
            st.execute("CREATE TABLE links AS SELECT * FROM (VALUES "
                    + "('a1', 'b1', 'call', true, true), ('a1', 'b2', 'call', true, true), ('a1', 'b3', 'sms', true, true),"
                    + "('a2', 'b1', 'call', true, true), ('a3', 'b4', 'call', true, false), ('a4', 'a4', 'call', true, true)"
                    + ") t(a, b, kind, a_typed, b_typed)");
        }
        DatasetProviders.forTest(new DuckDbDatasets());
    }

    @AfterAll
    static void close() throws Exception {
        DatasetProviders.forTest(null);
        if (conn != null) conn.close();
    }

    private static final List<String> COLS = List.of("id", "msisdn", "day", "amount");
    /** A normalised-key stand-in: trimmed text, an empty key is no Entity. */
    private static final UnaryOperator<String> KEY = c -> "NULLIF(trim(CAST(" + c + " AS VARCHAR)), '')";

    private static List<String> figures(String relationSql, UnaryOperator<String> distinctOf) throws Exception {
        List<String> out = new ArrayList<>();
        for (ColumnProfile.Stats s : ColumnProfile.stats("ds", relationSql, COLS, distinctOf))
            out.add(s.column() + " rows=" + s.rows() + " nonNull=" + s.nonNull() + " nulls=" + s.nulls() + " distinct=" + s.distinct());
        return out;
    }

    @Test
    void distinctOverTheValueAsVarchar() throws Exception {
        assertEquals(List.of(
                "id rows=5 nonNull=5 nulls=0 distinct=5",
                "msisdn rows=5 nonNull=4 nulls=1 distinct=3",
                "day rows=5 nonNull=4 nulls=1 distinct=3",          // approximate; DuckDB 1.5's sketch merged two of the 3 dates, 2.0's does not
                "amount rows=5 nonNull=4 nulls=1 distinct=3"), figures("SELECT * FROM src", ColumnProfile.AS_VARCHAR));
    }

    @Test
    void distinctOverANormalisedKeyThatDropsTheEmptyKey() throws Exception {
        assertEquals(List.of(
                "id rows=5 nonNull=5 nulls=0 distinct=5",
                "msisdn rows=5 nonNull=4 nulls=1 distinct=2",
                "day rows=5 nonNull=4 nulls=1 distinct=3",          // approximate; DuckDB 1.5's sketch merged two of the 3 dates, 2.0's does not
                "amount rows=5 nonNull=4 nulls=1 distinct=3"), figures("SELECT * FROM src", KEY));
    }

    @Test
    void anEmptyDatasetCountsZero() throws Exception {
        assertEquals(List.of(
                "id rows=0 nonNull=0 nulls=0 distinct=0",
                "msisdn rows=0 nonNull=0 nulls=0 distinct=0",
                "day rows=0 nonNull=0 nulls=0 distinct=0",
                "amount rows=0 nonNull=0 nulls=0 distinct=0"), figures("SELECT * FROM src WHERE false", ColumnProfile.AS_VARCHAR));
    }

    @Test
    void theDegreeSummaryKeepsDiscreteQuantiles() throws Exception {
        assertEquals("SELECT quantile_disc(d, 0.5) AS p50, quantile_disc(d, 0.9) AS p90, quantile_disc(d, 0.99) AS p99, "
                + "MAX(d) AS mx, COUNT(*) FILTER (WHERE d > 2) AS hubs, COUNT(*) FILTER (WHERE d = 1) AS single, COUNT(*) AS n FROM "
                + "(SELECT x, COUNT(DISTINCT o) AS d FROM (SELECT a AS x, b AS o FROM (SELECT DISTINCT a, b, kind, a_typed, b_typed FROM "
                + "__entity_links WHERE a <> '' AND b <> '' AND a <> b) AS e WHERE a_typed UNION ALL SELECT b AS x, a AS o FROM "
                + "(SELECT DISTINCT a, b, kind, a_typed, b_typed FROM __entity_links WHERE a <> '' AND b <> '' AND a <> b) AS e "
                + "WHERE b_typed) AS en GROUP BY x) AS g", DatasetLinkStats.degreeSummarySql(2));
        Map<String, Object> row = DatasetProviders.require().run(new DatasetProvider.Request(EntityProfiles.LINKS,
                "SELECT * FROM links", DatasetLinkStats.degreeSummarySql(2), 1, 0, List.of(), List.of())).rows().get(0);
        // degrees: a1=3, a2=1, a3=1, b1=2, b2=1, b3=1 (b4 is untyped, a4's self-link is dropped)
        assertEquals("1 3 3 3 1 4 6", row.get("p50") + " " + row.get("p90") + " " + row.get("p99") + " " + row.get("mx") + " "
                + row.get("hubs") + " " + row.get("single") + " " + row.get("n"));
    }
}
