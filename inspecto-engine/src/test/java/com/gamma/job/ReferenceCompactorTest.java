package com.gamma.job;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference Phase-2 P3 verify: {@link ReferenceCompactor} collapses an append-only versioned Reference
 * store to exactly the rows its read views can still return, bounding read amplification (one file per
 * batch per partition dir → one file), without ever exposing a partial state.
 *
 * <p>The fixture mirrors what the P1/P2 write path lays down: Hive-partitioned leaf dirs
 * ({@code region=<v>}) each accumulating a {@code <base>__v_<batchId>_out.parquet} per batch, every row
 * stamped with the §2.1 system columns.
 */
class ReferenceCompactorTest {

    private static String keyHash(String id) {
        return "md5(concat_ws(chr(31), COALESCE(CAST('" + id + "' AS VARCHAR), '')))";
    }

    private static String rowHash(String id, String tier) {
        return "md5(concat_ws(chr(31), COALESCE(CAST('" + id + "' AS VARCHAR), ''), "
                + "COALESCE(CAST('" + tier + "' AS VARCHAR), '')))";
    }

    /**
     * Append one batch, Hive-partitioned by {@code region} exactly as {@code PartitionWriter} would.
     * Rows are {@code {customer_id, tier, region, __op}}.
     */
    private static void appendBatch(Path store, String batchId, String validFrom,
                                    List<String[]> rows) throws Exception {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (i > 0) values.append(", ");
            values.append("('").append(r[0]).append("', '").append(r[1]).append("', '").append(r[2])
                  .append("', ").append(keyHash(r[0])).append(", ").append(rowHash(r[0], r[1]))
                  .append(", TIMESTAMP '").append(validFrom).append("', '").append(r[3])
                  .append("', '").append(batchId).append("')");
        }
        File db = DuckDbUtil.tempDbFile("refc_seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + values + ") "
                    + "t(customer_id, tier, region, __key_hash, __row_hash, __valid_from, __op, __batch_id)) "
                    + "TO '" + store.toString().replace("\\", "/") + "' (FORMAT PARQUET, "
                    + "PARTITION_BY (region), OVERWRITE_OR_IGNORE 1, FILENAME_PATTERN 'dim__v_"
                    + batchId + "_out')");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Every live {@code *.parquet} in the store (what a reader's glob sees). */
    private static List<Path> liveFiles(Path store) throws Exception {
        try (Stream<Path> w = Files.walk(store)) {
            return w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".parquet"))
                    .toList();
        }
    }

    /** The current view over the store, derived the same way {@code EnrichmentEngine.versionedView} does. */
    private static Map<String, String> currentView(Path store) throws Exception {
        Map<String, String> m = new HashMap<>();
        File db = DuckDbUtil.tempDbFile("refc_read_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT customer_id, tier FROM (SELECT * FROM read_parquet('"
                     + store.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0) "
                     + "QUALIFY row_number() OVER (PARTITION BY __key_hash ORDER BY __valid_from DESC) = 1) "
                     + "WHERE __op != 'delete'")) {
            while (rs.next()) m.put(rs.getString(1), rs.getString(2));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return m;
    }

    private static long rowCount(Path store) throws Exception {
        File db = DuckDbUtil.tempDbFile("refc_count_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('"
                     + store.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0)")) {
            rs.next();
            return rs.getLong(1);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Three batches: C1 changes tier twice, C2 is stable, C3 is tombstoned, C4 arrives late. */
    private static Path seedStore(Path dir) throws Exception {
        Path store = dir.resolve("refdb");
        Files.createDirectories(store);
        appendBatch(store, "b1", "2026-07-24 10:00:00", List.of(
                new String[]{"C1", "silver", "NA", "upsert"},
                new String[]{"C2", "gold", "EU", "upsert"},
                new String[]{"C3", "bronze", "NA", "upsert"}));
        appendBatch(store, "b2", "2026-07-24 11:00:00", List.of(
                new String[]{"C1", "gold", "NA", "upsert"},
                new String[]{"C3", "", "NA", "delete"}));
        appendBatch(store, "b3", "2026-07-24 12:00:00", List.of(
                new String[]{"C1", "platinum", "NA", "upsert"},
                new String[]{"C4", "silver", "SA", "upsert"}));
        return store;
    }

    @Test
    void compactedStoreEqualsTheCurrentViewAndCollapsesFileCount(@TempDir Path dir) throws Exception {
        Path store = seedStore(dir);
        Map<String, String> before = currentView(store);
        assertEquals(Map.of("C1", "platinum", "C2", "gold", "C4", "silver"), before,
                "fixture sanity: latest per key, tombstoned C3 gone");
        assertTrue(liveFiles(store).size() > 3, "one file per batch per partition accumulated");

        ReferenceCompactor.Result r = ReferenceCompactor.compact(store, 0);

        assertEquals(before, currentView(store), "the current view is unchanged by compaction");
        assertEquals(3L, rowCount(store), "compacted store holds EXACTLY the current-view rows");
        assertEquals(3L, r.rowsRetained());
        // One file per partition dir that still has retained rows (NA→C1, EU→C2, SA→C4).
        assertEquals(3, liveFiles(store).size(), "read amplification bounded: one file per partition");
    }

    @Test
    void keepForeverMergesFilesWithoutDroppingAnyVersion(@TempDir Path dir) throws Exception {
        Path store = seedStore(dir);
        long allVersions = rowCount(store);
        Map<String, String> before = currentView(store);

        ReferenceCompactor.Result r = ReferenceCompactor.compact(store, -1);

        assertEquals(allVersions, rowCount(store), "keep-forever drops no version (scd2 as-of stays whole)");
        assertEquals(allVersions, r.rowsRetained());
        assertEquals(before, currentView(store), "current view still derives correctly");
        assertEquals(3, liveFiles(store).size(), "…but the files are merged, one per partition");
    }

    @Test
    void aSecondPassIsANoOp(@TempDir Path dir) throws Exception {
        Path store = seedStore(dir);
        ReferenceCompactor.compact(store, 0);
        List<Path> after1 = liveFiles(store);
        Map<String, String> view1 = currentView(store);

        ReferenceCompactor.Result r2 = ReferenceCompactor.compact(store, 0);

        assertEquals(view1, currentView(store), "idempotent: the view does not move");
        assertEquals(after1.size(), liveFiles(store).size(), "no file growth on a repeat pass");
        assertEquals(3L, r2.rowsRetained());
    }

    /**
     * A key whose <b>partition</b> value changed has its versions in different dirs — the reason the
     * winner must be derived store-wide. A per-dir "keep the latest here" would resurrect the old region.
     */
    @Test
    void versionsSplitAcrossPartitionsKeepOnlyTheStoreWideWinner(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("refdb");
        Files.createDirectories(store);
        appendBatch(store, "b1", "2026-07-24 10:00:00", List.<String[]>of(
                new String[]{"C1", "silver", "NA", "upsert"}));
        appendBatch(store, "b2", "2026-07-24 11:00:00", List.<String[]>of(
                new String[]{"C1", "gold", "EU", "upsert"}));   // same key, DIFFERENT partition

        ReferenceCompactor.compact(store, 0);

        assertEquals(1L, rowCount(store), "the superseded NA version is gone, not resurrected");
        assertEquals(Map.of("C1", "gold"), currentView(store));
        assertEquals(1, liveFiles(store).size(), "the emptied NA partition keeps no file");
    }

    @Test
    void aStoreThatDoesNotExistIsANoOp(@TempDir Path dir) throws Exception {
        ReferenceCompactor.Result r = ReferenceCompactor.compact(dir.resolve("absent"), 0);
        assertEquals(0, r.dirsCompacted());
        assertEquals(0L, r.rowsRetained());
    }

    /** A killed run leaves only glob-invisible debris; the next pass heals it and compacts normally. */
    @Test
    void aCrashedRunIsHealedOnTheNextPass(@TempDir Path dir) throws Exception {
        Path store = seedStore(dir);
        Path na = Files.walk(store).filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().equals("region=NA")).findFirst().orElseThrow();

        // Simulate a crash *before* the reveal: originals hidden, journal written, no target.
        List<String> journal = new ArrayList<>();
        journal.add("compacted_1_out.parquet");
        for (Path p : liveFiles(na)) {
            journal.add(p.getFileName().toString());
            Files.move(p, p.resolveSibling(p.getFileName() + ".refcompacting"));
        }
        Files.write(na.resolve(".refcompact-journal"), journal);
        assertTrue(liveFiles(na).isEmpty(), "the crash left the NA partition invisible to readers");

        ReferenceCompactor.compact(store, 0);

        assertEquals(Map.of("C1", "platinum", "C2", "gold", "C4", "silver"), currentView(store),
                "heal() restored the hidden originals, then compaction ran on the whole store");
        assertFalse(Files.exists(na.resolve(".refcompact-journal")), "journal cleared");
    }

    /** The task is reachable as a {@code maintenance} sub-task, and does nothing on a dry run (MNT-1). */
    @Test
    void reachableAsAMaintenanceTaskAndDryRunSafe(@TempDir Path dir) throws Exception {
        Path store = seedStore(dir);
        int filesBefore = liveFiles(store).size();
        Path audit = Files.createDirectories(dir.resolve("audit"));
        JobConfig cfg = new JobConfig("rc", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "reference_compact", "dir", store.toString()));

        RunContext dryCtx = new RunContext("r-dry", "default", "rc", "manual", "r-dry", 0, Map.of(),
                new RunLogStore(audit.toString()), 100, new RunArtifactStore(audit.toString()));
        dryCtx.dryRun(true);
        JobResult dry = new MaintenanceJob(cfg, dir.toString()).run(dryCtx);
        assertTrue(dry.message().contains("dry-run"), dry.message());
        assertEquals(filesBefore, liveFiles(store).size(), "a dry run touches nothing");

        JobResult real = new MaintenanceJob(cfg, dir.toString()).run();
        assertTrue(real.message().startsWith("reference_compact:"), real.message());
        assertEquals(3, liveFiles(store).size());
    }
}
