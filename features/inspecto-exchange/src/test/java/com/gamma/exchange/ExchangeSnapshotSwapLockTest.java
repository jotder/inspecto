package com.gamma.exchange;

import com.gamma.alert.ScoreOutputDirs;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.exchange.ExchangeSnapshots.SnapshotMeta;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SNAPSHOT-SWAP-READER-RESIDUALS-1: an Exchange publish renders the owner's Dataset relation, which pins the store's
 * files, and its COPY scans them afterwards. A snapshot swap of the owner's store in between renamed the pinned file away
 * and the publish failed with "No files found"; the publish now holds the store until its COPY is done.
 */
class ExchangeSnapshotSwapLockTest {

    private DbConsignmentOutputStore registry;

    @BeforeEach
    void registryOn() throws Exception {
        DuckDbUtil.loadDriver();
        registry = DbConsignmentOutputStore.open("jdbc:duckdb:");
        ConsignmentOutputStores.use(registry);   // as in production: the relation pins an explicit file list
    }

    @AfterEach
    void reset() throws Exception {
        ExchangeSnapshotWriter.afterPin = () -> { };
        ConsignmentOutputStores.use(null);
        registry.close();
    }

    private static String unix(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** What every snapshot writer does: write the new file invisibly, hide the old, reveal the new. */
    private static void publish(Path store, int gen) throws Exception {
        Files.createDirectories(store);
        Path tmp = store.resolve("snap-" + gen + ".parquet.tmp");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT " + gen + " AS gen) TO '" + unix(tmp) + "' (FORMAT PARQUET)");
        }
        ScoreOutputDirs.swapIn(store, tmp, "snap-" + gen + ".parquet", "*.parquet");
    }

    private static List<Integer> gens(String glob) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT gen FROM read_parquet('" + glob + "') ORDER BY gen")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    @Test
    void aPublishHoldsTheOwnersStoreFromThePinUntilItsCopyHasScannedIt(@TempDir Path spacesRoot) throws Exception {
        Path registryDir = spacesRoot.resolve("finance").resolve("config").resolve("registry");
        Path dataRoot = spacesRoot.resolve("finance").resolve("data");
        Path links = dataRoot.resolve("links");
        publish(links, 1);
        new ComponentStore(registryDir).write("dataset", "links", Map.of("physicalRef", "links"));
        Path shared = Exchange.under(spacesRoot).dir();
        AtomicReference<Throwable> writerFailed = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { publish(links, 2); } catch (Throwable t) { writerFailed.set(t); }
        });
        ExchangeSnapshotWriter.afterPin = () -> {
            writer.start();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (writer.getState() != Thread.State.TERMINATED && !SnapshotSwapLock.hasQueuedThreads(links)) {
                if (System.nanoTime() > deadline) fail("the writer neither finished nor waited for the publish");
                try { Thread.sleep(5); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            }
        };

        SnapshotMeta meta = ExchangeSnapshotWriter.publish(shared, "finance", registryDir, dataRoot,
                spacesRoot.resolve("finance").resolve("views"), "links");
        writer.join(30_000);

        Path snapshot = ExchangeSnapshots.itemDir(shared, "finance", "links").resolve(meta.version()).resolve("snapshot.parquet");
        assertEquals(List.of(1), gens(unix(snapshot)), "the Exchange snapshot is the owner snapshot it pinned");
        assertNull(writerFailed.get(), "the swap landed once the publish was done: " + writerFailed.get());
        assertEquals(List.of(2), gens(unix(links) + "/*.parquet"));
    }
}
