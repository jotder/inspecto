package com.gamma.service;

import com.gamma.event.Event;
import com.gamma.event.EventQuery;
import com.gamma.event.EventStore;
import com.gamma.event.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
 * <p>⚠ <b>Updated 2026-09-12 (phase A).</b> The note that used to sit here said a degraded backend could
 * not be made fatal because {@code -Dinspecto.topology=partitioned} did not exist. It does now (A1), and
 * {@code StoreHealth.record} throws on a DEGRADED outcome while partitioned — so the degrade-to-memory
 * path below is the <b>single-node</b> behaviour, which remains deliberate. The partitioned refusal is
 * covered where the switch lives, not here.
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

    /**
     * An unrecognised value is the default, not an error — the same three-value contract the other toggles use.
     *
     * <p>⚠ This test used to use {@code postgres} and assert the fallback, on the stated grounds that it
     * "is D6 of the scale-out plan, unbuilt". **D6 shipped (A3, 2026-09-12)**, so {@code postgres} is now a
     * RECOGNISED database backend and asserting a fallback for it would pin the opposite of the truth. The
     * probe therefore has to be a value that is genuinely not a backend — and it must stay that way.
     */
    @Test
    void anUnrecognisedBackendFallsBackToTheInMemoryDefault(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir);
        withBackend("mysql", () -> {
            runAndStop(root, "voucher");
            assertEquals(List.of(), auditsAfterRestart(root),
                    "-Devents.backend=mysql is not a backend that exists; it must read as the in-memory "
                            + "default rather than half-opening something");
        });
    }

    // ── D6: -Devents.backend=db, the shared backend ───────────────────────────────────

    /**
     * The wiring proof for A3. A database-backed event store survives a restart exactly as parquet does —
     * but unlike parquet it is a backend two PROCESSES can share, which is the whole of D6: an event
     * ledger written to one pod's Parquet directory is invisible to every other pod.
     */
    @Test
    void theDatabaseBackendKeepsAuditedMutationsAcrossARestart(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir);
        withBackend("db", () -> {
            runAndStop(root, "voucher");
            List<Event> survived = auditsAfterRestart(root);
            assertEquals(1, survived.size(),
                    "a restart under -Devents.backend=db must not lose an audited mutation");
            assertEquals("alice", survived.get(0).attributes().get("actor"));
        });
    }

    /** The backend is selected case-insensitively, like every other opener's toggle. */
    @Test
    void theDatabaseBackendIsSelectedCaseInsensitively(@TempDir Path dir) {
        SpaceRoot root = SpaceRoot.under(dir);
        withBackend("DB", () -> {
            runAndStop(root, "voucher");
            assertEquals(1, auditsAfterRestart(root).size());
        });
    }

    /**
     * ⛔ A raw {@code jdbc:} value must reach the driver UNCHANGED. Six openers lowercased it before use
     * (fixed 2026-09-11) — Postgres database names, roles and passwords are all case-sensitive, and on a
     * case-sensitive filesystem a lowercased DuckDB path opens a different file. `events` never had the
     * bug; this pins that the {@code db} branch did not introduce it.
     */
    @Test
    void aRawJdbcUrlKeepsItsCaseAndIsUsedVerbatim(@TempDir Path dir) throws Exception {
        Path mixed = dir.resolve("MixedCase");
        Files.createDirectories(mixed);
        String url = "jdbc:duckdb:" + mixed.resolve("Events.db").toString().replace('\\', '/');
        SpaceRoot root = SpaceRoot.under(dir);
        withBackend(url, () -> {
            runAndStop(root, "voucher");
            assertEquals(1, auditsAfterRestart(root).size());
        });
        assertTrue(Files.exists(mixed.resolve("Events.db")),
                "the file must land at the CASED path the operator wrote, not a lowercased one");
    }

}
