package com.gamma.la.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinkTemporalDetectorTest {

    private static final long S = 1000;

    @Test
    void aDenseRunIsOneMergedBurstNotOnePerAnchor() {
        // 6 events 10 s apart, then one an hour later: a 30 s window holds 4, minEvents 4 -> ONE burst over the six.
        long[] t = {0, 10 * S, 20 * S, 30 * S, 40 * S, 50 * S, 3_600 * S};
        List<LinkTemporalDetector.Burst> b = LinkTemporalDetector.bursts(t, 30 * S, 4);
        assertEquals(List.of(new LinkTemporalDetector.Burst(0, 50 * S, 6)), b);
    }

    @Test
    void separateDenseRunsAreSeparateBurstsAndASparseSeriesHasNone() {
        long[] t = {0, S, 2 * S, 1_000 * S, 1_001 * S, 1_002 * S};
        assertEquals(2, LinkTemporalDetector.bursts(t, 5 * S, 3).size());
        assertEquals(List.of(), LinkTemporalDetector.bursts(new long[]{0, 100 * S, 200 * S, 300 * S}, 5 * S, 2),
                "a window never holds two events here");
    }

    @Test
    void theWindowEdgeIsInclusive() {
        assertEquals(1, LinkTemporalDetector.bursts(new long[]{0, 10 * S}, 10 * S, 2).size());
        assertEquals(0, LinkTemporalDetector.bursts(new long[]{0, 10 * S + 1}, 10 * S, 2).size());
    }

    @Test
    void aRegularSeriesIsPeriodicWithItsMedianGapAndAJitteredOneIsNot() {
        LinkTemporalDetector.Periodic p = LinkTemporalDetector.periodicity(new long[]{0, 60 * S, 120 * S, 180 * S, 240 * S}, 5, 0.1);
        assertNotNull(p);
        assertEquals(60 * S, p.periodMs());
        assertEquals(0.0, p.cv(), 1e-9);
        assertNull(LinkTemporalDetector.periodicity(new long[]{0, 10 * S, 130 * S, 140 * S, 400 * S}, 5, 0.1));
    }

    @Test
    void tooFewEventsAndSimultaneousEventsAreNeverPeriodic() {
        assertNull(LinkTemporalDetector.periodicity(new long[]{0, 60 * S, 120 * S}, 5, 0.1), "below minEvents");
        assertNull(LinkTemporalDetector.periodicity(new long[]{5, 5, 5, 5, 5}, 5, 1.0), "zero-mean gaps");
    }
}
