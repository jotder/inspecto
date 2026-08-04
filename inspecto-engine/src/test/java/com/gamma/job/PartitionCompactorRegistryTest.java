package com.gamma.job;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * §11.3's compaction state transition: {@link PartitionCompactor} must flip the registry rows of the files it
 * merges away to {@code COMPACTED_AWAY}, which is what lets {@code ReprocessCommand} refuse instead of silently
 * duplicating rows (the bug the compactor's own javadoc documents).
 *
 * <p>Registry-focused — the merge mechanics themselves are the compactor's existing contract.
 */
class PartitionCompactorRegistryTest {

    @AfterEach
    void clearRegistry() {
        ConsignmentOutputStores.use(null);
    }

    /** An aged Parquet file, old enough to be a compaction candidate. */
    private static Path aged(Path dir, String name, int rows) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        File db = DuckDbUtil.tempDbFile("compact_seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT i AS id FROM range(" + rows + ") t(i)) TO '"
                    + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        // Explicit mtime rather than relying on min_age_days=0 racing the clock.
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));
        return file;
    }

    private static ConsignmentOutput out(String consignment, Path path) {
        return new ConsignmentOutput(consignment, null, "cdr", "dt=2026-08-04", "2026-08-04",
                path.toString(), 1L, 100L, "2026-08-04T10:00:00Z", 0, State.LIVE);
    }

    private static JobConfig compactCfg(Path root) {
        return new JobConfig("compact_job", "compact", null, null, true, false,
                Map.of("dir", root.toString(), "min_age_days", "1", "min_files", "2"), null, null);
    }

    @Test
    void marksTheMergedFilesCompactedAwayAndLeavesTheRestLive(@TempDir Path root) throws Exception {
        Path part = root.resolve("dt=2026-08-04");
        Path a = aged(part, "c1_out.parquet", 3);
        Path b = aged(part, "c2_out.parquet", 4);

        Path otherPart = root.resolve("dt=2026-08-05");
        Path c = aged(otherPart, "c3_out.parquet", 5);   // alone in its dir ⇒ below min_files, not compacted

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out("c1", a), out("c2", b), out("c3", c)));
            ConsignmentOutputStores.use(store);

            JobResult result = PartitionCompactor.run(compactCfg(root));
            assertTrue(result.success(), result.message());

            assertEquals(State.COMPACTED_AWAY, store.outputs("c1").get(0).state(),
                    "a merged-away file must not stay LIVE — reprocess would unlink a path that is gone");
            assertEquals(State.COMPACTED_AWAY, store.outputs("c2").get(0).state());
            assertEquals(State.LIVE, store.outputs("c3").get(0).state(),
                    "a partition below min_files was never touched, so its row must not move");
        }
    }

    /** The flip is keyed by path precisely because one merged file absorbs several Consignments. */
    @Test
    void aMergedFileSpanningConsignmentsFlipsBoth(@TempDir Path root) throws Exception {
        Path part = root.resolve("dt=2026-08-04");
        Path a = aged(part, "c1_out.parquet", 2);
        Path b = aged(part, "c2_out.parquet", 2);

        try (DbConsignmentOutputStore store = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            store.record(List.of(out("c1", a), out("c2", b)));
            ConsignmentOutputStores.use(store);

            PartitionCompactor.run(compactCfg(root));

            assertTrue(Files.notExists(a), "precondition: the original really was merged away");
            assertEquals(State.COMPACTED_AWAY, store.outputs("c1").get(0).state());
            assertEquals(State.COMPACTED_AWAY, store.outputs("c2").get(0).state());
        }
    }

    /** Default-off: compaction must behave exactly as before when no registry is installed. */
    @Test
    void compactsNormallyWithNoRegistryInstalled(@TempDir Path root) throws Exception {
        Path part = root.resolve("dt=2026-08-04");
        aged(part, "c1_out.parquet", 3);
        aged(part, "c2_out.parquet", 4);
        assertNull(ConsignmentOutputStores.shared(), "precondition: registry absent");

        JobResult result = PartitionCompactor.run(compactCfg(root));
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("merged 2 file(s)"), result.message());
    }
}
