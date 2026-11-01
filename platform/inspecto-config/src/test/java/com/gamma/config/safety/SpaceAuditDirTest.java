package com.gamma.config.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPACE-DATA-ROOT-CONFIG-AUDIT-1: config/audit stores move onto the Space's data root, once, never overwriting. */
class SpaceAuditDirTest {

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
    void relocatedSpaceUsesTheDataRootsAudit() {
        SpaceAuditDir a = SpaceAuditDir.of(config, "snapshots");
        assertEquals(dataRoot.resolve("audit").resolve("snapshots"), a.dir());
        assertEquals(config.resolve("audit").resolve("snapshots"), a.legacy());
        assertEquals(PathJail.onDataRoot(base.resolve("audit/snapshots")), a.dir(), "the PathJail redirect, not a second rule");
    }

    @Test
    void firstOpenMovesTheOldFilesAcross() throws Exception {
        Path old = Files.createDirectories(config.resolve("audit/snapshots/investigations/inv-1"));
        Files.writeString(config.resolve("audit/snapshots/s1.json"), "{}");
        Files.writeString(old.resolve("log.jsonl"), "a\n");

        SpaceAuditDir a = SpaceAuditDir.of(config, "snapshots");

        assertEquals("{}", Files.readString(a.dir().resolve("s1.json")));
        assertEquals("a\n", Files.readString(a.dir().resolve("investigations/inv-1/log.jsonl")));
        assertFalse(Files.exists(config.resolve("audit/snapshots")), "the emptied old tree is removed");
    }

    @Test
    void moveIsIdempotentAndNeverOverwrites() throws Exception {
        Files.createDirectories(config.resolve("audit/entity-facts"));
        Files.writeString(config.resolve("audit/entity-facts/000000000001.json"), "old");
        Files.writeString(config.resolve("audit/entity-facts/000000000002.json"), "two");
        Path now = Files.createDirectories(dataRoot.resolve("audit/entity-facts"));
        Files.writeString(now.resolve("000000000001.json"), "new");

        SpaceAuditDir a = SpaceAuditDir.of(config, "entity-facts");

        assertEquals("new", Files.readString(now.resolve("000000000001.json")), "never overwritten");
        assertEquals("old", Files.readString(config.resolve("audit/entity-facts/000000000001.json")), "both kept");
        assertEquals("two", Files.readString(now.resolve("000000000002.json")));
        assertEquals(now.resolve("000000000001.json"), a.readable(now.resolve("000000000001.json")), "the new one is read");
        assertEquals(0, a.move(), "a second run moves nothing");
        assertEquals("old", Files.readString(config.resolve("audit/entity-facts/000000000001.json")));
    }

    @Test
    void aReadThatMissesTheNewLocationMovesTheLateFileAcross() throws Exception {
        SpaceAuditDir a = SpaceAuditDir.of(config, "la-uploads");   // moves nothing: the old tree is still empty
        Files.createDirectories(config.resolve("audit/la-uploads/records/c1"));
        Files.writeString(config.resolve("audit/la-uploads/records/c1/f.json"), "late");

        Path f = a.dir().resolve("records/c1/f.json");
        assertEquals(f, a.readable(f), "converges on the new place");
        assertEquals("late", Files.readString(f));
        assertFalse(Files.exists(config.resolve("audit/la-uploads")));
        assertEquals(a.dir().resolve("records/c1/none.json"), a.readable(a.dir().resolve("records/c1/none.json")));
    }

    @Test
    void whatTheMoveCannotTakeIsReadFromTheOldLocation() throws Exception {
        SpaceAuditDir a = SpaceAuditDir.of(config, "la-uploads");
        Files.createDirectories(config.resolve("audit/la-uploads/records/c1"));
        Files.writeString(config.resolve("audit/la-uploads/records/c1/f.json"), "late");
        Files.createDirectories(a.dir());
        Files.writeString(a.dir().resolve("records"), "a file where the move needs a directory");

        Path f = a.dir().resolve("records/c1/f.json");
        assertEquals(config.resolve("audit/la-uploads/records/c1/f.json"), a.readable(f), "the move failed: read the old file");
        assertEquals(List.of(config.resolve("audit/la-uploads/records/c1/f.json")), a.list(a.dir().resolve("records/c1")));
    }

    @Test
    void oneStuckFileDoesNotStrandTheFilesAfterIt() throws Exception {
        Files.createDirectories(config.resolve("audit/snapshots/a"));
        Files.createDirectories(config.resolve("audit/snapshots/b"));
        Files.writeString(config.resolve("audit/snapshots/a/1.json"), "a");
        Files.writeString(config.resolve("audit/snapshots/b/2.json"), "b");
        Path now = Files.createDirectories(dataRoot.resolve("audit/snapshots"));
        Files.writeString(now.resolve("a"), "a file where the move needs a directory");

        SpaceAuditDir a = SpaceAuditDir.of(config, "snapshots");   // the fault is logged, not thrown

        assertEquals("b", Files.readString(now.resolve("b/2.json")), "moved past the stuck file");
        assertTrue(Files.isRegularFile(config.resolve("audit/snapshots/a/1.json")), "the stuck file stays");
        assertEquals(config.resolve("audit/snapshots/a/1.json"), a.readable(now.resolve("a/1.json")));
        try (var s = Files.list(now)) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().endsWith(".moving.tmp")), "no temp file left");
        }
    }

    @Test
    void aListingIsNewFirstThenOldOnly() throws Exception {
        SpaceAuditDir a = SpaceAuditDir.of(config, "snapshots");
        Files.createDirectories(a.dir());
        Files.writeString(a.dir().resolve("a.json"), "new");
        Files.createDirectories(config.resolve("audit/snapshots"));
        Files.writeString(config.resolve("audit/snapshots/a.json"), "old twin");
        Files.writeString(config.resolve("audit/snapshots/b.json"), "old only");

        List<Path> listed = a.list(a.dir());
        assertEquals(List.of(a.dir().resolve("a.json"), config.resolve("audit/snapshots/b.json")), listed);
    }

    @Test
    void noDataRootChangesNothing() throws Exception {
        DiscoveredRoots.registerDataRoot("demo", base);   // equal to the base = not moved
        Files.createDirectories(config.resolve("audit/snapshots"));
        Files.writeString(config.resolve("audit/snapshots/s1.json"), "{}");

        SpaceAuditDir a = SpaceAuditDir.of(config, "snapshots");

        assertEquals(config.resolve("audit/snapshots"), a.dir());
        assertEquals(a.dir(), a.legacy());
        assertEquals(0, a.move());
        assertTrue(Files.isRegularFile(config.resolve("audit/snapshots/s1.json")), "nothing moved");
        assertFalse(Files.exists(base.resolve("audit")));
    }

    @Test
    void aWriteRootThatIsNotASpaceConfigIsUnchanged() {
        Path legacyRoot = tmp.resolve("write-root");
        assertEquals(legacyRoot.resolve("audit/snapshots"), SpaceAuditDir.of(legacyRoot, "snapshots").dir());
    }
}
