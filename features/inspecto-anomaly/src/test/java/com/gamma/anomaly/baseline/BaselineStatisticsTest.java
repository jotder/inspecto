package com.gamma.anomaly.baseline;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Planted-pattern tests for the S2 baseline statistics. Each mutation check names the mutant it kills. */
class BaselineStatisticsTest {

    /** 2026-09-07 is a Monday. */
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 7, 0, 0);

    /** 28 daily buckets plus the scored day: ordinary days 10..12, every Monday 100..102 (a planted weekday spike). */
    private static Map<LocalDateTime, Double> mondaySpikeHistory() {
        Map<LocalDateTime, Double> h = new LinkedHashMap<>();
        for (int d = 0; d < 28; d++) {
            LocalDateTime t = START.plusDays(d);
            double base = t.getDayOfWeek() == DayOfWeek.MONDAY ? 100 : 10;
            h.put(t, base + d % 3);
        }
        return h;
    }

    private static final LocalDateTime SCORED_MONDAY = START.plusDays(28);

    @Test
    void weeklySpikeIsNormalUnderWeekdaySeasonalityButHighWithout() {
        Map<LocalDateTime, Double> h = mondaySpikeHistory();
        h.put(SCORED_MONDAY, 101.0); // the scored bucket itself must be excluded
        // weekday seasonality needs only the 4 prior Mondays here
        Baseline weekday = new SeasonalBaseline(Seasonality.WEEKDAY, 4).compute(h, SCORED_MONDAY);
        Baseline none = new SeasonalBaseline(Seasonality.NONE, 4).compute(h, SCORED_MONDAY);

        assertEquals("MONDAY", weekday.basis());
        assertEquals(4, weekday.points(), "scored bucket excluded, only Mondays used");
        assertFalse(weekday.fellBack());
        assertTrue(Math.abs(weekday.z(101, 1)) < 2, "Monday look-alike is normal under weekday: " + weekday.z(101, 1));

        assertEquals(28, none.points());
        assertTrue(none.z(101, 1) > 10, "without seasonality the Monday is far above: " + none.z(101, 1));
    }

    @Test
    void weekdayHourFallsBackToWeekdayThenNoneAndSaysSo() {
        Map<LocalDateTime, Double> h = mondaySpikeHistory(); // all at 00h; score a 03h bucket
        LocalDateTime scored = SCORED_MONDAY.withHour(3);

        Baseline toWeekday = new SeasonalBaseline(Seasonality.WEEKDAY_HOUR, 4).compute(h, scored);
        assertTrue(toWeekday.fellBack());
        assertEquals("MONDAY 03h", toWeekday.requested());
        assertEquals("MONDAY", toWeekday.basis());
        assertTrue(toWeekday.reason().contains("fell back to MONDAY"), toWeekday.reason());

        Baseline toNone = new SeasonalBaseline(Seasonality.WEEKDAY_HOUR, 7).compute(h, scored);
        assertEquals("none", toNone.basis());
        assertEquals(28, toNone.points());

        Baseline hourToNone = new SeasonalBaseline(Seasonality.HOUR, 7).compute(h, scored);
        assertEquals("none", hourToNone.basis());
        assertTrue(hourToNone.fellBack());

        Baseline exactCell = new SeasonalBaseline(Seasonality.WEEKDAY_HOUR, 4).compute(h, SCORED_MONDAY);
        assertFalse(exactCell.fellBack(), "a cell with enough history does not fall back");
        assertEquals("MONDAY 00h", exactCell.basis());
    }

    @Test
    void tooLittleHistoryIsInsufficientNotScored() {
        Map<LocalDateTime, Double> h = new HashMap<>();
        for (int d = 0; d < 3; d++) h.put(START.plusDays(d), 10.0);
        Baseline b = new SeasonalBaseline(Seasonality.NONE, SeasonalBaseline.DEFAULT_MIN_POINTS).compute(h, START.plusDays(5));
        assertTrue(b.insufficient());
        assertEquals(3, b.points());
        assertTrue(Double.isNaN(b.z(50, 1)));
    }

    @Test
    void medianNotMeanSoOneOutlierCannotHideASecondSpike() {
        // mutant killed: median -> mean (the earlier spike would drag the centre up and mask this one)
        Map<LocalDateTime, Double> h = new HashMap<>();
        for (int d = 0; d < 9; d++) h.put(START.plusDays(d), 10.0 + d % 2);
        h.put(START.plusDays(9), 1000.0);
        Baseline b = new SeasonalBaseline(Seasonality.NONE, 7).compute(h, START.plusDays(10));
        assertEquals(10.5, b.median(), 1e-9);
        assertTrue(b.z(200, 1) > 50, "second spike still far above: " + b.z(200, 1));
    }

    @Test
    void madFloorKeepsAFlatHistoryFinite() {
        // mutant killed: MAD floor removed (a flat history, MAD = 0, would make +1 infinite)
        Map<LocalDateTime, Double> h = new HashMap<>();
        for (int d = 0; d < 10; d++) h.put(START.plusDays(d), 10.0);
        Baseline b = new SeasonalBaseline(Seasonality.NONE, 7).compute(h, START.plusDays(10));
        assertEquals(0.0, b.scaledMad());
        assertEquals(1.0, b.z(11, 1), 1e-9, "floor = max(1 unit, 5% of 10) = 1");
        assertEquals(1.0, RobustStats.floor(10, 1));
        assertEquals(50.0, RobustStats.floor(1000, 1), "5 % of |median| wins for a large median");
    }

    @Test
    void scaledMadIsConsistentWithSigma() {
        assertEquals(1.4826, RobustStats.scaledMad(List.of(1.0, 2.0, 3.0), 2.0), 1e-9);
    }

    // ---- peers ----

    private static Map<String, Double> observed = new HashMap<>();
    private static Map<String, String> cohorts = new HashMap<>();

    private static void population() {
        observed = new HashMap<>();
        cohorts = new HashMap<>();
        for (int i = 0; i < 40; i++) { observed.put("big" + i, 20.0 + i % 5); cohorts.put("big" + i, "PREPAID_S"); }
        for (int i = 0; i < 5; i++) { observed.put("small" + i, 500.0 + i); cohorts.put("small" + i, "VIP"); }
    }

    @Test
    void largeCohortGivesAPeerBaseline() {
        population();
        observed.put("sim", 640.0);
        cohorts.put("sim", "PREPAID_S");
        Baseline p = new PeerBaseline(30, false).compute(observed, cohorts, "PREPAID_S");
        assertFalse(p.insufficient());
        assertEquals(41, p.points());
        assertEquals("PREPAID_S", p.basis());
        assertTrue(p.z(640, 1) > 50);
        assertTrue(Math.abs(p.z(22, 1)) < 1, "an ordinary member is not unusual");
    }

    @Test
    void smallCohortIsRefusedUnlessPopulationFallbackOptedIn() {
        population();
        Baseline refused = new PeerBaseline(30, false).compute(observed, cohorts, "VIP");
        assertTrue(refused.insufficient(), "D-AD11 (a): a 5-entity cohort never silently scores");
        assertEquals(5, refused.points());
        assertTrue(Double.isNaN(refused.z(502, 1)));

        Baseline fallback = new PeerBaseline(30, true).compute(observed, cohorts, "VIP");
        assertFalse(fallback.insufficient());
        assertTrue(fallback.fellBack(), "D-AD11 (b) is flagged");
        assertEquals(PeerBaseline.POPULATION, fallback.basis());
        assertEquals("VIP", fallback.requested());
        assertEquals(45, fallback.points());

        Baseline tooSmallEvenForPopulation = new PeerBaseline(100, true).compute(observed, cohorts, "VIP");
        assertTrue(tooSmallEvenForPopulation.insufficient());
    }

    @Test
    void noSelfHistoryScoresAgainstPeersOnlyAndFlagsIt() {
        population();
        observed.put("newsim", 640.0);
        cohorts.put("newsim", "PREPAID_S");
        Baseline self = new SeasonalBaseline(Seasonality.WEEKDAY, 7).compute(Map.of(), SCORED_MONDAY);
        Baseline peer = new PeerBaseline(30, false).compute(observed, cohorts, "PREPAID_S");

        BaselineScore s = BaselineScore.of(640, self, peer, 1);
        assertTrue(s.peersOnly(), "D-AD12: flagged as peer-only");
        assertFalse(s.insufficient());
        assertTrue(Double.isNaN(s.zSelf()));
        assertTrue(s.zPeer() > 50);

        BaselineScore noPeers = BaselineScore.of(640, self, null, 1);
        assertFalse(noPeers.peersOnly());
        assertTrue(noPeers.insufficient(), "no self, no peers: insufficient, contributes 0");

        Baseline smallPeer = new PeerBaseline(30, false).compute(observed, cohorts, "VIP");
        assertTrue(BaselineScore.of(640, self, smallPeer, 1).insufficient());
    }

    @Test
    void withSelfHistoryItIsNotPeersOnly() {
        population();
        Map<LocalDateTime, Double> h = mondaySpikeHistory();
        Baseline self = new SeasonalBaseline(Seasonality.WEEKDAY, 4).compute(h, SCORED_MONDAY);
        Baseline peer = new PeerBaseline(30, false).compute(observed, cohorts, "PREPAID_S");
        BaselineScore s = BaselineScore.of(101, self, peer, 1);
        assertFalse(s.peersOnly());
        assertFalse(s.insufficient());
        assertFalse(Double.isNaN(s.zSelf()));
    }

    @Test
    void rejectsBadParameters() {
        assertThrows(IllegalArgumentException.class, () -> new SeasonalBaseline(null, 7));
        assertThrows(IllegalArgumentException.class, () -> new SeasonalBaseline(Seasonality.NONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new PeerBaseline(0, false));
        assertThrows(IllegalArgumentException.class, () -> RobustStats.median(List.of()));
    }
}
