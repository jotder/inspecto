package com.gamma.query;

import com.gamma.alert.ScoreOutputDirs;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.job.SnapshotSwapLock;
import com.gamma.pipeline.ComponentStore;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNAPSHOT-SWAP-UNLOCKED-READERS-1, the interactive readers' seam: a Dataset relation pins its store's files when it is
 * rendered (BI, Link Analysis, an Alert Rule's probe all render it before {@link QueryExecutor} scans it), and a
 * snapshot writer's swap in between renames the pinned file away. The executor renders the relation again and runs once
 * more holding the store, rather than every query holding it (a publish would wait for a user's query).
 */
class QueryExecutorSwapRetryTest {

    private DbConsignmentOutputStore registry;

    @BeforeEach
    void registryOn() throws Exception {
        DuckDbUtil.loadDriver();
        registry = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(registry);   // as in production: the relation pins an explicit file list
    }

    @AfterEach
    void reset() throws Exception {
        QueryExecutor.beforeScan = () -> { };
        ConsignmentOutputStores.use(null);
        registry.close();
    }

    /** What every snapshot writer does: write the new file invisibly, hide the old, reveal the new. */
    private static void publish(Path store, int gen) throws Exception {
        Files.createDirectories(store);
        Path tmp = store.resolve("snap-" + gen + ".parquet.tmp");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT " + gen + " AS gen) TO '" + tmp.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        ScoreOutputDirs.swapIn(store, tmp, "snap-" + gen + ".parquet", "*.parquet");
    }

    private static List<Integer> gens(Path store) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT gen FROM read_parquet('" + store.toString().replace('\\', '/')
                     + "/*.parquet') ORDER BY gen")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    private static List<Object> column(QueryExecutor.Result r) {
        return r.rows().stream().map(row -> row.get("gen")).toList();
    }

    private static QueryExecutor.Request read(String relation) {
        return new QueryExecutor.Request("links", relation, "SELECT gen FROM links", 10, 0, List.of(), List.of());
    }

    private static void awaitQueuedOrDone(Path store, Thread writer) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (writer.getState() != Thread.State.TERMINATED && !SnapshotSwapLock.hasQueuedThreads(store)) {
            if (System.nanoTime() > deadline) fail("the writer neither finished nor waited for the reader");
            try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }
    }

    @Test
    void aReadWhosePinnedFileASwapRemovedRendersAgainAndReadsThePublishedSnapshot(@TempDir Path data) throws Exception {
        Path links = data.resolve("links");
        publish(links, 1);
        String relation = DatasetRelation.relationSql(Map.of("physicalRef", "links"), data, null);
        publish(links, 2);   // between the pin and the scan, as a BI request's render and its query

        QueryExecutor.Result r = QueryExecutor.run(read(relation));

        assertEquals(List.of(2), column(r), "the re-pinned read sees the published snapshot, once");
    }

    @Test
    void theSecondAttemptHoldsTheStoreSoTheNextPublishWaitsForIt(@TempDir Path data) throws Exception {
        Path links = data.resolve("links");
        publish(links, 1);
        String relation = DatasetRelation.relationSql(Map.of("physicalRef", "links"), data, null);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Throwable> writerFailed = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { publish(links, 3); } catch (Throwable t) { writerFailed.set(t); }
        });
        QueryExecutor.beforeScan = () -> {
            try {
                if (attempts.incrementAndGet() == 1) publish(links, 2);   // the first attempt loses its pinned file
                else { writer.start(); awaitQueuedOrDone(links, writer); }   // a publish during the second must wait
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        QueryExecutor.Result r = QueryExecutor.run(read(relation));
        writer.join(30_000);

        assertEquals(2, attempts.get());
        assertEquals(List.of(2), column(r), "the second attempt read the snapshot it re-pinned");
        assertNull(writerFailed.get(), "the publish landed once the read was done: " + writerFailed.get());
        assertEquals(List.of(3), gens(links));
    }

    // ── SNAPSHOT-SWAP-READER-RESIDUALS-1 ──────────────────────────────────────────

    @Test
    void aRelationACallerWrappedInMoreSqlRendersTheRelationItEmbedsAgain(@TempDir Path data) throws Exception {
        Path links = data.resolve("links");
        publish(links, 1);
        String relation = DatasetRelation.relationSql(Map.of("physicalRef", "links"), data, null);
        // What Link Analysis does with two relations (a UNION) or one (a filtered sub-select) before the executor.
        String wrapped = "SELECT gen * 10 AS gen FROM (" + relation + ") AS w WHERE gen > 0";
        publish(links, 2);   // the embedded relation's pinned file is gone before the scan

        QueryExecutor.Result r = QueryExecutor.run(read(wrapped));

        assertEquals(List.of(20), column(r), "the wrapper ran again over the embedded relation rendered afresh");
    }

    @Test
    void twoRendersOfAnIdenticalRelationShareTheRegisteredString(@TempDir Path data) throws Exception {
        publish(data.resolve("links"), 1);
        String first = DatasetRelation.relationSql(Map.of("physicalRef", "links"), data, null);
        String second = DatasetRelation.relationSql(Map.of("physicalRef", "links"), data, null);

        assertEquals(first, second);
        // The registry holds its key weakly: an equal copy of its own would leave the second caller without a retry once
        // the first dropped the registered string.
        assertSame(first, second, "the second render hands back the registered string, so the entry lives while either holds it");
    }

    @Test
    void anAlertRuleMeasureReadsThePublishedSnapshotWhenASwapLandsBeforeItsScan(@TempDir Path writeRoot, @TempDir Path data)
            throws Exception {
        Path links = data.resolve("links");
        publish(links, 1);
        new ComponentStore(writeRoot.resolve("registry")).write("dataset", "links", Map.of("physicalRef", "links"));
        AtomicInteger attempts = new AtomicInteger();
        QueryExecutor.beforeScan = () -> {
            try {
                if (attempts.incrementAndGet() == 1) publish(links, 5);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        OptionalDouble v = new DatasetMeasureProbe(() -> writeRoot, () -> data).value("links", "sum(gen)");

        assertEquals(OptionalDouble.of(5.0), v, "the probe's own read retried, not an empty measure");
    }
}
