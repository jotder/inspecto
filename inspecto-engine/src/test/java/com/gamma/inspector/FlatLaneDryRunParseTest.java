package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.etl.Consignment;
import com.gamma.etl.ConsignmentPlanner;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import com.gamma.etl.SchemaSelector;
import com.gamma.etl.StreamingFileIngester;
import com.gamma.inspector.PipelineTestRun.MemberKind;
import com.gamma.inspector.PipelineTestRun.MemberOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FLAT-DRYRUN-COUNTS-ZERO-1 — a flat-lane dry run PARSES its real members (through
 * {@link PipelineTestRun#dryIngest}) and reports, per member, which KIND of end it reached, while landing
 * nothing. The kinds are the ones {@code EXECUTION-RESIDUALS} X4 needs told apart: a validation rejection
 * ({@code QUARANTINED_*}) is not a thrown fault.
 *
 * <p>Every side-effect assertion is paired with a probe that WOULD write in non-dry mode — the quarantine
 * move (the real pass moves a rejected member out of the inbox) and the DB-export watermark (the real commit
 * advances it) — so a green here cannot mean the fixture simply never wrote anything.
 */
class FlatLaneDryRunParseTest {

    private static final String MULTI = """
              batch:
                max_files: 10
            """;

    /** The fixture's first line is read as the header (as in {@code FlatLaneDryRunTest}). */
    private static final String HEADER = "ID,AMT,EVENT_DATE\n";
    /** Every data line one field short of the declared three — a field-mismatch rejection. */
    private static final String BAD = HEADER + "only-one-field\nstill-one\n";

    private static PipelineConfig load(Path dir, String section) throws Exception {
        return PipelineConfig.load(PipelineConfigBatchTestRef.writePipeline(dir, section).toString());
    }

    private static Consignment onlyBatch(PipelineConfig cfg, List<File> files) throws Exception {
        List<Consignment> batches = ConsignmentPlanner.plan(files,
                f -> new SchemaSelector.Selection(cfg.schemas().single(), null),
                cfg.processing().batchMaxFiles(), cfg.processing().batchMaxBytes(),
                cfg.identity().runTimestamp(), ConsignmentPlanner.Order.NAME);
        assertEquals(1, batches.size(), "fixture: one Consignment of every member");
        return batches.getFirst();
    }

    private static long filesUnder(String dir) throws Exception {
        if (dir == null || !Files.exists(Path.of(dir))) return 0;
        try (Stream<Path> w = Files.walk(Path.of(dir))) {
            return w.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void aDryRunParsesEveryMemberAndReportsWhichKindOfEndItReached(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, MULTI);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path good = Files.writeString(inbox.resolve("a_good.csv"), HEADER + "a1,1.0,2020-04-03\na2,2.0,2020-04-03\n");
        Path empty = Files.writeString(inbox.resolve("b_empty.csv"), "");
        Path bad = Files.writeString(inbox.resolve("c_bad.csv"), BAD);
        Consignment batch = onlyBatch(cfg, List.of(good.toFile(), empty.toFile(), bad.toFile()));

        PipelineTestRun.DryIngest dry = PipelineTestRun.dryIngest(batch, cfg);

        Map<String, MemberOutcome> by = dry.members().stream()
                .collect(Collectors.toMap(MemberOutcome::filename, Function.identity()));
        assertEquals(3, by.size(), dry.members().toString());

        MemberOutcome g = by.get("a_good.csv");
        assertEquals(MemberKind.WOULD_LAND, g.kind(), g.toString());
        assertEquals("SUCCESS", g.status());
        assertEquals(2, g.parsedRows(), "a real parse, not the old fabricated zero");

        MemberOutcome e = by.get("b_empty.csv");
        assertEquals(MemberKind.REJECTED, e.kind(), e.toString());
        assertEquals("QUARANTINED_EMPTY", e.status());

        MemberOutcome b = by.get("c_bad.csv");
        assertEquals(MemberKind.REJECTED, b.kind(), b.toString());
        assertTrue(b.status().startsWith("QUARANTINED_"), b.toString());
        assertFalse(b.reason().isBlank(), "a rejection carries the pass's own reason: " + b);

        assertEquals("SUCCESS", dry.outcome().status(), "a validation rejection does not fail the batch");
        assertEquals(2, dry.parsedRows());
        assertEquals(2, dry.wouldLandRows(), "the good member's rows would land");

        // ── zero side effects, against probes the real pass WOULD trip ───────────
        // The real pass quarantines b_empty/c_bad with a Files.move of the SOURCE: they must still be here.
        for (Path p : List.of(good, empty, bad)) assertTrue(Files.exists(p), "inbox member moved: " + p);
        assertEquals(0, filesUnder(cfg.dirs().quarantine()), "a dry run must quarantine nothing");
        assertEquals(0, filesUnder(cfg.dirs().database()), "a dry run must write no partition output");
        assertEquals(0, filesUnder(cfg.dirs().errors()), "the reject sidecars stay in the deleted scratch");
        assertEquals(0, filesUnder(cfg.dirs().temp()), "the scratch root is deleted before returning");
        assertTrue(ParkedBranches.drain(batch.batchId() + PipelineTestRun.DRY_BATCH_SUFFIX).isEmpty());
    }

    /**
     * X4's precondition: each member's rejected RECORDS — line number + reason, as the reject sidecar
     * {@code <errors>/<file>_errors.csv} records them — are read out of the scratch root before it is deleted.
     * Both homes the sidecar can have are covered: {@code errors/} for a member accepted while losing rows, and
     * the quarantine tree for a wholly-rejected member ({@code QuarantineManager} moves the sidecar with it).
     */
    @Test
    void aDryRunReportsEachMembersRejectedRecordsWithLineAndReason(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, MULTI);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path partial = Files.writeString(inbox.resolve("a_partial.csv"),
                HEADER + "a1,1.0,2020-04-03\nshort-line\na2,2.0,2020-04-03\nalso-short\n");
        Path clean = Files.writeString(inbox.resolve("b_clean.csv"), HEADER + "b1,1.0,2020-04-03\n");
        Path bad = Files.writeString(inbox.resolve("c_bad.csv"), BAD);
        Consignment batch = onlyBatch(cfg, List.of(partial.toFile(), clean.toFile(), bad.toFile()));

        PipelineTestRun.DryIngest dry = PipelineTestRun.dryIngest(batch, cfg);

        Map<String, MemberOutcome> by = dry.members().stream()
                .collect(Collectors.toMap(MemberOutcome::filename, Function.identity()));

        MemberOutcome p = by.get("a_partial.csv");
        assertEquals(MemberKind.WOULD_LAND, p.kind(), p.toString());
        assertEquals(2, p.rejectTotal(), "two short lines rejected: " + p);
        assertEquals(p.errorRows(), p.rejectTotal(), "the sidecar agrees with the pass's own count: " + p);
        assertEquals(2, p.rejects().size(), p.toString());
        List<Long> lines = p.rejects().stream().map(PipelineTestRun.RejectedRecord::line).toList();
        assertEquals(lines.stream().sorted().toList(), lines, "in file order: " + p);
        assertTrue(lines.get(0) > 0 && lines.get(1) > lines.get(0), "real line numbers: " + p);
        for (PipelineTestRun.RejectedRecord r : p.rejects())
            assertFalse(r.reason().isBlank(), "every record carries a reason: " + r);

        MemberOutcome c = by.get("b_clean.csv");
        assertEquals(0, c.rejectTotal(), c.toString());
        assertTrue(c.rejects().isEmpty(), c.toString());

        MemberOutcome b = by.get("c_bad.csv");
        assertEquals(MemberKind.REJECTED, b.kind(), b.toString());
        assertEquals(2, b.rejectTotal(), "the sidecar followed the member into the scratch quarantine: " + b);

        // Still zero side effects: the sidecars were read, then deleted with the scratch root.
        for (Path f : List.of(partial, clean, bad)) assertTrue(Files.exists(f), "inbox member moved: " + f);
        assertEquals(0, filesUnder(cfg.dirs().errors()), "no sidecar outside the scratch root");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()));
        assertEquals(0, filesUnder(cfg.dirs().temp()), "the scratch root is deleted before returning");
    }

    /** The per-member list is CAPPED; the total is not. */
    @Test
    void theRejectedRecordListIsCappedButTheTotalIsExact(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, MULTI);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        int bad = PipelineTestRun.REJECTS_PER_MEMBER + 25;
        StringBuilder sb = new StringBuilder(HEADER).append("a1,1.0,2020-04-03\n");
        for (int i = 0; i < bad; i++) sb.append("short-").append(i).append('\n');
        Path many = Files.writeString(inbox.resolve("many.csv"), sb.toString());
        Consignment batch = onlyBatch(cfg, List.of(many.toFile()));

        MemberOutcome m = PipelineTestRun.dryIngest(batch, cfg).members().getFirst();

        assertEquals(bad, m.rejectTotal(), m.kind() + " " + m.status());
        assertEquals(PipelineTestRun.REJECTS_PER_MEMBER, m.rejects().size());
    }

    /** Control for the quarantine probe: the same members ingested for real DO leave the inbox. */
    @Test
    void theRealPassQuarantinesTheSameMembers(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, MULTI);
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path empty = Files.writeString(inbox.resolve("b_empty.csv"), "");
        Path bad = Files.writeString(inbox.resolve("c_bad.csv"), BAD);
        Consignment batch = onlyBatch(cfg, List.of(empty.toFile(), bad.toFile()));

        IngestOutcome real = new CsvIngestStrategy().ingest(batch, cfg);

        assertEquals(2, real.memberAudits().size(), real.toString());
        assertFalse(Files.exists(empty), "the real pass moves a rejected member out of the inbox");
        assertFalse(Files.exists(bad));
        assertTrue(filesUnder(cfg.dirs().quarantine()) >= 2, "both rejected members land in quarantine");
    }

    /**
     * A thrown fault fails the whole BATCH: a mapping expression that raises at transform time, after every
     * member parsed cleanly. Each member is then a FAULT, not a rejection — it parsed, and still lands nothing.
     */
    @Test
    void aBatchFaultIsReportedAsAFaultNotARejection(@TempDir Path dir) throws Exception {
        load(dir, MULTI);   // writes mini_schema.toon; overwritten below, then reloaded
        Files.writeString(dir.resolve("mini_schema.toon"), com.gamma.etl.PipelineConfigBatchTest.miniSchema()
                .replace("AMT,AMT,DIRECT", "AMT,\"error('dry-run fault probe')\",EXPR"));
        PipelineConfig cfg = PipelineConfig.load(dir.resolve("mini_pipeline.toon").toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path good = Files.writeString(inbox.resolve("a_good.csv"), HEADER + "a1,1.0,2020-04-03\n");
        Path bad = Files.writeString(inbox.resolve("c_bad.csv"), BAD);
        Consignment batch = onlyBatch(cfg, List.of(good.toFile(), bad.toFile()));

        PipelineTestRun.DryIngest dry = assertDoesNotThrow(() -> PipelineTestRun.dryIngest(batch, cfg));

        assertEquals("FAILED", dry.outcome().status(), dry.outcome().toString());
        Map<String, MemberOutcome> by = dry.members().stream()
                .collect(Collectors.toMap(MemberOutcome::filename, Function.identity()));
        MemberOutcome g = by.get("a_good.csv");
        assertEquals(MemberKind.FAULT, g.kind(), "a well-formed member of a batch that threw: " + g);
        // ⚠ The native union lane records no per-member audit before its one transform, so a fault there
        // leaves the member un-audited (status null = "not reached"); the Java lane would say SUCCESS. Either
        // way the KIND is FAULT, which is the point: nothing about this member was rejected.
        assertTrue(g.reason() != null && g.reason().contains("dry-run fault probe"), g.toString());
        assertNotEquals(MemberKind.WOULD_LAND, by.get("c_bad.csv").kind(), by.get("c_bad.csv").toString());
        assertEquals(0, dry.wouldLandRows(), "a faulted batch lands nothing");
        assertTrue(Files.exists(good) && Files.exists(bad), "the inbox members are untouched");
    }

    /** Every member throws inside a plugin decoder. */
    public static class ThrowingIngester implements StreamingFileIngester {
        @Override
        public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
            throw new IllegalStateException("decoder exploded on " + file.getName());
        }
    }

    /**
     * ⚠ A PER-FILE decoder throw is NOT a batch fault: the plugin lane catches it per member and quarantines
     * that member {@code QUARANTINED_UNREADABLE}. Pinned because it is the distinction X4's manifest must key
     * on — "the decoder threw" and "the batch threw" are different kinds.
     */
    @Test
    void aPluginDecoderThrowIsAPerMemberRejectionNotABatchFault(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("seg_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        String s = schema.toString().replace("\\", "/");
        Path toon = dir.resolve("plugin_pipeline.toon");
        Files.writeString(toon, """
                name: PLUGIN_ETL
                version: 1
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  errors: %1$s/errors
                  quarantine: %1$s/quarantine
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  ingester: %2$s
                  segments:
                    MINI: %3$s
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir.toString().replace("\\", "/"), ThrowingIngester.class.getName(), s));
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path in = Files.writeString(inbox.resolve("events.bin"), "x\n");
        Consignment.Member m = new Consignment.Member(in.toFile(), 0, in.toFile().length(),
                new SchemaSelector.Selection(Map.of(), null));
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_plugin_0001", "mini", null,
                List.of(m));

        PipelineTestRun.DryIngest dry = assertDoesNotThrow(() -> PipelineTestRun.dryIngest(batch, cfg));

        assertEquals(1, dry.members().size());
        MemberOutcome only = dry.members().getFirst();
        assertEquals(MemberKind.REJECTED, only.kind(), only.toString());
        assertEquals("QUARANTINED_UNREADABLE", only.status(), only.toString());
        assertEquals("events.bin", only.filename(), "reported under the INBOX name, not the scratch copy");
        assertTrue(only.reason() != null && only.reason().contains("decoder exploded"), only.toString());
        assertEquals(0, dry.wouldLandRows());
        assertTrue(Files.exists(in), "the inbox member is untouched — the real pass would have quarantined it");
        assertEquals(0, filesUnder(cfg.dirs().quarantine()));
    }

    /**
     * The watermark/ledger probe: a DB-export watermark stashed for an inbox file is exactly what a real
     * commit advances. Under a dry run the ledger must not move AND the stash must still be pending — the
     * contained pass ran over a COPY, so it never even consumed the stash.
     */
    @Test
    void aDryRunNeitherAdvancesTheWatermarkNorConsumesItsStash(@TempDir Path dir) throws Exception {
        String key = "dryrun-probe-" + System.nanoTime();
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = load(dir, "");
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            Path a = Files.writeString(inbox.resolve("a.csv"), HEADER + "a1,1.0,2020-04-03\n");
            AcquisitionLedgers.stashDbWatermark(a.toAbsolutePath().normalize(), key, "42");

            CollectorProcessor.ingest(cfg, e -> { }, true);

            assertTrue(ledger.dbWatermark(key).isEmpty(), "a dry run must not advance the watermark");
            assertTrue(AcquisitionLedgers.hasPendingDbWatermark(key), "the stash must survive a dry run");

            // Control: the same cycle for real advances it — so the probe above could have tripped.
            CollectorProcessor.ingest(cfg, e -> { }, false);
            assertEquals(java.util.Optional.of("42"), ledger.dbWatermark(key),
                    "a real commit advances the watermark");
        } finally {
            AcquisitionLedgers.use(null);
        }
    }
}
