package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXECUTION-RESIDUALS X4, first slice: replay ONE file's rejected records from its reject sidecar as a new
 * Consignment, without re-ingesting the file's good rows.
 *
 * <p>The fixture's schema demands a fourth column ({@code NOTE}) the feed does not always send, so the short
 * lines are ejected to the sidecar while the full ones land — then the operator "fixes the schema" (drops the
 * column) and replays. Both CSV engines are exercised: each writes its own sidecar shape.
 */
class RecordReplayTest {

    private static final String HEADER = "ID,AMT,EVENT_DATE,NOTE\n";

    /** The schema before the fix: four fields, so a three-column line is rejected. */
    private static final String SCHEMA_V1 = """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[4]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
                NOTE,"3",VARCHAR
            mapping:
              canonicalName: mini
              rawName: mini
              rules[4]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                NOTE,NOTE,DIRECT
            """;

    /** The fix: NOTE is dropped, so the three-column lines now parse. */
    private static final String SCHEMA_V2 = com.gamma.etl.PipelineConfigBatchTest.miniSchema();

    private static Path pipeline(Path dir, String engine) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        Files.writeString(dir.resolve("mini_schema.toon"), SCHEMA_V1);
        Files.writeString(toon, Files.readString(toon).replace(
                "    delimiter: \",\"", "    delimiter: \",\"\n    engine: " + engine));
        return toon;
    }

    /** Every data line of every CSV output under the database dir (header lines dropped). */
    private static List<String> landedRows(PipelineConfig cfg) throws Exception {
        List<String> rows = new ArrayList<>();
        Path db = Path.of(cfg.dirs().database());
        if (!Files.exists(db)) return rows;
        try (Stream<Path> w = Files.walk(db)) {
            for (Path p : w.filter(f -> f.toString().endsWith(".csv")).sorted().toList()) {
                List<String> lines = Files.readAllLines(p);
                rows.addAll(lines.subList(1, lines.size()));
            }
        }
        return rows;
    }

    private static long rowsWithId(List<String> rows, String idPrefix) {
        return rows.stream().filter(r -> r.startsWith(idPrefix)).count();
    }

    /** The file whose first ingest ejects two records: one plain, one whose ID is a QUOTED value with a comma. */
    private static PipelineConfig ingestOnce(Path dir, String engine) throws Exception {
        Path toon = pipeline(dir, engine);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("feed.csv"), HEADER
                + "good1,1.0,2020-04-03,n1\n"
                + "short1,3.0,2020-04-03\n"
                + "good2,2.0,2020-04-03,n2\n"
                + "\"short,2\",4.0,2020-04-03\n");
        CollectorProcessor.run(cfg);
        return cfg;
    }

    // ── grounding: eject-and-continue is today's live behaviour ────────────────────

    /**
     * The operator's 2026-09-25 default is ALREADY what the live ingest lane does for a delimited file: the
     * good records land, the file is committed, and each rejected record goes to the sidecar with its line
     * number, reason and raw line. Only the replay was missing.
     */
    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb"})
    void ejectAndContinueIsTheLiveBehaviourTheGoodRowsLandAndTheRejectsGoToTheSidecar(String engine,
                                                                                    @TempDir Path dir) throws Exception {
        PipelineConfig cfg = ingestOnce(dir, engine);

        List<String> rows = landedRows(cfg);
        assertEquals(2, rows.size(), "the two good records landed: " + rows);
        assertEquals(1, rowsWithId(rows, "good1"));
        assertEquals(1, rowsWithId(rows, "good2"));
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), "feed.csv")), "the file was committed and backed up");

        Path sidecar = Path.of(cfg.dirs().errors(), "feed_errors.csv");
        assertTrue(Files.exists(sidecar), "the rejects were ejected to the sidecar");
        List<java.util.Map<String, String>> rejects = new ArrayList<>();
        com.gamma.util.Csv.readInto(sidecar, rejects);
        assertEquals(2, rejects.size(), rejects.toString());
        assertEquals("short1,3.0,2020-04-03", rejects.get(0).get("raw_line"));
        // 🔴 The raw line must survive byte-exact — a quote rewritten to an apostrophe splits the value.
        assertEquals("\"short,2\",4.0,2020-04-03", rejects.get(1).get("raw_line"), "quotes are preserved");
    }

    // ── the replay ───────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"java", "duckdb"})
    void replayLandsOnlyTheRejectedRecordsAsANewConsignment(String engine, @TempDir Path dir) throws Exception {
        PipelineConfig first = ingestOnce(dir, engine);
        Files.writeString(dir.resolve("mini_schema.toon"), SCHEMA_V2);     // the operator's fix
        PipelineConfig cfg = PipelineConfig.load(dir.resolve("mini_pipeline.toon").toString());

        RecordReplay.Result r = RecordReplay.replay(cfg, "feed.csv", null);

        assertEquals("SUCCESS", r.status(), r.toString());
        assertEquals(2, r.records(), r.toString());
        assertEquals(2, r.outputRows(), r.toString());
        assertEquals(0, r.errorRows(), r.toString());
        assertNotNull(r.batchId());

        List<String> rows = landedRows(cfg);
        assertEquals(4, rows.size(), "two good + two replayed — the good rows were NOT re-ingested: " + rows);
        assertEquals(1, rowsWithId(rows, "good1"));
        assertEquals(1, rowsWithId(rows, "good2"));
        assertEquals(1, rowsWithId(rows, "short1"));
        assertEquals(1, rows.stream().filter(x -> x.startsWith("\"short,2\"") || x.startsWith("short,2")).count(),
                "the quoted value landed whole: " + rows);

        // Attribution: the durable replay record ties the new Consignment back to the original file's lines.
        String record = Files.readString(Path.of(r.recordPath()));
        assertTrue(record.contains("\"originalFile\": \"feed.csv\""), record);
        assertTrue(record.contains("\"replayFile\": \"" + r.replayFile() + "\""), record);
        assertTrue(record.contains("\"batchId\": \"" + r.batchId() + "\""), record);
        assertTrue(record.contains("\"lines\""), record);
        assertTrue(r.replayFile().startsWith("feed__replay_") && r.replayFile().endsWith(".csv"), r.replayFile());
        assertEquals(first.dirs().backup(), cfg.dirs().backup());
        assertTrue(Files.exists(Path.of(cfg.dirs().backup(), r.replayFile())),
                "the replay input was committed like any member (kept in backup)");
    }

    /** Idempotence: the same sidecar replayed twice would double-land — the second is refused. */
    @Test
    void replayingTheSameSidecarTwiceIsRefused(@TempDir Path dir) throws Exception {
        ingestOnce(dir, "java");
        Files.writeString(dir.resolve("mini_schema.toon"), SCHEMA_V2);
        PipelineConfig cfg = PipelineConfig.load(dir.resolve("mini_pipeline.toon").toString());
        RecordReplay.replay(cfg, "feed.csv", null);

        IllegalStateException again = assertThrows(IllegalStateException.class,
                () -> RecordReplay.replay(cfg, "feed.csv", null));
        assertTrue(again.getMessage().contains("already replayed"), again.getMessage());
        assertEquals(4, landedRows(cfg).size(), "nothing landed twice");
    }

    /** The claim path a replay of {@code feed.csv} would take — keyed on the sidecar's content hash. */
    private static Path claimFor(PipelineConfig cfg) throws Exception {
        Path sidecar = Path.of(cfg.dirs().errors()).resolve("feed_errors.csv");
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sidecar)));
        return Path.of(cfg.dirs().manifestsDir()).toAbsolutePath().resolveSibling("replays").resolve(hash + ".json");
    }

    /**
     * A crash mid-replay leaves an EMPTY claim (created before anything was written, never completed) and maybe
     * a half-written input in the poll root. Once it is older than {@link RecordReplay#ABANDONED_CLAIM_AFTER} it
     * is treated as abandoned: reclaimed, the leftover input replaced, and the replay lands exactly once.
     */
    @Test
    void anEmptyClaimOlderThanTheStaleAgeIsReclaimed(@TempDir Path dir) throws Exception {
        ingestOnce(dir, "java");
        Files.writeString(dir.resolve("mini_schema.toon"), SCHEMA_V2);
        PipelineConfig cfg = PipelineConfig.load(dir.resolve("mini_pipeline.toon").toString());
        Path claim = claimFor(cfg);
        Files.createDirectories(claim.getParent());
        Files.createFile(claim);                                               // the crash's leftover claim
        Files.setLastModifiedTime(claim, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(RecordReplay.ABANDONED_CLAIM_AFTER).minusSeconds(60)));
        String hash = claim.getFileName().toString().replace(".json", "");
        Path leftover = Path.of(cfg.dirs().poll()).resolve(RecordReplay.replayName("feed.csv", hash));
        Files.writeString(leftover, "short1,3.0,20");                        // half-written input

        RecordReplay.Result r = RecordReplay.replay(cfg, "feed.csv", null);

        assertEquals("SUCCESS", r.status(), r.toString());
        assertEquals(4, landedRows(cfg).size(), "two good + two replayed, once: " + landedRows(cfg));
        assertTrue(Files.readString(claim).contains("\"batchId\""), "the reclaimed record is completed");
    }

    /** A FRESH empty claim may be a replay still in flight (another node, a slow run) — it stays refused. */
    @Test
    void aFreshEmptyClaimIsStillRefused(@TempDir Path dir) throws Exception {
        ingestOnce(dir, "java");
        Files.writeString(dir.resolve("mini_schema.toon"), SCHEMA_V2);
        PipelineConfig cfg = PipelineConfig.load(dir.resolve("mini_pipeline.toon").toString());
        Path claim = claimFor(cfg);
        Files.createDirectories(claim.getParent());
        Files.createFile(claim);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> RecordReplay.replay(cfg, "feed.csv", null));
        assertTrue(refused.getMessage().contains("in progress"), refused.getMessage());
        assertEquals(2, landedRows(cfg).size(), "nothing landed");
    }

    /** A COMPLETED record is never stale, however old — it is the proof the records already landed. */
    @Test
    void anOldCompletedRecordIsNeverReclaimed(@TempDir Path dir) throws Exception {
        ingestOnce(dir, "java");
        Files.writeString(dir.resolve("mini_schema.toon"), SCHEMA_V2);
        PipelineConfig cfg = PipelineConfig.load(dir.resolve("mini_pipeline.toon").toString());
        RecordReplay.Result first = RecordReplay.replay(cfg, "feed.csv", null);
        Files.setLastModifiedTime(Path.of(first.recordPath()), java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofDays(30))));

        IllegalStateException again = assertThrows(IllegalStateException.class,
                () -> RecordReplay.replay(cfg, "feed.csv", null));
        assertTrue(again.getMessage().contains("already replayed"), again.getMessage());
        assertEquals(4, landedRows(cfg).size(), "nothing landed twice");
    }

    /**
     * A replay whose records STILL fail (no fix applied) lands nothing and is quarantined whole — with its own
     * sidecar under its own name, so those records remain replayable in turn. The claim is kept: the original
     * sidecar's records are now represented by the replay file's.
     */
    @Test
    void stillRejectedRecordsMoveToTheReplayFilesOwnSidecar(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = ingestOnce(dir, "java");               // schema NOT fixed

        RecordReplay.Result r = RecordReplay.replay(cfg, "feed.csv", null);

        assertNotEquals("SUCCESS", r.status(), r.toString());
        assertEquals(0, r.outputRows(), r.toString());
        assertEquals(2, landedRows(cfg).size(), "still only the original good rows");
        String replaySidecar = com.gamma.etl.CsvIngester.stripExtensions(r.replayFile()) + "_errors.csv";
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().quarantine()))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().equals(replaySidecar)),
                    "the still-rejected records have their own sidecar: " + replaySidecar);
        }
        assertThrows(IllegalStateException.class, () -> RecordReplay.replay(cfg, "feed.csv", null),
                "the original sidecar stays claimed");
    }

    @Test
    void noSidecarIsNoSuchFile(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(pipeline(dir, "java").toString());
        assertThrows(NoSuchFileException.class, () -> RecordReplay.replay(cfg, "never.csv", null));
    }

    @Test
    void aPathIsNeverAFileName(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(pipeline(dir, "java").toString());
        assertThrows(IllegalArgumentException.class, () -> RecordReplay.replay(cfg, "../feed.csv", null));
        assertThrows(IllegalArgumentException.class, () -> RecordReplay.replay(cfg, "a/feed.csv", null));
    }
}
