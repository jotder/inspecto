package com.gamma.insights;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Golden selection of the Dashboard Brief (BI-INSIGHTS-1 P4 BR-0, plan §4.1): the rules, over hand-made stored rows. */
class InsightBriefTest {

    private static final Instant SCANNED = Instant.parse("2026-03-02T06:00:00Z");
    private static final Instant FRESH = Instant.parse("2026-03-02T18:00:00Z");

    private static Map<String, Object> row(String id, String run, Instant at, String band, double score, String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("insight_id", id);
        r.put("insight_key", "spike|region|" + id);
        r.put("insight_type", "spike");
        r.put("run_id", run);
        r.put("run_at", at.toString());
        r.put("measure", "amount");
        r.put("dimension", "region");
        r.put("slice_value", "slice-" + id);
        r.put("period", "2026-03-01");
        r.put("value", 400.0);
        r.put("expected", 100.0);
        r.put("delta", 300.0);
        r.put("score", score);
        r.put("band", band);
        r.put("status", status);
        r.put("explanation", InsightStore.json(Map.of("sentence", "sentence of " + id)));
        r.put("query", InsightStore.json(Map.of("measures", List.of())));
        return r;
    }

    private static List<String> ids(Map<String, Object> brief) {
        List<String> out = new ArrayList<>();
        for (Object l : (List<?>) brief.get("lines")) out.add(String.valueOf(((Map<?, ?>) l).get("insightId")));
        return out;
    }

    private static Map<String, Object> pick(List<Map<String, Object>> rows, Map<String, Map<String, Object>> fb, int max) {
        return InsightBrief.select("daily", "day", rows, fb, max, FRESH);
    }

    @Test
    void onlyTheElevatedAndHighBandsOfTheLatestRunAreBriefedBestScoreFirstTiesById() {
        List<Map<String, Object>> rows = List.of(
                row("b", "r2", SCANNED, "elevated", 60, null),
                row("a", "r2", SCANNED, "elevated", 60, null),          // ties b on score: insight_id breaks it
                row("c", "r2", SCANNED, "high", 90, null),
                row("d", "r2", SCANNED, "normal", 20, null),             // below the band cut
                row("old", "r1", SCANNED.minusSeconds(86400), "high", 99, null));   // an earlier run
        Map<String, Object> b = pick(rows, Map.of(), 5);
        assertEquals(List.of("c", "a", "b"), ids(b));
        assertEquals("r2", b.get("runId"));
        assertEquals("sentence of c", ((Map<?, ?>) ((List<?>) b.get("lines")).get(0)).get("sentence"));
        assertEquals("high", ((Map<?, ?>) ((List<?>) b.get("lines")).get(0)).get("band"));
        assertEquals("/studio/insights?profile=daily&insight=c", ((Map<?, ?>) ((List<?>) b.get("lines")).get(0)).get("link"));
        assertEquals(false, b.get("stale"));
        assertNull(b.get("note"));
        assertNull(b.get("summary"));
    }

    @Test
    void dismissedInsightsAreExcludedByTheStoredStatusAndByTheReadersCurrentFeedback() {
        List<Map<String, Object>> rows = List.of(
                row("a", "r1", SCANNED, "high", 90, "not_useful"),
                row("b", "r1", SCANNED, "high", 80, "expected"),
                row("c", "r1", SCANNED, "high", 70, "useful"),
                row("d", "r1", SCANNED, "high", 60, null),
                row("e", "r1", SCANNED, "high", 50, null));
        Map<String, Map<String, Object>> fb = Map.of("spike|region|e", Map.of("status", "not_useful"),
                "spike|region|a", Map.of("status", "useful"));    // the current word wins over the stored status
        assertEquals(List.of("a", "c", "d"), ids(pick(rows, fb, 5)));
    }

    @Test
    void maxCapsTheLinesAndTheHardCapIsTen() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 14; i++) rows.add(row(String.format("i%02d", i), "r1", SCANNED, "high", 90 - i, null));
        assertEquals(List.of("i00", "i01"), ids(pick(rows, Map.of(), 2)));
        assertEquals(10, ids(pick(rows, Map.of(), 50)).size());
    }

    @Test
    void nothingQualifyingSaysSoAndIsNeverPaddedWithLowBands() {
        Map<String, Object> b = pick(List.of(row("d", "r1", SCANNED, "normal", 20, null)), Map.of(), 5);
        assertEquals(List.of(), b.get("lines"));
        assertEquals("Nothing unusual in 2026-03-01", b.get("summary"));
        assertEquals("Brief for daily - scanned 2026-03-02T06:00:00Z, period 2026-03-01", b.get("header"));
    }

    @Test
    void aBriefOlderThanTwoGrainsSaysSoAndKeepsItsLines() {
        List<Map<String, Object>> rows = List.of(row("a", "r1", SCANNED, "high", 90, null));
        Map<String, Object> b = InsightBrief.select("daily", "day", rows, Map.of(), 5, SCANNED.plusSeconds(3 * 86400L));
        assertEquals(true, b.get("stale"));
        assertEquals("Last scan was 3 days ago", b.get("note"));
        assertEquals(List.of("a"), ids(b));
        // two days is still current for a day Profile; the grain, not the wall clock, sets the line
        assertEquals(false, InsightBrief.select("daily", "day", rows, Map.of(), 5, SCANNED.plusSeconds(2 * 86400L)).get("stale"));
        assertEquals(false, InsightBrief.select("daily", "week", rows, Map.of(), 5, SCANNED.plusSeconds(10 * 86400L)).get("stale"));
    }

    @Test
    void noRowsIsAnEmptyBriefNamingTheScanJob() {
        Map<String, Object> b = pick(List.of(), Map.of(), 5);
        assertEquals(List.of(), b.get("lines"));
        assertTrue(String.valueOf(b.get("note")).contains("insight.scan"), String.valueOf(b.get("note")));
    }
}
