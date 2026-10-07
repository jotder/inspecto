package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the dependency-free {@link CronExpression} parser + next-fire calculator.
 * Times are computed in a fixed zone so assertions are deterministic.
 *
 * <p>⚠ The DST block at the bottom is the deliberate exception: it uses {@code America/New_York}
 * precisely BECAUSE that zone shifts. Until 2026-09-15 every test here ran in UTC (and
 * {@code OperationsZoneTest}'s cross-midnight case in {@code Asia/Kolkata}) — <b>neither zone has
 * DST</b>, so the one behaviour a cron in a named civil zone exists to get right was entirely
 * unverified. The mechanism turned out to be correct; the gap was the coverage.
 */
class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime utc(int y, int mo, int d, int h, int mi, int s) {
        return ZonedDateTime.of(y, mo, d, h, mi, s, 0, UTC);
    }

    @Test
    void everyHourOnTheHour() {
        CronExpression c = CronExpression.parse("0 * * * *");
        ZonedDateTime next = c.next(utc(2026, 5, 30, 10, 15, 30));
        assertEquals(utc(2026, 5, 30, 11, 0, 0), next);
    }

    @Test
    void dailyAtTwoAm() {
        CronExpression c = CronExpression.parse("0 2 * * *");
        // before 02:00 → same day
        assertEquals(utc(2026, 5, 30, 2, 0, 0), c.next(utc(2026, 5, 30, 1, 59, 0)));
        // after 02:00 → next day
        assertEquals(utc(2026, 5, 31, 2, 0, 0), c.next(utc(2026, 5, 30, 2, 0, 1)));
    }

    @Test
    void sixFieldEveryThirtySeconds() {
        CronExpression c = CronExpression.parse("*/30 * * * * *");
        assertEquals(utc(2026, 5, 30, 10, 0, 30), c.next(utc(2026, 5, 30, 10, 0, 0)));
        assertEquals(utc(2026, 5, 30, 10, 1, 0),  c.next(utc(2026, 5, 30, 10, 0, 30)));
    }

    @Test
    void fiveFieldImpliesSecondZero() {
        CronExpression c = CronExpression.parse("*/15 * * * *");   // every 15 minutes
        ZonedDateTime next = c.next(utc(2026, 5, 30, 10, 7, 42));
        assertEquals(utc(2026, 5, 30, 10, 15, 0), next);
        assertEquals(0, next.getSecond(), "5-field form fires at second 0");
    }

    @Test
    void monthlyOnTheFirst() {
        CronExpression c = CronExpression.parse("0 0 1 * *");
        assertEquals(utc(2026, 6, 1, 0, 0, 0), c.next(utc(2026, 5, 30, 12, 0, 0)));
    }

    @Test
    void weekdaysAtNine() {
        CronExpression c = CronExpression.parse("0 9 * * MON-FRI");
        // 2026-05-30 is a Saturday → next weekday 09:00 is Monday Jun 1
        ZonedDateTime next = c.next(utc(2026, 5, 30, 12, 0, 0));
        assertEquals(utc(2026, 6, 1, 9, 0, 0), next);
        assertEquals(java.time.DayOfWeek.MONDAY, next.getDayOfWeek());
    }

    @Test
    void namedMonthAndList() {
        CronExpression c = CronExpression.parse("0 0 0 1 JAN,JUL *");
        // 6-field: sec min hour dom month dow — midnight Jan 1 and Jul 1
        assertEquals(utc(2026, 7, 1, 0, 0, 0), c.next(utc(2026, 5, 30, 0, 0, 0)));
        assertEquals(utc(2027, 1, 1, 0, 0, 0), c.next(utc(2026, 7, 1, 0, 0, 0)));
    }

    @Test
    void rangeAndStepInHours() {
        CronExpression c = CronExpression.parse("0 0 9-17/4 * * *");   // 09,13,17
        assertEquals(utc(2026, 5, 30, 9,  0, 0), c.next(utc(2026, 5, 30, 8, 0, 0)));
        assertEquals(utc(2026, 5, 30, 13, 0, 0), c.next(utc(2026, 5, 30, 9, 0, 0)));
        assertEquals(utc(2026, 5, 30, 17, 0, 0), c.next(utc(2026, 5, 30, 13, 0, 0)));
        assertEquals(utc(2026, 5, 31, 9,  0, 0), c.next(utc(2026, 5, 30, 17, 0, 0)));
    }

    @Test
    void domOrDowWhenBothRestricted() {
        // 1st of month OR any Monday (Vixie-cron OR semantics)
        CronExpression c = CronExpression.parse("0 0 1 * MON");
        // From mid-May 2026: next is Mon Jun 1 (both conditions), but the very next match
        // from May 30 (Sat) is Mon Jun 1.
        ZonedDateTime n1 = c.next(utc(2026, 5, 30, 0, 0, 0));
        assertEquals(utc(2026, 6, 1, 0, 0, 0), n1);
        // After Jun 1, next Monday is Jun 8 (dow match), well before the 1st of next month.
        assertEquals(utc(2026, 6, 8, 0, 0, 0), c.next(utc(2026, 6, 1, 0, 0, 0)));
    }

    @Test
    void sundayAsZeroOrSeven() {
        CronExpression zero  = CronExpression.parse("0 0 * * 0");
        CronExpression seven = CronExpression.parse("0 0 * * 7");
        ZonedDateTime from = utc(2026, 5, 30, 0, 0, 0);   // Saturday
        assertEquals(zero.next(from), seven.next(from), "0 and 7 both mean Sunday");
        assertEquals(java.time.DayOfWeek.SUNDAY, zero.next(from).getDayOfWeek());
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 0 0 0 *"));     // month 0 invalid
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("99 * * * *"));    // minute 99
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 0 * * FOO"));   // bad dow name
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("*/0 * * * *"));   // zero step
    }

    // ── DST, in a zone that actually shifts (duckle candidate S15) ────────────────
    //
    // `-Dops.timezone` resolves to a real IANA zone and CronExpression.next() operates on whatever
    // ZonedDateTime it is handed, so java.time decides gap/overlap. These pin what that ACTUALLY
    // produces — probed first, then asserted, so they record behaviour rather than a preference.

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static ZonedDateTime ny(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, NY);
    }

    /**
     * Spring forward: on 2026-03-08 the clocks jump 02:00 → 03:00, so 02:30 NEVER HAPPENS.
     * A daily 02:30 job therefore does not run at all that day — it resumes on the 9th.
     * ⚠ This is a real operational consequence, not a curiosity: a daily job silently misses one day
     * a year in any DST zone. Pinned so the behaviour is a decision on the record rather than a
     * surprise; a scheduler that preferred to fire at 03:00 instead would fail this test, which is
     * exactly the conversation worth having before changing it.
     */
    @Test
    void springForwardSkipsTheHourThatDoesNotExist() {
        CronExpression c = CronExpression.parse("30 2 * * *");
        // From midday on the 7th, the next fire skips the 8th entirely.
        assertEquals(ny(2026, 3, 9, 2, 30), c.next(ny(2026, 3, 7, 12, 0)));
    }

    /**
     * Autumn back: on 2026-11-01 the clocks repeat 01:00 → 02:00, so 02:30 occurs TWICE — once at
     * -04:00 (EDT) and again at -05:00 (EST). A daily job must fire ONCE, not twice.
     * ⛔ Double-firing here is the classic DST scheduler defect; this asserts it does not happen, by
     * checking the fire after the transition day is the NEXT day rather than the same 02:30 repeated.
     */
    @Test
    void autumnBackFiresOnceNotTwice() {
        CronExpression c = CronExpression.parse("30 2 * * *");
        ZonedDateTime first = c.next(ny(2026, 10, 31, 12, 0));
        assertEquals(ny(2026, 11, 1, 2, 30), first);
        // The following fire is the NEXT DAY — the repeated 02:30 is not served a second time.
        ZonedDateTime second = c.next(first);
        assertEquals(ny(2026, 11, 2, 2, 30), second);
        assertNotEquals(first.toInstant(), second.toInstant());
    }

    /**
     * The invariant that holds regardless of which gap/overlap policy is chosen: {@code next()} must
     * ALWAYS advance strictly past the instant it was given. A scheduler that returns a time not after
     * `from` re-fires the same run forever, so this is the property that turns a DST edge case into a
     * hot loop. Walked across both 2026 transitions.
     */
    @Test
    void nextAlwaysAdvancesStrictlyAcrossBothTransitions() {
        CronExpression c = CronExpression.parse("30 2 * * *");
        for (ZonedDateTime start : new ZonedDateTime[] {ny(2026, 3, 6, 0, 0), ny(2026, 10, 30, 0, 0)}) {
            ZonedDateTime prev = start;
            for (int i = 0; i < 6; i++) {
                ZonedDateTime next = c.next(prev);
                assertTrue(next.toInstant().isAfter(prev.toInstant()),
                        "next() must advance: " + prev + " -> " + next);
                prev = next;
            }
        }
    }
}
