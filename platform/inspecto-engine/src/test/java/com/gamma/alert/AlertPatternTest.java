package com.gamma.alert;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9c: the {@code pattern:} block of a windowed Alert Rule. Every refusal (a 422 at every save door, since
 * {@code AlertRoutes.parse} runs {@link AlertRule#fromMap}) is paired with the accepted shape one key away.
 */
class AlertPatternTest {

    static Map<String, Object> group(Map<String, Object> condition) {
        return Map.of("kind", "group", "op", "AND", "items", List.of(condition));
    }

    static Map<String, Object> eq(String field, String value) {
        return Map.of("kind", "condition", "field", field, "operator", "=", "value", value);
    }

    /** SIM swap, then a high-value transfer within 30 minutes, per MSISDN. */
    static Map<String, Object> sequence() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "swap-then-transfer");
        m.put("dataset", "telco_events");
        m.put("by", List.of("msisdn"));
        m.put("severity", "CRITICAL");
        m.put("window", new LinkedHashMap<>(Map.of("type", "tumbling", "size", "30m", "time", "event_ts", "lateness", "2m")));
        m.put("pattern", new LinkedHashMap<>(Map.of("type", "sequence",
                "steps", List.of(group(eq("event_type", "sim_swap")), group(eq("event_type", "transfer"))))));
        return m;
    }

    /** A switch feed silent for a whole 5-minute sliding Window. */
    static Map<String, Object> absence() {
        Map<String, Object> m = new HashMap<>();
        m.put("name", "switch-silent");
        m.put("dataset", "switch_feed");
        m.put("by", List.of("switch_id"));
        m.put("window", new LinkedHashMap<>(Map.of("type", "sliding", "size", "5m", "slide", "1m", "time", "event_ts",
                "lateness", "1m")));
        m.put("pattern", new LinkedHashMap<>(Map.of("type", "absence")));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pattern(Map<String, Object> rule) {
        return (Map<String, Object>) rule.get("pattern");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> window(Map<String, Object> rule) {
        return (Map<String, Object>) rule.get("window");
    }

    private static void refused(Map<String, Object> rule, String fragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(rule));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    private static AlertRule accepted(Map<String, Object> rule) {
        AlertRule r = AlertRule.fromMap(rule);
        assertTrue(r.isWindowed() && r.eventWindow().isPattern(), r.toString());
        return r;
    }

    @Test
    void bothDesignExamplesParseWithTheMeasureFixedAndRoundTrip() {
        for (Map<String, Object> raw : List.of(sequence(), absence())) {
            AlertRule r = accepted(raw);
            assertEquals("count", r.measure());
            assertEquals("gte", r.comparator());
            assertEquals(1.0, r.threshold());
            assertEquals(r, AlertRule.fromMap(r.toMap()), "the stored shape parses back to the same rule");
            assertEquals(raw.get("pattern"), r.toMap().get("pattern"));
        }
        assertTrue(accepted(sequence()).eventWindow().pattern().isSequence());
        assertTrue(accepted(absence()).eventWindow().pattern().isAbsence());
    }

    @Test
    void anAuthoredThresholdOrComparatorDoesNotChangeAPattern() {
        Map<String, Object> raw = absence();
        raw.put("comparator", "lt");
        raw.put("threshold", 5);
        AlertRule r = accepted(raw);
        assertEquals("gte", r.comparator());
        assertEquals(1.0, r.threshold());
    }

    @Test
    void aPatternNeedsTheWindowBlock() {
        Map<String, Object> noWindow = sequence();
        noWindow.remove("window");
        refused(noWindow, "alert.pattern needs the rule's window: block");
        Map<String, Object> ledgerWindow = sequence();
        ledgerWindow.put("window", "30m");
        refused(ledgerWindow, "alert.pattern needs the rule's window: block");
        accepted(sequence());
    }

    @Test
    void theTypeMustBeSequenceOrAbsence() {
        Map<String, Object> bad = absence();
        pattern(bad).put("type", "burst");
        refused(bad, "alert.pattern.type must be one of");
        Map<String, Object> none = absence();
        pattern(none).remove("type");
        refused(none, "alert.pattern.type must be one of");
        Map<String, Object> upper = absence();
        pattern(upper).put("type", "ABSENCE");
        accepted(upper);
    }

    @Test
    void anUnknownPatternKeyIsRefused() {
        Map<String, Object> bad = sequence();
        pattern(bad).put("within", "30m");
        refused(bad, "alert.pattern.within is not a pattern key");
        accepted(sequence());
    }

    @Test
    void aPatternMustBeABlock() {
        Map<String, Object> bad = absence();
        bad.put("pattern", "absence");
        refused(bad, "alert.pattern must be a block");
        accepted(absence());
    }

    @Test
    void aSequenceTakesExactlyTwoSteps() {
        Map<String, Object> one = sequence();
        pattern(one).put("steps", List.of(group(eq("event_type", "sim_swap"))));
        refused(one, "takes exactly 2 steps");
        Map<String, Object> three = sequence();
        pattern(three).put("steps", List.of(group(eq("event_type", "a")), group(eq("event_type", "b")),
                group(eq("event_type", "c"))));
        refused(three, "takes exactly 2 steps");
        Map<String, Object> none = sequence();
        pattern(none).remove("steps");
        refused(none, "takes exactly 2 steps");
        accepted(sequence());
    }

    @Test
    void stepsMustBeAListOfGroupRootedTrees() {
        Map<String, Object> notList = sequence();
        pattern(notList).put("steps", "a,b");
        refused(notList, "alert.pattern.steps must be a list");
        Map<String, Object> bare = sequence();
        pattern(bare).put("steps", List.of(eq("event_type", "sim_swap"), group(eq("event_type", "transfer"))));
        refused(bare, "alert.pattern.steps[0]");
        Map<String, Object> empty = sequence();
        pattern(empty).put("steps", List.of(group(eq("event_type", "sim_swap")), Map.of()));
        refused(empty, "alert.pattern.steps[1] must be a Condition Language tree");
        accepted(sequence());
    }

    @Test
    void anAbsenceTakesNoSteps() {
        Map<String, Object> bad = absence();
        pattern(bad).put("steps", List.of(group(eq("a", "1")), group(eq("b", "2"))));
        refused(bad, "an absence alert.pattern takes no steps");
        accepted(absence());
    }

    @Test
    void aSequenceWindowIsTumbling() {
        Map<String, Object> bad = sequence();
        window(bad).put("type", "sliding");
        window(bad).put("slide", "1m");
        refused(bad, "its window is tumbling");
        Map<String, Object> slideBy = sequence();
        window(slideBy).put("slide", "5m");
        refused(slideBy, "tumbling alert.window takes no slide");
        accepted(sequence());
        Map<String, Object> slidingAbsence = absence();
        assertEquals("sliding", accepted(slidingAbsence).eventWindow().type(), "an absence may slide");
    }

    @Test
    void aSequenceTakesNoRuleWhenButAnAbsenceDoes() {
        Map<String, Object> bad = sequence();
        bad.put("when", group(eq("channel", "app")));
        refused(bad, "a sequence alert.pattern takes no alert.when");
        accepted(sequence());
        Map<String, Object> scoped = absence();
        scoped.put("when", group(eq("record_type", "heartbeat")));
        assertNotNull(accepted(scoped).when());
    }

    @Test
    void aPatternTakesNoMeasureButCount() {
        Map<String, Object> bad = absence();
        bad.put("measure", "sum(amount)");
        refused(bad, "a Pattern alert (pattern:) takes no measure");
        Map<String, Object> count = absence();
        count.put("measure", "count");
        accepted(count);
    }

    @Test
    void theWindowBoundsStillApplyToAPattern() {
        Map<String, Object> tooLong = sequence();
        window(tooLong).put("size", "59m");
        refused(tooLong, "size + lateness must be at most 1h");
        Map<String, Object> justFits = sequence();
        window(justFits).put("size", "58m");
        accepted(justFits);
    }

    @Test
    void anAbsenceKeepsOneSlideMoreInTheBuffer() {
        AlertWindow absence = accepted(absence()).eventWindow();
        assertEquals(WindowSql.micros(java.time.Duration.ofMinutes(7)), absence.spanUs(), "5m + 1m lateness + the 1m slide before");
        AlertWindow sequence = accepted(sequence()).eventWindow();
        assertEquals(WindowSql.micros(java.time.Duration.ofMinutes(32)), sequence.spanUs());
    }
}
