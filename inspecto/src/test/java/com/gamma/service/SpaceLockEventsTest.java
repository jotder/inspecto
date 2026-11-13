package com.gamma.service;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LC-4 / LC-6 of `SPACE-LINK-CENSUS-1` — the Space lock emits {@code CONFIG_LINK_BROKEN} once per newly broken link
 * and {@code CONFIG_CHANGED_OUTSIDE} once per file newly changed outside the server, into the Space's own event log,
 * and never again for a finding the previous lock already held.
 */
class SpaceLockEventsTest {

    private static final String SPACE = "lc-events";
    private final List<Event> seen = new CopyOnWriteArrayList<>();
    private final EventLog log = EventLog.create();

    @AfterEach
    void unregister() {
        EventLog.unregister(SPACE);
    }

    private Path space(Path root) throws Exception {
        Path config = Files.createDirectories(root.resolve(SPACE).resolve("config"));
        Files.writeString(config.resolve("orphan_job.toon"), "job:\n  name: orphan\n  type: noop\n  on_pipeline: gone\n");
        EventLog.register(SPACE, log);
        log.addSubscriber(seen::add);
        return config;
    }

    private List<Event> of(String type) {
        return seen.stream().filter(e -> type.equals(e.type())).toList();
    }

    @Test
    void aBrokenLinkIsEmittedOnceAtTheFirstComputationAndNeverAgain(@TempDir Path root) throws Exception {
        Path config = space(root);
        SpaceLock.compute(SPACE, config, List.of());
        List<Event> broken = of(EventType.CONFIG_LINK_BROKEN);
        assertEquals(1, broken.size(), seen.toString());
        Event e = broken.get(0);
        assertEquals(EventLevel.WARN, e.level());
        assertEquals("space-lock", e.source());
        assertEquals(SPACE, e.attributes().get("space"));
        assertEquals("orphan_job.toon", e.attributes().get("path"));
        assertEquals("on_pipeline", e.attributes().get("key"));
        assertEquals("gone", e.attributes().get("target"));
        assertEquals("pipeline", e.attributes().get("toKind"));
        assertTrue(of(EventType.CONFIG_CHANGED_OUTSIDE).isEmpty(), "no baseline: nothing is marked outside");

        SpaceLock.compute(SPACE, config, List.of());
        SpaceLock.compute(SPACE, config, List.of());
        assertEquals(1, of(EventType.CONFIG_LINK_BROKEN).size(), "the previous lock holds it: not re-emitted");
    }

    @Test
    void aHandEditIsEmittedOnceWithTheLinksItBroke(@TempDir Path root) throws Exception {
        Path config = space(root);
        SpaceLock.compute(SPACE, config, List.of());
        seen.clear();

        Path hand = config.resolve("hand_job.toon");
        Files.writeString(hand, "job:\n  name: hand\n  type: noop\n  on_pipeline: also_gone\n");
        Files.setLastModifiedTime(hand, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        SpaceLock.compute(SPACE, config, List.of());

        List<Event> outside = of(EventType.CONFIG_CHANGED_OUTSIDE);
        assertEquals(1, outside.size(), seen.toString());
        assertEquals(EventLevel.INFO, outside.get(0).level());
        assertEquals("hand_job.toon", outside.get(0).attributes().get("path"));
        assertEquals("added", outside.get(0).attributes().get("change"));
        assertEquals("job", outside.get(0).attributes().get("kind"));
        assertEquals("1", outside.get(0).attributes().get("brokenLinks"));
        List<Event> broken = of(EventType.CONFIG_LINK_BROKEN);
        assertEquals(1, broken.size(), "only the NEW broken link, not orphan's again: " + broken);
        assertEquals("also_gone", broken.get(0).attributes().get("target"));

        seen.clear();
        SpaceLock.compute(SPACE, config, List.of());
        assertTrue(seen.isEmpty(), "an unchanged file keeps its mark but is not emitted again: " + seen);

        Files.delete(hand);
        SpaceLock.compute(SPACE, config, List.of());
        assertEquals(List.of("removed"), of(EventType.CONFIG_CHANGED_OUTSIDE).stream()
                .map(e -> e.attributes().get("change")).toList());
    }

    /** The accept path (LC-6): an accepted file leaves changedOutside; the re-signed lock stays a verified baseline. */
    @Test
    void anAcceptedHandEditLeavesTheReportAndTheLockStillVerifies(@TempDir Path root) throws Exception {
        Path config = space(root);
        SpaceLock.compute(SPACE, config, List.of());
        for (String n : List.of("a", "b")) {
            Path f = config.resolve(n + "_job.toon");
            Files.writeString(f, "job:\n  name: " + n + "\n  type: noop\n");
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
        }
        try {
            SpaceLockAccept.Result unknown = SpaceLockAccept.accept(SPACE, config, List.of("nope.toon"));
            assertEquals(List.of("nope.toon"), unknown.unknown());

            SpaceLockAccept.Result one = SpaceLockAccept.accept(SPACE, config, List.of("a_job.toon", "orphan_job.toon"));
            assertEquals(List.of("a_job.toon"), one.accepted().stream().map(m -> m.get("path")).toList());
            assertEquals(List.of("orphan_job.toon"), one.notChangedOutside());
            assertEquals(1, one.changedOutsideLeft());

            String[] note = {null};
            assertNotNull(SpaceLock.readVerified(config, SpaceLock.file(config), note), "the accepted lock verifies: " + note[0]);
            SpaceLock.Report next = SpaceLock.compute(SPACE, config, List.of());
            assertEquals("lock", next.body().get("baseline"));
            assertEquals(1, ((java.util.Map<?, ?>) next.body().get("changedOutside")).get("total"), next.body().toString());

            SpaceLockAccept.Result all = SpaceLockAccept.accept(SPACE, config, null);
            assertEquals(List.of("b_job.toon"), all.accepted().stream().map(m -> m.get("path")).toList());
            assertEquals(0, all.changedOutsideLeft());
            assertEquals(0, ((java.util.Map<?, ?>) SpaceLock.compute(SPACE, config, List.of()).body().get("changedOutside")).get("total"));
        } finally {
            SpaceLock.forget(config);
        }
    }
}
