package com.gamma.acquire;

import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-D per-process gap dedup: a persistent gap fires once; a filled-then-reopened gap fires again. */
class GapTrackerTest {

    @Test
    void firstSightingFiresAllThenPersistentGapsAreSuppressed() {
        GapTracker t = new GapTracker();
        assertEquals(List.of("a", "b"), t.newGaps("S", List.of("a", "b")));
        assertEquals(List.of(), t.newGaps("S", List.of("a", "b")), "same gaps next cycle ⇒ nothing new");
    }

    @Test
    void onlyNewlyMissingKeysFire() {
        GapTracker t = new GapTracker();
        t.newGaps("S", List.of("a"));
        assertEquals(List.of("b"), t.newGaps("S", List.of("a", "b")), "a already reported, only b is fresh");
    }

    @Test
    void filledGapIsForgottenAndRefiresIfItReopens() {
        GapTracker t = new GapTracker();
        t.newGaps("S", List.of("a"));
        assertEquals(List.of(), t.newGaps("S", List.of()), "gap filled ⇒ pruned, nothing to fire");
        assertEquals(List.of("a"), t.newGaps("S", List.of("a")), "reopened ⇒ a genuine new hole, fires again");
    }

    @Test
    void perSourceIsolation() {
        GapTracker t = new GapTracker();
        assertEquals(List.of("a"), t.newGaps("S1", List.of("a")));
        assertEquals(List.of("a"), t.newGaps("S2", List.of("a")), "S2 has its own memory");
    }

    @Test
    void resetClearsMemory() {
        GapTracker t = new GapTracker();
        t.newGaps("S", List.of("a"));
        t.reset("S");
        assertEquals(List.of("a"), t.newGaps("S", List.of("a")), "after reset the gap is fresh again");
    }

    /** Run {@code body} as a thread of Space {@code space} — the binding CollectorService.underSpace and ControlApi make. */
    private static void inSpace(String space, Runnable body) {
        String prev = MDC.get(EventLog.SPACE_MDC_KEY);
        MDC.put(EventLog.SPACE_MDC_KEY, space);
        try { body.run(); }
        finally { if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY); else MDC.put(EventLog.SPACE_MDC_KEY, prev); }
    }

    // ── SPACE-UNKEYED-STATICS-1: shared() is per Space, never one process-wide instance ───────────────

    /**
     * The defect: two Spaces tracking a same-named collector shared ONE remembered gap set, so the first
     * Space to report a hole SUPPRESSED the other Space's {@code SEQUENCE_GAP} event for the same key —
     * a missed gap, which is the opposite of what this tracker exists to guarantee.
     */
    @Test
    void aGapReportedInOneSpaceDoesNotSuppressAnotherSpacesSameNamedCollector() {
        inSpace("tenant-a", () -> assertEquals(List.of("7"), GapTracker.shared().newGaps("orders", List.of("7")),
                "tenant-a sees the hole first"));
        inSpace("tenant-a", () -> assertEquals(List.of(), GapTracker.shared().newGaps("orders", List.of("7")),
                "and does not re-report it on the next cycle"));
        inSpace("tenant-b", () -> assertEquals(List.of("7"), GapTracker.shared().newGaps("orders", List.of("7")),
                "tenant-b must still get its OWN first report of the same key"));
        GapTracker.forgetSpace("tenant-a");
        GapTracker.forgetSpace("tenant-b");
    }

    /** forgetSpace releases exactly one Space's remembered gaps. */
    @Test
    void forgetSpaceDropsOnlyThatSpacesTracker() {
        inSpace("gone", () -> GapTracker.shared().newGaps("c", List.of("1")));
        inSpace("stays", () -> GapTracker.shared().newGaps("c", List.of("1")));
        GapTracker.forgetSpace("gone");
        inSpace("gone", () -> assertEquals(List.of("1"), GapTracker.shared().newGaps("c", List.of("1")),
                "a forgotten Space reports the hole afresh"));
        inSpace("stays", () -> assertEquals(List.of(), GapTracker.shared().newGaps("c", List.of("1")),
                "the surviving Space still remembers it"));
        GapTracker.forgetSpace("gone");
        GapTracker.forgetSpace("stays");
    }
}
