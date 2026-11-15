package com.gamma.enrich;

import com.gamma.etl.PipelineConfig;
import com.gamma.job.SnapshotSwapLock;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNAPSHOT-SWAP-READER-RESIDUALS-1: an enrichment reads a by-name Reference Dataset by GLOB, never through a pinned file
 * list, so its read held no lock: a reference compaction hiding the store's files while the transform bound the glob
 * failed the run ("No files found") or read part of the store. The run now holds the reference store from its view until
 * the transform has scanned it, so the compaction's hide and reveal wait for it.
 */
class EnrichmentSwapLockTest {

    @AfterEach
    void reset() {
        EnrichmentEngine.afterPin = () -> { };
    }

    private static void write(Path file, String select) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("enrich_swap_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (" + select + ") TO '" + file.toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static Map<String, String> regions(Path outRoot) throws Exception {
        Map<String, String> m = new HashMap<>();
        File db = DuckDbUtil.tempDbFile("enrich_swap_verify_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT customer_id, region FROM read_parquet('" + outRoot.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0)")) {
            while (rs.next()) m.put(rs.getString("customer_id"), rs.getString("region"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return m;
    }

    @Test
    void anEnrichmentHoldsItsReferenceStoreUntilTheTransformHasScannedIt(@TempDir Path dir) throws Exception {
        Path refdb = dir.resolve("refdb");
        assertEquals(Map.of("C1", "NA", "C2", "EU"), enrichDuringACompactionOf(dir, refdb,
                refdb.resolve("dim_1_out.parquet"), "SELECT * FROM (VALUES ('C1', 'APAC'), ('C2', 'EU')) t(customer_id, region)"),
                "the enrichment read the reference store whole, before the compaction");
    }

    @Test
    void anEnrichmentHoldsItsStageOneInputUntilTheTransformHasScannedIt(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in");
        assertEquals(Map.of("C1", "NA", "C2", "EU"), enrichDuringACompactionOf(dir, in,
                in.resolve("p=x").resolve("part.parquet"), "SELECT 9 AS n"),
                "the enrichment read the input whole, before the compaction");
    }

    /**
     * Runs an enrichment whose transform reads its Stage-1 input and a by-name reference, while a compaction of
     * {@code store} (one of the two) replaces {@code old} with {@code replacement}: the compaction starts once the views are
     * registered and stops between its hide and its reveal until the enrichment is done. Returns what the run wrote.
     */
    private static Map<String, String> enrichDuringACompactionOf(Path dir, Path store, Path old, String replacement) throws Exception {
        Path refdb = dir.resolve("refdb");
        write(refdb.resolve("dim_1_out.parquet"), "SELECT * FROM (VALUES ('C1', 'NA'), ('C2', 'EU')) t(customer_id, region)");
        Path in = dir.resolve("in");
        write(in.resolve("p=x").resolve("part.parquet"), "SELECT 1 AS n");
        PipelineConfig producer = PipelineConfig.fromMap(Map.of(
                "name", "CUSTOMER_DIM",
                "produces", "reference",
                "reference", Map.of("load", "replace"),
                "dirs", Map.of("poll", dir.resolve("ref_in").toString(), "database", refdb.toString()),
                "output", Map.of("format", "PARQUET"),
                "processing", Map.of("threads", 1)));
        Path out = dir.resolve("out");
        EnrichmentConfig cfg = new EnrichmentConfig("CUSTOMER_REGIONS",
                new EnrichmentConfig.Input(in.toString().replace("\\", "/"), "PARQUET", List.of("p")),
                List.of(new EnrichmentConfig.Reference("customer_dim", null, null, "customer_dim")),
                new EnrichmentConfig.Output(out.toString().replace("\\", "/"), "PARQUET", "snappy", List.of("region")),
                "SELECT c.customer_id, c.region FROM customer_dim c, (SELECT max(n) AS n FROM input) i WHERE i.n = 1");

        Path tmp = store.resolve("compacted_2_out.parquet.tmp");
        write(tmp, replacement);
        CountDownLatch hidden = new CountDownLatch(1), reveal = new CountDownLatch(1);
        AtomicReference<Throwable> compactionFailed = new AtomicReference<>();
        Thread compaction = new Thread(() -> {
            try (SnapshotSwapLock.Held swap = SnapshotSwapLock.write(store)) {
                Files.move(old, old.resolveSibling(old.getFileName() + ".stale"), StandardCopyOption.ATOMIC_MOVE);
                hidden.countDown();
                assertTrue(reveal.await(30, TimeUnit.SECONDS));
                Files.move(tmp, old.resolveSibling("compacted_2_out.parquet"), StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable t) {
                compactionFailed.set(t);
            }
        });
        EnrichmentEngine.afterPin = () -> {   // the views are registered; the transform has not scanned them yet
            compaction.start();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (hidden.getCount() > 0 && !SnapshotSwapLock.hasQueuedThreads(store)) {
                if (System.nanoTime() > deadline) fail("the compaction neither hid the files nor waited for the enrichment");
                try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }
        };

        try {
            EnrichmentEngine.runResult(cfg, null, List.of(producer));
        } finally {
            reveal.countDown();
            compaction.join(30_000);
        }
        assertNull(compactionFailed.get(), "the compaction ran once the enrichment had read: " + compactionFailed.get());
        assertTrue(Files.exists(old.resolveSibling("compacted_2_out.parquet")));
        return regions(out);
    }
}
