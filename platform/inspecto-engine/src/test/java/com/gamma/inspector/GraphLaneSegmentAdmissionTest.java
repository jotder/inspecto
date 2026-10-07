package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.query.DecisionRuleApplier;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineLift;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The graph lane's admission for a MULTI-SCHEMA write (ELT §6 step-2 parity gap, 2026-09-16).
 *
 * <p>A {@code segments:} batch reaches {@code writeAndTrace} once per segment, each call holding that
 * segment's own materialised table — so the admission asks about the segment's own
 * {@code map_<KEY> → sink_<KEY>} chain. Asking the same question about the WHOLE lifted pipeline is what
 * used to refuse every such write: the lift carries one chain per schema plus a quarantine sink, so its
 * sink-node count can never equal the number of destinations {@code sinks[]} declares.
 */
class GraphLaneSegmentAdmissionTest {

    private static final DecisionRuleApplier.Result NO_RULES =
            new DecisionRuleApplier.Result(java.util.List.of(), java.util.List.of());

    @Test
    void aSegmentWriteIsAdmittedWhileTheWholePipelineQuestionStillRefuses(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = multiSchemaConfig(dir);

        assertTrue(ConsignmentIngestStrategy.graphLaneCarries(cfg, "CALL"),
                "the CALL segment's own map → sink chain is a single-seed, single-sink write the lane carries");
        assertTrue(ConsignmentIngestStrategy.graphLaneCarries(cfg, "SMS"), "and so is SMS's");
        assertFalse(ConsignmentIngestStrategy.graphLaneCarries(cfg, null),
                "asked about the whole pipeline it still refuses — 3 sink nodes against 1 declared destination");
        assertTrue(ConsignmentIngestStrategy.flatReason(cfg, NO_RULES, null).contains("sink count (3)"),
                "and the whole-pipeline refusal still names the count: "
                        + ConsignmentIngestStrategy.flatReason(cfg, NO_RULES, null));
    }

    @Test
    void theSeedIsTheSegmentsOwnMapAndTheSinkItsOwn(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = multiSchemaConfig(dir);
        PipelineGraph lifted = PipelineLift.lift(cfg);

        assertEquals("map_CALL", ConsignmentIngestStrategy.seedOfWrite(lifted, "CALL"));
        assertEquals(java.util.List.of("sink_CALL"),
                ConsignmentIngestStrategy.writeSinks(lifted, "CALL").stream()
                        .map(com.gamma.pipeline.PipelineNode::id).toList(),
                "the segment's own sink only — not the other schema's, and never the control-fed quarantine");
    }

    /**
     * ⚠ {@code writeScope} is overloaded: a chunked write passes the chunk BASE NAME in the same argument a
     * segment write passes its key. Only a name the config actually declares as a segment may be read as one.
     */
    @Test
    void aChunkBaseNameIsNotMistakenForASegmentKey(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = multiSchemaConfig(dir);

        assertEquals("CALL", ConsignmentIngestStrategy.segmentWrite(cfg, "CALL"));
        assertNull(ConsignmentIngestStrategy.segmentWrite(cfg, ""), "a whole-batch write has no segment");
        assertNull(ConsignmentIngestStrategy.segmentWrite(cfg, "events_20200403"),
                "a chunk base name names no declared segment");
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private static PipelineConfig multiSchemaConfig(Path dir) throws Exception {
        Path callSchema = dir.resolve("call_schema.toon");
        Path smsSchema = dir.resolve("sms_schema.toon");
        Files.writeString(callSchema, schemaToon("call"));
        Files.writeString(smsSchema, schemaToon("sms"));
        Path pipeline = dir.resolve("events_pipeline.toon");
        String toon = """
                name: EVENTS_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  ingester: %s
                  segments:
                    CALL: %s
                    SMS: %s
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                ConsignmentIngestorPluginTest.StubEventIngester.class.getName(),
                callSchema.toString().replace("\\", "/"), smsSchema.toString().replace("\\", "/"));
        Files.writeString(pipeline, toon.replace("\\", "/"));
        return PipelineConfig.load(pipeline.toString());
    }

    private static String schemaToon(String name) {
        return """
                partitions[4]{column,source,type}:
                  record_type,EVENT_TYPE,VARCHAR
                  year,EVENT_DATE,DATE_YEAR
                  month,EVENT_DATE,DATE_MONTH
                  day,EVENT_DATE,DATE_DAY
                raw:
                  name: %s
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_TYPE,"1",VARCHAR
                    EVENT_DATE,"2",DATE
                mapping:
                  canonicalName: %s
                  rawName: %s
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_TYPE,EVENT_TYPE,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """.formatted(name, name, name);
    }
}
