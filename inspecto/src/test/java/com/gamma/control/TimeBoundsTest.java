package com.gamma.control;

import com.gamma.util.OperationsZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /events} time bounds are anchored in the {@linkplain OperationsZone operations zone}, not the
 * JVM default. A bare {@code yyyy-MM-dd[ HH:mm:ss]} bound is an operator's wall clock — typed into a
 * console alongside a schedule that already fires in that zone — so reading it in a different one hands
 * back a window silently offset by the difference.
 *
 * <p>⚠ Every zone here is named explicitly. A test that asserts against {@code systemDefault()} passes on
 * every box and proves nothing, which is the trap {@code OperationsZoneTest} calls out.
 *
 * <p>Was {@code EventRoutesTimeBoundTest}; renamed with the EDG-01 cell 6 extraction (2026-09-08). The
 * helper it covers stayed in CORE rather than leaving with the {@code /events*} feed, because
 * {@link AuditLogRoutes} parses bounds the same way and a second copy would let the two drift.
 */
class TimeBoundsTest {

    @AfterEach
    void clearZone() {
        System.clearProperty(OperationsZone.PROPERTY);
    }

    private static long expected(String local, String zone) {
        return LocalDateTime.parse(local.replace(' ', 'T')).atZone(ZoneId.of(zone)).toInstant().toEpochMilli();
    }

    @Test
    void aBareDateIsMidnightInTheOperationsZone() {
        System.setProperty(OperationsZone.PROPERTY, "UTC");
        assertEquals(expected("2026-08-15 00:00:00", "UTC"), TimeBounds.epochMillis("2026-08-15"));

        System.setProperty(OperationsZone.PROPERTY, "Asia/Kolkata");
        assertEquals(expected("2026-08-15 00:00:00", "Asia/Kolkata"), TimeBounds.epochMillis("2026-08-15"));
    }

    /** The behaviour change, stated as a difference rather than a value: the same text is a different
     *  instant in a different operations zone — 5h30m apart here. */
    @Test
    void theSameTextResolvesToADifferentInstantPerZone() {
        System.setProperty(OperationsZone.PROPERTY, "UTC");
        long utc = TimeBounds.epochMillis("2026-08-15 06:00:00");
        System.setProperty(OperationsZone.PROPERTY, "Asia/Kolkata");
        long kolkata = TimeBounds.epochMillis("2026-08-15 06:00:00");

        assertEquals(19800_000L, utc - kolkata, "+05:30 means the Kolkata reading is the earlier instant");
    }

    /** Epoch millis are already absolute, so the zone must not touch them. */
    @Test
    void anEpochMillisBoundIsZoneIndependent() {
        System.setProperty(OperationsZone.PROPERTY, "Pacific/Kiritimati");
        assertEquals(1_755_000_000_000L, TimeBounds.epochMillis("1755000000000"));
        assertNull(TimeBounds.epochMillis("  "));
    }

    /**
     * A misconfigured {@code -Dops.timezone} must NOT be reported as a bad query. The resolve happens
     * outside the parse's {@code catch (RuntimeException)}, which would otherwise turn a server
     * misconfiguration into a 400 blaming the operator's own input.
     */
    @Test
    void anUnresolvableOperationsZoneIsNotBlamedOnTheQuery() {
        System.setProperty(OperationsZone.PROPERTY, "Mars/Olympus_Mons");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TimeBounds.epochMillis("2026-08-15"));
        assertTrue(e.getMessage().contains(OperationsZone.PROPERTY), e.getMessage());
    }
}
