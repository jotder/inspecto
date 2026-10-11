package com.gamma.exchange;

import com.gamma.exchange.ExchangeSnapshots.SnapshotMeta;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXCHANGE-SNAPSHOT-MULTIPOD-1: two JVMs sharing a store may publish the same due snapshot at once. Neither
 * may corrupt the other — distinct version dirs/temp files, a prune that spares a peer's in-flight version,
 * and a {@code last-refresh.toon} an older attempt's failure cannot overwrite.
 */
class ExchangeSnapshotMultiPodTest {

    private static final long T = 1_760_000_000_000L;

    private static String unix(Path p) {
        return p.toString().replace('\\', '/');
    }

    @Test
    void twoPublishersInTheSameMillisecondBothSucceed(@TempDir Path spacesRoot) throws Exception {
        Path registry = spacesRoot.resolve("finance").resolve("config").resolve("registry");
        Path dataRoot = spacesRoot.resolve("finance").resolve("data");
        Files.createDirectories(dataRoot.resolve("tax_receipts"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT * FROM range(50000) t(amount)) TO "
                    + "'" + unix(dataRoot.resolve("tax_receipts").resolve("part.parquet")) + "' (FORMAT PARQUET)");
        }
        new ComponentStore(registry).write("dataset", "tax_receipts", Map.of("physicalRef", "tax_receipts"));
        Path shared = Exchange.under(spacesRoot).dir();
        Path views = spacesRoot.resolve("finance").resolve("views");

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<SnapshotMeta>> runs = List.of(
                    pool.submit(() -> { go.await(); return ExchangeSnapshotWriter.publish(shared, "finance", registry, dataRoot, views, "tax_receipts", T); }),
                    pool.submit(() -> { go.await(); return ExchangeSnapshotWriter.publish(shared, "finance", registry, dataRoot, views, "tax_receipts", T); }));
            go.countDown();
            SnapshotMeta a = runs.get(0).get();
            SnapshotMeta b = runs.get(1).get();
            assertNotEquals(a.version(), b.version(), "same-millisecond publishes get distinct versions");
            assertEquals(50000, a.rows());
            assertEquals(50000, b.rows());

            Path itemDir = ExchangeSnapshots.itemDir(shared, "finance", "tax_receipts");
            SnapshotMeta live = ExchangeSnapshots.readCurrent(itemDir).orElseThrow();
            assertTrue(live.version().equals(a.version()) || live.version().equals(b.version()));
            assertTrue(Files.exists(itemDir.resolve(live.version()).resolve("snapshot.parquet")),
                    "current.toon names a complete version");
            assertTrue(Files.exists(itemDir.resolve(a.version()).resolve("snapshot.parquet")));
            assertTrue(Files.exists(itemDir.resolve(b.version()).resolve("snapshot.parquet")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pruneSparesYoungVersionsAndKeepsLegacyIdsOrdered(@TempDir Path itemDir) throws Exception {
        long hour = 3_600_000L;
        String oldLegacy = "v" + (T - 5 * hour);          // pre-fix id shape
        String olderNew = "v" + (T - 4 * hour) + "-aaaaaaaa";
        String live = "v" + (T - 3 * hour);                 // legacy-shaped live version
        String peerInFlight = "v" + (T - 60_000) + "-bbbbbbbb"; // a peer's version, still being written
        for (String v : List.of(oldLegacy, olderNew, live, peerInFlight)) Files.createDirectories(itemDir.resolve(v));
        Files.writeString(itemDir.resolve(peerInFlight).resolve("snapshot-x.parquet.tmp"), "partial");

        ExchangeSnapshots.prune(itemDir, live, 0, T, java.util.Set.of());

        assertTrue(Files.exists(itemDir.resolve(live)), "the live version is never pruned");
        assertTrue(Files.exists(itemDir.resolve(peerInFlight)), "a version younger than the grace period survives");
        assertFalse(Files.exists(itemDir.resolve(oldLegacy)), "old legacy-id versions are still pruned");
        assertFalse(Files.exists(itemDir.resolve(olderNew)));

        assertTrue(ExchangeSnapshots.compareVersions(olderNew, oldLegacy) > 0);
        assertTrue(ExchangeSnapshots.compareVersions(live, olderNew) > 0, "legacy and suffixed ids order by time");
    }

    @Test
    void pruneNeverDeletesAPinnedVersion(@TempDir Path spacesRoot) throws Exception {
        Exchange ex = Exchange.under(spacesRoot);
        ex.putOffer(new Offer("dataset", "tax_receipts", "finance", "FY26", Map.of(), "a.rao", 1L));
        ShareGrant g = ex.request("dataset", "tax_receipts", "finance", "audit", "r.gupta", "audit", null);
        String pinned = "v" + (T - 10 * 3_600_000L);
        String stale = "v" + (T - 9 * 3_600_000L);
        String live = "v" + (T - 8 * 3_600_000L) + "-aaaaaaaa";
        ex.setPin(g.id(), pinned);
        Path itemDir = ExchangeSnapshots.itemDir(ex.dir(), "finance", "tax_receipts");
        for (String v : List.of(pinned, stale, live)) Files.createDirectories(itemDir.resolve(v));

        ExchangeSnapshots.prune(itemDir, live, 0, T,
                ExchangeSnapshotWriter.pinnedVersions(ex.dir(), "finance", "tax_receipts"));

        assertTrue(Files.exists(itemDir.resolve(pinned)), "a pinned consumer's version survives");
        assertTrue(Files.exists(itemDir.resolve(live)));
        assertFalse(Files.exists(itemDir.resolve(stale)));
    }

    @Test
    void anOlderAttemptsFailureCannotOverwriteANewerSuccess(@TempDir Path itemDir) {
        SnapshotMeta meta = new SnapshotMeta("v" + (T + 5) + "-cccccccc", 1, "x", List.of());
        ExchangeSnapshotSweep.recordOutcome(itemDir, "schedule", meta, null, T + 5);
        ExchangeSnapshotSweep.recordOutcome(itemDir, "schedule", null, new IllegalStateException("boom"), T);

        Map<String, Object> last = ExchangeSnapshots.readLastRefresh(itemDir).orElseThrow();
        assertEquals("published", last.get("status"));
        assertEquals(meta.version(), last.get("version"));

        // a genuinely later failure still records
        ExchangeSnapshotSweep.recordOutcome(itemDir, "schedule", null, new IllegalStateException("late"), T + 9);
        assertEquals("failed", ExchangeSnapshots.readLastRefresh(itemDir).orElseThrow().get("status"));
    }

    @Test
    void anOlderVersionNeverMovesCurrentBackwards(@TempDir Path itemDir) throws Exception {
        SnapshotMeta newer = new SnapshotMeta("v" + (T + 1) + "-dddddddd", 2, "n", List.of());
        SnapshotMeta older = new SnapshotMeta("v" + T + "-eeeeeeee", 1, "o", List.of());
        Files.createDirectories(itemDir);
        ExchangeSnapshots.writeCurrent(itemDir, newer);
        ExchangeSnapshots.writeCurrent(itemDir, older);
        assertEquals(newer.version(), ExchangeSnapshots.readCurrent(itemDir).orElseThrow().version());
    }
}
