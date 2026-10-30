package com.gamma.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPACE-DATA-ROOT-1: where a Space's data, audit and DuckDB stores live, and the load-time checks. */
class SpaceDataRootTest {

    @TempDir Path tmp;

    @AfterEach
    void clearProperty() {
        System.clearProperty(SpaceRoot.DATA_ROOT_PROPERTY);
    }

    private Path space(String id, String dataRootLine) throws Exception {
        Path base = Files.createDirectories(tmp.resolve("spaces").resolve(id).resolve("config")).getParent();
        Files.writeString(base.resolve("space.toon"), "display_name: " + id + "\n"
                + (dataRootLine == null ? "" : "data_root: " + dataRootLine + "\n"));
        return base;
    }

    private static String toon(Path p) {
        return p.toString().replace('\\', '/');
    }

    @Test
    void unsetKeepsEveryStoreUnderTheBase() throws Exception {
        Path base = space("demo", null);
        SpaceRoot root = SpaceRoot.under(base);
        assertEquals(base, root.dataRoot());
        assertEquals(base.resolve("data").toString(), root.dataDir());
        assertEquals(base.resolve("audit").toString(), root.auditDir());
        assertTrue(root.objectsDbUrl().endsWith(base.resolve("duckdb").resolve("inspecto-ops.db").toString()));
    }

    @Test
    void declaredDataRootCarriesDataAuditAndDuckdb() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Path base = space("demo", toon(mnt.resolve("demo")));
        SpaceRoot root = SpaceRoot.under(base);
        Path d = mnt.resolve("demo");
        assertEquals(d, root.dataRoot());
        assertEquals(d.resolve("data").toString(), root.dataDir());
        assertEquals(d.resolve("data").resolve("events"), root.eventsDir());
        assertEquals(d.resolve("audit").toString(), root.auditDir());
        assertTrue(root.objectsDbUrl().endsWith(d.resolve("duckdb").resolve("inspecto-ops.db").toString()));
        assertEquals(base.resolve("config"), root.config());
    }

    @Test
    void conventionMountDefaultsToMountSlashId() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        System.setProperty(SpaceRoot.DATA_ROOT_PROPERTY, mnt.toString());
        assertEquals(mnt.resolve("demo"), SpaceRoot.under(space("demo", null)).dataRoot());
    }

    @Test
    void manifestWinsOverConventionMount() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Path other = Files.createDirectories(tmp.resolve("big"));
        System.setProperty(SpaceRoot.DATA_ROOT_PROPERTY, mnt.toString());
        assertEquals(other.resolve("demo"), SpaceRoot.under(space("demo", toon(other.resolve("demo")))).dataRoot());
    }

    @Test
    void claimCreatesTheLayoutAndStampsTheOwner() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Path base = space("demo", toon(mnt.resolve("demo")));
        SpaceRoot root = SpaceRoot.under(base);
        SpaceDataRoot.claim(root, SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));
        for (String sub : new String[]{"data", "audit", "duckdb"}) assertTrue(Files.isDirectory(mnt.resolve("demo").resolve(sub)));
        assertEquals(base.toString(), Files.readString(mnt.resolve("demo").resolve(SpaceDataRoot.STAMP)).trim());
        SpaceDataRoot.claim(root, SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));   // re-boot is fine
    }

    @Test
    void aCopiedSpacePointingAtTheSameDataRootIsRefused() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Path orig = space("demo", toon(mnt.resolve("demo")));
        SpaceDataRoot.claim(SpaceRoot.under(orig), SpaceContext.SpaceManifest.read(orig.resolve("space.toon"), "demo"));
        Path copy = space("demo-copy", toon(mnt.resolve("demo")));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                SpaceRoot.under(copy), SpaceContext.SpaceManifest.read(copy.resolve("space.toon"), "demo-copy")));
        assertTrue(e.getMessage().contains("belongs to the Space at"));
    }

    @Test
    void relativeDataRootIsRefused() throws Exception {
        Path base = space("demo", "../elsewhere");
        assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo")));
    }

    @Test
    void unmountedVolumeIsRefusedAndNothingIsCreated() throws Exception {
        Path missing = tmp.resolve("not-mounted").resolve("demo");
        Path base = space("demo", toon(missing));
        assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo")));
        assertFalse(Files.exists(missing.getParent()));
    }

    @Test
    void dataLeftUnderTheBaseIsRefusedNotSplit() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Path base = space("demo", toon(mnt.resolve("demo")));
        Files.writeString(Files.createDirectories(base.resolve("data/orders")).resolve("a.csv"), "x");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo")));
        assertTrue(e.getMessage().contains("move data/ there first"));
    }

    @Test
    void adoptSeededMovesCreationSeedOntoTheDataRoot() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Path base = space("demo", toon(mnt.resolve("demo")));
        Files.writeString(Files.createDirectories(base.resolve("data/samples/orders")).resolve("a.csv"), "x");
        Files.createDirectories(base.resolve("duckdb"));
        SpaceRoot root = SpaceRoot.under(base);
        SpaceDataRoot.adoptSeeded(root);
        assertTrue(Files.isRegularFile(mnt.resolve("demo/data/samples/orders/a.csv")));
        assertFalse(Files.exists(base.resolve("data")));
        assertFalse(Files.exists(base.resolve("duckdb")));
        SpaceDataRoot.claim(root, SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));
    }

    @Test
    void aNonEmptyUnclaimedDirectoryIsNeverClaimed() throws Exception {
        Path disk = Files.createDirectories(tmp.resolve("bigdisk"));
        Files.writeString(disk.resolve("someone-elses.dat"), "x");
        Path base = space("demo", toon(disk));   // the operator forgot the /<id>
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo")));
        assertTrue(e.getMessage().contains("not empty"));
        assertFalse(Files.exists(disk.resolve(SpaceDataRoot.STAMP)));
        assertThrows(IllegalStateException.class, () -> SpaceDataRoot.adoptSeeded(SpaceRoot.under(base)));
    }

    @Test
    void stateMovedThereByHandIsClaimed() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Files.writeString(Files.createDirectories(mnt.resolve("demo/data/orders")).resolve("a.csv"), "x");
        Files.createDirectories(mnt.resolve("demo/duckdb"));
        Path base = space("demo", toon(mnt.resolve("demo")));
        SpaceDataRoot.claim(SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));
        assertTrue(SpaceDataRoot.ownedBy(mnt.resolve("demo"), base));
    }

    @Test
    void aDataRootOverlappingAnySpaceIsRefused() throws Exception {
        Path other = space("other", null);
        for (Path bad : new Path[]{other.resolve("config").resolve("x"), other, tmp.resolve("spaces").resolve("demo-data")}) {
            Path base = space("demo", toon(bad));
            assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                    SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo")), bad.toString());
        }
    }

    @Test
    void ownershipComparesCanonicalPaths() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Path base = space("demo", toon(mnt.resolve("demo")));
        SpaceDataRoot.claim(SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));
        assertTrue(SpaceDataRoot.ownedBy(mnt.resolve("demo"), base.resolve("config").resolve("..")));
        assertFalse(SpaceDataRoot.ownedBy(mnt.resolve("demo"), space("other", null)));
    }

    @Test
    void theSpacesRootNeverTakesTheConventionMount() throws Exception {
        Path mnt = Files.createDirectories(tmp.resolve("mnt"));
        Path spacesRoot = space("demo", null).getParent();
        System.setProperty(SpaceRoot.DATA_ROOT_PROPERTY, mnt.toString());
        assertEquals(spacesRoot, SpaceRoot.under(spacesRoot).dataRoot());
    }

    @Test
    void manifestRoundTripsDataRootAndOmitsItWhenBlank() throws Exception {
        Path f = tmp.resolve("space.toon");
        new SpaceContext.SpaceManifest("Demo", "", "t", "/mnt/x").write(f);
        assertEquals("/mnt/x", SpaceContext.SpaceManifest.read(f, "demo").dataRoot());
        new SpaceContext.SpaceManifest("Demo", "", "t").write(f);
        assertFalse(Files.readString(f).contains("data_root"));
    }
}
