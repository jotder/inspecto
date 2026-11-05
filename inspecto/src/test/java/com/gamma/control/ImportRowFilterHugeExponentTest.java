package com.gamma.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gamma.spi.auth.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A decimal filter value with a huge exponent is refused before {@code BigDecimal.toPlainString} expands it into
 * ~1e9 digits (an OutOfMemoryError or a 1 GB predicate). Verification finding F1 of ADD-DATA-ROW-FILTER-1, 2026-11-05.
 */
class ImportRowFilterHugeExponentTest {

    private static final List<ImportRowFilter.Column> KEPT = List.of(new ImportRowFilter.Column("amount", "amount", "DOUBLE"));

    @Test
    void aHugeExponentIsRefusedAsNotANumber() {
        for (String huge : List.of("1E999999999", "1E-999999999", "1E2147483647", "1E41")) {
            for (Map<String, Object> cond : List.of(
                    Map.<String, Object>of("column", "amount", "op", "eq", "value", huge),
                    Map.<String, Object>of("column", "amount", "op", "range", "min", huge))) {
                ApiException e = assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> assertThrows(ApiException.class, () -> ImportRowFilter.of(List.of(cond), KEPT, Set.of())), huge);
                assertTrue(e.getMessage().contains("is not a number"), huge + ": " + e.getMessage());
            }
        }
    }

    @Test
    void anOrdinaryDecimalStillRenders() {
        ImportRowFilter.Result r = ImportRowFilter.of(
                List.of(Map.of("column", "amount", "op", "eq", "value", "1.50E2")), KEPT, Set.of());
        assertTrue(r.where().contains("150"), r.where());
        assertEquals(1, r.stored().size());
    }
}
