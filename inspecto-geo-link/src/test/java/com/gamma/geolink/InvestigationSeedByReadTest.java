package com.gamma.geolink;

import com.gamma.control.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-17 step 4 — the bounded {@code SELECT DISTINCT} behind {@code seedBy} (design §4.4.1). The route's cap is
 * {@link InvestigationRoutes#SEED_BY_DISTINCT_CAP} (20 000), too large to exceed in a real-HTTP fixture, so the refusal
 * is pinned here with a small cap: above it is a 422 NAMING the cap — never a silent sample.
 */
class InvestigationSeedByReadTest {

    private static final String RELATION = "SELECT * FROM (VALUES ('a','x'),('b','x'),('c',NULL),('a','y')) AS t(src,dst)";

    @Test
    void readsTheDistinctNonNullValuesOfOneColumn() {
        assertEquals(new TreeSet<>(List.of("a", "b", "c")),
                new TreeSet<>(InvestigationRoutes.distinctValues("ds", RELATION, "src", 3)), "exactly at the cap is fine");
        assertEquals(new TreeSet<>(List.of("x", "y")),
                new TreeSet<>(InvestigationRoutes.distinctValues("ds", RELATION, "dst", 3)), "NULL is not a value");
    }

    @Test
    void aColumnAboveTheCapIsRefusedNamingTheCap() throws Exception {
        ApiException refused = assertThrows(ApiException.class,
                () -> InvestigationRoutes.distinctValues("ds", RELATION, "src", 2));
        var status = ApiException.class.getDeclaredField("status");   // package-private in com.gamma.control
        status.setAccessible(true);
        assertEquals(422, status.getInt(refused));
        assertTrue(refused.getMessage().contains("at most 2 distinct values") && refused.getMessage().contains("'src'"),
                refused.getMessage());
    }
}
