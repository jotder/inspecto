package com.gamma.etl;

import com.gamma.util.CurrentSpace;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code SPACE-UNKEYED-STATICS-1}, second half — the live progress snapshots are keyed by
 * <b>(space, pipeline)</b>, never by pipeline alone.
 *
 * <p>The defect: two Spaces running a same-named pipeline — which is exactly what applying one Space
 * template twice produces, since the shipped template names its pipeline — overwrote each other's snapshot,
 * so one tenant's screen reported the other tenant's progress.
 *
 * <p>⚠ This half could not be fixed the way the circuit breaker and gap tracker were. Those live in
 * {@code inspecto-acquire}, which can read {@code EventLog.currentSpaceId()}; these live in
 * {@code inspecto-etl}, and {@code inspecto-event} <b>depends on</b> {@code inspecto-etl}, so reaching the
 * space id there would have been a dependency <b>cycle</b>. The space id therefore moved to
 * {@link CurrentSpace} in {@code inspecto-util}, which both modules already depend on (operator decision,
 * 2026-09-10, option A), and {@code EventLog.currentSpaceId()} delegates to it.
 */
class ProgressSpaceIsolationTest {

    /** Run {@code body} as a thread of Space {@code space} — the binding the collector service and control plane make. */
    private static void inSpace(String space, Runnable body) {
        String prev = MDC.get(CurrentSpace.SPACE_MDC_KEY);
        MDC.put(CurrentSpace.SPACE_MDC_KEY, space);
        try { body.run(); }
        finally { if (prev == null) MDC.remove(CurrentSpace.SPACE_MDC_KEY); else MDC.put(CurrentSpace.SPACE_MDC_KEY, prev); }
    }

    @Test
    void ingestProgressIsPerSpaceForASameNamedPipeline() {
        inSpace("tenant-a", () -> IngestProgress.track("orders", "b-a", "a.csv", 1, 10));
        inSpace("tenant-b", () -> IngestProgress.track("orders", "b-b", "b.csv", 7, 9));

        inSpace("tenant-a", () -> {
            IngestProgress.Snapshot s = IngestProgress.current("orders");
            assertNotNull(s, "tenant-a must still see its own snapshot");
            assertEquals("a.csv", s.file(), "tenant-b's tracking must not have overwritten tenant-a's");
            assertEquals(1, s.index());
        });
        inSpace("tenant-b", () -> {
            IngestProgress.Snapshot s = IngestProgress.current("orders");
            assertNotNull(s);
            assertEquals("b.csv", s.file());
            assertEquals(7, s.index());
        });
        assertNull(IngestProgress.current("orders"),
                "the default Space (no MDC) is a third key and nothing tracked into it");

        IngestProgress.forgetSpace("tenant-a");
        IngestProgress.forgetSpace("tenant-b");
    }

    @Test
    void stepProgressIsPerSpaceForASameNamedPipeline() {
        inSpace("tenant-a", () -> StepProgress.track("orders", "c-a", "parse", 1, 3));
        inSpace("tenant-b", () -> StepProgress.track("orders", "c-b", "sink", 3, 3));

        inSpace("tenant-a", () -> assertEquals("parse", StepProgress.current("orders").step(),
                "tenant-b reaching the sink must not move tenant-a's step gauge"));
        inSpace("tenant-b", () -> assertEquals("sink", StepProgress.current("orders").step()));
        assertNull(StepProgress.current("orders"), "the default Space is untouched");

        StepProgress.forgetSpace("tenant-a");
        StepProgress.forgetSpace("tenant-b");
    }

    /** {@code clear} is per Space too — finishing a run in one Space must not blank another's gauge. */
    @Test
    void clearingOneSpacesRunLeavesTheOtherSpacesSnapshot() {
        inSpace("tenant-a", () -> IngestProgress.track("orders", "b-a", "a.csv", 1, 10));
        inSpace("tenant-b", () -> IngestProgress.track("orders", "b-b", "b.csv", 2, 10));

        inSpace("tenant-a", () -> IngestProgress.clear("orders"));

        inSpace("tenant-a", () -> assertNull(IngestProgress.current("orders"), "tenant-a cleared its own"));
        inSpace("tenant-b", () -> assertNotNull(IngestProgress.current("orders"),
                "tenant-b's run is still in flight — clearing tenant-a must not blank it"));

        IngestProgress.forgetSpace("tenant-b");
    }

    /** forgetSpace drops exactly one Space's snapshots, so a deleted Space neither leaks nor takes a sibling with it. */
    @Test
    void forgetSpaceDropsOnlyThatSpacesSnapshots() {
        inSpace("gone", () -> {
            IngestProgress.track("p", "b1", "f", 1, 2);
            StepProgress.track("p", "c1", "parse", 1, 2);
        });
        inSpace("stays", () -> {
            IngestProgress.track("p", "b2", "g", 1, 2);
            StepProgress.track("p", "c2", "sink", 2, 2);
        });

        IngestProgress.forgetSpace("gone");
        StepProgress.forgetSpace("gone");

        inSpace("gone", () -> {
            assertNull(IngestProgress.current("p"), "a forgotten Space keeps nothing");
            assertNull(StepProgress.current("p"));
        });
        inSpace("stays", () -> {
            assertNotNull(IngestProgress.current("p"), "the surviving Space keeps its own");
            assertNotNull(StepProgress.current("p"));
        });

        IngestProgress.forgetSpace("stays");
        StepProgress.forgetSpace("stays");
    }

    /** The delegation is the point of option A: one value, reachable from a module that cannot see EventLog. */
    @Test
    void anUnboundThreadIsTheDefaultSpaceAndNeverNull() {
        assertEquals(CurrentSpace.DEFAULT_SPACE_ID, CurrentSpace.id(),
                "an unbound thread must resolve to the default space, or a computeIfAbsent key would be null");
        inSpace("tenant-a", () -> assertEquals("tenant-a", CurrentSpace.id()));
        inSpace("", () -> assertEquals(CurrentSpace.DEFAULT_SPACE_ID, CurrentSpace.id(),
                "an EMPTY binding is the default space too, not an empty-string key"));
    }
}
