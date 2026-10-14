package com.gamma.expectation;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-DATA-TRUST-1 S7: the volume Expectation the la-daily-feed template will ship (S8) loads through the same
 * ComponentStore a space registry uses and validates as a trailing-days baseline: 7 days, below 50% / above 200%,
 * 3 prior days required.
 */
class LaDailyRowsBandFixtureTest {

    @Test
    void theShippedBandExpectationLoadsAndMeansWhatTheOperatorAsked(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("expectations"));
        try (var in = getClass().getResourceAsStream("/la-data-trust/expectations/la_daily_rows_band.toon")) {
            Files.copy(in, dir.resolve("la_daily_rows_band.toon"));
        }
        List<ComponentRegistry.Component> found = new ComponentStore(root).list("expectation");
        assertEquals(1, found.size());
        Expectation e = Expectation.fromMap(found.get(0).content());
        assertEquals("baseline", e.kind());
        assertEquals("daily_xdr/database", e.target());
        var b = e.baseline();
        assertEquals(List.of("EVENT_DATE"), b.groupBy());
        assertEquals(7, b.trailingDays());
        assertEquals(3, b.minPriorDays());
        assertEquals(50.0, b.maxDecrease());
        assertEquals(100.0, b.maxIncrease());
        assertEquals("percent", b.limitUnit());
    }
}
