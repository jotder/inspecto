package com.gamma.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KPI History (`BI-INSIGHTS-1` P0, I-3): period selection, partial periods, retention, the stored file. */
class KpiHistoryTest {

    private static final KpiDefinition.Grain MONTH = KpiDefinition.Grain.MONTH;

    /** Orders in May, June and (partly) August 2026; nothing in July. */
    private static final String RELATION = """
            SELECT * FROM (VALUES
              (DATE '2026-05-20', 10.0),
              (DATE '2026-06-03', 20.0),
              (DATE '2026-06-30', 5.0),
              (DATE '2026-08-01', 7.0),
              (DATE '2026-08-13', 3.0),
              (DATE '2026-08-20', 1000.0)
            ) AS t(order_date, amount)""";

    private static KpiDefinition kpi(String measure) {
        return KpiDefinition.fromMap("revenue", Map.of("dataset", "orders", "measure", measure,
                "timeField", "order_date", "grain", "month", "target", 20, "direction", "up"));
    }

    private static LocalDate d(String s) { return LocalDate.parse(s); }

    @Test
    void theFirstRunStartsAtTheDataNotAtTheRetentionStart() {
        assertEquals(List.of(d("2026-05-01"), d("2026-06-01"), d("2026-07-01"), d("2026-08-01")),
                KpiHistory.periodsToEvaluate(MONTH, d("2026-05-20"), d("2026-08-13"), Set.of()));
    }

    @Test
    void oldDataIsCutAtThreeYears() {
        List<LocalDate> p = KpiHistory.periodsToEvaluate(MONTH, d("2010-01-01"), d("2026-08-13"), Set.of());
        assertEquals(d("2023-08-01"), p.get(0));
        assertEquals(37, p.size());
    }

    @Test
    void aStoredClosedPeriodIsSkippedExceptThePreviousOne() {
        Set<LocalDate> stored = Set.of(d("2026-05-01"), d("2026-06-01"), d("2026-07-01"));
        assertEquals(List.of(d("2026-07-01"), d("2026-08-01")),
                KpiHistory.periodsToEvaluate(MONTH, d("2026-05-20"), d("2026-08-13"), stored));
    }

    @Test
    void noDataEvaluatesNothing() {
        assertTrue(KpiHistory.periodsToEvaluate(MONTH, null, d("2026-08-13"), Set.of()).isEmpty());
    }

    @Test
    void theCurrentPeriodIsToDateAndIncomplete() throws Exception {
        KpiHistory.Row r = KpiHistory.evaluate(kpi("sum(amount)"), RELATION, d("2026-08-01"), d("2026-08-13"));
        assertEquals(10.0, r.value());   // the 20th is after asOf
        assertFalse(r.complete());
        assertEquals(d("2026-09-01"), r.periodEnd());
        assertEquals("RED", r.rag());
    }

    @Test
    void aClosedPeriodIsWholeAndComplete() throws Exception {
        KpiHistory.Row r = KpiHistory.evaluate(kpi("sum(amount)"), RELATION, d("2026-06-01"), d("2026-08-13"));
        assertEquals(25.0, r.value());
        assertTrue(r.complete());
        assertEquals("GREEN", r.rag());
    }

    @Test
    void anEmptyPeriodHasNoValueNotZero() throws Exception {
        assertNull(KpiHistory.evaluate(kpi("sum(amount)"), RELATION, d("2026-07-01"), d("2026-08-13")).value());
    }

    @Test
    void freshRowsReplaceStoredOnesAndOldRowsAreDropped() {
        KpiHistory.Row old = new KpiHistory.Row(d("2023-07-01"), d("2023-08-01"), 1.0, null, true);
        KpiHistory.Row june = new KpiHistory.Row(d("2026-06-01"), d("2026-07-01"), 1.0, null, false);
        KpiHistory.Row juneNow = new KpiHistory.Row(d("2026-06-01"), d("2026-07-01"), 25.0, "GREEN", true);
        assertEquals(List.of(juneNow), KpiHistory.merge(List.of(old, june), List.of(juneNow),
                KpiHistory.retentionStart(MONTH, d("2026-08-13"))));
    }

    @Test
    void aSnapshotIsStoredAndARerunRestatesOnlyTheRecentPeriods(@TempDir Path data) throws Exception {
        KpiHistory.Summary first = KpiHistory.snapshot(data, kpi("sum(amount)"), RELATION, d("2026-08-13"),
                Instant.parse("2026-08-13T10:00:00Z"));
        assertEquals(4, first.evaluated());
        assertEquals(data.resolve("kpi_history_revenue"), first.store());
        assertTrue(Files.isRegularFile(first.store().resolve(KpiHistory.MARKER)));

        List<KpiHistory.Row> rows = KpiHistory.read(first.store(), KpiHistory.definition(kpi("sum(amount)")));
        assertEquals(List.of(10.0, 25.0), rows.subList(0, 2).stream().map(KpiHistory.Row::value).toList());
        assertNull(rows.get(2).value());
        assertFalse(rows.get(3).complete());

        // A day later: May and June are stored complete; July (the previous period) and August are restated.
        KpiHistory.Summary second = KpiHistory.snapshot(data, kpi("sum(amount)"), RELATION, d("2026-08-14"),
                Instant.parse("2026-08-14T10:00:00Z"));
        assertEquals(2, second.evaluated());
        assertEquals(4, second.rows());
        try (var files = Files.list(second.store())) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().endsWith(".parquet")).count());
        }
    }

    @Test
    void aChangedGrainRebuildsTheHistoryInsteadOfMixingPeriods(@TempDir Path data) throws Exception {
        KpiHistory.snapshot(data, kpi("sum(amount)"), RELATION, d("2026-08-13"), Instant.parse("2026-08-13T10:00:00Z"));
        KpiDefinition weekly = KpiDefinition.fromMap("revenue", Map.of("dataset", "orders", "measure", "sum(amount)",
                "timeField", "order_date", "grain", "week", "target", 20, "direction", "up"));

        KpiHistory.Summary s = KpiHistory.snapshot(data, weekly, RELATION, d("2026-08-14"), Instant.parse("2026-08-14T10:00:00Z"));

        List<KpiHistory.Row> rows = KpiHistory.read(s.store(), KpiHistory.definition(weekly));
        assertEquals(s.rows(), s.evaluated(), "every period re-evaluated: no month row survives");
        assertTrue(rows.stream().allMatch(r -> r.periodEnd().equals(r.periodStart().plusWeeks(1))), "only week-long rows");
        assertEquals(d("2026-05-18"), rows.get(0).periodStart());
        assertTrue(KpiHistory.read(s.store(), KpiHistory.definition(kpi("sum(amount)"))).isEmpty());
    }

    @Test
    void aDirectoryAnotherOwnerMadeIsRefused(@TempDir Path data) throws Exception {
        Files.createDirectories(data.resolve("kpi_history_revenue"));
        assertThrows(IllegalStateException.class, () -> KpiHistory.snapshot(data, kpi("sum(amount)"), RELATION,
                d("2026-08-13"), Instant.now()));
    }
}
