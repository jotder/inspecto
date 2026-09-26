package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReconBreaks} — the server-side port of the SPA's Break lifecycle (R2-03): the {@code mergeBreaks}
 * rules, the {@code breaksFromSets} expansion, and the {@code breakKeyOf} key text, which must equal what the
 * browser computes from the same JSON or the Breaks page's overlay misses every recorded Break.
 */
class ReconBreaksTest {

    private static final String RUN1 = "2026-07-01T00:00:00Z";
    private static final String RUN2 = "2026-08-01T00:00:00Z";

    private static ReconBreaks.Break open(String type, String key, String column) {
        return ReconBreaks.Break.fresh(key, null, type, column, null, null, null);
    }

    // ── merge: the locked lifecycle ─────────────────────────────────────────────────

    @Test
    void aNewBreakIsOpenAndStampedWithTheRunInstant() {
        List<ReconBreaks.Break> merged = ReconBreaks.merge(List.of(), List.of(open("missing_right", "MEA · voice", null)), RUN1);
        assertEquals(1, merged.size());
        assertEquals("open", merged.get(0).status());
        assertEquals(RUN1, merged.get(0).firstSeenAt());
    }

    /** The defect the aging mechanism exists to avoid: a fresh Break arrives unstamped on EVERY run. */
    @Test
    void aStillPresentBreakKeepsItsFirstSightingAcrossRuns() {
        List<ReconBreaks.Break> fresh = List.of(open("value_break", "EU · data", "amount"));
        List<ReconBreaks.Break> run1 = ReconBreaks.merge(List.of(), fresh, RUN1);
        List<ReconBreaks.Break> run2 = ReconBreaks.merge(run1, fresh, RUN2);
        List<ReconBreaks.Break> run3 = ReconBreaks.merge(run2, fresh, "2026-09-01T00:00:00Z");
        assertEquals(1, run3.size());
        assertEquals(RUN1, run3.get(0).firstSeenAt());
    }

    @Test
    void aResolvedBreakStaysResolvedWithItsNote() {
        ReconBreaks.Break resolved = open("value_break", "EU · data", "amount").withStatus("resolved", "known FX gap")
                .withFirstSeenAt(RUN1);
        List<ReconBreaks.Break> merged = ReconBreaks.merge(List.of(resolved),
                List.of(open("value_break", "EU · data", "amount")), RUN2);
        assertEquals("resolved", merged.get(0).status());
        assertEquals("known FX gap", merged.get(0).note());
        assertEquals(RUN1, merged.get(0).firstSeenAt());
    }

    @Test
    void aReopenNoteSurvivesTheNextRun() {
        ReconBreaks.Break reopened = open("missing_left", "APAC · sms", null).withStatus("open", "came back").withFirstSeenAt(RUN1);
        List<ReconBreaks.Break> merged = ReconBreaks.merge(List.of(reopened), List.of(open("missing_left", "APAC · sms", null)), RUN2);
        assertEquals("open", merged.get(0).status());
        assertEquals("came back", merged.get(0).note());
    }

    @Test
    void aBreakThatIsGoneAutoClosesAndAnAutoClosedOneThatStaysGoneIsDropped() {
        ReconBreaks.Break wasOpen = open("missing_right", "MEA · voice", null).withFirstSeenAt(RUN1);
        ReconBreaks.Break wasResolved = open("value_break", "EU · data", "amount").withStatus("resolved", "ok").withFirstSeenAt(RUN1);
        ReconBreaks.Break wasClosed = open("missing_left", "APAC · sms", null).withStatus("auto_closed", null);
        List<ReconBreaks.Break> merged = ReconBreaks.merge(List.of(wasOpen, wasResolved, wasClosed), List.of(), RUN2);
        assertEquals(2, merged.size(), "the already auto-closed one is dropped (bounded history)");
        assertEquals(List.of("auto_closed", "auto_closed"), merged.stream().map(ReconBreaks.Break::status).toList());
        assertEquals("ok", merged.get(1).note(), "an auto-closed Break keeps its note");
    }

    @Test
    void anAutoClosedBreakThatReappearsIsOpenAgainWithItsFirstSighting() {
        ReconBreaks.Break closed = open("missing_left", "APAC · sms", null).withStatus("auto_closed", "old").withFirstSeenAt(RUN1);
        List<ReconBreaks.Break> merged = ReconBreaks.merge(List.of(closed), List.of(open("missing_left", "APAC · sms", null)), RUN2);
        assertEquals("open", merged.get(0).status());
        assertNull(merged.get(0).note());
        assertEquals(RUN1, merged.get(0).firstSeenAt());
    }

    @Test
    void aBreakRecordedWithoutAStampIsStampedByTheNextRun() {
        ReconBreaks.Break appended = ReconBreaks.Break.identityOnly("value_break", "EU · data", "amount", "resolved", null);
        List<ReconBreaks.Break> merged = ReconBreaks.merge(List.of(appended), List.of(open("value_break", "EU · data", "amount")), RUN2);
        assertEquals(RUN2, merged.get(0).firstSeenAt());
        assertEquals("resolved", merged.get(0).status());
    }

    // ── identity (one contract with the SPA's breakId and the promote dedupe) ───────

    @Test
    void identityEscapesTheSeparatorSoDistinctTriplesStayDistinct() {
        assertEquals("value_break|EU\\|voice|amount", ReconBreaks.identity("value_break", "EU|voice", "amount"));
        assertNotEquals(ReconBreaks.identity("value_break", "EU|voice", "amount"),
                ReconBreaks.identity("value_break", "EU", "voice|amount"));
        assertEquals("missing_left|k|", ReconBreaks.identity("missing_left", "k", null));
    }

    // ── key text: byte-identical to the browser's String() of the JSON ──────────────

    @Test
    void numbersRenderAsJavaScriptRendersThem() {
        assertEquals("1", ReconBreaks.jsString(1.0));
        assertEquals("100", ReconBreaks.jsString(100.0));
        assertEquals("12.5", ReconBreaks.jsString(new BigDecimal("12.50")));
        assertEquals("0.5", ReconBreaks.jsString(0.5));
        assertEquals("0.000001", ReconBreaks.jsString(1e-6));
        assertEquals("1.5e-7", ReconBreaks.jsString(1.5e-7));
        assertEquals("100000000000000000000", ReconBreaks.jsString(1e20));
        assertEquals("1e+21", ReconBreaks.jsString(1e21));
        assertEquals("-42", ReconBreaks.jsString(-42L));
        assertEquals("0", ReconBreaks.jsString(-0.0));
        assertEquals("1.1", ReconBreaks.jsString(1.1f), "a float travels as its own shortest spelling");
        assertEquals("123456789012", ReconBreaks.jsString(123456789012L));
        assertEquals("", ReconBreaks.jsString(null));
        assertEquals("true", ReconBreaks.jsString(true));
    }

    @Test
    void keyTextJoinsTheKeyColumnsInOrder() {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("product", "voice");
        key.put("region", "EU");
        key.put("day", null);
        assertEquals("EU · voice · ", ReconBreaks.keyText(key, List.of("region", "product", "day")));
    }

    // ── fromSets: the breaksFromSets expansion ──────────────────────────────────────

    @Test
    void aValueBreakRowExpandsToOneBreakPerColumnOutsideTolerance() {
        ReconService.Spec spec = ReconService.Spec.of(
                List.of(new ReconService.Side("a", "SELECT 1", null, null), new ReconService.Side("b", "SELECT 1", null, null)),
                List.of("region"),
                List.of(new ReconService.Measure("amount", "sum", "percent", 0.5),
                        new ReconService.Measure("qty", "sum", "exact", 0)),
                false);
        Map<String, Object> row = Map.of("key", Map.of("region", "EU"),
                "a", Map.of("amount", 118.0, "qty", 3L), "b", Map.of("amount", 114.0, "qty", 3L));
        Map<String, ReconService.BreakSet> sets = new LinkedHashMap<>();
        sets.put("missing_right", new ReconService.BreakSet(List.of(Map.of("key", Map.of("region", "MEA"))), 1, false));
        sets.put("value_break", new ReconService.BreakSet(List.of(row), 1, false));

        List<ReconBreaks.Break> out = ReconBreaks.fromSets(spec, sets);
        assertEquals(2, out.size(), "qty is equal, so only amount breaks");
        assertEquals("missing_right", out.get(0).type());
        assertEquals("MEA", out.get(0).key());
        ReconBreaks.Break vb = out.get(1);
        assertEquals("value_break", vb.type());
        assertEquals("amount", vb.column());
        assertEquals(-4.0, vb.diff(), "diff is B − A");
        assertEquals("open", vb.status());
    }

    /** The 200-per-set page the Board used to merge — every Break past it was auto-closed. */
    @Test
    void computeRecordsEveryBreakNotAPage() throws Exception {
        ReconService.Spec spec = ReconService.Spec.of(
                List.of(new ReconService.Side("a", "SELECT range AS id, 1.0 AS amount FROM range(250)", null, null),
                        new ReconService.Side("b", "SELECT 0 AS id, 1.0 AS amount", null, null)),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), true);
        List<ReconBreaks.Break> all = ReconBreaks.compute(spec, ReconStateStore.MAX_BREAKS);
        assertEquals(249, all.size(), "ids 1..249 are only in A");
        assertTrue(all.stream().anyMatch(b -> b.key().equals("240")));
    }

    @Test
    void computeRefusesASetOverTheCapRatherThanRecordingItShort() {
        ReconService.Spec spec = ReconService.Spec.of(
                List.of(new ReconService.Side("a", "SELECT range AS id, 1.0 AS amount FROM range(30)", null, null),
                        new ReconService.Side("b", "SELECT 0 AS id, 1.0 AS amount", null, null)),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), true);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> ReconBreaks.compute(spec, 10));
        assertTrue(refused.getMessage().contains("too many to record"), refused.getMessage());
    }
}
