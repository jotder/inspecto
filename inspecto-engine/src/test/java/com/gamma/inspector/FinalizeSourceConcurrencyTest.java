package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.DbAcquisitionLedger;
import com.gamma.acquire.LedgerEntry;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.consignment.DbFileStageStore;
import com.gamma.consignment.FileStage;
import com.gamma.consignment.FileStageRecord;
import com.gamma.consignment.FileStages;
import com.gamma.etl.Consignment;
import com.gamma.etl.MarkerManager;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The concurrency harness for {@link ConsignmentIngestor#finalizeSource} (BACKLOG §4, delimited-grammar
 * row (b)): the ledger's block contiguity was stress-pinned in {@code ConsignmentAuditWriterTest}, but the
 * three DB stores the finalisation writes — {@link DbConsignmentOutputStore},
 * {@link DbAcquisitionLedger}, {@link DbFileStageStore} — and the marker fail-fast were, until this
 * class, argued only structurally ({@code synchronized} + one shared connection per store).
 *
 * <p>Two claims are pinned, each falsifiable on its own:
 * <ol>
 *   <li><b>Store consistency under concurrent finalizes of DISTINCT batches.</b> Every store is a
 *       process-wide shared singleton, so N pipelines finalizing at once all funnel into the same
 *       three connections. Losing a row, tearing a transaction, or cross-wiring two batches' entries
 *       would surface here as a missing/mismatched record.</li>
 *   <li><b>The marker fail-fast under concurrent finalizes of the SAME file.</b>
 *       {@code Files.createFile} is the atomic arbiter: exactly one finalize may claim a file, and
 *       the loser must FAIL (propagate), never silently succeed — a silent second success is a
 *       double-commit.</li>
 * </ol>
 *
 * <p>⚠ The stores' own javadoc disclaims anything beyond {@code synchronized} + a single shared
 * connection ({@code DbAcquisitionLedger:81-85}) — this harness pins exactly that claim, no more. A
 * future connection pool removes the serialisation and MUST re-run this class against the new shape.
 */
class FinalizeSourceConcurrencyTest {

    private static final int THREADS = 8;

    @AfterEach
    void clearSharedStores() {
        // Every store is process-wide static — never leak into another test.
        ConsignmentOutputStores.use(null);
        FileStages.use(null);
        AcquisitionLedgers.use(null);
    }

    private static Consignment.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Consignment.Member(f, id, f.length(), sel);
    }

    /** A checksum-dedup pipeline (ledger records, markers skipped), one per worker directory. */
    private static PipelineConfig checksumPipeline(Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        String toon = """
            name: FINALIZE_STRESS_ETL
            active: true
            version: 1
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
              log_dir: %s/logs
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            collector:
              duplicate:
                mode: checksum
                algorithm: SHA256
                on_change: reprocess
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"));
        Path p = dir.resolve("stress_pipeline.toon");
        Files.writeString(p, toon);
        return PipelineConfig.load(p.toString());
    }

    /** The two-partition table {@code writeAndTrace} writes from (the §11.3 fixture's shape). */
    private static Connection openWithTwoPartitions(File db) throws Exception {
        DuckDbUtil.loadDriver();
        Connection conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE transformed AS SELECT * FROM (VALUES
                      ('alice', 250.0, '2026', '07', '01', 1),
                      ('carol', 999.0, '2026', '07', '02', 1)
                    ) v(name, cost, year, month, day, __src_id)""");
        }
        return conn;
    }

    /**
     * N distinct batches finalize at once through the three SHARED stores. Afterwards every batch's
     * rows must be present, complete, and attributed to the right batch — in all three stores plus
     * the manifest directory.
     */
    @Test
    void concurrentFinalizesOfDistinctBatchesKeepEveryStoreConsistent(@TempDir Path root) throws Exception {
        try (DbConsignmentOutputStore outputs = DbConsignmentOutputStore.open("jdbc:duckdb:");
             DbFileStageStore stages = DbFileStageStore.open("jdbc:duckdb:");
             DbAcquisitionLedger ledger = DbAcquisitionLedger.open("jdbc:duckdb:", null, null)) {
            ConsignmentOutputStores.use(outputs);
            FileStages.use(stages);
            AcquisitionLedgers.use(ledger);

            record Work(PipelineConfig cfg, Consignment batch, String rel) {}
            List<Work> work = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Path dir = Files.createDirectories(root.resolve("w" + i));
                PipelineConfig cfg = checksumPipeline(dir);
                Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
                // Unique file name per worker: the ledger keys on (sourceId, relativePath) and every
                // worker shares one sourceId, so a shared name would be one key written N times.
                Path f = inbox.resolve("feed_" + i + ".csv");
                Files.writeString(f, "ID,AMT,EVENT_DATE\nx" + i + ",9.0,2020-04-03\n");
                Consignment batch = new Consignment("stress_batch_" + i, "mini", null,
                        List.of(member(cfg, f.toFile(), 0)));
                work.add(new Work(cfg, batch, "feed_" + i + ".csv"));
            }

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (Work w : work) {
                    futures.add(pool.submit(() -> {
                        File db = DuckDbUtil.tempDbFile("fin_stress_");
                        try (Connection conn = openWithTwoPartitions(db)) {
                            ConsignmentIngestStrategy.Written written = ConsignmentIngestStrategy.writeAndTrace(
                                    conn, "transformed", List.of("year", "month", "day"), w.cfg(),
                                    w.cfg().dirs().database(), "b1", w.batch().batchId(),
                                    Map.of(1, w.rel()), "");
                            start.await(); // every worker has its outputs ready — finalize together
                            ConsignmentIngestor.finalizeSource(w.batch(), w.cfg(),
                                    w.batch().members(), written.outputs(), written.lineage());
                            return null;
                        } finally {
                            DuckDbUtil.deleteTempDb(db);
                        }
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) f.get(); // propagate any worker failure
            } finally {
                pool.shutdownNow();
            }

            for (Work w : work) {
                String sourceId = w.cfg().collector().id();

                // §11.3 output registry: this batch's two partition files, attributed to IT alone.
                List<ConsignmentOutput> rows = outputs.outputs(w.batch().batchId());
                assertEquals(2, rows.size(), "one registry row per output file for " + w.batch().batchId());
                assertEquals(2L, rows.stream().mapToLong(ConsignmentOutput::rows).sum(),
                        "summed row_count reconciles for " + w.batch().batchId());
                for (ConsignmentOutput o : rows)
                    assertEquals(w.batch().batchId(), o.consignmentId(),
                            "no cross-wiring: every row names its own batch");

                // Fingerprint ledger: the file is PROCESSED under its own (sourceId, rel) key.
                LedgerEntry e = ledger.find(sourceId, w.rel()).orElseThrow(
                        () -> new AssertionError("ledger lost " + w.rel() + " under concurrency"));
                assertEquals(LedgerEntry.PROCESSED, e.status());
                assertNotNull(e.checksum(), "checksum mode records the fingerprint");

                // File-stage trail: the full finalize sequence, in order, for this file.
                List<FileStage> stageSeq = stages.stages(sourceId, w.rel()).stream()
                        .map(FileStageRecord::stage).toList();
                assertEquals(List.of(FileStage.REGISTERED, FileStage.MANIFESTED,
                                FileStage.OUTPUT_REGISTERED, FileStage.BACKED_UP, FileStage.MARKED),
                        stageSeq, "the crash-ordered stage trail survives concurrency for " + w.rel());

                // Manifest: required, one per batch, in the batch's own manifests dir.
                try (var files = Files.list(Path.of(w.cfg().dirs().manifestsDir()))) {
                    assertEquals(1, files.count(), "exactly one manifest for " + w.batch().batchId());
                }

                // Backup happened (the inbox file moved), so a crash cannot re-ingest it.
                assertFalse(Files.exists(Path.of(w.cfg().dirs().poll()).resolve(w.rel())),
                        "the inbox file was backed up out of the poll dir");
            }
        }
    }

    /**
     * Two finalizes race over the SAME file (PATH-mode dedup, markers armed). {@code Files.createFile}
     * is the arbiter: exactly one wins; the loser must propagate {@link FileAlreadyExistsException} —
     * a silent second success would be a double-commit the poll loop can never detect.
     */
    /** PATH-mode pipeline with NO backup dir: the marker is the only resource the two racers contend
     *  for, so the loser's exception is deterministically the marker's — a backup dir would add a
     *  same-file {@code Files.move} race whose loser can throw platform-dependent exceptions first. */
    private static PipelineConfig pathModeNoBackupPipeline(Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        String toon = """
            name: MARKER_RACE_ETL
            active: true
            version: 1
            dirs:
              poll: %s/inbox
              database: %s/db
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
              log_dir: %s/logs
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"));
        Path p = dir.resolve("race_pipeline.toon");
        Files.writeString(p, toon);
        return PipelineConfig.load(p.toString());
    }

    @Test
    void concurrentFinalizeOfTheSameFileFailsFastOnTheMarker(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pathModeNoBackupPipeline(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path f = inbox.resolve("contended.csv");
        Files.writeString(f, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Consignment.Member> survivors = List.of(member(cfg, f.toFile(), 0));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Throwable>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String batchId = "contended_batch_" + i;
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        // Empty outputs/lineage: the registry leg is deliberately out of play — this
                        // test isolates the marker arbiter, the §11.3 leg is test 1's.
                        ConsignmentIngestor.finalizeSource(new Consignment(batchId, "mini", null, survivors),
                                cfg, survivors, List.of(), List.of());
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            start.countDown();
            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> o : outcomes) results.add(o.get());

            long failures = results.stream().filter(java.util.Objects::nonNull).count();
            assertEquals(1, failures, "exactly one finalize must lose the marker race — zero means a "
                    + "double-commit went unnoticed, two means the winner failed too");
            Throwable loser = results.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            assertInstanceOf(FileAlreadyExistsException.class, loser,
                    "the loser fails on the ATOMIC marker create, not some later accident");
            assertTrue(Files.exists(MarkerManager.getMarkerPath(f.toFile(), cfg)),
                    "the winner's marker stands");
        } finally {
            pool.shutdownNow();
        }
    }
}
