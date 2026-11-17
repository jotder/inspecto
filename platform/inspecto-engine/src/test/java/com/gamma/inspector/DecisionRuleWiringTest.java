package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.DecisionRules;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-wiring tests for the record-level Decision Rule consequences: {@code writeAndTrace} (the
 * shared tail of every ingest path) applies the pipeline's enabled rules to the {@code transformed}
 * table via {@link com.gamma.query.DecisionRuleApplier} before {@code PartitionWriter} — so
 * {@code drop} rows never reach the output, {@code quarantine} rows land as record-level Parquet
 * under {@code dirs.quarantine}, {@code route} rows land under {@code dirs.database/<destination>}
 * with their own outputs, and {@code tag} appends a {@code __tags} column. Rules are loaded from the
 * space's component registry through {@link DecisionRules} (registered here for the default space,
 * as {@code SpaceBootstrap} does for a real space).
 */
class DecisionRuleWiringTest {

    @AfterEach
    void clearRegistry() {
        DecisionRules.clear();
    }

    // ── harness ─────────────────────────────────────────────────────────────────

    private PipelineConfig config(Path dir) throws Exception {
        return TestConfigs.csv(dir.resolve("cfg"), PipelineConfigBatchTest.miniSchema())
                .format("PARQUET").load();
    }

    private void writeRule(Path dir, String name, Map<String, Object> content) throws Exception {
        new ComponentStore(registry(dir)).write("decision-rule", name, content);
        DecisionRules.register("default", registry(dir));
    }

    private Path registry(Path dir) {
        return dir.resolve("registry");
    }

    private static Map<String, Object> when(String field, String operator, String value) {
        return Map.of("kind", "group", "op", "AND", "items", List.of(
                Map.of("kind", "condition", "field", field, "operator", operator, "value", value)));
    }

    /** A 4-row {@code transformed}-shaped table: data cols + partition cols + {@code __src_id}. */
    private Connection openWithTable(File db) throws Exception {
        DuckDbUtil.loadDriver();
        Connection conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE transformed AS SELECT * FROM (VALUES
                      ('alice',  250.0, '2026', '07', '01', 1),
                      ('bob',     50.0, '2026', '07', '01', 1),
                      ('carol',  999.0, '2026', '07', '02', 1),
                      ('dave',   100.0, '2026', '07', '02', 1)
                    ) v(name, cost, year, month, day, __src_id)""");
        }
        return conn;
    }

    private ConsignmentIngestStrategy.Written run(Connection conn, PipelineConfig cfg) throws Exception {
        return ConsignmentIngestStrategy.writeAndTrace(conn, "transformed", List.of("year", "month", "day"),
                cfg, cfg.dirs().database(), "b1", "B1", Map.of(1, "f.csv"), "");
    }

    private long countParquet(Connection conn, String dirGlob) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM read_parquet('" + dirGlob.replace('\\', '/') + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ── the four record-level consequences ──────────────────────────────────────

    @Test
    void dropRemovesMatchingRowsFromOutput(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        writeRule(dir, "drop_expensive", Map.of(
                "name", "drop_expensive", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("cost", ">", "500"), "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "drop", "destination", ""))));
        File db = DuckDbUtil.tempDbFile("drw_drop_");
        try (Connection conn = openWithTable(db)) {
            var written = run(conn, cfg);
            assertFalse(written.outputs().isEmpty());
            assertEquals(3, countParquet(conn, cfg.dirs().database() + "/**/*.parquet"),
                    "carol (999) was dropped");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void tagAppendsTagsColumnToMatchingRows(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        writeRule(dir, "tag_high", Map.of(
                "name", "tag_high", "targetType", "pipeline", "target", "test_etl",   // normalised alias
                "when", when("cost", ">", "100"), "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "tag", "destination", "high_value"))));
        File db = DuckDbUtil.tempDbFile("drw_tag_");
        try (Connection conn = openWithTable(db)) {
            run(conn, cfg);
            String glob = (cfg.dirs().database() + "/**/*.parquet").replace('\\', '/');
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM read_parquet('" + glob
                         + "', union_by_name=true) WHERE __tags = 'high_value'")) {
                rs.next();
                assertEquals(2, rs.getLong(1), "alice (250) and carol (999) tagged");
            }
            assertEquals(4, countParquet(conn, glob), "tag removes nothing");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void quarantineMovesMatchingRowsToRecordQuarantine(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        writeRule(dir, "hold_bob", Map.of(
                "name", "hold_bob", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("name", "=", "bob"), "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "quarantine", "destination", "manual review"))));
        File db = DuckDbUtil.tempDbFile("drw_quar_");
        try (Connection conn = openWithTable(db)) {
            run(conn, cfg);
            assertEquals(3, countParquet(conn, cfg.dirs().database() + "/**/*.parquet"),
                    "bob left the main output");
            Path recordDir = Path.of(cfg.dirs().quarantine(), "records", "hold_bob");
            try (Stream<Path> files = Files.walk(recordDir)) {
                Path parquet = files.filter(p -> p.toString().endsWith("_records.parquet"))
                        .findFirst().orElseThrow();
                assertEquals(1, countParquet(conn, parquet.toString()));
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void routeMovesMatchingRowsToDestinationSubdirWithOutputsAndLineage(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        writeRule(dir, "route_cheap", Map.of(
                "name", "route_cheap", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("cost", "<", "100"), "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "route", "destination", "review"))));
        File db = DuckDbUtil.tempDbFile("drw_route_");
        try (Connection conn = openWithTable(db)) {
            var written = run(conn, cfg);
            assertEquals(3, countParquet(conn, cfg.dirs().database() + "/year*/**/*.parquet"),
                    "bob left the main output");
            assertEquals(1, countParquet(conn, cfg.dirs().database() + "/review/**/*.parquet"),
                    "bob landed under the destination subdir");
            assertTrue(written.outputs().stream()
                            .anyMatch(o -> o.outputFile().replace('\\', '/').contains("/review/")),
                    "routed output is reported: " + written.outputs());
            assertTrue(written.lineage().stream()
                            .anyMatch(l -> l.outputFile().replace('\\', '/').contains("/review/")),
                    "routed rows carry lineage: " + written.lineage());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * X5 L3 — the Decision Rule {@code route:} counts, grounded: the ingest lane's {@code transform} Step Receipt
     * carries one outlet per rule that moved rows — {@code route:<rule>} for a route, {@code reject:dropped} for a
     * drop — and {@code data} for what went on to the write, so {@code in} = the sum of its outlets.
     */
    @Test
    void theTransformReceiptCountsWhatEachRuleMovedAndWhatWentOn(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        writeRule(dir, "route_cheap", Map.of(
                "name", "route_cheap", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("cost", "<", "100"), "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "route", "destination", "review"))));
        writeRule(dir, "drop_expensive", Map.of(
                "name", "drop_expensive", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("cost", ">", "500"), "priority", 20, "enabled", true,
                "consequences", List.of(Map.of("action", "drop", "destination", ""))));
        File db = DuckDbUtil.tempDbFile("drw_receipt_");
        try (Connection conn = openWithTable(db)) {
            IngestTrail.drain("B1");
            System.setProperty(ConsignmentIngestStrategy.LANE_PROPERTY, "flat");   // the flat lane's phase receipts
            ConsignmentIngestStrategy.Written written;
            try {
                written = run(conn, cfg);
            } finally {
                System.clearProperty(ConsignmentIngestStrategy.LANE_PROPERTY);
            }
            IngestOutcome outcome = new IngestOutcome(java.time.LocalDateTime.now(), "SUCCESS", "", List.of(),
                    List.of(), written.outputs(), written.lineage(), 4, "mini");
            var receipts = IngestTrail.receipts(IngestTrail.drain("B1"), "B1", "TEST_ETL", "run-1", outcome, "",
                    cfg.sinks().size(), "mini", false);
            var transform = receipts.stream().filter(r -> r.step().equals("transform")).findFirst().orElseThrow();
            Map<String, Long> outlets = new java.util.LinkedHashMap<>();
            transform.outlets().forEach(o -> outlets.put(o.outlet(), o.rows()));
            assertEquals(Map.of("data", 2L, "route:route_cheap", 1L, "reject:dropped", 1L), outlets,
                    "bob routed, carol dropped, alice and dave went on: " + transform);
            assertEquals(4L, transform.in());
            var sink = receipts.stream().filter(r -> r.step().equals("sink:0")).findFirst().orElseThrow();
            assertEquals(2L, sink.in(), "the write took what the rules left: " + receipts);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── route + sinks>1: routed rows REPLICATE to every destination (operator, 2026-10-06) ──────

    /** The {@link #config} pipeline with two parquet destinations, hot and cold. */
    private PipelineConfig twoSinkConfig(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir.resolve("cfg"), PipelineConfigBatchTest.miniSchema())
                .format("PARQUET").write();
        String d = dir.toString().replace('\\', '/');
        Files.writeString(toon, Files.readString(toon).replace("processing:",
                "sinks[2]{database,format}:\n  \"" + d + "/hot\",PARQUET\n  \"" + d + "/cold\",PARQUET\nprocessing:"));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertEquals(2, cfg.sinks().size());
        return cfg;
    }

    private void routedRowsLandInEveryDestination(Path dir, String lane) throws Exception {
        PipelineConfig cfg = twoSinkConfig(dir);
        writeRule(dir, "route_cheap", Map.of(
                "name", "route_cheap", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("cost", "<", "100"), "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "route", "destination", "review"))));
        File db = DuckDbUtil.tempDbFile("drw_route_fan_");
        String prior = System.getProperty(ConsignmentIngestStrategy.LANE_PROPERTY);
        System.setProperty(ConsignmentIngestStrategy.LANE_PROPERTY, lane);
        try (Connection conn = openWithTable(db)) {
            var written = run(conn, cfg);
            for (String sink : List.of("hot", "cold")) {
                Path root = dir.resolve(sink);
                assertEquals(3, countParquet(conn, root + "/year*/**/*.parquet"),
                        lane + ": the remainder fans out to " + sink);
                assertEquals(1, countParquet(conn, root + "/review/**/*.parquet"),
                        lane + ": bob is replicated under " + sink + "/review");
                assertEquals(1, written.outputs().stream()
                                .filter(o -> o.outputFile().replace('\\', '/').contains("/" + sink + "/review/"))
                                .count(), lane + ": one routed output per destination: " + written.outputs());
                assertTrue(written.lineage().stream()
                                .anyMatch(l -> l.outputFile().replace('\\', '/').contains("/" + sink + "/review/")
                                        && l.inputFile().equals("f.csv") && l.rowCount() == 1),
                        lane + ": routed lineage names the " + sink + " copy: " + written.lineage());
            }
        } finally {
            if (prior == null) System.clearProperty(ConsignmentIngestStrategy.LANE_PROPERTY);
            else System.setProperty(ConsignmentIngestStrategy.LANE_PROPERTY, prior);
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void routeWithTwoSinksReplicatesRoutedRowsOnTheFlatLane(@TempDir Path dir) throws Exception {
        routedRowsLandInEveryDestination(dir, "flat");
    }

    @Test
    void routeWithTwoSinksReplicatesRoutedRowsOnTheGraphLane(@TempDir Path dir) throws Exception {
        routedRowsLandInEveryDestination(dir, "graph");
    }

    @Test
    void disabledOrOtherPipelineRulesAreIgnored(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        writeRule(dir, "disabled_drop", Map.of(
                "name", "disabled_drop", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("cost", ">", "0"), "priority", 10, "enabled", false,
                "consequences", List.of(Map.of("action", "drop", "destination", ""))));
        writeRule(dir, "other_pipeline", Map.of(
                "name", "other_pipeline", "targetType", "pipeline", "target", "somewhere_else",
                "when", when("cost", ">", "0"), "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "drop", "destination", ""))));
        File db = DuckDbUtil.tempDbFile("drw_off_");
        try (Connection conn = openWithTable(db)) {
            run(conn, cfg);
            assertEquals(4, countParquet(conn, cfg.dirs().database() + "/**/*.parquet"),
                    "nothing applied");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void aBrokenRuleIsSkippedAndNeverFailsTheBatch(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = config(dir);
        writeRule(dir, "broken", Map.of(
                "name", "broken", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("no_such_column", ">", "1"), "priority", 5, "enabled", true,
                "consequences", List.of(Map.of("action", "drop", "destination", ""))));
        writeRule(dir, "still_works", Map.of(
                "name", "still_works", "targetType", "pipeline", "target", "TEST_ETL",
                "when", when("name", "=", "dave"), "priority", 10, "enabled", true,
                "consequences", List.of(Map.of("action", "drop", "destination", ""))));
        File db = DuckDbUtil.tempDbFile("drw_broken_");
        try (Connection conn = openWithTable(db)) {
            var written = run(conn, cfg);
            assertFalse(written.outputs().isEmpty(), "the batch still wrote");
            assertEquals(3, countParquet(conn, cfg.dirs().database() + "/**/*.parquet"),
                    "the later rule still applied");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
