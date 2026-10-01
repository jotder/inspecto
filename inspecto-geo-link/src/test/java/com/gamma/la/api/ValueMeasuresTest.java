package com.gamma.la.api;

import com.gamma.alert.AlertRule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ── rolling windows (`last`) ──────────────────────────────────────────────────────────────────────

    private static Map<String, Object> rolling(String name, String last, Object... kv) {
        Map<String, Object> m = block(name);
        m.remove("from");
        m.remove("to");
        m.put("last", last);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static java.time.Clock at(String utcInstant) {
        return java.time.Clock.fixed(java.time.Instant.parse(utcInstant), java.time.ZoneOffset.UTC);
    }

    /** Live check 2026-09-30 (plan §5.10): a from/to with Z or an offset is normalised to UTC, not refused. */
    @Test
    void aFixedWindowWithZOrAnOffsetIsNormalisedToUtc() {
        ValueMeasures.Spec z = ValueMeasures.parse(block("structuring", "from", "2026-09-01T00:00:00Z",
                "to", "2026-09-08T00:00:00Z"), true);
        assertEquals("2026-09-01T00:00", z.from());
        assertEquals("2026-09-08T00:00", z.to());
        ValueMeasures.Spec off = ValueMeasures.parse(block("structuring", "from", "2026-09-01T02:00:00+02:00",
                "to", "2026-09-07T19:30:00-04:30"), true);
        assertEquals("2026-09-01T00:00", off.from(), "shifted to UTC");
        assertEquals("2026-09-08T00:00", off.to(), "shifted to UTC");
        // the twin: a zone-less value stays as written (already read as UTC)
        assertEquals("2026-09-01T02:00", ValueMeasures.parse(block("structuring", "from", "2026-09-01T02:00:00",
                "to", "2026-09-02T00:00:00"), true).from());
    }

    @Test
    void aRollingWindowResolvesAgainstTheClockInUtcAndIsStoredRelative() {
        ValueMeasures.Spec s = ValueMeasures.parse(rolling("structuring", "24h"), true, at("2026-09-02T00:00:00.750Z"));
        assertEquals("2026-09-01T00:00", s.from());
        assertEquals("2026-09-02T00:00", s.to(), "truncated to the second, UTC");
        ValueMeasures.Spec later = ValueMeasures.parse(rolling("structuring", "7d"), true, at("2026-09-30T12:00:00Z"));
        assertEquals("2026-09-23T12:00", later.from());
        assertEquals("2026-09-30T12:00", later.to());
        Map<String, Object> stored = s.toMap();
        assertEquals("24h", stored.get("last"));
        assertFalse(stored.containsKey("from") || stored.containsKey("to"), "stored relative, never a baked window");
        // a re-parse of the stored block at a later clock moves the window (what the sweep does)
        assertEquals("2026-09-09T00:00", ValueMeasures.parse(stored, true, at("2026-09-10T00:00:00Z")).from());
    }

    @Test
    void aRollingWindowFollowsTheClockOverTheData() throws Exception {
        java.util.function.Function<String, List<Object>> on = now -> {
            try {
                return entities(ValueMeasures.evaluate("tx", ROWS, "src", "dst", "ch",
                        ValueMeasures.parse(rolling("structuring", "24h"), false, at(now))));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        assertEquals(List.of("HUB"), on.apply("2026-09-02T00:00:00Z"), "the ring sits inside the last 24 h");
        assertEquals(List.of(), on.apply("2026-09-10T00:00:00Z"), "a week later the window has moved past it");
    }

    @Test
    void exactlyOneWindowAndTheSameCap() {
        for (Map<String, Object> bad : List.of(rolling("structuring", "7d", "from", "2026-09-01", "to", "2026-09-08"),
                rolling("structuring", "7d", "to", "2026-09-08"), rolling("structuring", "7w"),
                rolling("structuring", "0d"), rolling("structuring", "-1d"), rolling("structuring", "32d"),
                rolling("structuring", "745h")))
            assertThrows(IllegalArgumentException.class, () -> ValueMeasures.parse(bad, true), bad.toString());
        Map<String, Object> none = block("structuring");
        none.remove("from");
        none.remove("to");
        assertThrows(IllegalArgumentException.class, () -> ValueMeasures.parse(none, true), "no window at all");
        assertEquals("31d", ValueMeasures.parse(rolling("structuring", "31d"), true).last());
        assertEquals("744h", ValueMeasures.parse(rolling("structuring", "744h"), true).last(), "744 h = 31 days");
    }

    // ── cashOutConcentration restricted to an `agent` Entity List ────────────────────────────────────

    private static ValueMeasures.Result agents(ValueMeasures.Agents a, Object... kv) throws Exception {
        Object[] all = new Object[kv.length + 4];
        all[0] = "cashOutKinds";
        all[1] = "cash_out";
        all[2] = "agentList";
        all[3] = "tills";
        System.arraycopy(kv, 0, all, 4, kv.length);
        return ValueMeasures.evaluate("tx", ROWS, "src", "dst", "ch",
                ValueMeasures.parse(block("cashOutConcentration", all), false), a);
    }

    @Test
    void anAgentListRestrictsTheAgentsButNotTheShareOfAllCashOut() throws Exception {
        // the list's sealed normaliser (upper-trim) matches a member stated in another form
        ValueMeasures.Agents till1 = new ValueMeasures.Agents("upper-trim", Set.of("TILL1"), null);
        assertEquals(List.of(), entities(agents(till1)), "TILL1 takes 100 of 57 600 — under the default share");
        ValueMeasures.Result loose = agents(till1, "minShare", 0, "minPayers", 1);
        assertEquals(List.of("TILL1"), entities(loose), "TILL6 is not on the list, so it is not answered");
        assertEquals(100.0 / 57_600, ((Number) loose.entities().get(0).get("share")).doubleValue(), 1e-9,
                "the denominator stays ALL cash-out, not the list's");
        assertEquals(List.of("TILL6"), entities(agents(new ValueMeasures.Agents("upper-trim", Set.of("TILL6"), null))));
        String lower = ROWS.replace("'TILL6'", "' till6 '");
        assertEquals(List.of(" till6 "), entities(ValueMeasures.evaluate("tx", lower, "src", "dst", "ch",
                ValueMeasures.parse(block("cashOutConcentration", "cashOutKinds", "cash_out", "agentList", "tills"), false),
                new ValueMeasures.Agents("upper-trim", Set.of("TILL6"), null))), "compared under the list's normaliser");
        assertEquals(List.of(), entities(agents(new ValueMeasures.Agents("upper-trim", Set.of(), null))),
                "an empty list admits no agent");
    }

    @Test
    void aMaskedAgentListAnswersTheListsTokenNeverTheRawValue() throws Exception {
        byte[] key = new byte[32];
        ValueMeasures.Result r = agents(new ValueMeasures.Agents("upper-trim", Set.of("TILL6"), key));
        assertEquals(List.of(EntityMasking.token(key, "TILL6")), entities(r));
    }

    @Test
    void agentListIsASettingOfCashOutConcentrationOnly() {
        assertThrows(IllegalArgumentException.class, () -> ValueMeasures.parse(block("structuring", "agentList", "tills"), true));
        assertThrows(IllegalArgumentException.class, () -> ValueMeasures.parse(block("cashOutConcentration",
                "cashOutKinds", "cash_out", "agentList", "Not A List"), true));
        ValueMeasures.Spec s = ValueMeasures.parse(block("cashOutConcentration", "cashOutKinds", "cash_out",
                "agentList", "tills"), true);
        assertEquals("tills", s.toMap().get("agentList"));
        assertThrows(IllegalStateException.class, () -> ValueMeasures.evaluate("tx", ROWS, "src", "dst", "ch", s),
                "an unresolved list is never silently ignored");
    }

    @Test
    void theWindowIsHalfOpenAndReadsTimestampsAsTheyAre() throws Exception {
        assertTrue(entities(run(block("structuring", "from", "2026-09-01T10:00:01"))).isEmpty(),
                "every smurf leg is at 10:00:00 — before the window");
    }
}
