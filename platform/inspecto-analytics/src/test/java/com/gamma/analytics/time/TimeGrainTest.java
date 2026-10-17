package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** Hand-computed figures for {@link TimeGrain}'s JVM forms (ANALYTICS-CORE-1 M5). DuckDB agreement: the parity test. */
class TimeGrainTest {

    private static final LocalDateTime T = LocalDateTime.parse("2026-11-15T13:47:21.123456789");

    @Test
    void wallClockTruncationPerGrain() {
        assertEquals(LocalDateTime.parse("2026-11-15T13:47:21"), TimeGrain.SECOND.truncate(T));
        assertEquals(LocalDateTime.parse("2026-11-15T13:47"), TimeGrain.MINUTE.truncate(T));
        assertEquals(LocalDateTime.parse("2026-11-15T13:00"), TimeGrain.HOUR.truncate(T));
        assertEquals(LocalDateTime.parse("2026-11-15T00:00"), TimeGrain.DAY.truncate(T));
        assertEquals(LocalDateTime.parse("2026-11-09T00:00"), TimeGrain.WEEK.truncate(T), "a Sunday's week starts the Monday before");
        assertEquals(LocalDateTime.parse("2026-11-01T00:00"), TimeGrain.MONTH.truncate(T));
        assertEquals(LocalDateTime.parse("2026-10-01T00:00"), TimeGrain.QUARTER.truncate(T));
        assertEquals(LocalDateTime.parse("2026-01-01T00:00"), TimeGrain.YEAR.truncate(T));
    }

    @Test
    void weekIsIsoMondayAcrossTheYearBoundary() {
        assertEquals(LocalDate.parse("2026-12-28"), TimeGrain.WEEK.truncate(LocalDate.parse("2027-01-03")));
        assertEquals(LocalDate.parse("2027-01-04"), TimeGrain.WEEK.truncate(LocalDate.parse("2027-01-04")), "a Monday is its own week start");
        assertEquals(LocalDate.parse("2020-12-28"), TimeGrain.WEEK.truncate(LocalDate.parse("2021-01-01")), "ISO week 53 of 2020");
        assertEquals(LocalDate.parse("2028-02-01"), TimeGrain.MONTH.truncate(LocalDate.parse("2028-02-29")));
        assertEquals(LocalDate.parse("2026-07-01"), TimeGrain.QUARTER.truncate(LocalDate.parse("2026-09-30")));
        assertEquals(LocalDate.parse("2026-09-30"), TimeGrain.HOUR.truncate(LocalDate.parse("2026-09-30")), "sub-day grain keeps the date");
    }

    @Test
    void zonedLabelAndStartUseTheStatedZoneNotTheHost() {
        Instant t = Instant.parse("2026-11-15T20:30:00Z");
        assertEquals(LocalDateTime.parse("2026-11-16T00:00"), TimeGrain.DAY.label(t, ZoneId.of("Asia/Kolkata")), "02:00 IST is the next day");
        assertEquals(Instant.parse("2026-11-15T18:30:00Z"), TimeGrain.DAY.truncate(t, ZoneId.of("Asia/Kolkata")));
        assertEquals(Instant.parse("2026-11-15T20:00:00Z"), TimeGrain.HOUR.truncate(t, ZoneId.of("UTC")));
        assertEquals(Instant.parse("2026-11-15T20:30:00Z"), TimeGrain.HOUR.truncate(t, ZoneId.of("Asia/Kolkata")),
                "02:00 IST is a local hour start");
    }

    @Test
    void theTwoOccurrencesOfARepeatedHourAreTwoHourBucketsButOneLabel() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Instant first = Instant.parse("2026-10-25T00:30:00Z");    // 02:30 CEST
        Instant second = Instant.parse("2026-10-25T01:30:00Z");   // 02:30 CET
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), TimeGrain.HOUR.truncate(first, berlin));
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"), TimeGrain.HOUR.truncate(second, berlin));
        assertEquals(TimeGrain.HOUR.label(first, berlin), TimeGrain.HOUR.label(second, berlin));
        assertEquals(Instant.parse("2026-10-24T22:00:00Z"), TimeGrain.DAY.truncate(second, berlin), "the day starts at CEST midnight");
    }

    @Test
    void aDayStartInsideASkippedHourMovesLaterByTheGap() {
        // Chile springs forward at 24:00: 2026-09-06 00:00 never happens; the day starts at 01:00 -03.
        Instant t = Instant.parse("2026-09-06T12:00:00Z");
        assertEquals(Instant.parse("2026-09-06T04:00:00Z"), TimeGrain.DAY.truncate(t, ZoneId.of("America/Santiago")));
    }

    @Test
    void plusMovesWholeBuckets() {
        LocalDateTime start = LocalDateTime.parse("2026-01-01T00:00");
        assertEquals(LocalDateTime.parse("2026-04-01T00:00"), TimeGrain.QUARTER.plus(start, 1));
        assertEquals(LocalDateTime.parse("2025-12-18T00:00"), TimeGrain.WEEK.plus(start, -2));
        assertEquals("INTERVAL (14) DAY", TimeGrain.WEEK.intervalSql(2));
        assertEquals("INTERVAL (-3) MONTH", TimeGrain.QUARTER.intervalSql(-1));
    }

    @Test
    void parseOfAndZoneRules() {
        assertEquals(TimeGrain.QUARTER, TimeGrain.parse(" Quarter "));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TimeGrain.parse("decade")).getMessage().contains("week"));
        assertEquals(TimeGrain.SECOND, TimeGrain.of(ChronoUnit.SECONDS));
        assertEquals(TimeGrain.YEAR, TimeGrain.of(ChronoUnit.YEARS));
        assertThrows(IllegalArgumentException.class, () -> TimeGrain.of(ChronoUnit.DECADES));
        assertEquals("UTC", TimeGrain.zoneId(ZoneOffset.UTC));
        assertEquals("UTC", TimeGrain.zoneId(ZoneId.of("Etc/UTC")));
        assertEquals("Asia/Kolkata", TimeGrain.zoneId(ZoneId.of("Asia/Kolkata")), "a fixed-rule REGION stays a region");
        assertThrows(IllegalArgumentException.class, () -> TimeGrain.zoneId(ZoneOffset.ofHoursMinutes(5, 30)));
        assertEquals("DATE_TRUNC('week', \"t\")", TimeGrain.WEEK.truncateSql("\"t\""));
    }
}
