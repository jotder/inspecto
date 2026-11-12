package com.gamma.alert;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PA-9b: the {@code window:} block of a windowed Measure Alert Rule. Every refusal is paired with the accepted shape
 * one key away, so each test proves the boundary, not only that something is refused.
 */
class AlertWindowTest {

    /** A windowed rule over {@code voice_cdr}; {@code window} entries override the sliding 10m/1m default. */
    static Map<String, Object> rule(Map<String, Object> window) {
        Map<String, Object> w = new LinkedHashMap<>(Map.of("type", "sliding", "size", "10m", "slide", "1m", "time", "event_ts"));
        w.putAll(window);
        w.values().removeIf(v -> v == null);
        Map<String, Object> m = new HashMap<>();
        m.put("name", "wangiri-burst");
        m.put("dataset", "voice_cdr");
        m.put("measure", "countDistinct(b_number)");
        m.put("by", List.of("a_number"));
        m.put("comparator", "gte");
        m.put("threshold", 30);
        m.put("severity", "CRITICAL");
        m.put("window", w);
        return m;
    }

    /** A ConfigSource with no Pipelines, Enrichments or semantic models. */
    static com.gamma.catalog.ConfigSource noPipelines() {
        return new com.gamma.catalog.ConfigSource() {
            @Override public List<com.gamma.etl.PipelineConfig> pipelines() { return List.of(); }
            @Override public List<com.gamma.enrich.EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<com.gamma.catalog.SemanticModel> semantics() { return List.of(); }
        };
    }

    private static Map<String, Object> with(Map<String, Object> window) {
        return rule(window);
    }

    private static void refused(Map<String, Object> rule, String fragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AlertRule.fromMap(rule));
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    private static AlertRule accepted(Map<String, Object> rule) {
        AlertRule r = AlertRule.fromMap(rule);
        assertTrue(r.isWindowed());
        return r;
    }

    private static Map<String, Object> w(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void theDesignExampleParsesAndRoundTrips() {
        Map<String, Object> raw = with(w("lateness", "2m", "distinctOn", List.of("topic", "partition", "offset")));
        AlertRule r = accepted(raw);
        AlertWindow win = r.eventWindow();
        assertEquals("sliding", win.type());
        assertEquals(java.time.Duration.ofMinutes(10), win.size());
        assertEquals(java.time.Duration.ofMinutes(1), win.slide());
        assertEquals(java.time.Duration.ofMinutes(2), win.lateness());
        assertEquals(10, win.slides());
        assertNull(r.window(), "the ledger window string stays empty");
        assertEquals(Map.of("type", "sliding", "size", "10m", "slide", "1m", "time", "event_ts", "lateness", "2m",
                "distinctOn", List.of("topic", "partition", "offset")), r.toMap().get("window"));
        assertEquals(r, AlertRule.fromMap(r.toMap()), "the stored shape parses back to the same rule");
    }

    @Test
    void aTumblingWindowSlidesByItsSizeAndTakesNoOtherSlide() {
        AlertRule r = accepted(with(w("type", "tumbling", "slide", null)));
        assertEquals(r.eventWindow().size(), r.eventWindow().slide());
        assertEquals(1, r.eventWindow().slides());
        assertFalse(((Map<?, ?>) r.toMap().get("window")).containsKey("slide"));
        refused(with(w("type", "tumbling", "slide", "1m")), "a tumbling alert.window takes no slide");
        accepted(with(w("type", "tumbling", "slide", "10m")));   // the same as its size: harmless
    }

    @Test
    void sizePlusLatenessIsAtMostOneHour() {
        accepted(with(w("size", "50m", "slide", "5m", "lateness", "10m")));
        refused(with(w("size", "50m", "slide", "5m", "lateness", "11m")), "size + lateness must be at most 1h");
        accepted(with(w("size", "1h", "slide", "1m")));
        refused(with(w("size", "61m", "slide", "1m")), "size + lateness must be at most 1h");
    }

    @Test
    void aWindowSlidesAtMostSixtyTimes() {
        accepted(with(w("size", "60s", "slide", "1s")));
        refused(with(w("size", "61s", "slide", "1s")), "at most 60 times");
    }

    @Test
    void theSizeIsAWholeMultipleOfTheSlideAndNoSmaller() {
        accepted(with(w("size", "10m", "slide", "2m")));
        refused(with(w("size", "10m", "slide", "3m")), "whole multiple");
        accepted(with(w("size", "10m", "slide", "10m")));
        refused(with(w("size", "10m", "slide", "11m")), "must not exceed");
        refused(with(w("slide", null)), "a sliding alert.window needs a positive slide");
    }

    @Test
    void durationsAreSecondsMinutesOrHours() {
        accepted(with(w("size", "1h", "slide", "1h")));
        refused(with(w("size", "1d", "slide", "1h")), "must be Ns, Nm or Nh");
        refused(with(w("size", "0m")), "size must be a positive duration");
        refused(with(w("size", "99999999999999999h")), "must be Ns, Nm or Nh");   // a 422, never an overflow (500)
        refused(with(w("size", "999999s", "slide", "1s")), "size + lateness must be at most 1h");
    }

    @Test
    void theTypeTimeAndKeysAreChecked() {
        refused(with(w("type", "session")), "alert.window.type must be one of");
        refused(with(w("time", null)), "alert.window.time must name the event-time column");
        refused(with(w("time", "event ts")), "alert.window.time must name the event-time column");
        refused(with(w("timeColumn", "x")), "alert.window.timeColumn is not a window key");
        refused(with(w("distinctOn", List.of("offset", "offset"))), "names a column twice");
        refused(with(w("distinctOn", List.of("off-set"))), "must be a plain column name");
    }

    @Test
    void aWindowedRuleTakesGtOrGteOnly() {
        Map<String, Object> raw = rule(Map.of());
        raw.put("comparator", "gt");
        accepted(raw);
        raw.put("comparator", "lt");
        refused(raw, "a windowed alert takes comparator gt or gte");
    }

    @Test
    void aWindowBlockBelongsOnADatasetMeasureRuleOnly() {
        Map<String, Object> ledger = rule(Map.of());
        ledger.remove("dataset");
        ledger.remove("measure");
        ledger.remove("by");
        ledger.put("metric", "error_rate");
        refused(ledger, "applies to a Dataset measure rule");
        Map<String, Object> ledgerString = new HashMap<>(ledger);
        ledgerString.put("window", "1h");
        assertFalse(AlertRule.fromMap(ledgerString).isWindowed(), "a ledger rule's window string is unchanged");
    }

    @Test
    void aWhenTreeIsAcceptedOnAWindowedRuleOnly() {
        Map<String, Object> when = Map.of("kind", "group", "op", "AND", "items",
                List.of(Map.of("kind", "condition", "field", "duration_s", "operator", "<", "value", "3")));
        Map<String, Object> windowed = rule(Map.of());
        windowed.put("when", when);
        assertEquals(when, accepted(windowed).when());
        Map<String, Object> plain = new HashMap<>(windowed);
        plain.remove("window");
        refused(plain, "has none unless it is windowed");
    }

    @Test
    void aWindowedRuleDoesNotHeal() {
        Map<String, Object> raw = rule(Map.of());
        raw.put("healAfterSweeps", 3);
        refused(raw, "takes no healAfterSweeps");
    }

    @Test
    void theSweepSkipsAWindowedRule() {
        // over the whole Dataset its threshold would be compared against all history
        AlertService svc = new AlertService(List.of(AlertRule.fromMap(rule(Map.of()))), noPipelines(), null);
        java.util.concurrent.atomic.AtomicInteger probed = new java.util.concurrent.atomic.AtomicInteger();
        svc.measureProbe((ds, m) -> {
            probed.incrementAndGet();
            return java.util.OptionalDouble.of(1_000_000);
        });
        svc.groupedMeasureProbe(r -> {
            probed.incrementAndGet();
            return java.util.Optional.empty();
        });
        assertTrue(svc.evaluateRules().isEmpty());
        assertEquals(0, probed.get(), "neither measure probe ran for a windowed rule");
    }
}
