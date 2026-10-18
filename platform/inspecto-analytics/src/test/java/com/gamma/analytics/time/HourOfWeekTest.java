package com.gamma.analytics.time;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class HourOfWeekTest {

    @Test
    void slotsAreIsoWeekdayMondayZero() {
        LocalDateTime monday = LocalDateTime.of(2026, 10, 12, 0, 0);       // a Monday
        LocalDateTime sunday = LocalDateTime.of(2026, 10, 18, 23, 59);
        assertEquals(0, HourOfWeek.HOUR_OF_WEEK.slot(monday));
        assertEquals(167, HourOfWeek.HOUR_OF_WEEK.slot(sunday));
        assertEquals(0, HourOfWeek.WEEKDAY.slot(monday));
        assertEquals(6, HourOfWeek.WEEKDAY.slot(sunday));
        assertEquals(23, HourOfWeek.HOUR_OF_DAY.slot(sunday));
        assertEquals(1 * 24 + 3, HourOfWeek.HOUR_OF_WEEK.slot(LocalDateTime.of(2026, 10, 13, 3, 30)));
    }

    @Test
    void theZoneDecidesTheSlot() {
        Instant t = Instant.parse("2026-10-12T23:30:00Z");                 // Monday 23:30 UTC
        assertEquals(23, HourOfWeek.HOUR_OF_WEEK.slot(t, ZoneId.of("UTC")));
        assertEquals(1 * 24 + 5, HourOfWeek.HOUR_OF_WEEK.slot(t, ZoneId.of("Asia/Kolkata")));   // Tuesday 05:00
    }

    @Test
    void labelsAndParse() {
        assertEquals("TUESDAY 03h", HourOfWeek.HOUR_OF_WEEK.label(27));
        assertEquals("03h", HourOfWeek.HOUR_OF_DAY.label(3));
        assertEquals("SUNDAY", HourOfWeek.WEEKDAY.label(6));
        assertEquals(HourOfWeek.HOUR_OF_WEEK, HourOfWeek.parse(" Hour_Of_Week "));
        assertThrows(IllegalArgumentException.class, () -> HourOfWeek.parse("minute"));
        assertThrows(IllegalArgumentException.class, () -> HourOfWeek.WEEKDAY.label(7));
    }

    @Test
    void aPureWeeklyHourShapeIsFullyExplainedAndNoiseIsNot() {
        int n = 168 * 4;
        int[] slot = new int[n];
        double[] shaped = new double[n], flat = new double[n];
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < n; i++) {
            slot[i] = i % 168;
            shaped[i] = 10 + (i % 168) % 24 * 2 + ((i % 168) / 24 >= 5 ? -7 : 0);
            flat[i] = r.nextDouble();
        }
        var p = SeasonalProfile.of(HourOfWeek.HOUR_OF_WEEK, slot, shaped);
        assertEquals(1.0, p.strength().getAsDouble(), 1e-12);
        assertEquals(168, p.slots().size());
        assertEquals(4, p.slots().get(0).n());
        // a flat random series is explained by 168 slots only as much as 168 free means overfit 672 points (~25 %)
        assertTrue(SeasonalProfile.of(HourOfWeek.HOUR_OF_WEEK, slot, flat).strength().getAsDouble() < 0.4);
    }

    @Test
    void hourFreeSlotsAreAbsentAndConstantHasNoStrength() {
        var p = SeasonalProfile.of(HourOfWeek.HOUR_OF_DAY, new int[] {3, 3, 9}, new double[] {1, 3, 8});
        assertEquals(2, p.slots().size());
        assertEquals(2.0, p.slots().get(0).mean());
        assertTrue(SeasonalProfile.of(HourOfWeek.HOUR_OF_DAY, new int[] {3, 4}, new double[] {5, 5}).strength().isEmpty());
        assertTrue(SeasonalProfile.of(HourOfWeek.HOUR_OF_DAY, new int[0], new double[0]).strength().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> SeasonalProfile.of(HourOfWeek.HOUR_OF_DAY, new int[] {24}, new double[] {1}));
        assertThrows(IllegalArgumentException.class,
                () -> SeasonalProfile.of(HourOfWeek.HOUR_OF_DAY, new int[] {1}, new double[] {Double.NaN}));
    }
}
