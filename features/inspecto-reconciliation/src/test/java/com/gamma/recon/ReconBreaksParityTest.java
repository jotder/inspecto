package com.gamma.recon;

import com.gamma.query.DatasetRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RECON-UPLIFT-1 B1 parity: {@link ReconBreaks#compute} now reads every Break set off ONE shared {@code __grain}; it
 * must return exactly the Breaks the per-type, per-pair scans returned before - same keys, types, values, order.
 *
 * <p>Two oracles, so the guard outlives the old code: {@link #legacy} re-runs the ORIGINAL path ({@link ReconService#breaks}
 * per pair + {@link ReconBreaks#fromSets}, still the {@code /recon/breaks} route's SQL) and must agree live; and each
 * fixture's {@code GOLDEN} is the stored result of that old path, recorded when B1 landed. A faster result that differs by
 * one Break is a failure, so a change to either path shows here first.
 */
class ReconBreaksParityTest {

    private static final int CAP = ReconStateStore.MAX_BREAKS;

    @BeforeEach
    void clear() { ReconDay.Cache.clear(); }

    // ── the oracle ──────────────────────────────────────────────────────────────────

    /** The pre-B1 {@code ReconBreaks.compute}: one {@link ReconService#breaks} scan set per pair, then {@code fromSets}. */
    private static List<ReconBreaks.Break> legacy(ReconService.Spec spec, int cap) throws Exception {
        List<ReconBreaks.Break> out = new ArrayList<>();
        for (int other = 1; other < spec.sides().size(); other++) {
            String pair = ReconBreaks.PAIRS.get(other - 1);
            Map<String, ReconService.BreakSet> sets = ReconService.breaks(spec, null, null, other, cap, 0);
            for (Map.Entry<String, ReconService.BreakSet> e : sets.entrySet())
                if (e.getValue().truncated())
                    throw new IllegalArgumentException("reconciliation has more than " + cap + " " + pair + " "
                            + e.getKey() + " Breaks — too many to record; narrow it (filters, tolerances) before recording a run");
            for (ReconBreaks.Break b : ReconBreaks.fromSets(spec, sets)) out.add(b.withPair(pair));
        }
        if (out.size() > cap)
            throw new IllegalArgumentException("reconciliation has " + out.size() + " Breaks, more than the "
                    + cap + " a run may record; narrow it (filters, tolerances) before recording a run");
        return out;
    }

    /** Every field of a Break that a recorded run carries, in the order {@code compute} emits them. */
    private static List<String> render(List<ReconBreaks.Break> breaks) {
        List<String> out = new ArrayList<>();
        for (ReconBreaks.Break b : breaks)
            out.add(b.pair() + "|" + b.type() + "|" + b.key() + "|" + b.column() + "|" + b.leftValue() + "|" + b.rightValue()
                    + "|" + b.diff() + "|" + b.keyValues() + "|" + b.status());
        return out;
    }

    /** New path == live legacy path == the stored expected result, in the same order. */
    private static void check(ReconService.Spec spec, List<String> golden) throws Exception {
        List<String> expected = render(legacy(spec, CAP));
        List<String> actual = render(ReconBreaks.compute(spec, CAP));
        assertEquals(expected, actual, "shared-grain Breaks differ from the per-type scans");
        assertEquals(golden, actual, "differs from the stored expected result; actual = " + actual);
    }

    private static ReconService.Side side(String id, String values, String cols) {
        return new ReconService.Side(id, "SELECT * FROM (VALUES " + values + ") AS t(" + cols + ")", null, null);
    }

    private static ReconService.Spec spec(List<ReconService.Side> sides, List<String> keys, List<ReconService.Measure> ms,
                                          ReconService.Cardinality card) {
        return ReconService.Spec.of(sides, keys, ms, true, card);
    }

    // ── 2-way ───────────────────────────────────────────────────────────────────────

    private static ReconService.Spec twoWay() {
        String cols = "region, product, amount, qty";
        return spec(List.of(
                side("a", "('EU','voice',100.0,1),('EU','voice',100.0,1),('EU','data',118.0,2),('US','voice',50.0,1),"
                        + "('MEA','voice',10.0,1),('LAT','x',5.0,1)", cols),
                side("b", "('EU','voice',200.0,2),('EU','data',114.0,2),('US','voice',50.0,3),('APAC','sms',7.0,1),"
                        + "('LAT','x',5.0,1)", cols)),
                List.of("region", "product"),
                List.of(new ReconService.Measure("amount", "sum", "percent", 0.5), new ReconService.Measure("qty", "sum", "exact", 0)),
                ReconService.Cardinality.MANY_TO_MANY);
    }

    private static final List<String> GOLDEN_TWO_WAY = List.of(
            "AB|missing_right|MEA · voice|null|null|null|null|null|open",
            "AB|missing_left|APAC · sms|null|null|null|null|null|open",
            "AB|value_break|EU · data|amount|118.0|114.0|-4.0|null|open",
            "AB|value_break|US · voice|qty|1|3|2.0|null|open");

    @Test
    void twoWayFixture() throws Exception {
        check(twoWay(), GOLDEN_TWO_WAY);
    }

    // ── 3-way: each pair has its own Breaks, and a key can sit on any subset of the sides ─

    private static ReconService.Spec threeWay(ReconService.Cardinality card) {
        String cols = "id, amount";
        return spec(List.of(
                side("a", "(0,1.0),(1,1.0),(2,1.0),(3,1.0),(4,1.0),(5,1.0),(6,1.0),(7,1.0),(7,1.0)", cols),
                side("b", "(1,1.0),(2,1.0),(3,2.0),(4,1.0),(5,1.0),(6,1.0),(7,1.0),(8,1.0)", cols),
                side("c", "(2,1.0),(3,1.0),(4,5.0),(5,1.0),(7,2.0),(8,1.0),(9,1.0),(9,1.0)", cols)),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), card);
    }

    private static final List<String> GOLDEN_THREE_WAY = List.of(
            "AB|missing_right|0|null|null|null|null|null|open",
            "AB|missing_left|8|null|null|null|null|null|open",
            "AB|value_break|3|amount|1.0|2.0|1.0|null|open",
            "AB|value_break|7|amount|2.0|1.0|-1.0|null|open",
            "AC|missing_right|0|null|null|null|null|null|open",
            "AC|missing_right|1|null|null|null|null|null|open",
            "AC|missing_right|6|null|null|null|null|null|open",
            "AC|missing_left|8|null|null|null|null|null|open",
            "AC|missing_left|9|null|null|null|null|null|open",
            "AC|value_break|4|amount|1.0|5.0|4.0|null|open");

    @Test
    void threeWayFixture() throws Exception {
        check(threeWay(ReconService.Cardinality.MANY_TO_MANY), GOLDEN_THREE_WAY);
    }

    // ── the day-pruned path: ReconDay.resolve hands compute a day-filtered, file-pruned spec ─

    private static final List<String> GOLDEN_DAY_PRUNED = List.of(
            "AB|missing_right|m1|null|null|null|null|null|open",
            "AB|missing_left|m5|null|null|null|null|null|open",
            "AB|value_break|m2|active|0|1|1.0|null|open");

    @Test
    void dayPrunedFixture(@TempDir Path data) throws Exception {
        for (Object[] s : new Object[][]{
                {"a", "('m1',1,'2026-09-26'),('m2',0,'2026-09-26'),('m3',1,'2026-09-26')", "('m8',1,'2026-09-25'),('m9',0,'2026-09-25')"},
                {"b", "('m2',1,'2026-09-26'),('m3',1,'2026-09-26'),('m5',1,'2026-09-26')", "('m8',0,'2026-09-25')"}}) {
            ReconDayPruneTest.write(data, (String) s[0], "day=26", (String) s[1], "DATE", null);
            ReconDayPruneTest.write(data, (String) s[0], "day=25", (String) s[2], "DATE", null);
        }
        Map<String, Object> cfg = Map.of("datasets", List.of("a", "b"), "keyColumns", List.of("msisdn"),
                "compareColumns", List.of(Map.of("column", "active")));
        ReconDay.Scoped scoped = ReconDay.resolve(cfg, id -> Map.of("physicalRef", id, "dateField", "event_date"),
                id -> DatasetRelation.relationSql(Map.of("physicalRef", id, "dateField", "event_date"), data, null), data, "2026-09-26");
        assertFalse(scoped.spec().sides().get(0).relationSql().contains("day=25"), "the other day's file is pruned");
        check(scoped.spec(), GOLDEN_DAY_PRUNED);
    }

    // ── tolerance edge: a delta exactly on the tolerance matches, one hair over breaks ──

    private static final List<String> GOLDEN_ABSOLUTE_EDGE = List.of(
            "AB|value_break|nullVsZero|amount|null|0E-7|null|null|open",
            "AB|value_break|over|amount|10.0|10.5000001|0.5000000999999994|null|open");

    @Test
    void toleranceEdge() throws Exception {
        String cols = "id, amount";
        check(spec(List.of(
                side("a", "('on',10.0),('over',10.0),('under',10.0),('nullL',NULL),('nullBoth',NULL),('nullVsZero',NULL)", cols),
                side("b", "('on',10.5),('over',10.5000001),('under',9.5),('nullL',NULL),('nullBoth',NULL),('nullVsZero',0.0)", cols)),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "absolute", 0.5)),
                ReconService.Cardinality.MANY_TO_MANY), GOLDEN_ABSOLUTE_EDGE);
    }

    private static final List<String> GOLDEN_PERCENT_EDGE = List.of(
            "AB|value_break|over|amount|100.0|105.01|5.010000000000005|null|open",
            "AB|value_break|zeroLeft|amount|0.0|0.10|0.1|null|open");

    /** Percent is left-relative: 5% of 100 is 5, and a zero left needs exact equality. */
    @Test
    void percentToleranceEdge() throws Exception {
        String cols = "id, amount";
        check(spec(List.of(
                side("a", "('on',100.0),('over',100.0),('zeroLeft',0.0),('zeroBoth',0.0)", cols),
                side("b", "('on',105.0),('over',105.01),('zeroLeft',0.1),('zeroBoth',0.0)", cols)),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "percent", 5)),
                ReconService.Cardinality.MANY_TO_MANY), GOLDEN_PERCENT_EDGE);
    }

    // ── a duplicate key: a cardinality break, on the matched key only ───────────────

    private static ReconService.Spec duplicateKey(ReconService.Cardinality card) {
        String cols = "id, amount";
        return spec(List.of(
                side("a", "('d1',1.0),('d1',1.0),('d2',2.0),('d3',3.0),('only_a',9.0)", cols),
                side("b", "('d1',2.0),('d2',1.0),('d2',1.0),('d3',3.0),('only_b',9.0)", cols)),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), card);
    }

    private static final List<String> GOLDEN_DUPLICATE_ONE_TO_ONE = List.of(
            "AB|missing_right|only_a|null|null|null|null|null|open",
            "AB|missing_left|only_b|null|null|null|null|null|open",
            "AB|cardinality_break|d1|null|2|1|null|{id=d1}|open",
            "AB|cardinality_break|d2|null|1|2|null|{id=d2}|open");

    @Test
    void duplicateKeyFixture() throws Exception {
        check(duplicateKey(ReconService.Cardinality.ONE_TO_ONE), GOLDEN_DUPLICATE_ONE_TO_ONE);
    }

    /** The other assertions and a 3-way spec carrying one: parity with the live path (the golden rows above pin the shape). */
    @Test
    void everyCardinalityMatchesTheLivePath() throws Exception {
        for (ReconService.Cardinality c : ReconService.Cardinality.values()) {
            assertEquals(render(legacy(duplicateKey(c), CAP)), render(ReconBreaks.compute(duplicateKey(c), CAP)), c.name());
            assertEquals(render(legacy(threeWay(c), CAP)), render(ReconBreaks.compute(threeWay(c), CAP)), "3-way " + c.name());
        }
    }

    // ── a NULL key is one group that matches itself, NULL-safely ────────────────────

    private static final List<String> GOLDEN_NULL_KEY = List.of(
            "AB|missing_right|k1 · y|null|null|null|null|null|open",
            "AB|missing_left|k2 · z|null|null|null|null|null|open",
            "AB|value_break|n1 · a|amount|null|1.0|null|null|open",
            "AB|value_break| · |amount|3.0|4.0|1.0|null|open");

    @Test
    void nullKeyFixture() throws Exception {
        String cols = "region, product, amount";
        check(spec(List.of(
                side("a", "(NULL,'x',1.0),('k1','y',2.0),(NULL,NULL,3.0),('n1','a',NULL)", cols),
                side("b", "(NULL,'x',1.0),(NULL,NULL,4.0),('k2','z',5.0),('n1','a',1.0)", cols)),
                List.of("region", "product"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)),
                ReconService.Cardinality.MANY_TO_MANY), GOLDEN_NULL_KEY);
    }

    // ── no compare columns (record count only) and the cap refusal ──────────────────

    @Test
    void recordCountOnlyHasNoValueBreaks() throws Exception {
        ReconService.Spec spec = ReconService.Spec.of(List.of(side("a", "(1),(2),(3)", "id"), side("b", "(2),(3),(4)", "id")),
                List.of("id"), List.of(), true);
        assertEquals(render(legacy(spec, CAP)), render(ReconBreaks.compute(spec, CAP)));
        assertEquals(List.of("AB|missing_right|1|null|null|null|null|null|open", "AB|missing_left|4|null|null|null|null|null|open"),
                render(ReconBreaks.compute(spec, CAP)));
    }

    /** Refused with the same words at the same cap: one set over it, and both pairs together over it. */
    @Test
    void theCapRefusesExactlyAsBefore() {
        for (int cap : new int[]{1, 2, 3, 4, 5, 6}) {
            for (ReconService.Spec spec : List.of(twoWay(), threeWay(ReconService.Cardinality.MANY_TO_MANY))) {
                String before = outcome(() -> render(legacy(spec, cap)));
                assertEquals(before, outcome(() -> render(ReconBreaks.compute(spec, cap))), "cap " + cap);
            }
        }
        assertTrue(outcome(() -> render(ReconBreaks.compute(twoWay(), 1))).startsWith("refused:"));
    }

    private interface Run { List<String> go() throws Exception; }

    private static String outcome(Run r) {
        try {
            return r.go().toString();
        } catch (IllegalArgumentException refused) {
            return "refused: " + refused.getMessage();
        } catch (Exception e) {
            return "failed: " + e;
        }
    }

    // ── the grain feeding the Job: summary and Breaks from ONE sandbox ──────────────

    /** The summary read off the shared grain equals the one {@code dayRun} reports, and the Breaks equal {@code compute}'s. */
    @Test
    void oneGrainServesTheSummaryAndTheBreaks() throws Exception {
        for (ReconService.Spec spec : List.of(twoWay(), threeWay(ReconService.Cardinality.ONE_TO_ONE))) {
            try (ReconService.Grain grain = ReconService.openGrain(spec)) {
                assertEquals(ReconService.dayRun(spec, 0, 0, 0, 0, 0).day().summary(), grain.summary());
                assertEquals(render(ReconBreaks.compute(spec, CAP)), render(ReconBreaks.compute(grain, CAP)));
            }
        }
    }

    /**
     * A key whose TYPE differs between the sides (DATE vs TIMESTAMP): the grain's unified key would read back as a
     * timestamp and change the key text, hence the recorded Break's id. Such a Reconciliation must take the original
     * per-type scans, so each key keeps its own side's type (found by the independent review, 2026-10-18).
     */
    @Test
    void aKeyWhoseTypeDiffersBetweenSidesKeepsItsOwnSidesText() throws Exception {
        ReconService.Spec spec = spec(List.of(
                side("a", "(DATE '2026-01-01',1.0),(DATE '2026-01-02',2.0)", "d, amount"),
                side("b", "(TIMESTAMP '2026-01-01 00:00:00',1.0),(TIMESTAMP '2026-01-03 00:00:00',2.0)", "d, amount")),
                List.of("d"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), ReconService.Cardinality.MANY_TO_MANY);
        List<String> expected = render(legacy(spec, CAP));
        List<String> actual = render(ReconBreaks.compute(spec, CAP));
        assertEquals(expected, actual, "mixed key types must give exactly the per-type scans' Breaks");
        assertTrue(actual.stream().anyMatch(b -> b.contains("missing_right|2026-01-02|")),
                "the anchor-only key reads as A's DATE, not a timestamp: " + actual);
        assertFalse(actual.stream().anyMatch(b -> b.contains("missing_right|2026-01-02 00:00")), actual.toString());
    }

    // ── RECON-UPLIFT-RESIDUALS-1 (6): the reviewer's parity gaps — each must agree with the live per-type scans ──

    /** Live parity, plus the Breaks it must contain. */
    private static List<String> parity(ReconService.Spec spec) throws Exception {
        List<String> actual = render(ReconBreaks.compute(spec, CAP));
        assertEquals(render(legacy(spec, CAP)), actual, "shared-grain Breaks differ from the per-type scans");
        return actual;
    }

    @Test
    void aColumnMapRenameReadsEachSidesOwnColumn() throws Exception {
        ReconService.Side b = new ReconService.Side("b",
                "SELECT * FROM (VALUES (1,1.0),(2,3.0),(4,1.0)) AS t(ident, amt)", Map.of("id", "ident", "amount", "amt"), null);
        List<String> out = parity(spec(List.of(side("a", "(1,1.0),(2,2.0),(3,1.0)", "id, amount"), b),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), ReconService.Cardinality.MANY_TO_MANY));
        assertEquals(List.of("AB|missing_right|3|null|null|null|null|null|open", "AB|missing_left|4|null|null|null|null|null|open",
                "AB|value_break|2|amount|2.0|3.0|1.0|null|open"), out);
    }

    @Test
    void anEmptySideMakesEveryKeyMissing() throws Exception {
        ReconService.Side empty = new ReconService.Side("b",
                "SELECT * FROM (VALUES (1,1.0)) AS t(id, amount) WHERE false", null, null);
        List<String> out = parity(spec(List.of(side("a", "(1,1.0),(2,2.0)", "id, amount"), empty),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), ReconService.Cardinality.ONE_TO_ONE));
        assertEquals(List.of("AB|missing_right|1|null|null|null|null|null|open", "AB|missing_right|2|null|null|null|null|null|open"), out);
        List<String> mirrored = parity(spec(List.of(empty, side("a", "(1,1.0)", "id, amount")),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), ReconService.Cardinality.ONE_TO_ONE));
        assertEquals(List.of("AB|missing_left|1|null|null|null|null|null|open"), mirrored);
    }

    @Test
    void aNullMeasureOnOneSideOfAThreeWay() throws Exception {
        String cols = "id, amount";
        List<String> out = parity(spec(List.of(
                side("a", "(1,1.0),(2,1.0)", cols), side("b", "(1,NULL),(2,1.0)", cols), side("c", "(1,1.0),(2,NULL)", cols)),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), ReconService.Cardinality.MANY_TO_MANY));
        assertTrue(out.contains("AB|value_break|1|amount|1.0|null|null|null|open"), out.toString());
        assertTrue(out.contains("AC|value_break|2|amount|1.0|null|null|null|open"), out.toString());
        assertEquals(2, out.size(), out.toString());
    }

    @Test
    void aNullInOneColumnOfAMultiColumnKey() throws Exception {
        String cols = "region, product, amount";
        List<String> out = parity(spec(List.of(
                side("a", "('EU',NULL,1.0),('EU','x',2.0),(NULL,'y',3.0)", cols),
                side("b", "('EU',NULL,1.5),('EU','x',2.0),('US','y',3.0)", cols)),
                List.of("region", "product"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)),
                ReconService.Cardinality.ONE_TO_ONE));
        assertTrue(out.stream().anyMatch(b -> b.startsWith("AB|value_break|EU · |amount|1.0|1.5")), out.toString());
        assertTrue(out.stream().anyMatch(b -> b.startsWith("AB|missing_right| · y|")), out.toString());
        assertTrue(out.stream().anyMatch(b -> b.startsWith("AB|missing_left|US · y|")), out.toString());
    }

    /** RECON-UPLIFT-RESIDUALS-1 (5): a grain Break set no longer carries a misleading {@code impact {a:null,b:null}}. */
    @Test
    void grainBreakSetsCarryNoImpactBlock() throws Exception {
        ReconService.Spec spec = spec(List.of(side("a", "(1,1.0,5.0),(2,1.0,6.0)", "id, amount, fee"),
                        side("b", "(1,2.0,5.0)", "id, amount, fee")),
                List.of("id"), List.of(new ReconService.Measure("amount", "sum", "exact", 0)), ReconService.Cardinality.MANY_TO_MANY)
                .withImpact("fee");
        try (ReconService.Grain grain = ReconService.openGrain(spec)) {
            for (ReconService.BreakSet set : grain.breakSets(1, CAP).values())
                for (Map<String, Object> row : set.rows()) assertFalse(row.containsKey("impact"), row.toString());
        }
        Map<String, ReconService.BreakSet> live = ReconService.breaks(spec, null, null, 1, CAP, 0);
        assertTrue(live.get("value_break").rows().get(0).containsKey("impact"), "the /recon/breaks route keeps its impact");
    }
}
