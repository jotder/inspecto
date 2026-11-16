package com.gamma.job;

import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-QUERY-MASKING-1: a scheduled Dataset export has no viewer, so the file it writes always carries aliased entity keys
 * ({@link ReportRowMasking}, provided by the control-plane module - this test lives where both are on the classpath).
 */
class ReportJobMaskingTest {

    private static final String A = "441700900101", B = "441700900202";

    /**
     * These tests are about masking by default, which is on where people sign in (LA-ONBOARD-1 OB-6): no test classpath sets
     * auth.mode, so without this every test here runs as "no sign-in", where it is off.
     */
    @org.junit.jupiter.api.BeforeEach
    void signIn() {
        System.setProperty("auth.mode", "oidc");
        // these tests reach their registry through -Dassist.write.root, the fallback SpaceConfigRoot uses only while no
        // Space has registered "default" - an earlier class that booted one (SpaceMigratorTest, on the Linux order) leaks it
        com.gamma.pipeline.SpaceConfigRoot.clear();
    }

    @AfterEach
    void signOut() {
        System.clearProperty("auth.mode");
    }

    @AfterEach
    void clearRoot() { System.clearProperty("assist.write.root"); }

    private static void seed(Path root) throws Exception {
        new ViewStore(root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('" + A + "','EU',10.0),('" + A + "','EU',5.0),('" + B + "','US',7.0)) AS t(msisdn,region,amount)",
                "2026-07-08T00:00:00Z"));
        ComponentStore reg = new ComponentStore(root.resolve("registry"));
        reg.write("dataset", "calls_ds", Map.of("view", "calls_view", "columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "region"), Map.of("name", "amount"))));
        reg.write("dataset", "plain_ds", Map.of("view", "calls_view", "columns", List.of(
                Map.of("name", "msisdn"), Map.of("name", "region"), Map.of("name", "amount"))));
        System.setProperty("assist.write.root", root.toString());
    }

    private static JobResult run(Map<String, String> params) throws Exception {
        return new ReportJob(new JobConfig("calls_report", JobType.REPORT, null, null, true, false, params), null).run();
    }

    private static String artifact(Path out, String ext) throws Exception {
        try (Stream<Path> files = Files.list(out)) {
            return Files.readString(files.filter(f -> f.getFileName().toString().endsWith(ext)).findFirst().orElseThrow());
        }
    }

    @Test
    void aRawExportAndAnAggregateBothCarryAliasesNotKeys(@TempDir Path root, @TempDir Path out) throws Exception {
        seed(root);
        assertEquals("SUCCESS", run(Map.of("scope", "dataset", "dataset", "calls_ds", "out_dir", out.toString())).status());
        String raw = artifact(out, ".csv");
        assertFalse(raw.contains(A) || raw.contains(B), raw);
        assertTrue(raw.contains("masked:"), raw);
        assertTrue(raw.contains("EU"), "an unclassified column is untouched: " + raw);

        try (Stream<Path> files = Files.list(out)) { files.forEach(f -> f.toFile().delete()); }
        assertEquals("SUCCESS", run(Map.of("scope", "dataset", "dataset", "calls_ds", "measures", "count",
                "group_by", "msisdn", "out_dir", out.toString())).status());
        String grouped = artifact(out, ".csv");
        assertFalse(grouped.contains(A) || grouped.contains(B), grouped);
        assertEquals(3, grouped.lines().count(), "header + one row per subscriber (2), grouping ran over the raw keys: " + grouped);
    }

    @Test
    void anUnclassifiedDatasetExportIsUntouched(@TempDir Path root, @TempDir Path out) throws Exception {
        seed(root);
        assertEquals("SUCCESS", run(Map.of("scope", "dataset", "dataset", "plain_ds", "out_dir", out.toString())).status());
        String csv = artifact(out, ".csv");
        assertTrue(csv.contains(A) && csv.contains(B), "the negative control: with nothing classified the keys are written: " + csv);
        assertFalse(csv.contains("masked:"), csv);
    }

    @Test
    void aSpaceThatTurnedMaskingOffWritesRawKeys(@TempDir Path root, @TempDir Path out) throws Exception {
        seed(root);
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
        assertEquals("SUCCESS", run(Map.of("scope", "dataset", "dataset", "calls_ds", "out_dir", out.toString())).status());
        assertTrue(artifact(out, ".csv").contains(A), "maskingMode none opts the export out, as it does every BI route");
    }

    @Test
    void aMeasureThatWouldReturnARawKeyFailsTheRun(@TempDir Path root, @TempDir Path out) throws Exception {
        seed(root);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(Map.of("scope", "dataset",
                "dataset", "calls_ds", "measures", "max(msisdn)", "out_dir", out.toString())));
        assertTrue(e.getMessage().contains("msisdn"), e.getMessage());
        try (Stream<Path> files = Files.list(out)) { assertEquals(0, files.count(), "nothing written"); }
        assertEquals("SUCCESS", run(Map.of("scope", "dataset", "dataset", "plain_ds", "measures", "max(msisdn)",
                "out_dir", out.toString())).status(), "the same measure over an unclassified Dataset runs");
    }

    /**
     * REPORT-PDF-CHARTS-1 review finding 2: a chart grouped by a classified key draws on the dashboard (its cosmetic category
     * order is dropped for a non-revealer, ordering by the first Measure), so the PDF draws it too — with the dashboard's
     * aliases as labels, never the raw keys. Before the fix the axis order was not cosmetic and the masking refused the Run.
     */
    @Test
    void aChartGroupedByAClassifiedKeyDrawsAliasesAsTheDashboardDoes(@TempDir Path root, @TempDir Path out) throws Exception {
        seed(root);
        Map<String, Object> bar = Map.of("vizType", "bar", "datasetId", "calls_ds", "controls", Map.of(
                "x", List.of(Map.of("field", "msisdn")), "y", List.of(Map.of("field", "amount", "agg", "sum"))));
        new ComponentStore(root.resolve("registry")).write("widget", "spend_by_subscriber", bar);
        Map<String, String> params = Map.of("scope", "dataset", "dataset", "calls_ds", "measures", "count",
                "group_by", "region", "format", "pdf", "widgets", "spend_by_subscriber", "out_dir", out.toString());

        JobResult r = run(params);
        assertEquals("SUCCESS", r.status(), r.message());

        ReportJob job = new ReportJob(new JobConfig("calls_report", JobType.REPORT, null, null, true, false, params), null);
        List<Map<String, Object>> drawn = job.chartRows(ReportWidget.of("spend_by_subscriber", bar)).rows();
        assertEquals(2, drawn.size(), "one bar per subscriber: " + drawn);
        for (Map<String, Object> row : drawn) {
            String label = String.valueOf(row.get("msisdn"));
            assertTrue(label.startsWith("masked:"), "the label is the alias: " + drawn);
            assertFalse(label.contains(A) || label.contains(B), drawn.toString());
        }
        assertEquals(List.of(15.0, 7.0), drawn.stream().map(m -> ((Number) m.get("sum_amount")).doubleValue()).toList(),
                "ordered by the Measure, descending (the dashboard's order for a masked key), never by the raw key");

        // the negative control: over the unclassified Dataset the same chart draws the raw keys, in key order
        Map<String, Object> plain = Map.of("vizType", "bar", "datasetId", "plain_ds", "controls", bar.get("controls"));
        List<Map<String, Object>> raw = job.chartRows(ReportWidget.of("plain_bar", plain)).rows();
        assertEquals(List.of(A, B), raw.stream().map(m -> String.valueOf(m.get("msisdn"))).toList());
    }

    @Test
    void theReadyEventNamesTheAliasedColumnsAndIsEmptyWhenNothingWas(@TempDir Path root, @TempDir Path out) throws Exception {
        seed(root);
        List<Object> aliased = new CopyOnWriteArrayList<>();
        BiConsumer<EventLog, Event> tap = (log, e) -> {
            if ("REPORT_READY".equals(e.type())) aliased.add(e.attributes().get("aliasedColumns"));
        };
        EventLog.addTap(tap);
        try {
            run(Map.of("scope", "dataset", "dataset", "calls_ds", "out_dir", out.toString()));
            run(Map.of("scope", "dataset", "dataset", "plain_ds", "out_dir", out.toString()));
        } finally { EventLog.removeTap(tap); }
        assertEquals(List.of("[msisdn]", "[]"), new ArrayList<>(aliased), "classified: msisdn; the negative control: none");
    }
}
