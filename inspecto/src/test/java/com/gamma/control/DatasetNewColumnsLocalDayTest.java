package com.gamma.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DATES-ONE-ZONE-1 (Basic live check 2026-11-06): the arrivals list showed a file received at {@code 2026-11-06
 * 02:02} (the server's local clock) while the kept-column refusal said it was kept "on 2026-11-05" - the UTC date
 * of the same moment. The refusal now names the day in the server's local time, as the arrivals list does.
 */
class DatasetNewColumnsLocalDayTest {

    private final TimeZone before = TimeZone.getDefault();

    @AfterEach
    void restore() {
        TimeZone.setDefault(before);
    }

    private static Map<String, Object> dataset(String at) {
        return Map.of("columns", List.of(Map.of("name", "fee",
                DatasetNewColumns.KEPT_FROM, Map.of("file", "live-newcol.csv", "at", at))));
    }

    @Test
    void theKeptNoteNamesTheLocalDayNotTheUtcDay() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));   // UTC+05:30: 20:32 UTC is 02:02 the next day
        assertEquals("kept from 'live-newcol.csv' on 2026-11-06",
                DatasetNewColumns.keptNotes(dataset("2026-11-05T20:32:25.123Z")).get("fee"));
    }

    @Test
    void aZoneBehindUtcMovesTheDayBack() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        assertEquals("kept from 'live-newcol.csv' on 2026-11-04",
                DatasetNewColumns.keptNotes(dataset("2026-11-05T03:00:00Z")).get("fee"));
    }

    @Test
    void aValueThatIsNotAnInstantKeepsItsOwnDate() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        assertEquals("kept from 'live-newcol.csv' on 2026-11-05",
                DatasetNewColumns.keptNotes(dataset("2026-11-05 23:59:00")).get("fee"));
    }
}
