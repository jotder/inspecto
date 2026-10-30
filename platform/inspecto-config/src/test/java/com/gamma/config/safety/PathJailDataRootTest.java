package com.gamma.config.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPACE-DATA-ROOT-1: paths under a Space's data/, audit/, duckdb/ resolve onto its registered data root. */
class PathJailDataRootTest {

    @TempDir Path tmp;
    Path base;
    Path config;
    Path dataRoot;

    @BeforeEach
    void setUp() throws Exception {
        base = tmp.resolve("spaces").resolve("demo");
        config = Files.createDirectories(base.resolve("config"));
        dataRoot = Files.createDirectories(tmp.resolve("mnt").resolve("demo"));
        DiscoveredRoots.register("demo", base);
        DiscoveredRoots.registerDataRoot("demo", dataRoot);
    }

    @AfterEach
    void tearDown() {
        DiscoveredRoots.clear();
    }

    @Test
    void dataPathResolvesOntoTheDataRoot() {
        assertEquals(dataRoot.resolve("data/orders/inbox"),
                PathJail.resolveDataPath(config, "data/orders/inbox", "dirs.poll"));
    }

    @Test
    void jobPathThroughDotDotResolvesOntoTheDataRoot() {
        assertEquals(dataRoot.resolve("data/backups"),
                PathJail.resolveJobPath(config, "../data/backups", "backup_dir"));
        assertEquals(dataRoot.resolve("audit/runlog"),
                PathJail.resolveJobPath(config, "../audit/runlog", "dir"));
    }

    @Test
    void absoluteSpellingOfTheOldLocationIsRebasedToo() {
        assertEquals(dataRoot.resolve("duckdb/x.db"),
                PathJail.resolveJobPath(config, base.resolve("duckdb/x.db").toString(), "database"));
    }

    @Test
    void configPathsStayUnderTheBase() {
        assertEquals(config.resolve("schemas/a.toon"),
                PathJail.resolveConfigRef(config, "schemas/a.toon", "schema_file"));
        assertEquals(base.resolve("database"), PathJail.resolveJobPath(config, "../database", "dir"));
    }

    @Test
    void noDataRootLeavesPathsUnchanged() {
        DiscoveredRoots.registerDataRoot("demo", base);   // equal to the base = not moved
        assertEquals(base.resolve("data/orders"), PathJail.resolveDataPath(config, "data/orders", "dirs.poll"));
    }

    @Test
    void dataRootIsAnAllowedRootOfItsSpaceOnly() {
        assertTrue(SafetyPolicy.baseRoots("demo").contains(dataRoot));
        assertTrue(!SafetyPolicy.baseRoots("other").contains(dataRoot));
    }

    @Test
    void theRelocatedDataRootItselfIsStillNotADataHome() {
        Path resolved = PathJail.resolveDataPath(config, "data", "dirs.poll");
        assertThrows(PathJail.Escape.class,
                () -> PathJail.refuseDataHome(resolved, base, List.of(base), "data", "dirs.poll"));
        assertTrue(PathJail.isDataRoot(dataRoot.resolve("data")));
    }

    @Test
    void theDataRootItselfAndItsStoresAreNeverADataHomeOrAllowlistEntry() {
        for (Path bad : new Path[]{dataRoot, dataRoot.getParent(), dataRoot.resolve("duckdb"), dataRoot.resolve("audit/x")}) {
            assertThrows(PathJail.Escape.class,
                    () -> PathJail.refuseDataHome(bad, base, List.of(base), bad.toString(), "dirs.poll"), bad.toString());
            assertTrue(PathJail.readAllowlistRefusal(bad) != null, bad.toString());
        }
        Path ok = dataRoot.resolve("data/orders");
        PathJail.refuseDataHome(ok, base, List.of(base), ok.toString(), "dirs.poll");
    }

    @Test
    void unregisterDropsTheDataRoot() {
        DiscoveredRoots.unregister("demo");
        DiscoveredRoots.register("demo", base);
        assertEquals(base.resolve("data/orders"), PathJail.resolveDataPath(config, "data/orders", "dirs.poll"));
    }
}
