package com.gamma.job;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A data-root walk never touches a live Run's snapshot links ({@code .run-snapshots/}, com.gamma.query.RunSnapshot) or
 * DuckDB spill ({@code .duckdb_tmp/}): {@code cleanup} would delete files a Run still reads, the object-store export
 * would upload copies of store files, and the storage report would count them twice.
 */
class DataRootInternalDirsTest {

    private static Path file(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(Duration.ofDays(10))));
        return p;
    }

    @Test
    void cleanupNeverDeletesARunsLinksOrSpill(@TempDir Path root) throws Exception {
        Path store = file(root.resolve("cdr").resolve("old.parquet"));
        Path link = file(root.resolve(DuckDbUtil.RUN_SNAPSHOTS_DIR_NAME).resolve("run-1").resolve("0").resolve("cdr").resolve("old.parquet"));
        Path spill = file(root.resolve(DuckDbUtil.SPILL_DIR_NAME).resolve("spill.parquet"));
        JobConfig cfg = new JobConfig("m", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "cleanup", "dir", root.toString(), "retention_days", "1", "glob", "*.parquet"));

        JobResult r = new MaintenanceJob(cfg).run();

        assertEquals("SUCCESS", r.status(), r.message());
        assertFalse(Files.exists(store), "precondition: the policy does delete an old store file");
        assertTrue(Files.exists(link), "a live Run's link is not the cleanup's to delete");
        assertTrue(Files.exists(spill), "nor is DuckDB spill");
    }

    @Test
    void theObjectStoreExportPlansNeitherLinksNorSpill(@TempDir Path root) throws Exception {
        file(root.resolve("cdr").resolve("a.parquet"));
        file(root.resolve(DuckDbUtil.RUN_SNAPSHOTS_DIR_NAME).resolve("run-1").resolve("0").resolve("cdr").resolve("a.parquet"));
        file(root.resolve(DuckDbUtil.SPILL_DIR_NAME).resolve("spill.parquet"));

        List<String> keys = ObjectStoreExportJobType.plan(root, root, "").stream().map(ObjectStoreExportJobType.Planned::key).toList();

        assertEquals(List.of("cdr/a.parquet"), keys);
    }

    @Test
    void theFileRepositoryAuditNeverWalksIntoARunsLinksOrSpill(@TempDir Path root) throws Exception {
        file(root.resolve("cdr").resolve("b0_out.parquet.tmp"));   // a real stale partial
        file(root.resolve(DuckDbUtil.RUN_SNAPSHOTS_DIR_NAME).resolve("run-1").resolve("0").resolve("cdr").resolve("x.parquet.tmp"));
        file(root.resolve(DuckDbUtil.SPILL_DIR_NAME).resolve("spill.tmp"));
        JobConfig cfg = new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "file_repository_audit"));

        JobResult r = new MaintenanceJob(cfg, root.toString()).run();

        assertTrue(r.message().contains("1 finding(s)"), "only the store's partial: " + r.message());
    }

    @Test
    void theDataRootWalkOfBackupAndStorageReportSkipsBothFolders(@TempDir Path root) throws Exception {
        Path store = file(root.resolve("cdr").resolve("a.parquet"));
        file(root.resolve(DuckDbUtil.RUN_SNAPSHOTS_DIR_NAME).resolve("run-1").resolve("0").resolve("cdr").resolve("a.parquet"));
        file(root.resolve(DuckDbUtil.SPILL_DIR_NAME).resolve("spill.parquet"));

        assertEquals(List.of(store), DuckDbUtil.regularFilesSkippingSpill(root));
    }
}
