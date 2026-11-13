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
        System.clearProperty(AppRoot.PROPERTY);
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

    /** SPACE-RELOCATABLE-ROOTS-1: a relative data_root resolves against the APP ROOT, not the Space folder. */
    @Test
    void relativeDataRootResolvesAgainstTheAppRoot() throws Exception {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        Path base = space("demo", "la-data/demo");
        assertEquals(tmp.resolve("la-data").resolve("demo"), SpaceRoot.under(base).dataRoot());
    }

    /**
     * SPACE-RELOCATABLE-ROOTS-1: a relative data root is claimed like any other and IS stamped — but with the
     * Space's path FROM the data root, so the stamp travels with the install instead of pinning it to one path.
     */
    @Test
    void aRelativeDataRootIsStampedRelatively() throws Exception {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        Path base = space("demo", "la-data/demo");
        SpaceRoot root = SpaceRoot.under(base);
        SpaceDataRoot.claim(root, SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));
        Path d = tmp.resolve("la-data").resolve("demo");
        for (String sub : new String[]{"data", "audit", "duckdb"}) assertTrue(Files.isDirectory(d.resolve(sub)));
        Path owner = Path.of(Files.readString(d.resolve(SpaceDataRoot.STAMP)).trim());
        assertFalse(owner.isAbsolute(), "the stamp must not pin the Space to this install's path");
        assertEquals(base, d.resolve(owner).normalize());
        assertTrue(SpaceDataRoot.ownedBy(d, base));
        SpaceDataRoot.claim(root, SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));   // re-boot is fine
    }

    /**
     * The hole an earlier draft left: a relative data root that lands on a directory another Space already owns is
     * refused like any other. Skipping the stamp for relative roots would have let this Space claim — and purge — it.
     */
    @Test
    void aRelativeDataRootOnAnotherSpacesDataIsRefused() throws Exception {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        Path mnt = Files.createDirectories(tmp.resolve("la-data"));
        Path owner = space("owner", toon(mnt.resolve("shared")));
        SpaceDataRoot.claim(SpaceRoot.under(owner), SpaceContext.SpaceManifest.read(owner.resolve("space.toon"), "owner"));
        Path thief = space("thief", "la-data/shared");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                SpaceRoot.under(thief), SpaceContext.SpaceManifest.read(thief.resolve("space.toon"), "thief")));
        assertTrue(e.getMessage().contains("belongs to the Space at"), e.getMessage());
    }

    /**
     * ⚠ Windows: {@code Path.of("/mnt/bigdisk").isAbsolute()} is false. Classifying by that would take an operator's
     * POSIX path for a relative one, skip the mount check and plant the tree on the current drive.
     */
    @Test
    void aPosixStylePathIsTreatedAsRootedNotRelative() throws Exception {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        Path base = space("demo", "/mnt/definitely-not-mounted/demo");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo")));
        assertTrue(e.getMessage().contains("is the volume mounted?"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("mnt")), "nothing is created under the app root either");
    }

    /**
     * The point of the whole change: the SAME install copied to a second path boots there, each copy against its
     * own data. Under an absolute data_root this is the failure the stamp produces
     * ({@code aCopiedSpacePointingAtTheSameDataRootIsRefused}).
     */
    @Test
    void theInstallBootsAfterItMoves() throws Exception {
        Path first = Files.createDirectories(tmp.resolve("install-a"));
        System.setProperty(AppRoot.PROPERTY, first.toString());
        Path base = Files.createDirectories(first.resolve("spaces").resolve("demo").resolve("config")).getParent();
        Files.writeString(base.resolve("space.toon"), "display_name: demo\ndata_root: la-data/demo\n");
        SpaceDataRoot.claim(SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));
        assertTrue(Files.isDirectory(first.resolve("la-data").resolve("demo").resolve("data")));

        // the whole install is COPIED to a second path - stamp included, which is what used to refuse the Space
        Path second = tmp.resolve("install-b");
        copyTree(first, second);
        assertTrue(Files.isRegularFile(second.resolve("la-data").resolve("demo").resolve(SpaceDataRoot.STAMP)),
                "the copy carries the stamp; the point is that it still resolves");
        System.setProperty(AppRoot.PROPERTY, second.toString());
        Path moved = second.resolve("spaces").resolve("demo");
        SpaceDataRoot.claim(SpaceRoot.under(moved), SpaceContext.SpaceManifest.read(moved.resolve("space.toon"), "demo"));
        assertEquals(second.resolve("la-data").resolve("demo"), SpaceRoot.under(moved).dataRoot());
        assertTrue(SpaceDataRoot.ownedBy(second.resolve("la-data").resolve("demo"), moved));
        // and the original still owns its own, at its own path
        assertTrue(SpaceDataRoot.ownedBy(first.resolve("la-data").resolve("demo"), first.resolve("spaces").resolve("demo")));
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (java.util.stream.Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    /** "It resolves" is not the test: a relative root is held to the SAME overlap rule as an absolute one. */
    @Test
    void aRelativeDataRootInsideTheSpacesRootIsStillRefused() throws Exception {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        Path base = space("demo", "spaces/demo/data");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SpaceDataRoot.claim(
                SpaceRoot.under(base), SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo")));
        assertTrue(e.getMessage().contains("overlaps"), e.getMessage());
    }

    /** Purge deletes a moved data root only when it is owned — the relative stamp must satisfy that, or data leaks. */
    @Test
    void aRelativeDataRootIsOwnedSoPurgeReachesIt() throws Exception {
        System.setProperty(AppRoot.PROPERTY, tmp.toString());
        Path base = space("demo", "la-data/demo");
        SpaceRoot root = SpaceRoot.under(base);
        SpaceDataRoot.claim(root, SpaceContext.SpaceManifest.read(base.resolve("space.toon"), "demo"));
        assertTrue(SpaceDataRoot.ownedBy(root.dataRoot(), base));
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
