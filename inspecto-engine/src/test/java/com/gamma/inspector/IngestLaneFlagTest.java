package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.query.DecisionRuleApplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ELT amendment §6 step 3 / D-2: the legacy flat lane behind {@code -Dingest.lane}. The admission itself
 * is proven by {@link FlatVsGraphLaneParityTest}; this pins only what the FLAG does on top of it.
 */
class IngestLaneFlagTest {

    private static final DecisionRuleApplier.Result NO_RULES = new DecisionRuleApplier.Result(List.of(), List.of());

    @AfterEach
    void clearFlag() {
        System.clearProperty(ConsignmentIngestStrategy.LANE_PROPERTY);
    }

    /** A single-destination non-route pipeline; {@code withTemp=false} makes it un-carryable (no ledger home). */
    private static PipelineConfig config(Path dir, boolean withTemp) throws Exception {
        Files.createDirectories(dir);
        String d = dir.toString().replace("\\", "/");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Path toon = dir.resolve("lane_pipeline.toon");
        Files.writeString(toon, """
            name: LANE_FLAG
            active: true
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
            %2$s
              quarantine: %1$s/quarantine
              status_dir: %1$s/status
            output:
              format: CSV
            processing:
              threads: 1
              schema_file: "%3$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, withTemp ? "  temp: " + d + "/temp" : "", schema.toString().replace("\\", "/")));
        return PipelineConfig.load(toon.toString());
    }

    @Test
    void autoIsTheAdmissionAsDesigned(@TempDir Path dir) throws Exception {
        assertNotNull(ConsignmentIngestStrategy.admittedLift(config(dir.resolve("a"), true), NO_RULES),
                "a carryable pipeline diverts");
        assertNull(ConsignmentIngestStrategy.admittedLift(config(dir.resolve("b"), false), NO_RULES),
                "an un-carryable pipeline stays flat, silently — that is the designed default");
    }

    @Test
    void flatIsTheKillSwitch(@TempDir Path dir) throws Exception {
        System.setProperty(ConsignmentIngestStrategy.LANE_PROPERTY, "flat");
        assertNull(ConsignmentIngestStrategy.admittedLift(config(dir, true), NO_RULES),
                "flat never diverts, whatever the admission said");
    }

    @Test
    void graphDisablesTheLegacyLaneAndRefusesLoudlyNamingWhy(@TempDir Path dir) throws Exception {
        System.setProperty(ConsignmentIngestStrategy.LANE_PROPERTY, "graph");
        assertNotNull(ConsignmentIngestStrategy.admittedLift(config(dir.resolve("a"), true), NO_RULES),
                "a carryable pipeline still diverts");
        PipelineConfig flatOnly = config(dir.resolve("b"), false);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConsignmentIngestStrategy.admittedLift(flatOnly, NO_RULES),
                "the flat lane is disabled — the write must FAIL, not quietly fall back");
        assertTrue(ex.getMessage().contains("no scratch dir"), ex.getMessage());
        assertTrue(ex.getMessage().contains("lane_flag"), "names the (lowercased) pipeline: " + ex.getMessage());
    }

    @Test
    void anUnknownValueIsRefusedRatherThanReadAsAuto(@TempDir Path dir) throws Exception {
        System.setProperty(ConsignmentIngestStrategy.LANE_PROPERTY, "hybrid");
        PipelineConfig cfg = config(dir, true);
        assertThrows(IllegalArgumentException.class, () -> ConsignmentIngestStrategy.admittedLift(cfg, NO_RULES));
    }
}
