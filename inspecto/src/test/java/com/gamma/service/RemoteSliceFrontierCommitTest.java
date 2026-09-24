package com.gamma.service;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KAFKA-OFFSET-REKEY-1 (design slice 0, stream-consumer-design §2.2): drives the REAL remote path — the
 * acquisition driver ({@link PipelineScheduler#dispatchAcquireCycle()}: discover, fetch to STAGING, land into the
 * INBOX) then the ingest cycle ({@code dispatchCycle}: {@code ConsignmentIngestor} commit) — over
 * {@link FakeOffsetTailConnectorFactory}, which stashes its reached offset exactly as {@code KafkaConnector} and
 * {@code DbExportConnector} do (keyed by the {@code dest} path {@code fetchTo} is handed). The commit looks the
 * stash up by the landed inbox path, so the question is whether the frontier actually advances.
 */
class RemoteSliceFrontierCommitTest {

    private InMemoryAcquisitionLedger ledger;

    @BeforeEach void setUp() { ledger = new InMemoryAcquisitionLedger(); AcquisitionLedgers.use(ledger); }
    @AfterEach  void tearDown() { AcquisitionLedgers.use(null); }

    private static PipelineScheduler scheduler(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("pipelineScheduler");
        f.setAccessible(true);
        return (PipelineScheduler) f.get(svc);
    }

    private interface Condition { boolean holds() throws Exception; }

    private static boolean awaitTrue(Condition cond) throws Exception {
        for (int i = 0; i < 200; i++) { if (cond.holds()) return true; Thread.sleep(50); }
        return false;
    }

    private static List<String> files(Path root, String suffix) throws Exception {
        if (!Files.exists(root)) return List.of();
        try (var s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> n.endsWith(suffix)).sorted().collect(Collectors.toList());
        }
    }

    /** One acquire cycle then one ingest cycle; waits until the inbox holds no un-marked slice. */
    private static void cycle(CollectorService svc, Path dir) throws Exception {
        scheduler(svc).dispatchAcquireCycle();
        Thread.sleep(500);                                  // let the async acquisition land (or find nothing)
        scheduler(svc).dispatchCycle();
        assertTrue(awaitTrue(() -> files(dir.resolve("inbox"), ".csv").size()
                        <= files(dir.resolve("markers"), ".processed").size()),
                "ingest cycle did not mark the landed slice(s): inbox=" + files(dir.resolve("inbox"), ".csv")
                        + " markers=" + files(dir.resolve("markers"), ".processed"));
        Thread.sleep(300);
    }

    private static Path pipeline(Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """);
        String toon = """
            name: TAIL_ETL
            active: true
            version: 1
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
              log_dir: %1$s/logs
            collector:
              connector: faketail
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              batch:
                max_files: 100
                max_bytes: 268435456
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir.toString().replace("\\", "/"), schema.toString().replace("\\", "/"));
        Path p = dir.resolve("tail_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    /** Data rows across every ingested output file (header lines excluded). */
    private static long outputRows(Path db) throws Exception {
        long n = 0;
        for (String f : files(db, ".csv"))
            for (String line : Files.readAllLines(db.resolve(f)))
                if (!line.isBlank() && !line.startsWith("ID")) n++;
        return n;
    }

    @Test
    void committedSliceAdvancesTheFrontierAndTheNextCycleIngestsOnlyNewOffsets(@TempDir Path dir) throws Exception {
        FakeOffsetTailConnectorFactory.FETCHED.clear();
        FakeOffsetTailConnectorFactory.END.set(3);          // offsets 0,1,2 on the fake topic
        CollectorService svc = new CollectorService(List.of(pipeline(dir)), 3600, 1);
        try {
            cycle(svc, dir);
            assertEquals(List.of("tail-p0-0-3.csv"), FakeOffsetTailConnectorFactory.FETCHED, "cycle 1 drains [0,3)");
            assertEquals(1, files(dir.resolve("markers"), ".processed").size(),
                    "cycle 1 committed the slice; tree=" + files(dir, ""));
            String frontierAfter1 = ledger.dbWatermark(FakeOffsetTailConnectorFactory.WATERMARK_KEY).orElse("<none>");
            long rowsAfter1 = outputRows(dir.resolve("db"));

            // Cycle 2 with NO new messages: nothing may be fetched or ingested.
            cycle(svc, dir);
            List<String> fetchedAfter2 = List.copyOf(FakeOffsetTailConnectorFactory.FETCHED);
            long rowsAfter2 = outputRows(dir.resolve("db"));

            // Cycle 3, one new message (offset 3): only [3,4) may be emitted — never an overlapping [0,4).
            FakeOffsetTailConnectorFactory.END.set(4);
            cycle(svc, dir);
            List<String> fetchedAfter3 = List.copyOf(FakeOffsetTailConnectorFactory.FETCHED);
            long rowsAfter3 = outputRows(dir.resolve("db"));
            String frontierAfter3 = ledger.dbWatermark(FakeOffsetTailConnectorFactory.WATERMARK_KEY).orElse("<none>");

            // Diagnosis: is the reached offset still sitting un-taken under the STAGING path fetchTo was handed?
            Path staged = dir.resolve("temp").resolve("acquire").resolve("tail-p0-0-4.csv");
            String orphan = AcquisitionLedgers.takeDbWatermark(staged).map(w -> w.value()).orElse("<none>");

            assertAll(
                () -> assertEquals(3, rowsAfter1, "cycle 1 ingests offsets 0..2"),
                () -> assertEquals("3", frontierAfter1, "the [0,3) commit must advance the frontier to 3"),
                () -> assertEquals(List.of("tail-p0-0-3.csv"), fetchedAfter2, "cycle 2 (no new messages) fetches nothing"),
                () -> assertEquals(3, rowsAfter2, "cycle 2 (no new messages) ingests no rows"),
                () -> assertEquals(List.of("tail-p0-0-3.csv", "tail-p0-3-4.csv"), fetchedAfter3,
                        "cycle 3 emits only the new offset, never an overlapping slice"),
                () -> assertEquals(4, rowsAfter3, "every offset ingested exactly once"),
                () -> assertEquals("4", frontierAfter3, "frontier after cycle 3"),
                () -> assertEquals("<none>", orphan, "no reached offset left orphaned under the staging path"));
        } finally {
            svc.close();
        }
    }

    /** What a process restart does to the in-memory frontier stash (the package-private seam, reflectively). */
    private static void forgetInMemoryFrontiers() throws Exception {
        Method m = AcquisitionLedgers.class.getDeclaredMethod("clearPendingDbWatermarks");
        m.setAccessible(true);
        m.invoke(null);
    }

    private static void awaitLanded(Path dir, int n) throws Exception {
        assertTrue(awaitTrue(() -> files(dir.resolve("inbox"), ".csv").size() >= n),
                "acquisition did not land " + n + " slice(s): inbox=" + files(dir.resolve("inbox"), ".csv"));
        Thread.sleep(300);                                  // let the land's bookkeeping finish
    }

    /**
     * STREAM-CONSUMER-1 Q3: a restart between land and commit must not lose the frontier. The slice lands, the
     * in-memory stash is wiped (the restart), then the commit runs: it must still advance the frontier, so the
     * next cycle drains only the new offset — never the landed range again.
     */
    @Test
    void aRestartBetweenLandAndCommitKeepsTheFrontier(@TempDir Path dir) throws Exception {
        FakeOffsetTailConnectorFactory.FETCHED.clear();
        FakeOffsetTailConnectorFactory.END.set(3);
        CollectorService svc = new CollectorService(List.of(pipeline(dir)), 3600, 1);
        try {
            scheduler(svc).dispatchAcquireCycle();
            awaitLanded(dir, 1);
            forgetInMemoryFrontiers();                      // the restart: landed, not yet committed

            scheduler(svc).dispatchCycle();
            assertTrue(awaitTrue(() -> files(dir.resolve("markers"), ".processed").size() == 1), "slice committed");
            Thread.sleep(300);
            String frontier = ledger.dbWatermark(FakeOffsetTailConnectorFactory.WATERMARK_KEY).orElse("<none>");

            FakeOffsetTailConnectorFactory.END.set(4);      // one new message
            cycle(svc, dir);

            assertAll(
                () -> assertEquals("3", frontier, "the commit recovers the landed slice's frontier after a restart"),
                () -> assertEquals(List.of("tail-p0-0-3.csv", "tail-p0-3-4.csv"), FakeOffsetTailConnectorFactory.FETCHED,
                        "the next cycle drains only the new offset"),
                () -> assertEquals(4, outputRows(dir.resolve("db")), "no row ingested twice"),
                () -> assertEquals("4", ledger.dbWatermark(FakeOffsetTailConnectorFactory.WATERMARK_KEY).orElse("<none>")));
        } finally {
            svc.close();
        }
    }

    /**
     * Design slice 2, the in-flight fence: while a slice is landed but uncommitted, a second acquisition cycle
     * must not fetch an overlapping slice for the same partition — even across a restart.
     */
    @Test
    void aLandedButUncommittedSliceFencesItsPartition(@TempDir Path dir) throws Exception {
        FakeOffsetTailConnectorFactory.FETCHED.clear();
        FakeOffsetTailConnectorFactory.END.set(3);
        CollectorService svc = new CollectorService(List.of(pipeline(dir)), 3600, 1);
        try {
            scheduler(svc).dispatchAcquireCycle();
            awaitLanded(dir, 1);

            FakeOffsetTailConnectorFactory.END.set(4);      // backlog grows before the commit
            scheduler(svc).dispatchAcquireCycle();          // same process: fenced
            Thread.sleep(500);
            forgetInMemoryFrontiers();                      // restart: the fence must survive it too
            scheduler(svc).dispatchAcquireCycle();
            Thread.sleep(500);
            List<String> fetchedBeforeCommit = List.copyOf(FakeOffsetTailConnectorFactory.FETCHED);

            cycle(svc, dir);                                // commits [0,3), then the next acquire drains [3,4)
            cycle(svc, dir);

            assertAll(
                () -> assertEquals(List.of("tail-p0-0-3.csv"), fetchedBeforeCommit,
                        "no second slice while [0,3) is landed but uncommitted"),
                () -> assertEquals(List.of("tail-p0-0-3.csv", "tail-p0-3-4.csv"), FakeOffsetTailConnectorFactory.FETCHED),
                () -> assertEquals(4, outputRows(dir.resolve("db")), "every offset ingested exactly once"),
                () -> assertEquals("4", ledger.dbWatermark(FakeOffsetTailConnectorFactory.WATERMARK_KEY).orElse("<none>")));
        } finally {
            svc.close();
        }
    }
}
