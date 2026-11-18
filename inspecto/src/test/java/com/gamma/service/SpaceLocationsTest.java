package com.gamma.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Every check {@link SpaceLocations} makes, each failing for its own reason ({@code SPACE-LOCATIONS-1} D1, D2, D7). */
class SpaceLocationsTest {

    @TempDir Path tmp;
    private Path install, spacesRoot, disks;
    private final Map<String, Path[]> hosted = new LinkedHashMap<>();
    private Set<String> taken = Set.of();

    @BeforeEach
    void setUp() throws Exception {
        install = Files.createDirectories(tmp.resolve("install"));
        spacesRoot = Files.createDirectories(install.resolve("spaces"));
        disks = Files.createDirectories(tmp.resolve("disks"));
        System.setProperty(AppRoot.PROPERTY, install.toString());
        System.setProperty(SpaceLocations.PROPERTY, disks + File.pathSeparator + install);
        SpaceLocations.minFreeBytesForTest = 0;
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(AppRoot.PROPERTY);
        System.clearProperty(SpaceLocations.PROPERTY);
        SpaceLocations.minFreeBytesForTest = -1;
    }

    private List<SpaceLocations.Check> checks(Path p, SpaceLocations.Use use, String id, Path base) {
        return SpaceLocations.checks(p, use, spacesRoot, hosted, id, base, taken::contains);
    }

    /** The first failing check's name and reason, or null when all pass. */
    private String firstFailure(Path p, SpaceLocations.Use use, String id, Path base) {
        return checks(p, use, id, base).stream().filter(c -> !c.ok()).findFirst()
                .map(c -> c.check() + ": " + c.reason()).orElse(null);
    }

    @Test
    void aGoodFolderPassesEveryCheck() {
        assertNull(firstFailure(disks.resolve("acme"), SpaceLocations.Use.CREATE, "acme", null));
        assertNull(firstFailure(disks.resolve("acme-data"), SpaceLocations.Use.DATA, "acme", spacesRoot.resolve("acme")));
    }

    @Test
    void allowed() {
        String why = firstFailure(tmp.resolve("other").resolve("acme"), SpaceLocations.Use.CREATE, "acme", null);
        assertTrue(why.startsWith("allowed:"), why);
        assertEquals(403, checks(tmp.resolve("other"), SpaceLocations.Use.DATA, null, null).get(0).status());
    }

    @Test
    void install() {
        assertTrue(firstFailure(install, SpaceLocations.Use.DATA, "acme", null).startsWith("install:"), "the install itself");
        assertTrue(firstFailure(spacesRoot.resolve("_templates").resolve("x"), SpaceLocations.Use.DATA, "acme", null)
                .startsWith("install:"), "a templates folder");
        assertTrue(firstFailure(spacesRoot.resolve("acme"), SpaceLocations.Use.CREATE, "acme", null)
                .contains("needs no folder path"));
    }

    @Test
    void overlap() throws Exception {
        hosted.put("beta", new Path[]{disks.resolve("beta"), disks.resolve("beta-data")});
        assertTrue(firstFailure(disks.resolve("beta-data").resolve("acme"), SpaceLocations.Use.DATA, "acme",
                spacesRoot.resolve("acme")).contains("overlaps Space 'beta'"));
        Path base = disks.resolve("gamma").resolve("acme");
        assertTrue(firstFailure(base.resolve("data"), SpaceLocations.Use.DATA, "acme", base).contains("its own Space folder"));
        assertTrue(firstFailure(disks.resolve("gamma").resolve("acme-data"), SpaceLocations.Use.DATA, "acme", base)
                .contains("holds its Space folder"));
    }

    @Test
    void name() {
        assertTrue(firstFailure(disks.resolve("Not_An_Id"), SpaceLocations.Use.CREATE, null, null).startsWith("name:"));
        assertTrue(firstFailure(disks.resolve("acme"), SpaceLocations.Use.CREATE, "other", null).contains("must be the Space id"));
        taken = Set.of("acme");
        assertTrue(firstFailure(disks.resolve("acme"), SpaceLocations.Use.CREATE, "acme", null).contains("already exists"));
    }

    @Test
    void reachable() {
        assertTrue(firstFailure(disks.resolve("missing").resolve("acme"), SpaceLocations.Use.CREATE, "acme", null)
                .startsWith("reachable:"), "an unmounted parent");
        assertTrue(firstFailure(disks.resolve("acme"), SpaceLocations.Use.ATTACH, null, null).startsWith("reachable:"),
                "attach needs the folder");
    }

    @Test
    void writable() throws Exception {
        Path ro = Files.createDirectories(disks.resolve("ro"));
        assumeTrue(ro.toFile().setWritable(false) && !Files.isWritable(ro), "this file system ignores a read-only folder");
        try {
            assertTrue(firstFailure(ro.resolve("acme"), SpaceLocations.Use.CREATE, "acme", null).startsWith("writable:"));
        } finally {
            ro.toFile().setWritable(true);
        }
    }

    @Test
    void freeSpace() {
        SpaceLocations.minFreeBytesForTest = Long.MAX_VALUE;
        String why = firstFailure(disks.resolve("acme"), SpaceLocations.Use.CREATE, "acme", null);
        assertTrue(why.startsWith("free-space:") && why.contains("MIN_FREE_GB"), why);
    }

    @Test
    void stamp() throws Exception {
        Path data = Files.createDirectories(disks.resolve("acme-data"));
        Files.writeString(data.resolve(SpaceDataRoot.STAMP), spacesRoot.resolve("someone-else") + "\n");
        assertTrue(firstFailure(data, SpaceLocations.Use.DATA, "acme", spacesRoot.resolve("acme"))
                .contains("belongs to another Space"));
        Files.writeString(data.resolve(SpaceDataRoot.STAMP), spacesRoot.resolve("acme") + "\n");
        assertNull(firstFailure(data, SpaceLocations.Use.DATA, "acme", spacesRoot.resolve("acme")),
                "already this Space's data folder");
    }

    @Test
    void content() throws Exception {
        Path full = Files.createDirectories(disks.resolve("acme"));
        Files.writeString(full.resolve("x.txt"), "x");
        assertTrue(firstFailure(full, SpaceLocations.Use.CREATE, "acme", null).contains("not empty"));
        assertTrue(firstFailure(full, SpaceLocations.Use.DATA, "acme", spacesRoot.resolve("acme")).contains("no Space has claimed"));
        assertTrue(firstFailure(full, SpaceLocations.Use.ATTACH, null, null).contains("not a Space"));
        Files.createDirectories(full.resolve("config"));
        assertNull(firstFailure(full, SpaceLocations.Use.ATTACH, null, null));
        assertTrue(checks(full, SpaceLocations.Use.SPACE, null, null).stream().allMatch(SpaceLocations.Check::ok));
    }

    @Test
    void disabledWithoutTheSetting() {
        System.clearProperty(SpaceLocations.PROPERTY);
        assumeTrue(System.getenv(SpaceLocations.ENV) == null);
        SpaceLocations.Refused r = assertThrows(SpaceLocations.Refused.class, SpaceLocations::requireEnabled);
        assertEquals(409, r.status());
        assertEquals(SpaceLocations.DISABLED, r.code());
    }

    @Test
    void pathsAreParsedAndWrittenRelativeToTheAppRoot() {
        assertEquals(install.resolve("disk").resolve("x").toAbsolutePath().normalize(), SpaceLocations.parse("disk/x", "path"));
        assertThrows(IllegalArgumentException.class, () -> SpaceLocations.parse("//server/share", "path"));
        // mixed leading separators are a network share on Windows too: refused before the disk is touched
        assertThrows(IllegalArgumentException.class, () -> SpaceLocations.parse("/\\127.0.0.1\\nosuchshare\\acme", "path"));
        assertThrows(IllegalArgumentException.class, () -> SpaceLocations.parse("\\/127.0.0.1/nosuchshare/acme", "path"));
        assertThrows(IllegalArgumentException.class, () -> SpaceLocations.parse("\\\\127.0.0.1\\nosuchshare\\acme", "path"));
        assertThrows(IllegalArgumentException.class, () -> SpaceLocations.parse("s3://bucket", "path"));
        assertEquals("disk/x", SpaceLocations.written(install.resolve("disk").resolve("x")));
        assertEquals(disks.resolve("x").toAbsolutePath().normalize().toString().replace('\\', '/'),
                SpaceLocations.written(disks.resolve("x")));
    }
}
