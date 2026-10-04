package com.gamma.query;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.job.PlatformServiceRegistry;
import com.gamma.job.PlatformServices;
import com.gamma.util.JdbcDrivers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link DatasetAccess} (S3-3, D-10 option 1): file-pinned, catalog-pruned, per-Space, fail-closed reads. */
class DatasetAccessTest {

    @AfterEach
    void clearRegistry() {
        ConsignmentOutputStores.use(null);
    }

    private static String parquet(Connection conn, Path dir, String name, int id) throws Exception {
        Files.createDirectories(dir);
        String path = dir.resolve(name + ".parquet").toString().replace("\\", "/");
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT " + id + " AS id) TO '" + path + "' (FORMAT PARQUET)");
        }
        return path;
    }

    private static DatasetAccess access(Path root) {
        Map<String, Map<String, Object>> datasets = Map.of("cdr", Map.of("physicalRef", "cdr"));
        return DatasetAccess.over("own", id -> Optional.ofNullable(datasets.get(id)), () -> root, () -> null);
    }

    @Test
    void readsADatasetInItsOwnSpaceAsAPinnedListThatPrunesUnreadableFiles(@TempDir Path root) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            Path dir = root.resolve("cdr");
            String live = parquet(conn, dir, "live", 1);
            String old = parquet(conn, dir, "old", 2);
            db.record(List.of(row(live, State.LIVE), row(old, State.SUPERSEDED)));
            ConsignmentOutputStores.use(db);

            DatasetAccess.Read r = access(root).read("own", "cdr");

            assertEquals(List.of("cdr/live.parquet"), r.files().orElseThrow(), "the superseded file is pruned");
            assertTrue(r.relationSql().contains("live.parquet"), r.relationSql());
            assertFalse(r.relationSql().contains("old.parquet"), "the relation reads the pruned list: " + r.relationSql());
            assertTrue(r.relationSql().contains("["), "pinned to an explicit list, not a glob: " + r.relationSql());
        }
    }

    @Test
    void aFileLandingAfterTheReadIsNotInItsPinnedRelation(@TempDir Path root) throws Exception {
        try (Connection conn = JdbcDrivers.connect("jdbc:duckdb:");
             DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            String a = parquet(conn, root.resolve("cdr"), "a", 1);
            db.record(List.of(row(a, State.LIVE)));
            ConsignmentOutputStores.use(db);
            DatasetAccess.Read r = access(root).read("own", "cdr");
            parquet(conn, root.resolve("cdr"), "b", 2);
            assertFalse(r.relationSql().contains("b.parquet"));
        }
    }

    @Test
    void aForeignSpaceIsRefusedNamingTheIdsNeverEmpty(@TempDir Path root) {
        DatasetAccess a = access(root);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> a.read("other", "cdr"));
        assertTrue(e.getMessage().contains("other") && e.getMessage().contains("cdr"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> a.read(null, "cdr"));
    }

    @Test
    void anUnknownDatasetIsRefusedNamingIt(@TempDir Path root) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> access(root).read("own", "nope"));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> access(root).read("own", null));
    }

    @Test
    void aConsumerWithoutTheDatasetsGrantCannotReachItAndADisabledServiceRefusesTheGrant(@TempDir Path root) {
        PlatformServiceRegistry reg = new PlatformServiceRegistry();
        reg.register("datasets", DatasetAccess.class, access(root));
        reg.register("noop", Runnable.class, () -> {});

        PlatformServices other = reg.grant(Set.of("noop"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> other.get(DatasetAccess.class));
        assertTrue(e.getMessage().contains("DatasetAccess"), e.getMessage());

        assertNotNull(reg.grant(Set.of("datasets")).get(DatasetAccess.class));
        reg.disable("datasets");
        IllegalStateException d = assertThrows(IllegalStateException.class, () -> reg.grant(Set.of("datasets")));
        assertTrue(d.getMessage().contains("datasets"), d.getMessage());
    }

    private static ConsignmentOutput row(String path, State state) {
        return new ConsignmentOutput("c-" + path.hashCode(), "run-1", "cdr", "", null, path,
                1, 100, "2026-08-10T10:00:00Z", state);
    }
}
