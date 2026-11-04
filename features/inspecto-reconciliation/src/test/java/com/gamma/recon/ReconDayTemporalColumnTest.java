package com.gamma.recon;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A Reconciliation reads a side one day at a time by its temporal column. LA-COLUMN-ROLES-1 (operator 2026-11-04) lets a
 * Dataset have several Time columns ({@code role: temporal}); then the day column is the link mapping's time, and with
 * none named the side is refused as before - several Time columns must never quietly pick one.
 */
class ReconDayTemporalColumnTest {

    private static final List<Map<String, Object>> TWO_TIMES = List.of(
            Map.of("name", "booked_at", "role", "temporal"),
            Map.of("name", "settled_at", "role", "temporal"),
            Map.of("name", "amount", "role", "measure"));

    @Test
    void oneTemporalColumnIsTheDay() {
        assertEquals("booked_at", ReconDay.temporalColumn("ds", Map.of("columns",
                List.of(Map.of("name", "booked_at", "role", "temporal")))));
    }

    @Test
    void severalTemporalColumnsReadTheLinkMappingsTime() {
        assertEquals("settled_at", ReconDay.temporalColumn("ds", Map.of("columns", TWO_TIMES,
                "defaultLinkMapping", Map.of("from", "a", "to", "b", "time", "settled_at"))));
    }

    @Test
    void severalTemporalColumnsAndNoneNamedAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReconDay.temporalColumn("ds", Map.of("columns", TWO_TIMES)));
        assertThrows(IllegalArgumentException.class, () -> ReconDay.temporalColumn("ds", Map.of("columns", TWO_TIMES,
                "dateField", "booked_at")), "a dateField does not settle an ambiguous role either");
    }
}
