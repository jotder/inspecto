package com.gamma.la.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D7-6 - the idle markers and their precedence, the open-Draft count, and the crash sweep of a rebase (fixtures, no HTTP). */
class DraftLifecycleTest {

    private static final class Tick extends Clock {
        volatile Instant now = Instant.parse("2026-10-03T08:00:00Z");

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final Tick tick = new Tick();

    @AfterEach
    void restore() {
        DraftLifecycle.clock = Clock.systemUTC();
    }

    private static String header(String id) {
        return "{\"draftId\":\"" + id + "\",\"actor\":\"a\",\"baseStep\":1}";
    }

    private static Path make(Path inv) throws IOException {
        String id = DraftStore.newId();
        assertTrue(DraftStore.create(inv, id, header(id)));
        return DraftStore.draftDir(inv, id);
    }

    @Test
    void markerPrecedenceIsPromotedThenDiscardedThenHibernatedThenOpen(@TempDir Path inv) throws Exception {
        Path d = make(inv);
        assertEquals("open", DraftLifecycle.state(d));
        assertTrue(DraftLifecycle.hibernate(d));
        assertFalse(DraftLifecycle.hibernate(d), "a second hibernate is a no-op");
        assertEquals("hibernated", DraftLifecycle.state(d));
        // every marker present at once (a crash can leave any mix): the strongest wins
        Files.writeString(d.resolve(DraftStore.DISCARDED), "{\"expired\":true}");
        assertEquals("discarded", DraftLifecycle.state(d));
        assertTrue(DraftLifecycle.wasExpired(d));
        Files.writeString(d.resolve(DraftStore.PROMOTED), "{}");
        assertEquals("promoted", DraftLifecycle.state(d));
    }

    @Test
    void closingADraftDeletesItsIdleMarkers(@TempDir Path inv) throws Exception {
        Path d = make(inv);
        DraftLifecycle.touch(d);
        assertTrue(Files.isRegularFile(d.resolve(DraftLifecycle.ACCESSED)));
        DraftLifecycle.hibernate(d);
        assertTrue(DraftStore.markDiscarded(d, "{}"));
        assertFalse(Files.exists(d.resolve(DraftLifecycle.HIBERNATED)));
        assertFalse(Files.exists(d.resolve(DraftLifecycle.ACCESSED)));
        Path p = make(inv);
        DraftLifecycle.hibernate(p);
        assertTrue(DraftStore.markPromoted(p, "{}"));
        assertFalse(Files.exists(p.resolve(DraftLifecycle.HIBERNATED)));
    }

    @Test
    void idleTimeComesFromTheClockAndSurvivesARestartThroughAccessedJson(@TempDir Path inv) throws Exception {
        DraftLifecycle.clock = tick;
        Path d = make(inv);
        DraftLifecycle.touch(d);
        tick.now = tick.now.plus(Duration.ofMinutes(61));
        assertEquals(Duration.ofMinutes(61), DraftLifecycle.idle(d));
        // a second touch inside the persist interval does not rewrite the file, but memory is exact
        DraftLifecycle.touch(d);
        assertEquals(Duration.ZERO, DraftLifecycle.idle(d));
        // "restart": a new Draft directory object is not in memory, so only accessed.json answers
        Path other = make(inv);
        DraftLifecycle.touch(other);
        String before = Files.readString(other.resolve(DraftLifecycle.ACCESSED));
        tick.now = tick.now.plus(Duration.ofMinutes(1));
        DraftLifecycle.touch(other);
        assertEquals(before, Files.readString(other.resolve(DraftLifecycle.ACCESSED)), "a read does not cost a file write inside the interval");
        tick.now = tick.now.plus(Duration.ofMinutes(10));
        DraftLifecycle.touch(other);
        assertTrue(!before.equals(Files.readString(other.resolve(DraftLifecycle.ACCESSED))), "the persisted value is refreshed after the interval");
    }

    @Test
    void openIdsAndCountOpenReadNoHeaderAndCountHibernatedButNotClosed(@TempDir Path investigations) throws Exception {
        Path inv1 = investigations.resolve("inv-1"), inv2 = investigations.resolve("inv-2");
        Files.createDirectories(inv1);
        Files.createDirectories(inv2);
        Path a = make(inv1), b = make(inv1), c = make(inv2);
        DraftLifecycle.hibernate(b);
        DraftStore.markDiscarded(c, "{}");
        make(inv2);
        DraftStore.headerReads.set(0);
        assertEquals(3, DraftStore.countOpen(investigations), "a, hibernated b and the new one; the discarded c is closed");
        assertEquals(0, DraftStore.headerReads.get());
        assertEquals(2, DraftStore.openIds(inv1).size());
    }

    // -- the crash sweep -----------------------------------------------------------------------------------------------

    private static Path aside(Path inv, String id) {
        return DraftStore.draftsDir(inv).resolve(".old-" + id + "-" + UUID.randomUUID());
    }

    @Test
    void anAsideWithNoLiveDraftIsMovedBackAfterItsHeaderIsVerified(@TempDir Path inv) throws Exception {
        Path d = make(inv);
        String id = d.getFileName().toString();
        Files.writeString(d.resolve("log.jsonl"), "{\"step\":2}\n");
        Path aside = aside(inv, id);
        Files.move(d, aside);   // the crash between the two renames
        int[] r = DraftStore.recover(inv, Instant.now(), Duration.ofHours(1), p -> p);
        assertEquals(1, r[0]);
        assertTrue(Files.isRegularFile(d.resolve("log.jsonl")), "the pre-rebase Draft is whole again");
        assertFalse(Files.exists(aside));
    }

    @Test
    void anAsideWhoseHeaderDoesNotNameTheDraftIsLeftAloneAndCounted(@TempDir Path inv) throws Exception {
        String id = DraftStore.newId();
        Path aside = aside(inv, id);
        Files.createDirectories(aside);
        Files.writeString(aside.resolve(DraftStore.HEADER), header(DraftStore.newId()));   // some OTHER draft's header
        int[] r = DraftStore.recover(inv, Instant.now(), Duration.ofHours(1), p -> p);
        assertEquals(0, r[0]);
        assertEquals(1, r[2]);
        assertTrue(Files.isDirectory(aside), "nothing is deleted or moved on unverified evidence");
        assertFalse(Files.exists(DraftStore.draftDir(inv, id)));
    }

    @Test
    void anAsideBesideALiveDraftWithItsHeaderIsDeleted(@TempDir Path inv) throws Exception {
        Path d = make(inv);
        Path aside = aside(inv, d.getFileName().toString());
        Files.createDirectories(aside);
        Files.writeString(aside.resolve(DraftStore.HEADER), header(d.getFileName().toString()));   // the crash fell after the 2nd rename
        int[] r = DraftStore.recover(inv, Instant.now(), Duration.ofHours(1), p -> p);
        assertEquals(1, r[1]);
        assertFalse(Files.exists(aside));
        assertTrue(Files.isRegularFile(d.resolve(DraftStore.HEADER)), "the live Draft is untouched");
    }

    @Test
    void aLiveDraftWithoutAHeaderIsNeverReplacedByItsAside(@TempDir Path inv) throws Exception {
        String id = DraftStore.newId();
        Files.createDirectories(DraftStore.draftDir(inv, id));   // a live directory with no header: unexplained
        Path aside = aside(inv, id);
        Files.createDirectories(aside);
        Files.writeString(aside.resolve(DraftStore.HEADER), header(id));
        int[] r = DraftStore.recover(inv, Instant.now(), Duration.ofHours(1), p -> p);
        assertEquals(1, r[2]);
        assertTrue(Files.isDirectory(aside));
    }

    @Test
    void anUnfinishedStageIsDeletedOnlyAfterTheGracePeriod(@TempDir Path inv) throws Exception {
        Path root = DraftStore.draftsDir(inv);
        Files.createDirectories(root);
        Path stage = Files.createTempDirectory(root, ".rebase-");
        Path fork = Files.createTempDirectory(root, ".fork-");
        int[] early = DraftStore.recover(inv, Instant.now(), Duration.ofHours(1), p -> p);
        assertEquals(0, early[1], "a stage younger than the grace period may be a live rebase");
        assertTrue(Files.isDirectory(stage) && Files.isDirectory(fork));
        int[] late = DraftStore.recover(inv, Instant.now().plus(Duration.ofHours(2)), Duration.ofHours(1), p -> p);
        assertEquals(2, late[1]);
        assertFalse(Files.exists(stage) || Files.exists(fork));
    }

    @Test
    void recoveryTakesTheSameMonitorARebaseHoldsWhileItSwaps(@TempDir Path inv) throws Exception {
        Path d = make(inv);
        Path aside = aside(inv, d.getFileName().toString());
        Files.move(d, aside);
        Object monitor = new Object();
        boolean[] done = new boolean[1];
        Thread t;
        synchronized (monitor) {   // a rebase mid-swap holds it
            t = new Thread(() -> {
                try {
                    DraftStore.recover(inv, Instant.now(), Duration.ofHours(1), p -> monitor);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                done[0] = true;
            });
            t.start();
            Thread.sleep(150);
            assertFalse(done[0], "the sweep waits for the rebase instead of moving its aside");
            assertTrue(Files.isDirectory(aside));
        }
        t.join(10_000);
        assertTrue(done[0]);
        assertTrue(Files.isDirectory(d));
    }
}
