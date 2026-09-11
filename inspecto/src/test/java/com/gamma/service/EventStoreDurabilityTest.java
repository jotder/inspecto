package com.gamma.service;

import com.gamma.event.Event;
import com.gamma.event.EventQuery;
import com.gamma.event.EventStore;
import com.gamma.event.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code EVENTS-DURABLE-1}: whether an audited mutation survives a service restart is decided entirely by
 * {@code -Devents.backend}, and this test makes both halves of that a <b>choice</b> rather than an accident.
 *
 * <p>The engine default is {@code memory} — a bounded ring that forgets everything on restart. That is the
 * right default for Personal (zero files, zero configuration), and it is why the Standard/Enterprise
 * launchers emitted by {@code inspecto/package.ps1} pass {@code -Devents.backend=parquet}: the
 * tamper-evident, append-only audit trail those editions sell cannot be a ring that empties on a restart.
 *
 * <p>⚠ The memory case below asserts the <b>drop</b>. It is not a wish — it pins the documented behaviour
 * ({@code observability.md} §3.1) so that a future change to the default has to come here and say so.
 *
 * <p>⛔ Not covered here, deliberately: a parquet backend that <em>cannot open</em> degrades to memory
 * rather than failing the boot ({@link ServiceStores#openEventStore}). Making that fatal is phase A of the
 * signed scale-out plan (§5.1), which hangs it on {@code -Dinspecto.topology=partitioned} — a switch that
 * does not exist in the tree yet. Asserting a boot failure here would pin behaviour nothing implements.
 */
class EventStoreDurabilityTest {

    private static final String KEY = "events.backend";

    /** Run {@code body} with {@code events.backend} set to {@code value} ({@code null} = unset), restoring it. */
    private static void withBackend(String value, Runnable body) {
        String previous = System.getProperty(KEY);
        try {
            if (value == null) System.clearProperty(KEY);
            else System.setProperty(KEY, value);
            body.run();
        } finally {
            if (previous == null) System.clearProperty(KEY);
            else System.setProperty(KEY, previous);
        }
    }

    private static Event auditEvent(String target) {
        return Event.builder(EventType.AUDIT)
                .source(EventStoreDurabilityTest.class.getName())
                .message("pipeline deleted")
                .actor("alice").actorType("user")
                .action("pipeline.deleted").actionCategory("destructive")
                .target("pipeline", target)
                .build();
    }

    /** Append an audit event through a store opened over {@code root}, then close it — one "service run". */
    private static void runAndStop(SpaceRoot root, String target) {
        EventStore store = ServiceStores.openEventStore(root);
        store.append(auditEvent(target));
        try {
            store.close();          // flushes the buffer; this IS the restart boundary
        } catch (Exception e) {
            throw new AssertionError("closing the event store must not throw", e);
        }
    }

    /** The audited mutations a freshly opened store can still see. */
    private static List<Event> auditsAfterRestart(SpaceRoot root) {
        EventStore store = ServiceStores.openEventStore(root);
        try {
            return store.query(EventQuery.recent(EventQuery.MAX_LIMIT)).stream()
                    .filter(e -> EventType.AUDIT.equals(e.type()))
                    .toList();
        } finally {
            try {
                store.close();
            } catch (Exception ignored) {
                // closing a read-only probe cannot fail the assertion under test
            }
        }
    }

    /** What Standard+ bundles get: the audit trail is still there after a restart. */
    @Test
    void parquetKeepsAuditedMutationsAcrossARestart(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir);
        withBackend("parquet", () -> {
            runAndStop(root, "voucher");
            List<Event> survived = auditsAfterRestart(root);
            assertEquals(1, survived.size(),
                    "a restart under -Devents.backend=parquet must not lose an audited mutation — this is "
                            + "the whole reason the Standard/Enterprise launchers set it");
            assertEquals("pipeline.deleted", survived.get(0).attributes().get("action"));
            assertEquals("alice", survived.get(0).attributes().get("actor"),
                    "the actor must survive too: an audit row without its actor answers nothing");
        });
    }

    /** And it lands under the space, not the process CWD — discover mode keeps one trail per space. */
    @Test
    void parquetWritesUnderTheSpaceRootNotTheWorkingDirectory(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir);
        withBackend("parquet", () -> runAndStop(root, "voucher"));
        assertTrue(root.eventsDir().toFile().isDirectory(),
                "the launchers deliberately leave -Devents.dir unset so the trail follows SpaceRoot.eventsDir()");
        assertTrue(root.eventsDir().startsWith(dir),
                "an events dir resolved outside the space would pool every space's audit trail in the CWD");
    }

    /** The default, unchanged: Personal forgets on restart, and that is the documented behaviour. */
    @Test
    void theDefaultBackendDropsTheAuditTrailOnRestart(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir);
        withBackend(null, () -> {
            runAndStop(root, "voucher");
            assertEquals(List.of(), auditsAfterRestart(root),
                    "the default is an in-memory ring and it forgets on restart (observability.md §3.1). "
                            + "If this ever fails, the default changed — update the docs and the launchers, "
                            + "do not delete this assertion");
        });
    }

    /** An unrecognised value is the default, not an error — the same three-value contract the other toggles use. */
    @Test
    void anUnrecognisedBackendFallsBackToTheInMemoryDefault(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir);
        withBackend("postgres", () -> {
            runAndStop(root, "voucher");
            assertEquals(List.of(), auditsAfterRestart(root),
                    "-Devents.backend=postgres is not a backend that exists (it is D6 of the scale-out plan, "
                            + "unbuilt); it must read as the in-memory default rather than half-opening something");
        });
    }
}
