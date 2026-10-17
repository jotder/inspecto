package com.gamma.acquire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden figures for where {@link FileSequenceGaps#analyze} starts enumerating its window (ANALYTICS-CORE-1 M5): the
 * window start floored to the template's bucket, at every unit a template can carry. Written against the class's own
 * private truncation BEFORE it moved to the Analytics Kernel's {@code TimeGrain}; the move must not change one key.
 */
class FileSequenceGapsBucketStartGoldenTest {

    private static List<String> empty(String template, String from, String to) {
        return FileSequenceGaps.analyze(template, List.of(), LocalDateTime.parse(from), LocalDateTime.parse(to), null)
                .emptyBuckets();
    }

    @Test
    void anUnalignedWindowStartIsFlooredToItsBucketAtEveryUnit() {
        assertEquals(List.of("2024", "2025", "2026"), empty("F_{yyyy}.csv", "2024-07-09T10:00", "2026-01-01T00:00"));
        assertEquals(List.of("202601", "202602", "202603", "202604"),
                empty("F_{yyyyMM}.csv", "2026-01-31T13:45", "2026-04-02T00:00"));
        assertEquals(List.of("20280228", "20280229", "20280301"),
                empty("F_{yyyyMMdd}.csv", "2028-02-28T23:59:59.999", "2028-03-01T00:00"));
        assertEquals(List.of("2026123122", "2026123123", "2027010100"),
                empty("F_{yyyyMMddHH}.csv", "2026-12-31T22:59:59", "2027-01-01T00:30"));
        assertEquals(List.of("202603290159", "202603290200", "202603290201"),
                empty("F_{yyyyMMddHHmm}.csv", "2026-03-29T01:59:30", "2026-03-29T02:01:00"));
        assertEquals(List.of("20261025025958", "20261025025959", "20261025030000"),
                empty("F_{yyyyMMddHHmmss}.csv", "2026-10-25T02:59:58.75", "2026-10-25T03:00:00"));
    }
}
