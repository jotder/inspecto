package com.gamma.consignment;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Period;

import static org.junit.jupiter.api.Assertions.*;

/** D-9's {@code scope:} vocabulary — pure, so no TempDir and no database. */
class DedupScopeTest {

    @Test
    void absentOrConsignmentMeansTodaysBehaviour() {
        assertFalse(DedupScope.parse(null).isWindowed());
        assertFalse(DedupScope.parse("  ").isWindowed());
        assertFalse(DedupScope.parse("consignment").isWindowed());
        assertFalse(DedupScope.parse("CONSIGNMENT").isWindowed(), "the spelling is not case-sensitive");
        assertInstanceOf(DedupScope.WithinConsignment.class, DedupScope.parse(null));
    }

    @Test
    void aWindowCarriesItsPeriod() {
        DedupScope s = DedupScope.parse("window(P4D)");
        assertTrue(s.isWindowed());
        assertEquals(Period.ofDays(4), ((DedupScope.Window) s).period());
        assertEquals(Period.ofMonths(1), ((DedupScope.Window) DedupScope.parse(" WINDOW(P1M) ")).period());
    }

    @Test
    void aMalformedScopeIsRefusedWithTheShapeItWanted() {
        var e = assertThrows(IllegalArgumentException.class, () -> DedupScope.parse("4 days"));
        assertTrue(e.getMessage().contains("window(<ISO-8601 period>)"), e.getMessage());

        var bad = assertThrows(IllegalArgumentException.class, () -> DedupScope.parse("window(4d)"));
        assertTrue(bad.getMessage().contains("ISO-8601"), bad.getMessage());
    }

    /** ⛔ The amendment's D-9 row: never faked with unbounded history. */
    @Test
    void aWindowThatNeverAdvancesIsRefused() {
        var zero = assertThrows(IllegalArgumentException.class, () -> DedupScope.parse("window(P0D)"));
        assertTrue(zero.getMessage().contains("no unbounded window"), zero.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DedupScope.parse("window(P-4D)"));
    }

    /**
     * 🔴 Windows are anchored on the epoch, not on "today". Anchoring on the run date would give two runs
     * on different days DIFFERENT boundaries for the same record — so one key could be admitted twice by
     * construction, which is the whole thing the ledger exists to prevent.
     */
    @Test
    void theSameRecordFallsInTheSameWindowWhoeverComputesIt() {
        DedupScope.Window w = (DedupScope.Window) DedupScope.parse("window(P4D)");
        LocalDate d = LocalDate.of(2026, 9, 1);

        LocalDate start = w.startFor(d);
        assertEquals(start, w.startFor(start), "a window's own start lies in that window");
        assertEquals(start, w.startFor(start.plusDays(3)), "…and so does its last day");
        assertNotEquals(start, w.startFor(start.plusDays(4)), "the next day opens the next window");
        // The boundary is a multiple of the period from the epoch, so it never drifts with the run date.
        assertEquals(0L, start.toEpochDay() % 4);
    }

    /** The authoring refusal lives beside the vocabulary so a caller cannot forget it. */
    @Test
    void aWindowWithoutAnOrderByIsRefused() {
        DedupScope win = DedupScope.parse("window(P4D)");
        assertNotNull(DedupScope.refusal(win, null));
        assertNotNull(DedupScope.refusal(win, "  "));
        assertTrue(DedupScope.refusal(win, null).contains("order_by"));
        assertTrue(DedupScope.refusal(win, null).contains("DURABLE"),
                "the message must say WHY a window is stricter than a plain dedup");

        assertNull(DedupScope.refusal(win, "event_time DESC"), "with a tie-break it is legal");
        assertNull(DedupScope.refusal(DedupScope.parse(null), null),
                "and an unwindowed dedup keeps order_by OPTIONAL, exactly as before");
    }
}
