package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.config.safety.DiscoveredRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The migrate command: a flat working-directory layout relocates into spaces/&lt;id&gt;/ that discovers cleanly. */
class SpaceMigratorTest {

    @Test
    void migratesAFlatTreeIntoADiscoverableSpace(@TempDir Path tmp) throws Exception {
        // ── a flat deployment: a config tree + the working-dir runtime artifacts ──
        Path workingDir = Files.createDirectories(tmp.resolve("flat"));
        Path flatConfig = Files.createDirectories(workingDir.resolve("config"));
        Path pipe = TestConfigs.csv(flatConfig, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(pipe, flatConfig.resolve("etl_pipeline.toon"));   // a *_pipeline.toon discover() will see
        Files.createDirectories(workingDir.resolve("database"));     // flat data
        Files.createDirectories(workingDir.resolve("jobs_audit"));   // flat audit
        Files.writeString(workingDir.resolve("inspecto-status.db"), "stub");   // a flat duckdb file

        Path spacesRoot = tmp.resolve("spaces");
        List<SpaceMigrator.Step> applied = SpaceMigrator.migrate(workingDir, flatConfig, spacesRoot, "default", false);
        assertFalse(applied.isEmpty(), "steps were applied");

        // ── the per-space layout is in place; the flat sources are gone ──
        Path base = spacesRoot.resolve("default");
        assertTrue(Files.exists(base.resolve("config").resolve("etl_pipeline.toon")), "config tree relocated");
        assertTrue(Files.isDirectory(base.resolve("data")), "database → data");
        assertTrue(Files.isDirectory(base.resolve("audit")), "jobs_audit → audit");
        assertTrue(Files.exists(base.resolve("duckdb").resolve("inspecto-status.db")), "db file → duckdb/");
        assertTrue(Files.exists(base.resolve("space.toon")), "manifest written");
        assertFalse(Files.exists(flatConfig), "the flat config was moved, not copied");
        assertFalse(Files.exists(workingDir.resolve("database")), "the flat data dir was moved");

        // ── it boots: discover finds exactly the one migrated space ──
        try (SpaceManager mgr = SpaceManager.discover(spacesRoot)) {
            assertEquals(1, mgr.size());
            assertTrue(mgr.space(SpaceId.of("default")).isPresent());
        }

        // ── idempotent: re-running relocates nothing (sources already gone) ──
        assertTrue(SpaceMigrator.migrate(workingDir, flatConfig, spacesRoot, "default", false).isEmpty(),
                "a second migrate is a clean no-op");
    }

    @Test
    void dryRunPlansButMovesNothing(@TempDir Path tmp) throws Exception {
        Path workingDir = Files.createDirectories(tmp.resolve("flat"));
        Path flatConfig = Files.createDirectories(workingDir.resolve("config"));
        Files.writeString(flatConfig.resolve("x_pipeline.toon"), "name: X\n");
        Files.createDirectories(workingDir.resolve("database"));

        Path spacesRoot = tmp.resolve("spaces");
        List<SpaceMigrator.Step> plan = SpaceMigrator.migrate(workingDir, flatConfig, spacesRoot, "default", true);

        assertFalse(plan.isEmpty(), "a dry run still reports the plan");
        assertFalse(Files.exists(spacesRoot.resolve("default")), "dry run created nothing");
        assertTrue(Files.exists(flatConfig), "dry run moved nothing");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SpaceRoot.DATA_ROOT_PROPERTY);
        DiscoveredRoots.clear();
    }

    /** A flat deployment with an empty config tree, a data dir and one DuckDB file. */
    private static Path flat(Path tmp) throws Exception {
        Path workingDir = Files.createDirectories(tmp.resolve("flat"));
        Files.createDirectories(workingDir.resolve("config"));
        Files.createDirectories(workingDir.resolve("database"));
        Files.writeString(workingDir.resolve("inspecto-status.db"), "stub");
        return workingDir;
    }

    @Test
    void migratesIntoTheFolderASpaceReferencePointsAt(@TempDir Path tmp) throws Exception {
        Path workingDir = flat(tmp);
        Path spacesRoot = Files.createDirectories(tmp.resolve("spaces"));
        Path ext = Files.createDirectories(tmp.resolve("elsewhere").resolve("default"));
        Files.writeString(spacesRoot.resolve("default.ref"), ext.toString());

        SpaceMigrator.migrate(workingDir, workingDir.resolve("config"), spacesRoot, "default", false);

        assertFalse(Files.exists(spacesRoot.resolve("default")), "no folder shadows the reference");
        assertTrue(Files.isDirectory(ext.resolve("config")));
        assertTrue(Files.exists(ext.resolve("duckdb").resolve("inspecto-status.db")));
        try (SpaceManager mgr = SpaceManager.discover(spacesRoot)) {
            assertEquals(ext.toAbsolutePath().normalize(), mgr.space(SpaceId.of("default")).orElseThrow().root().base());
        }
    }

    @Test
    void anUnfollowableReferenceIsRefusedBeforeAnythingMoves(@TempDir Path tmp) throws Exception {
        Path workingDir = flat(tmp);
        Path spacesRoot = Files.createDirectories(tmp.resolve("spaces"));
        Files.writeString(spacesRoot.resolve("default.ref"), tmp.resolve("gone").resolve("default").toString());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SpaceMigrator.migrate(workingDir, workingDir.resolve("config"), spacesRoot, "default", false));
        assertTrue(e.getMessage().contains("default.ref"), e.getMessage());
        assertFalse(Files.exists(spacesRoot.resolve("default")), "no shadowing folder was created");
        assertTrue(Files.isDirectory(workingDir.resolve("config")), "the flat config was not moved");
    }

    @Test
    void stateLandsOnTheDataRootTheSpaceBootsWith(@TempDir Path tmp) throws Exception {
        Path workingDir = flat(tmp);
        Path spacesRoot = tmp.resolve("spaces");
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        System.setProperty(SpaceRoot.DATA_ROOT_PROPERTY, mnt.toString());

        SpaceMigrator.migrate(workingDir, workingDir.resolve("config"), spacesRoot, "default", false);

        Path base = spacesRoot.resolve("default");
        assertTrue(Files.isDirectory(base.resolve("config")));
        assertFalse(Files.exists(base.resolve("data")), "nothing lands under the base");
        assertFalse(Files.exists(base.resolve("duckdb")));
        assertTrue(Files.isDirectory(mnt.resolve("default").resolve("data")));
        assertTrue(Files.exists(mnt.resolve("default").resolve("duckdb").resolve("inspecto-status.db")));
        assertTrue(SpaceDataRoot.ownedBy(mnt.resolve("default"), base), "claimed before the move");
        try (SpaceManager mgr = SpaceManager.discover(spacesRoot)) {
            assertEquals(mnt.resolve("default"), mgr.space(SpaceId.of("default")).orElseThrow().root().dataRoot(),
                    "the boot accepts the migrated data root");
        }
    }

    @Test
    void aRelativeSpacesRootStillStampsAnAbsoluteBase(@TempDir Path tmp) throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Assumptions.assumeTrue(cwd.getRoot().equals(tmp.getRoot()), "a relative path needs the temp dir on the same drive");
        Path workingDir = flat(tmp);
        Path spacesRoot = cwd.relativize(tmp.resolve("spaces"));   // the CLI default is relative
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        System.setProperty(SpaceRoot.DATA_ROOT_PROPERTY, mnt.toString());

        SpaceMigrator.migrate(workingDir, workingDir.resolve("config"), spacesRoot, "default", false);

        String stamp = Files.readString(mnt.resolve("default").resolve(SpaceDataRoot.STAMP)).trim();
        assertTrue(Path.of(stamp).isAbsolute(), "a relative stamp breaks a server started elsewhere: " + stamp);
    }

    @Test
    void anUnmountedDataRootIsRefusedBeforeAnythingMoves(@TempDir Path tmp) throws Exception {
        Path workingDir = flat(tmp);
        System.setProperty(SpaceRoot.DATA_ROOT_PROPERTY, tmp.resolve("not-mounted").toString());

        assertThrows(IllegalStateException.class, () -> SpaceMigrator.migrate(
                workingDir, workingDir.resolve("config"), tmp.resolve("spaces"), "default", false));
        assertTrue(Files.isDirectory(workingDir.resolve("database")), "the flat data was not moved");
        assertTrue(Files.exists(workingDir.resolve("inspecto-status.db")));
    }
}
