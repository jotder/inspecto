package com.gamma.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Golden figures for {@link KpiDefinition.Grain#start} (ANALYTICS-CORE-1 M5), written BEFORE the period start moved to
 * the Analytics Kernel's {@code TimeGrain}: weeks start on Monday (across a year boundary and ISO week 53), quarters in
 * Jan/Apr/Jul/Oct, a leap day's month and year.
 */
class KpiPeriodStartGoldenTest {

    private static void starts(KpiDefinition.Grain g, String day, String expected) {
        assertEquals(LocalDate.parse(expected), g.start(LocalDate.parse(day)), g + " " + day);
    }

    @Test
    void periodStartsPerGrain() {
        starts(KpiDefinition.Grain.DAY, "2028-02-29", "2028-02-29");
        starts(KpiDefinition.Grain.WEEK, "2027-01-03", "2026-12-28");
        starts(KpiDefinition.Grain.WEEK, "2027-01-04", "2027-01-04");
        starts(KpiDefinition.Grain.WEEK, "2021-01-01", "2020-12-28");
        starts(KpiDefinition.Grain.WEEK, "2026-10-17", "2026-10-12");
        starts(KpiDefinition.Grain.MONTH, "2028-02-29", "2028-02-01");
        starts(KpiDefinition.Grain.MONTH, "2026-12-31", "2026-12-01");
        starts(KpiDefinition.Grain.QUARTER, "2026-03-31", "2026-01-01");
        starts(KpiDefinition.Grain.QUARTER, "2026-04-01", "2026-04-01");
        starts(KpiDefinition.Grain.QUARTER, "2026-09-30", "2026-07-01");
        starts(KpiDefinition.Grain.QUARTER, "2026-11-15", "2026-10-01");
        starts(KpiDefinition.Grain.YEAR, "2028-02-29", "2028-01-01");
        starts(KpiDefinition.Grain.YEAR, "2026-12-31", "2026-01-01");
    }
}
