package com.gamma.inspector;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.etl.Batch;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §11.3 slice 2 — the output registry's production caller on the ingest path. {@code finalizeSource} indexes
 * every file the Consignment wrote, beside (and after) the JSON manifest, with a row count summed from
 * {@code LineageCollector}'s matrix.
 *
 * <p>The load-bearing assertion is §7.2's <b>reconciliation</b>: the registry's summed {@code row_count} equals
 * the number of rows actually written. That is the check that would catch the summation being wrong — a per-file
 * count is the one field {@code PartitionOutput} cannot supply, so it is the one that can silently drift.
 */
class ConsignmentOutputRegistrationTest {

    @AfterEach
    void clearRegistry() {
        ConsignmentOutputStores.use(null);   // the registry is process-wide static — never leak into another test
    }

    private Batch.Member member(PipelineConfig cfg, File f, int id) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        return new Batch.Member(f, id, f.length(), sel);
    }

    /** Four rows over two record-days, contributed by two members so each output file needs a real sum. */
    private Connection openWithTwoPartitions(File db) throws Exception {
        DuckDbUtil.loadDriver();
        Connection conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE transformed AS SELECT * FROM (VALUES
                      ('alice', 250.0, '2026', '07', '01', 1),
                      ('bob',    50.0, '2026', '07', '01', 2),
                      ('carol', 999.0, '2026', '07', '02', 1),
                      ('dave',  100.0, '2026', '07', '02', 2)
                    ) v(name, cost, year, month, day, __src_id)""");
        }
        return conn;
    }

    @Test
    void registersEveryOutputFileWithRowCountsThatReconcile(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Batch.Member> survivors = List.of(member(cfg, solo.toFile(), 0));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, survivors);

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);

            File db = DuckDbUtil.tempDbFile("cor_ingest_");
            try (Connection conn = openWithTwoPartitions(db)) {
                BatchIngestStrategy.Written written = BatchIngestStrategy.writeAndTrace(
                        conn, "transformed", List.of("year", "month", "day"), cfg,
                        cfg.dirs().database(), "b1", batch.batchId(), Map.of(1, "a.csv", 2, "b.csv"), true);

                assertEquals(2, written.outputs().size(), "harness precondition: two partitions written");
                assertEquals(4, written.lineage().size(),
                        "harness precondition: 2 members x 2 partitions of lineage detail");

                BatchProcessor.finalizeSource(batch, cfg, survivors, written.outputs(), written.lineage());
            } finally {
                DuckDbUtil.deleteTempDb(db);
            }

            List<ConsignmentOutput> rows = store.outputs(batch.batchId());

            assertEquals(2, rows.size(), "one registry row per output file, across all partitions (§5.3)");
            assertEquals(4L, rows.stream().mapToLong(ConsignmentOutput::rows).sum(),
                    "§7.2 reconciliation: summed row_count must equal the rows written");
            for (ConsignmentOutput o : rows) {
                assertEquals(2L, o.rows(), "each partition took one row from each of the two members");
                assertEquals(batch.batchId(), o.consignmentId());
                assertEquals(ConsignmentOutput.State.LIVE, o.state());
                assertEquals(0, o.generation());
                assertTrue(o.bytes() > 0, "bytes come from the revealed file on disk: " + o.path());
                assertTrue(Files.exists(Path.of(o.path())), "the registered path must be the revealed file");
                assertNotNull(o.writtenAt());
                assertEquals(com.gamma.util.CanonicalHash.sha256(cfg.schemas().single()),
                        o.schemaFingerprint(),
                        "§3.4.3: every row pins the fingerprint of the schema that wrote it");
            }
            assertEquals(List.of("2026-07-01", "2026-07-02"),
                    rows.stream().map(ConsignmentOutput::recordDay).sorted().toList(),
                    "record_day is derived from the partition key (a write-time approximation, per §10.1)");
        }
    }

    /** The same four rows, plus the {@code __event_time} column {@code DataTransformer} materialises (§3.1):
     *  two event times per record-day so each file's bounds have a real, non-zero spread. */
    private Connection openWithEventTime(File db) throws Exception {
        DuckDbUtil.loadDriver();
        Connection conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE transformed AS SELECT * FROM (VALUES
                      ('alice', 250.0, '2026', '07', '01', 1, TIMESTAMP '2026-07-01 00:00:00'),
                      ('bob',    50.0, '2026', '07', '01', 2, TIMESTAMP '2026-07-01 06:00:00'),
                      ('carol', 999.0, '2026', '07', '02', 1, TIMESTAMP '2026-07-02 12:00:00'),
                      ('dave',  100.0, '2026', '07', '02', 2, TIMESTAMP '2026-07-02 12:00:01')
                    ) v(name, cost, year, month, day, __src_id, __event_time)""");
        }
        return conn;
    }

    /**
     * §3.1 — every registry row carries the event-time range of its own file and the pipeline that produced it,
     * and {@code __event_time} never reaches the written output.
     */
    @Test
    void registersEventTimeBoundsAndProducer(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Batch.Member> survivors = List.of(member(cfg, solo.toFile(), 0));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, survivors);

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);

            File db = DuckDbUtil.tempDbFile("cor_bounds_");
            try (Connection conn = openWithEventTime(db)) {
                BatchIngestStrategy.Written written = BatchIngestStrategy.writeAndTrace(
                        conn, "transformed", List.of("year", "month", "day"), cfg,
                        cfg.dirs().database(), "b1", batch.batchId(), Map.of(1, "a.csv", 2, "b.csv"), true);
                assertEquals(2, written.bounds().size(), "bounds are keyed by output file, one per written file");
                BatchProcessor.finalizeSource(batch, cfg, survivors, written.outputs(), written.lineage(),
                        written.bounds());
            } finally {
                DuckDbUtil.deleteTempDb(db);
            }

            List<ConsignmentOutput> rows = store.outputs(batch.batchId()).stream()
                    .sorted(java.util.Comparator.comparing(ConsignmentOutput::recordDay)).toList();
            assertEquals(2, rows.size());

            ConsignmentOutput first = rows.get(0), second = rows.get(1);
            assertEquals("2026-07-01T00:00:00", first.bounds().min());
            assertEquals("2026-07-01T06:00:00", first.bounds().max());
            assertEquals(6 * 3600 * 1000L, first.bounds().spreadMs(), "six hours, in milliseconds");
            assertEquals("2026-07-02T12:00:00", second.bounds().min());
            assertEquals("2026-07-02T12:00:01", second.bounds().max());
            assertEquals(1000L, second.bounds().spreadMs());

            for (ConsignmentOutput o : rows)
                assertEquals(cfg.identity().pipelineName(), o.producer(),
                        "§3.6 needs to know which producer advanced this file's watermark");

            // The output schema must be untouched: __event_time exists to be measured, never to be written.
            DuckDbUtil.loadDriver();
            try (Connection probe = java.sql.DriverManager.getConnection("jdbc:duckdb:");
                 Statement st = probe.createStatement();
                 // Bare path, not read_parquet: this pipeline's sink writes CSV, and DuckDB infers the
                 // reader from the extension either way.
                 var rs = st.executeQuery("SELECT * FROM '"
                         + first.path().replace('\\', '/') + "' LIMIT 0")) {
                var md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++)
                    assertNotEquals("__event_time", md.getColumnName(i),
                            "the internal event-time column must be excluded from written output");
            }
        }
    }

    /**
     * Decision D3 — a write path that materialises no event time still registers its files, with null bounds.
     * Null must read as <em>unknown</em>: the producer is still recorded, so the row is not simply "empty".
     */
    @Test
    void withoutEventTimeBoundsAreNullAndNothingBreaks(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Batch.Member> survivors = List.of(member(cfg, solo.toFile(), 0));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0001", "mini", null, survivors);

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(store);

            File db = DuckDbUtil.tempDbFile("cor_nobounds_");
            try (Connection conn = openWithTwoPartitions(db)) {   // no __event_time column at all
                BatchIngestStrategy.Written written = BatchIngestStrategy.writeAndTrace(
                        conn, "transformed", List.of("year", "month", "day"), cfg,
                        cfg.dirs().database(), "b1", batch.batchId(), Map.of(1, "a.csv", 2, "b.csv"), true);
                assertTrue(written.bounds().isEmpty(), "no event-time column ⇒ no bounds, and no failure");
                BatchProcessor.finalizeSource(batch, cfg, survivors, written.outputs(), written.lineage(),
                        written.bounds());
            } finally {
                DuckDbUtil.deleteTempDb(db);
            }

            List<ConsignmentOutput> rows = store.outputs(batch.batchId());
            assertEquals(2, rows.size(), "the files are still registered — addressing degrades, it does not break");
            for (ConsignmentOutput o : rows) {
                assertNull(o.bounds(), "unknown, not empty");
                assertEquals(cfg.identity().pipelineName(), o.producer());
            }
        }
    }

    /**
     * Default-off must stay a true no-op: with no registry registered for the space, the Consignment still
     * commits. The manifest — not this table — is authoritative for a file's existence, which is what makes
     * recording fail-open safe.
     */
    @Test
    void commitsNormallyWhenNoRegistryIsRegistered(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path solo = inbox.resolve("solo.csv");
        Files.writeString(solo, "ID,AMT,EVENT_DATE\nx,9.0,2020-04-03\n");
        List<Batch.Member> survivors = List.of(member(cfg, solo.toFile(), 0));
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_mini_0002", "mini", null, survivors);

        assertNull(ConsignmentOutputStores.shared(), "precondition: no registry for the default space");

        File db = DuckDbUtil.tempDbFile("cor_off_");
        try (Connection conn = openWithTwoPartitions(db)) {
            BatchIngestStrategy.Written written = BatchIngestStrategy.writeAndTrace(
                    conn, "transformed", List.of("year", "month", "day"), cfg,
                    cfg.dirs().database(), "b2", batch.batchId(), Map.of(1, "a.csv", 2, "b.csv"), true);
            assertDoesNotThrow(() -> BatchProcessor.finalizeSource(
                    batch, cfg, survivors, written.outputs(), written.lineage()));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
