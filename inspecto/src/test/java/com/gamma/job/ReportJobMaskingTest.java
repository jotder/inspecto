package com.gamma.job;

import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BI-QUERY-MASKING-1: a scheduled Dataset export has no viewer, so the file it writes always carries aliased entity keys
 * ({@link ReportRowMasking}, provided by the control-plane module - this test lives where both are on the classpath).
 */
class ReportJobMaskingTest {

    private static final String A = "441700900101", B = "441700900202";

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
}
