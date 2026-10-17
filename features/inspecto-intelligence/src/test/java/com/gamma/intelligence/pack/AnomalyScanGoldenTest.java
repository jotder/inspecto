package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.tool.Tool;
import com.gamma.service.CollectorService;
import com.gamma.util.BrowsableStore;
import com.gamma.util.ConnectionSource;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden figures of the Assistant's {@code anomaly_scan} tool (ANALYTICS-CORE-1 S7), pinned against the tool as it
 * stood before it took its definitions from the Analytics Kernel: z = (x − mean) / <b>population</b> stddev over the
 * column's non-NULL values. The hand figures were computed independently (Python {@code statistics.fmean} /
 * {@code pstdev}); a sample-stddev definition would move every z by √((n−1)/n), which the cutoff-3.1 case catches.
 */
class AnomalyScanGoldenTest {

    // v: ten ordinary values, one NULL and one outlier (95); n = 11 non-NULL values.
    private static final String ROWS = "(1,10),(2,12),(3,11),(4,13),(5,9),(6,10),(7,11),(8,12),(9,NULL),(10,10),(11,11),(12,95)";
    private static final double MEAN = 18.545454545454547;
    private static final double SD_POP = 24.201290861002498;
    private static final double Z_95 = 3.1591102265434468;
    private static final double Z_9 = -0.39441923161362896;

    private final List<CollectorService> services = new ArrayList<>();

    @AfterEach
    void close() {
        services.forEach(CollectorService::close);
        services.clear();
    }

    private <T> T withScan(String ddl, String rows, Function<Tool, T> body) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute(ddl);
                if (rows != null) st.execute("INSERT INTO metrics VALUES " + rows);
            }
            BrowsableStore store = new BrowsableStore() {
                @Override public String browseId() { return "jobs"; }
                @Override public String browseLabel() { return "Test"; }
                @Override public List<String> browseTables() { return List.of("metrics"); }
                @Override public ConnectionSource browseSource() { return JdbcDrivers.source(conn); }
            };
            CollectorService svc = new CollectorService(List.of(), 3600, 1);
            services.add(svc);
            Tool scan = InspectoTools.tools(svc, null, () -> List.of(store)).stream()
                    .filter(t -> t.spec().name().equals("anomaly_scan")).findFirst().orElseThrow();
            return body.apply(scan);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(Tool scan, Map<String, Object> args) {
        ToolResult r = scan.invoke(new ToolCall("anomaly_scan", args, new RunId("golden")));
        assertTrue(r.ok(), r.error());
        return (Map<String, Object>) r.value();
    }

    private static void close(double expected, Object actual) {
        assertEquals(expected, ((Number) actual).doubleValue(), 1e-12 * Math.max(1, Math.abs(expected)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zscoreUsesTheMeanAndPopulationStddevOfTheNonNullValues() throws Exception {
        Map<String, Object> out = withScan("CREATE TABLE metrics(id INTEGER, v DOUBLE)", ROWS,
                scan -> invoke(scan, Map.of("table", "metrics", "column", "v", "threshold", 3.1)));
        assertEquals("zscore", out.get("method"));
        close(MEAN, out.get("mean"));
        close(SD_POP, out.get("stddev"));
        assertEquals(11L, out.get("scanned"), "NULL is not counted");
        assertEquals(1, out.get("flagged"), "population z of 95 is 3.159; a sample stddev would give 3.012 < 3.1");
        Map<String, Object> row = ((List<Map<String, Object>>) out.get("rows")).get(0);
        assertEquals(95.0, row.get("value"));
        assertEquals(12, row.get("rowIdentifier"));
        close(Z_95, row.get("zscore"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anIntegerColumnGivesTheSameFiguresAndTheCutoffIsOnTheAbsoluteZ() throws Exception {
        Map<String, Object> out = withScan("CREATE TABLE metrics(id INTEGER, v BIGINT)", ROWS,
                scan -> invoke(scan, Map.of("table", "metrics", "column", "v", "threshold", 0.39)));
        close(MEAN, out.get("mean"));
        close(SD_POP, out.get("stddev"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals(2, out.get("flagged"), "|z| > 0.39: 95 (3.159) and 9 (−0.394)");
        assertEquals(95L, rows.get(0).get("value"), "ordered by |z| descending");
        close(Z_95, rows.get(0).get("zscore"));
        assertEquals(9L, rows.get(1).get("value"));
        close(Z_9, rows.get(1).get("zscore"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theThresholdMethodBoundsTheValueAndStillReportsTheZ() throws Exception {
        Map<String, Object> out = withScan("CREATE TABLE metrics(id INTEGER, v DOUBLE)", ROWS,
                scan -> invoke(scan, Map.of("table", "metrics", "column", "v", "method", "threshold", "threshold", 12.5)));
        assertEquals(2, out.get("flagged"), "95 and 13 are above 12.5");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertEquals(95.0, rows.get(0).get("value"));
        assertEquals(13.0, rows.get(1).get("value"));
        close(-0.2291387916993464, rows.get(1).get("zscore"));
    }

    @Test
    void aConstantColumnHasZeroSpreadAndFlagsNothing() throws Exception {
        Map<String, Object> out = withScan("CREATE TABLE metrics(id INTEGER, v DOUBLE)", "(1,7),(2,7),(3,7)",
                scan -> invoke(scan, Map.of("table", "metrics", "column", "v")));
        close(7.0, out.get("mean"));
        close(0.0, out.get("stddev"));
        assertEquals(3L, out.get("scanned"));
        assertEquals(0, out.get("flagged"));
    }

    @Test
    void aSingleValueHasZeroPopulationSpreadAndFlagsNothing() throws Exception {
        Map<String, Object> out = withScan("CREATE TABLE metrics(id INTEGER, v DOUBLE)", "(1,4),(2,NULL)",
                scan -> invoke(scan, Map.of("table", "metrics", "column", "v")));
        close(4.0, out.get("mean"));
        close(0.0, out.get("stddev"));
        assertEquals(1L, out.get("scanned"));
        assertEquals(0, out.get("flagged"));
    }

    @Test
    void anAllNullColumnIsAnErrorResult() throws Exception {
        ToolResult r = withScan("CREATE TABLE metrics(id INTEGER, v DOUBLE)", "(1,NULL),(2,NULL)",
                scan -> scan.invoke(new ToolCall("anomaly_scan", Map.of("table", "metrics", "column", "v"), new RunId("g"))));
        assertFalse(r.ok());
        assertTrue(r.error().contains("no numeric values"), r.error());
    }
}
