package com.gamma.analytics.cohort;

import static org.junit.jupiter.api.Assertions.*;

import com.gamma.analytics.time.TimeGrain;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Hand-computed retention and funnel figures (the rules are in the two classes' notes). */
class CohortTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Retention.Event ev(String entity, String day) {
        return new Retention.Event(entity, Instant.parse(day + "T12:00:00Z"));
    }

    private static List<Retention.Event> events() {
        return List.of(ev("u1", "2026-01-05"), ev("u1", "2026-02-10"), ev("u1", "2026-04-01"),
                ev("u2", "2026-01-20"), ev("u2", "2026-03-03"),
                ev("u3", "2026-02-02"),
                ev("u4", "2026-02-15"), ev("u4", "2026-02-20"), ev("u4", "2026-03-01"),
                ev("u5", "2026-03-30"),
                ev("u6", "2026-05-02"),                                   // after the as-of: not observed
                new Retention.Event(null, Instant.parse("2026-01-01T00:00:00Z")),
                new Retention.Event("u7", null));                          // ignored
    }

    private static Retention.Params params(Retention.Mode mode, int maxPeriods, LocalDateTime from) {
        return new Retention.Params(TimeGrain.MONTH, UTC, LocalDateTime.of(2026, 4, 15, 0, 0), from, mode, maxPeriods);
    }

    /** {@code [cohort month, period, size, active]} rows. */
    private static List<String> rows(List<Retention.Cell> cells) {
        List<String> out = new ArrayList<>();
        for (Retention.Cell c : cells) out.add(c.cohort().getMonthValue() + ":" + c.period() + ":" + c.size() + ":" + c.active());
        return out;
    }

    @Test
    void exactRetentionByMonthCohort() {
        assertEquals(List.of("1:0:2:2", "1:1:2:1", "1:2:2:1", "1:3:2:1",
                        "2:0:2:2", "2:1:2:1", "2:2:2:0",
                        "3:0:1:1", "3:1:1:0"),
                rows(Retention.compute(events(), params(Retention.Mode.EXACT, 120, null))));
    }

    @Test
    void unboundedRetentionNeverRisesWithThePeriod() {
        List<Retention.Cell> cells = Retention.compute(events(), params(Retention.Mode.UNBOUNDED, 120, null));
        assertEquals(List.of("1:0:2:2", "1:1:2:2", "1:2:2:2", "1:3:2:1",
                        "2:0:2:2", "2:1:2:1", "2:2:2:0",
                        "3:0:1:1", "3:1:1:0"), rows(cells));
        assertEquals(1.0, cells.get(0).rate());
        assertEquals(0.5, cells.get(3).rate());
    }

    @Test
    void fromDropsOldCohortsWithoutMakingOldEntitiesLookNewAndMaxPeriodsCaps() {
        assertEquals(List.of("2:0:2:2", "2:1:2:1", "3:0:1:1", "3:1:1:0"),
                rows(Retention.compute(events(), params(Retention.Mode.EXACT, 1, LocalDateTime.of(2026, 2, 17, 9, 0)))));
        // u1 and u2 stay in January even though 'from' is February: they are not re-counted as February cohort members
    }

    @Test
    void theZoneDecidesTheBucket() {
        List<Retention.Event> one = List.of(new Retention.Event("a", Instant.parse("2026-01-31T23:30:00Z")));
        var kolkata = new Retention.Params(TimeGrain.MONTH, ZoneId.of("Asia/Kolkata"), LocalDateTime.of(2026, 2, 28, 0, 0),
                null, Retention.Mode.EXACT, 5);
        var utc = new Retention.Params(TimeGrain.MONTH, UTC, LocalDateTime.of(2026, 2, 28, 0, 0), null, Retention.Mode.EXACT, 5);
        assertEquals(2, Retention.compute(one, kolkata).get(0).cohort().getMonthValue());
        assertEquals(1, Retention.compute(one, utc).get(0).cohort().getMonthValue());
    }

    @Test
    void weeksAndDaysCountWholeBuckets() {
        List<Retention.Event> e = List.of(new Retention.Event("a", Instant.parse("2026-10-12T08:00:00Z")),    // a Monday
                new Retention.Event("a", Instant.parse("2026-10-26T20:00:00Z")));                            // two weeks on
        var week = new Retention.Params(TimeGrain.WEEK, UTC, LocalDateTime.of(2026, 10, 30, 0, 0), null, Retention.Mode.EXACT, 10);
        assertEquals(List.of("a"), List.of("a"));
        List<Retention.Cell> cells = Retention.compute(e, week);
        assertEquals(3, cells.size(), "weeks 0, 1, 2 are observable by Friday of week 2");
        assertEquals(List.of(1L, 0L, 1L), cells.stream().map(Retention.Cell::active).toList());
        var day = new Retention.Params(TimeGrain.DAY, UTC, LocalDateTime.of(2026, 10, 26, 0, 0), null, Retention.Mode.EXACT, 30);
        assertEquals(15, Retention.compute(e, day).size());
    }

    @Test
    void retentionInputRules() {
        assertThrows(IllegalArgumentException.class, () -> new Retention.Params(TimeGrain.MINUTE, UTC,
                LocalDateTime.now(), null, Retention.Mode.EXACT, 5));
        assertThrows(IllegalArgumentException.class, () -> new Retention.Params(TimeGrain.DAY, UTC,
                LocalDateTime.now(), null, Retention.Mode.EXACT, -1));
        assertEquals(List.of(), Retention.compute(List.of(), params(Retention.Mode.EXACT, 5, null)));
    }

    // ── funnel ───────────────────────────────────────────────────────────────────────────────────────────────────

    private static Funnel.Event f(String entity, String step, String time) {
        return new Funnel.Event(entity, step, Instant.parse("2026-10-01T" + time + ":00Z"));
    }

    private static List<Funnel.Event> funnelEvents() {
        return List.of(
                f("e1", "view", "10:00"), f("e1", "cart", "10:10"), f("e1", "buy", "10:30"),
                f("e2", "view", "10:00"), f("e2", "buy", "10:05"),                       // no cart: buying first does not count
                f("e3", "cart", "09:00"), f("e3", "view", "10:00"),                      // cart before the view: not after it
                f("e4", "view", "10:00"), f("e4", "cart", "10:00"), f("e4", "buy", "12:00"),   // same instant counts
                f("e5", "view", "08:00"), f("e5", "view", "11:00"), f("e5", "cart", "11:05"),  // anchor is the earliest view
                f("e6", "cart", "10:00"),                                                // never viewed
                f("e1", "refund", "11:00"));                                             // not a step: ignored
    }

    private static List<String> steps(List<Funnel.StepResult> r) {
        return r.stream().map(s -> s.name() + ":" + s.entities()).toList();
    }

    @Test
    void anOpenFunnelCountsEarliestFirstChains() {
        var r = Funnel.compute(funnelEvents(), new Funnel.Params(List.of("view", "cart", "buy"), null));
        assertEquals(List.of("view:5", "cart:3", "buy:2"), steps(r));
        assertTrue(r.get(0).fromPrevious().isEmpty());
        assertEquals(0.6, r.get(1).fromPrevious().getAsDouble(), 1e-12);
        assertEquals(2.0 / 3, r.get(2).fromPrevious().getAsDouble(), 1e-12);
        assertEquals(0.4, r.get(2).fromFirst().getAsDouble(), 1e-12);
    }

    @Test
    void aWindowIsMeasuredFromTheAnchorAndDoesNotRescueALaterAttempt() {
        var r = Funnel.compute(funnelEvents(), new Funnel.Params(List.of("view", "cart", "buy"), Duration.ofHours(1)));
        assertEquals(List.of("view:5", "cart:2", "buy:1"), steps(r), "e5's cart is 3h after its first view; e4's buy 2h");
    }

    @Test
    void funnelInputRules() {
        assertThrows(IllegalArgumentException.class, () -> new Funnel.Params(List.of("a"), null));
        assertThrows(IllegalArgumentException.class, () -> new Funnel.Params(List.of("a", "a"), null));
        assertThrows(IllegalArgumentException.class, () -> new Funnel.Params(List.of("a", "b"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Funnel.Params(List.of("a", "b"), Duration.ofMillis(1500)));
        var empty = Funnel.compute(List.of(), new Funnel.Params(List.of("a", "b"), null));
        assertEquals(List.of("a:0", "b:0"), steps(empty));
        assertTrue(empty.get(1).fromPrevious().isEmpty() && empty.get(1).fromFirst().isEmpty(), "no division by zero");
    }
}
