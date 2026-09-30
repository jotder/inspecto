package com.gamma.geolink;

import com.gamma.alert.AlertRule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-18 — each named value Measure over a whole relation, with its default (visible) thresholds, on a small planted
 * ledger: a structuring ring into HUB that HUB forwards, five CASHERs cashing out at TILL6, a benefit skim into SK,
 * one 6 000 transfer, one unvalued row and one row outside the window.
 */
public class ValueMeasuresTest {

    public static final String ROWS;

    static {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) row(sb, "S" + i, "HUB", "cash_deposit", "950", "2026-09-01 10:00:00");
        row(sb, "HUB", "R1", "wire", "11000", "2026-09-01 20:00:00");
        row(sb, "X", "Y", "wire", "6000", "2026-09-01 12:00:00");
        for (int i = 1; i <= 5; i++) {
            row(sb, "H2", "C" + i, "wire", "12000", "2026-09-01 09:00:00");
            row(sb, "C" + i, "TILL6", "cash_out", "11500", "2026-09-01 11:00:00");
        }
        row(sb, "N1", "TILL1", "cash_out", "100", "2026-09-01 15:00:00");
        for (int i = 1; i <= 3; i++) {
            row(sb, "GOV", "B" + i, "benefit", "450", "2026-09-01 07:00:00");
            row(sb, "B" + i, "SK", "wire", "270", "2026-09-01 12:00:00");
        }
        row(sb, "Q", "HUB", "wire", "n/a", "2026-09-01 10:00:00");     // unvalued: skipped and counted
        row(sb, "Z", "HUB", "cash_deposit", "950", "2026-10-15 10:00:00");   // outside the window
        ROWS = "SELECT * FROM (VALUES " + sb.substring(1) + ") AS t(src, dst, ch, amt, booked)";
    }

    private static void row(StringBuilder sb, String s, String t, String k, String v, String at) {
        sb.append(",('").append(s).append("','").append(t).append("','").append(k).append("','").append(v)
                .append("','").append(at).append("')");
    }

    static Map<String, Object> block(String name, Object... kv) {
        Map<String, Object> m = new HashMap<>(Map.of("name", name, "valueCol", "amt", "timeCol", "booked",
                "from", "2026-09-01", "to", "2026-09-08"));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static ValueMeasures.Result run(Map<String, Object> block) throws Exception {
        return run(ROWS, block);
    }

    private static ValueMeasures.Result run(String relation, Map<String, Object> block) throws Exception {
        return ValueMeasures.evaluate("tx", relation, "src", "dst", "ch", ValueMeasures.parse(block, false));
    }

    private static List<Object> entities(ValueMeasures.Result r) {
        return r.entities().stream().map(e -> e.get("entity")).toList();
    }

    @Test
    void structuringFindsTheSubThresholdRingOverTheWholeRelation() throws Exception {
        ValueMeasures.Result r = run(block("structuring"));
        assertEquals(List.of("HUB"), entities(r));
        assertEquals(12L, ((Number) r.entities().get(0).get("legs")).longValue());
        assertEquals(12L, ((Number) r.entities().get(0).get("payers")).longValue());
        assertEquals(1, r.unvalued(), "the 'n/a' row is skipped AND counted");
        assertFalse(r.truncated());
    }

    @Test
    void passThroughAnswersTheRatioAndRetentionAsADerivedLabel() throws Exception {
        ValueMeasures.Result r = run(block("passThrough"));
        assertEquals(List.of("HUB", "C1", "C2", "C3", "C4", "C5"), entities(r),
                "HUB forwards 11 000 of 11 400 (0.965), each CASHER 11 500 of 12 000 (0.958); TILL6 keeps all");
        assertEquals(1 - 11000.0 / 11400, ((Number) r.entities().get(0).get("retention")).doubleValue(), 1e-9);
        assertEquals(List.of("HUB"), entities(run(block("passThrough", "minRatio", 0.96))), "editable");
    }

    @Test
    void velocityAndTimeToCashOutMeasureHoursToTheNextOutboundLink() throws Exception {
        assertEquals(List.of("C1", "C2", "C3", "C4", "C5", "HUB"), entities(run(block("velocity"))),
                "C* forward in 2 h, HUB in 10 h");
        assertEquals(List.of("C1", "C2", "C3", "C4", "C5"),
                entities(run(block("timeToCashOut", "cashOutKinds", List.of("cash_out")))),
                "only a cash_out link counts — HUB's wire does not");
        assertEquals(List.of(), entities(run(block("velocity", "maxHours", 1))), "the threshold is editable");
    }

    @Test
    void cashOutConcentrationAndBenefitTransfer() throws Exception {
        ValueMeasures.Result c = run(block("cashOutConcentration", "cashOutKinds", "cash_out"));
        assertEquals(List.of("TILL6"), entities(c));
        assertEquals(5L, ((Number) c.entities().get(0).get("payers")).longValue());
        // the fixture holds exactly 3 recipients, so its threshold is stated (the default is ≥ 5 since 2026-09-30)
        assertEquals(List.of("SK"), entities(run(block("benefitTransfer", "benefitKinds", List.of("benefit"),
                "minRecipients", 3))));
        assertEquals(List.of(), entities(run(block("benefitTransfer", "benefitKinds", List.of("benefit")))),
                "3 recipients stay under the ≥ 5 default");
        assertEquals(List.of(), entities(run(block("benefitTransfer", "benefitKinds", "benefit", "minRecipients", 4))));
    }

    @Test
    void valueWeightedLinksSumsEachPair() throws Exception {
        ValueMeasures.Result r = run(block("valueWeightedLinks"));
        assertEquals("C1", r.entities().get(0).get("target"), "the heaviest pair first (12 000)");
        assertEquals(12000.0, ((Number) r.entities().get(0).get("total")).doubleValue());
    }

    @Test
    void parseFillsVisibleDefaultsAndRefusesWhatItCannotRead() {
        ValueMeasures.Spec s = ValueMeasures.parse(block("structuring"), true);
        assertEquals(Map.of("min", 900.0, "max", 1000.0, "minLegs", 10.0, "minPayers", 5.0), s.thresholds());
        assertEquals("≥ 10 legs 900 ≤ amt < 1000 from ≥ 5 payers", ValueMeasures.label(s));
        for (Map<String, Object> bad : List.of(block("median"), block("structuring", "filter", "amt>=5000"),
                block("structuring", "valueCol", "amt; DROP"), block("structuring", "to", "2026-08-01"),
                block("structuring", "to", "2026-10-15"), block("structuring", "min", "-1"),
                block("structuring", "min", 1000, "max", 900), block("timeToCashOut"),
                block("structuring", "from", "yesterday")))
            assertThrows(IllegalArgumentException.class, () -> ValueMeasures.parse(bad, false), bad.toString());
        assertThrows(IllegalArgumentException.class, () -> ValueMeasures.parse(block("valueWeightedLinks"), true),
                "a weighting is readable, never alertable");
    }

    /** The one hand-kept mirror this change adds: the engine's alertable names = this module's. */
    @Test
    void theEnginesValueMeasureMirrorMatches() {
        assertEquals(ValueMeasures.ALERTABLE, AlertRule.VALUE_MEASURES);
    }

    /** The shipped demo corpus (stories (a)–(d) plus LA-18's (f)–(g)) exercises every alertable Measure. */
    @Test
    void theDemoCorpusExercisesEveryMeasure() throws Exception {
        String demo = "SELECT * FROM read_csv('../spaces/demo/data/samples/mule_transfers/*.csv', header = true, "
                + "all_varchar = true)";
        java.util.function.Function<Map<String, Object>, List<Object>> on = b -> {
            b.putAll(Map.of("valueCol", "AMOUNT", "timeCol", "BOOKED_AT", "from", "2026-09-01", "to", "2026-09-04"));
            try {
                return entities(ValueMeasures.evaluate("tx", demo, "PAYER_ACCOUNT", "PAYEE_ACCOUNT", "CHANNEL",
                        ValueMeasures.parse(b, true)));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        assertEquals(List.of("MULE-HUB-01"), on.apply(new HashMap<>(Map.of("name", "structuring"))),
                "story (a): the smurf deposits — none ≥ 5 000 — still found over the whole Dataset");
        List<Object> pass = on.apply(new HashMap<>(Map.of("name", "passThrough")));
        for (String e : List.of("MULE-HUB-01", "RELAY-01", "RELAY-02", "CASHER-01", "CASHER-08"))
            assertTrue(pass.contains(e), e + " in " + pass);
        assertTrue(pass.stream().noneMatch(e -> String.valueOf(e).startsWith("ACC-")), "no ordinary account: " + pass);
        List<Object> ttc = on.apply(new HashMap<>(Map.of("name", "timeToCashOut", "cashOutKinds", "cash_out")));
        assertEquals(8, ttc.size(), "story (f): the eight CASHERs, and only them: " + ttc);
        assertTrue(ttc.stream().allMatch(e -> String.valueOf(e).startsWith("CASHER-")), ttc.toString());
        assertEquals(List.of("TILL-06"),
                on.apply(new HashMap<>(Map.of("name", "cashOutConcentration", "cashOutKinds", "cash_out"))));
        List<Object> benefit = on.apply(new HashMap<>(Map.of("name", "benefitTransfer", "benefitKinds", "benefit")));
        // Operator 2026-09-30: the default is ≥ 5 recipients — at ≥ 3 eight ordinary accounts also qualified on
        // this corpus; at the default only the planted skimmer does (plan §2.6.1).
        assertEquals(List.of("SKIMMER-01"), benefit, "story (g) alone at the default: " + benefit);
        assertTrue(on.apply(new HashMap<>(Map.of("name", "benefitTransfer", "benefitKinds", "benefit",
                "minRecipients", 3))).size() > 1, "the looser ≥ 3 twin still flags ordinary accounts");
    }

    @Test
    void theWindowIsHalfOpenAndReadsTimestampsAsTheyAre() throws Exception {
        assertTrue(entities(run(block("structuring", "from", "2026-09-01T10:00:01"))).isEmpty(),
                "every smurf leg is at 10:00:00 — before the window");
    }
}
