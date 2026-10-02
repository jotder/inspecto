package com.gamma.geolink;

import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutput.State;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.la.core.InputFingerprint;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link EngineDatasetProvider#inputFingerprint}: a real physicalRef Dataset over files in a temp data root (D-3 design 5.3a). */
class EngineDatasetProviderFingerprintTest {

    private final EngineDatasetProvider provider = new EngineDatasetProvider();
    private static final Map<String, Object> DS = Map.of("physicalRef", "orders");

    @AfterEach
    void clearRegistry() {
        ConsignmentOutputStores.use(null);
    }

    private static Path file(Path dataRoot, String rel, int bytes, long mtime) throws Exception {
        Path p = dataRoot.resolve("orders").resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[bytes]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
        return p;
    }

    private InputFingerprint fp(Path dataRoot) {
        return provider.inputFingerprint(DS, dataRoot, dataRoot.resolveSibling("write-unused"));
    }

    @Test
    void stableAcrossCallsAndAcrossAMovedRootAndChangesOnEveryKindOfEdit(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("one"));
        Path a;
        a = file(root, "dt=1/a.parquet", 10, 1_000_000L);
        file(root, "dt=2/b.parquet", 20, 2_000_000L);
        InputFingerprint base = fp(root);
        assertTrue(base.known(), base.value());
        assertEquals(2, base.files().size());
        assertEquals("orders/dt=1/a.parquet", base.files().get(0).path(), "relative to the data root, forward slashes");
        assertEquals(base.value(), fp(root).value(), "stable across calls");

        Path moved = tmp.resolve("two");                                         // a moved Space root
        Files.move(root, moved);
        assertEquals(base.value(), fp(moved).value(), "no absolute path is embedded");
        assertFalse(base.files().stream().anyMatch(f -> f.path().contains(tmp.toString().replace('\\', '/'))));

        file(moved, "dt=3/c.parquet", 5, 3_000_000L);                            // addition
        InputFingerprint added = fp(moved);
        assertNotEquals(base.value(), added.value());
        assertEquals(3, added.files().size());

        Files.delete(moved.resolve("orders/dt=3/c.parquet"));                    // deletion returns to base content
        assertEquals(base.value(), fp(moved).value());
        Files.delete(moved.resolve("orders/dt=1/a.parquet"));
        assertNotEquals(base.value(), fp(moved).value(), "deleted file");

        file(moved, "dt=1/a.parquet", 11, 1_000_000L);                           // size changed
        assertNotEquals(base.value(), fp(moved).value());
        a = moved.resolve("orders/dt=1/a.parquet");
        Files.write(a, new byte[10]);
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_000_001L));           // mtime only
        assertNotEquals(base.value(), fp(moved).value());
        Files.setLastModifiedTime(a, FileTime.fromMillis(1_000_000L));
        assertEquals(base.value(), fp(moved).value(), "back to the original size and mtime");
    }

    @Test
    void aSupersededFileIsSubtractedThroughTheConsignmentCatalog(@TempDir Path root) throws Exception {
        Path a = file(root, "a.parquet", 10, 1_000_000L);
        file(root, "b.parquet", 20, 2_000_000L);
        InputFingerprint before = fp(root);
        try (DbConsignmentOutputStore db = DbConsignmentOutputStore.open("jdbc:duckdb:")) {
            ConsignmentOutputStores.use(db);
            String path = a.toString().replace('\\', '/');
            db.record(List.of(new ConsignmentOutput("c-1", "run-1", "cdr", "", null, path, 1, 100, "2026-08-10T10:00:00Z", State.SUPERSEDED)));
            InputFingerprint after = fp(root);
            assertEquals(List.of("orders/b.parquet"), after.files().stream().map(InputFingerprint.FileStamp::path).toList());
            assertNotEquals(before.value(), after.value());
        }
    }

    @Test
    void aMissingStoreIsAnEmptyKnownListNotUnknown(@TempDir Path root) {
        InputFingerprint f = fp(root);
        assertTrue(f.known());
        assertTrue(f.files().isEmpty());
    }

    @Test
    void aViewBackedDatasetHasTheNoFilesSentinelOfItsSql(@TempDir Path root) throws Exception {
        new ViewStore(root.resolve("views")).write(new ViewDefinition("v", "flow-x", List.of(), "SELECT 1 AS a", "2026-10-02T00:00:00Z"));
        InputFingerprint f = provider.inputFingerprint(Map.of("view", "v"), root, root);
        assertFalse(f.known());
        assertEquals(InputFingerprint.noFiles("SELECT 1 AS a").value(), f.value());
        assertTrue(f.value().startsWith("no-files:"));
        new ViewStore(root.resolve("views")).write(new ViewDefinition("v", "flow-x", List.of(), "SELECT 2 AS a", "2026-10-02T00:00:00Z"));
        assertNotEquals(f.value(), provider.inputFingerprint(Map.of("view", "v"), root, root).value(), "carries the SQL hash");
    }

    @Test
    void aVirtualDatasetListsItsSourceStoresFiles(@TempDir Path root) throws Exception {
        file(root, "a.parquet", 10, 1_000_000L);
        InputFingerprint f = provider.inputFingerprint(Map.of("sql", "SELECT * FROM orders", "sourceName", "orders"), root, root);
        assertTrue(f.known());
        assertEquals(1, f.files().size());
    }
}
