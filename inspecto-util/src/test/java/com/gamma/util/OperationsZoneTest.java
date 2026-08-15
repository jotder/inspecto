package com.gamma.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operations zone — the one the scheduler fires on and {@code $today} resolves in.
 *
 * <p>⚠ These deliberately avoid asserting against any zone that could be this machine's default: a zone
 * test that happens to pin {@code systemDefault()} passes everywhere and proves nothing. The
 * cross-midnight case below picks its two zones so they disagree <b>whatever</b> the host is.
 */
class OperationsZoneTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(OperationsZone.PROPERTY);
    }

    /** Unset ⇒ exactly today's behaviour. This is the whole reason the change needs no migration. */
    @Test
    void unsetFallsBackToTheJvmDefault() {
        System.clearProperty(OperationsZone.PROPERTY);
        assertEquals(ZoneId.systemDefault(), OperationsZone.resolve());
    }

    /** Blank is unset, not an error — an empty `-Dops.timezone=` must not fail a boot. */
    @Test
    void blankIsTreatedAsUnset() {
        System.setProperty(OperationsZone.PROPERTY, "   ");
        assertEquals(ZoneId.systemDefault(), OperationsZone.resolve());
    }

    @Test
    void aConfiguredZoneWins() {
        System.setProperty(OperationsZone.PROPERTY, "Asia/Kolkata");
        assertEquals(ZoneId.of("Asia/Kolkata"), OperationsZone.resolve());
        System.setProperty(OperationsZone.PROPERTY, "  UTC  ");
        assertEquals(ZoneId.of("UTC"), OperationsZone.resolve(), "surrounding whitespace is trimmed");
    }

    /**
     * ⛔ Fail loud, never fall back. A typo'd zone that silently degraded to the JVM default would run
     * every schedule in the wrong zone while the operator believed otherwise — and a typo is
     * indistinguishable from intent.
     */
    @Test
    void anUnresolvableZoneThrowsAndNamesTheOffendingValue() {
        System.setProperty(OperationsZone.PROPERTY, "Asia/Kolkatta");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, OperationsZone::resolve);
        assertTrue(ex.getMessage().contains("Asia/Kolkatta"),
                "the message names the value, which the descriptive key's CrossFieldRule cannot: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(OperationsZone.PROPERTY),
                "and names the property, so the operator knows where to fix it: " + ex.getMessage());
    }

    /**
     * The point of the whole change: the resolved zone actually moves the date the {@code $today} family
     * is cut on, and the instant a cron trigger is compared at. Pinned here on the mechanism both
     * consumers use ({@code LocalDate.ofInstant} / {@code Instant.atZone}) rather than on the JVM default.
     *
     * <p>2026-08-15T20:00Z is 2026-08-16 in Asia/Kolkata (+05:30) and still 2026-08-15 in UTC — a
     * disagreement that holds regardless of what this machine's zone is.
     */
    @Test
    void theResolvedZoneChangesWhichDayAnInstantFallsOn() {
        Instant fireTime = Instant.parse("2026-08-15T20:00:00Z");

        System.setProperty(OperationsZone.PROPERTY, "UTC");
        LocalDate inUtc = LocalDate.ofInstant(fireTime, OperationsZone.resolve());

        System.setProperty(OperationsZone.PROPERTY, "Asia/Kolkata");
        LocalDate inKolkata = LocalDate.ofInstant(fireTime, OperationsZone.resolve());

        assertEquals(LocalDate.of(2026, 8, 15), inUtc);
        assertEquals(LocalDate.of(2026, 8, 16), inKolkata);
        assertNotEquals(inUtc, inKolkata, "if these ever agree the fixture has stopped testing anything");
    }
}
