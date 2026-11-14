package com.gamma.expectation;

import com.gamma.alert.ScoreOutputDirs;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.job.SnapshotSwapLock;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNAPSHOT-SWAP-UNLOCKED-READERS-1: an Expectation's count pins the target's files and scans them afterwards; a snapshot
 * writer's swap in between renamed the pinned file away and the count failed with "No files found". The evaluation now
 * holds the target from pin to scan, so the publish waits for it.
 */
class ExpectationEvaluatorSwapLockTest {

    private DbConsignmentOutputStore registry;

    @BeforeEach
    void registryOn() throws Exception {
        DuckDbUtil.loadDriver();
        registry = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(registry);   // as in production: the count pins an explicit file list
    }

    @AfterEach
    void reset() throws Exception {
        ExpectationEvaluator.afterPin = () -> { };
        ConsignmentOutputStores.use(null);
        registry.close();
    }

    /** What every snapshot writer does: write the new file invisibly, hide the old, reveal the new. */
    private static void publish(Path store, int gen, String values) throws Exception {
        Files.createDirectories(store);
        Path tmp = store.resolve("snap-" + gen + ".parquet.tmp");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") t(gen)) TO '" + tmp.toString().replace('\\', '/')
                    + "' (FORMAT PARQUET)");
        }
        ScoreOutputDirs.swapIn(store, tmp, "snap-" + gen + ".parquet", "*.parquet");
    }

    @Test
    void anExpectationHoldsItsTargetFromThePinUntilTheCountHasScannedIt(@TempDir Path data) throws Exception {
        Path orders = data.resolve("orders");
        publish(orders, 1, "(1), (NULL)");   // one violation of non_null
        AtomicReference<Throwable> writerFailed = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { publish(orders, 2, "(2)"); } catch (Throwable t) { writerFailed.set(t); }
        });
        ExpectationEvaluator.afterPin = () -> {
            writer.start();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (writer.getState() != Thread.State.TERMINATED && !SnapshotSwapLock.hasQueuedThreads(orders)) {
                if (System.nanoTime() > deadline) fail("the writer neither finished nor waited for the count");
                try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }
        };

        ExpectationEvaluator.Result r = ExpectationEvaluator.evaluate(
                Expectation.fromMap(Map.of("name", "e", "target", "orders", "column", "gen", "kind", "non_null")), data);
        writer.join(30_000);
        ExpectationEvaluator.afterPin = () -> { };

        assertEquals(1, r.violations(), "the count read the snapshot it pinned");
        assertNull(writerFailed.get(), "the publish landed once the count was done: " + writerFailed.get());
        assertEquals(0, ExpectationEvaluator.evaluate(
                Expectation.fromMap(Map.of("name", "e", "target", "orders", "column", "gen", "kind", "non_null")), data)
                .violations(), "and the next count sees the published snapshot");
    }
}
