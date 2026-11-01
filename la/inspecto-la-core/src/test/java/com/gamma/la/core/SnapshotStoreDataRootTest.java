package com.gamma.la.core;

import com.gamma.config.safety.DiscoveredRoots;
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

/**
 * SPACE-DATA-ROOT-CONFIG-AUDIT-1: snapshots and Investigations live under the data root's {@code audit/snapshots}
 * once the Space has one; what the old {@code config/audit/snapshots} holds is moved on first open, or still read.
 */
class SnapshotStoreDataRootTest {

    @TempDir Path tmp;
    Path base;
    Path config;
    Path dataRoot;

    @BeforeEach
    void setUp() throws Exception {
        base = tmp.resolve("spaces/demo");
        config = Files.createDirectories(base.resolve("config"));
        dataRoot = Files.createDirectories(tmp.resolve("mnt/demo"));
        DiscoveredRoots.register("demo", base);
        DiscoveredRoots.registerDataRoot("demo", dataRoot);
    }

    @AfterEach
    void tearDown() {
        DiscoveredRoots.clear();
    }

    @Test
    void newSnapshotsLandUnderTheDataRoot() throws Exception {
        SnapshotStore store = new SnapshotStore(config);
        assertTrue(store.create("snap-1", "{}"));
        store.attach("snap-1", "case-1", "t");
        assertTrue(Files.isRegularFile(dataRoot.resolve("audit/snapshots/snap-1.json")));
        assertTrue(Files.isRegularFile(dataRoot.resolve("audit/snapshots/attachments.jsonl")));
        assertFalse(Files.exists(config.resolve("audit")), "nothing written into the config tree");
        assertEquals(dataRoot.resolve("audit/snapshots"), store.directory());
    }

    @Test
    void existingSnapshotsAndInvestigationsAreMovedOnFirstOpen() throws Exception {
        Path old = Files.createDirectories(config.resolve("audit/snapshots/investigations/inv-1"));
        Files.writeString(config.resolve("audit/snapshots/snap-old.json"), "{\"old\":true}");
        Files.writeString(old.resolve("header.json"), "{\"id\":\"inv-1\"}");
        Files.writeString(old.resolve("log.jsonl"), "{\"op\":\"a\"}\n");

        SnapshotStore store = new SnapshotStore(config);
        assertEquals("{\"old\":true}", store.read("snap-old"));
        FsInvestigationLayout layout = new FsInvestigationLayout(config);
        assertEquals(List.of("inv-1"), layout.listInvestigations());
        assertEquals(List.of("{\"op\":\"a\"}"), layout.readLog("inv-1"));
        assertEquals(dataRoot.resolve("audit/snapshots/investigations/inv-1"), layout.investigationDir("inv-1"));
        assertFalse(Files.exists(config.resolve("audit/snapshots")));
    }

    @Test
    void whatOnlyTheOldLocationHoldsIsStillRead() throws Exception {
        SnapshotStore store = new SnapshotStore(config);   // moves nothing: the old tree is empty
        FsInvestigationLayout layout = new FsInvestigationLayout(config);
        assertTrue(store.create("snap-new", "{}"));
        // written into the old place after the move ran (an older process, or a file the move could not take)
        Path old = Files.createDirectories(config.resolve("audit/snapshots/investigations/inv-old"));
        Files.writeString(config.resolve("audit/snapshots/snap-old.json"), "{\"old\":true}");
        Files.writeString(old.resolve("header.json"), "{\"id\":\"inv-old\"}");

        int[] total = new int[1];
        assertEquals(2, store.list(10, total).size(), "the old-only snapshot is listed");
        assertEquals(2, total[0]);
        assertEquals(List.of("inv-old"), layout.listInvestigations());
        assertEquals("{\"id\":\"inv-old\"}", layout.readInvestigation("inv-old"));
        assertEquals(dataRoot.resolve("audit/snapshots/investigations/inv-old"), layout.investigationDir("inv-old"),
                "a late Investigation is moved whole when touched, never split across two places");
        assertEquals("{\"old\":true}", store.read("snap-old"));
        assertTrue(Files.isRegularFile(dataRoot.resolve("audit/snapshots/snap-old.json")));
        assertFalse(Files.exists(old), "nothing left behind");
    }

    @Test
    void anInvestigationTheMoveCannotTakeIsUsedWholeWhereItIs() throws Exception {
        Path old = Files.createDirectories(config.resolve("audit/snapshots/investigations/inv-1"));
        Files.writeString(old.resolve("header.json"), "{\"id\":\"inv-1\"}");
        Files.writeString(old.resolve("log.jsonl"), "{\"op\":\"a\"}\n");
        Files.createDirectories(dataRoot.resolve("audit/snapshots"));
        Files.writeString(dataRoot.resolve("audit/snapshots/investigations"), "a file where the move needs a directory");

        FsInvestigationLayout layout = new FsInvestigationLayout(config);
        assertEquals(List.of("inv-1"), layout.listInvestigations());
        assertEquals(old, layout.investigationDir("inv-1"), "read where it is, never a second empty copy");
        assertEquals("{\"id\":\"inv-1\"}", layout.readInvestigation("inv-1"));
        assertEquals(List.of("{\"op\":\"a\"}"), layout.readLog("inv-1"));
        assertFalse(new FsInvestigationStore(config).create("inv-1", "{}"),
                "the store's containment accepts the old place, and an existing id is still a 409");
    }

    @Test
    void withoutADataRootNothingChanges() throws Exception {
        DiscoveredRoots.registerDataRoot("demo", base);   // equal to the base = not moved
        Files.createDirectories(config.resolve("audit/snapshots"));
        Files.writeString(config.resolve("audit/snapshots/snap-old.json"), "{}");
        SnapshotStore store = new SnapshotStore(config);
        assertTrue(store.create("snap-new", "{}"));
        assertEquals(config.resolve("audit/snapshots"), store.directory());
        assertTrue(Files.isRegularFile(config.resolve("audit/snapshots/snap-old.json")));
        assertTrue(Files.isRegularFile(config.resolve("audit/snapshots/snap-new.json")));
        assertFalse(Files.exists(base.resolve("audit")));
    }
}
