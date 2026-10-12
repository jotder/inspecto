package com.gamma.inspector;

import com.gamma.etl.Consignment;
import com.gamma.etl.MemberStatus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INGEST-MULTIFILE-BATCH-REPLAN-DUP-1 stage 1 (operator decision D3, 2026-10-12): two members of ONE batch that
 * share an output stem ({@code a/x.bin} and {@code b/x.bin} both stem {@code x}) must not silently overwrite each
 * other. Generation mode is the only lane that names a member's output by its stem inside a multi-member batch
 * ({@code <stem>_gNNNNN_out.*}); the consolidated lanes (CSV, native CSV, union) name a multi-member batch by its
 * batch id, so they cannot collide inside one batch. The second member is failed CLOSED: quarantined under
 * {@code output_stem_collision} with one audit reason, the first member's output untouched.
 */
class OutputStemCollisionTest {

    /** {@code TYPE,ID,DATE} lines; the same stub the streaming-plugin tests use. */
    public static class StubIngester implements com.gamma.etl.StreamingFileIngester {
        @Override
        public void ingest(File file, com.gamma.etl.RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
            for (String line : Files.readAllLines(file.toPath())) {
                if (line.isBlank()) continue;
                String[] p = line.split(",", -1);
                sink.emit(p[0], p[1], p[0], p[2]);
            }
        }
    }

    private static final String CALL = "CALL,%s,2020-04-03\n";

    private static File inbox(PipelineConfig cfg, String rel, String body) throws Exception {
        Path f = Path.of(cfg.dirs().poll()).resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
        return f.toFile();
    }

    private static Consignment batch(PipelineConfig cfg, File... files) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(Map.of(), null);
        java.util.ArrayList<Consignment.Member> ms = new java.util.ArrayList<>();
        for (int i = 0; i < files.length; i++) ms.add(new Consignment.Member(files[i], i, files[i].length(), sel));
        return new Consignment(cfg.identity().runTimestamp() + "_events_0001", "events", null, List.copyOf(ms));
    }

    private static List<Path> data(PipelineConfig cfg) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        if (!Files.exists(db)) return List.of();
        try (Stream<Path> s = Files.walk(db)) { return s.filter(Files::isRegularFile).toList(); }
    }

    private static String allData(PipelineConfig cfg) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Path p : data(cfg)) sb.append(Files.readString(p));
        return sb.toString();
    }

    private static PipelineConfig cfg(Path dir) throws Exception { return cfg(dir, false); }

    private static PipelineConfig cfg(Path dir, boolean perMember) throws Exception {
        Path schema = dir.resolve("call_schema.toon");
        Files.writeString(schema, SCHEMA);
        Path pipeline = dir.resolve("events_pipeline.toon");
        Files.writeString(pipeline, PIPELINE.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                StubIngester.class.getName(), schema.toString().replace("\\", "/"))
                .replace("processing:\n", perMember ? "processing:\n  output_per_member: true\n" : "processing:\n"));
        return PipelineConfig.load(pipeline.toString());
    }

    @Test
    void generationModeSameStemDifferentDirsFailsTheSecondMemberClosed(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        File a = inbox(cfg, "a/x.bin", String.format(CALL, "AAA1"));
        File b = inbox(cfg, "b/x.bin", String.format(CALL, "BBB2"));

        IngestOutcome out = new StreamingPluginIngestStrategy(1).ingest(batch(cfg, a, b), cfg);

        assertEquals("SUCCESS", out.status(), out.error());
        assertEquals(List.of("x.bin"), out.survivors().stream().map(m -> m.file().getName()).toList());
        assertEquals(1, out.survivors().size());
        assertTrue(allData(cfg).contains("AAA1"), "the first member's rows landed: " + allData(cfg));
        assertFalse(allData(cfg).contains("BBB2"), "the second member's rows must not land (or overwrite)");
        MemberAudit second = out.memberAudits().get(1);
        assertEquals(MemberStatus.QUARANTINED_MISMATCH, second.status());
        assertTrue(second.error().startsWith("output_stem_collision:"), second.error());
        assertFalse(b.exists(), "the colliding member left the inbox");
        assertTrue(Files.exists(Path.of(cfg.dirs().quarantine(), "b", "output_stem_collision", "x.bin")),
                "quarantined under its own directory, evidence kept");
    }

    @Test
    void generationModeDistinctStemsAreUnaffected(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        File a = inbox(cfg, "a/x.bin", String.format(CALL, "AAA1"));
        File b = inbox(cfg, "b/y.bin", String.format(CALL, "BBB2"));

        IngestOutcome out = new StreamingPluginIngestStrategy(1).ingest(batch(cfg, a, b), cfg);

        assertEquals("SUCCESS", out.status(), out.error());
        assertEquals(2, out.survivors().size());
        assertTrue(allData(cfg).contains("AAA1") && allData(cfg).contains("BBB2"));
    }

    @Test
    void singleFileBatchIsUnaffected(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        File a = inbox(cfg, "a/x.bin", String.format(CALL, "AAA1"));

        IngestOutcome out = new StreamingPluginIngestStrategy(1).ingest(batch(cfg, a), cfg);

        assertEquals("SUCCESS", out.status(), out.error());
        assertEquals(1, out.survivors().size());
        assertEquals(MemberStatus.SUCCESS, out.memberAudits().get(0).status());
        assertTrue(allData(cfg).contains("AAA1"));
    }

    /** The consolidated (union) lane names a multi-member batch by its batch id: same stems cannot collide there. */
    @Test
    void unionModeSameStemMembersBothLandUnderTheBatchId(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        File a = inbox(cfg, "a/x.bin", String.format(CALL, "AAA1"));
        File b = inbox(cfg, "b/x.bin", String.format(CALL, "BBB2"));

        IngestOutcome out = new StreamingPluginIngestStrategy().ingest(batch(cfg, a, b), cfg);

        assertEquals("SUCCESS", out.status(), out.error());
        assertEquals(2, out.survivors().size());
        assertTrue(allData(cfg).contains("AAA1") && allData(cfg).contains("BBB2"), allData(cfg));
    }

    /** {@code processing.output_per_member}: the origin hash makes the stem unique, so the stage-1 refusal never fires. */
    @Test
    void generationModeOutputPerMemberLandsBothSameStemMembers(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, true);
        File a = inbox(cfg, "a/x.bin", String.format(CALL, "AAA1"));
        File b = inbox(cfg, "b/x.bin", String.format(CALL, "BBB2"));

        IngestOutcome out = new StreamingPluginIngestStrategy(1).ingest(batch(cfg, a, b), cfg);

        assertEquals("SUCCESS", out.status(), out.error());
        assertEquals(2, out.survivors().size(), "no output_stem_collision: " + out.memberAudits());
        assertTrue(allData(cfg).contains("AAA1") && allData(cfg).contains("BBB2"), allData(cfg));
        assertEquals(2, data(cfg).stream().filter(p -> p.getFileName().toString().matches("x__[0-9a-f]{8}_g.*")).count(),
                data(cfg).toString());
    }

    /** The multi-schema union lane (per-segment write) under output_per_member: one file per member. */
    @Test
    void unionModeOutputPerMemberWritesOneFilePerMember(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir, true);
        File a = inbox(cfg, "a/x.bin", String.format(CALL, "AAA1"));
        File b = inbox(cfg, "b/x.bin", String.format(CALL, "BBB2"));

        IngestOutcome out = new StreamingPluginIngestStrategy().ingest(batch(cfg, a, b), cfg);

        assertEquals("SUCCESS", out.status(), out.error());
        assertEquals(2, out.survivors().size());
        assertTrue(allData(cfg).contains("AAA1") && allData(cfg).contains("BBB2"), allData(cfg));
        assertEquals(2, data(cfg).stream().filter(p -> p.getFileName().toString().matches("x__[0-9a-f]{8}_out.*")).count(),
                data(cfg).toString());
    }

    private static final String SCHEMA = """
            partitions[4]{column,source,type}:
              record_type,EVENT_TYPE,VARCHAR
              year,EVENT_DATE,DATE_YEAR
              month,EVENT_DATE,DATE_MONTH
              day,EVENT_DATE,DATE_DAY
            raw:
              name: call
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                EVENT_TYPE,"1",VARCHAR
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: call
              rawName: call
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                EVENT_TYPE,EVENT_TYPE,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;

    private static final String PIPELINE = """
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
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """;
}
