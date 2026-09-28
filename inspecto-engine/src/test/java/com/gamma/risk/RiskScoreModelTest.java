package com.gamma.risk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Save-time refusals of a {@code risk-score} model — every one fails closed, naming the field. */
class RiskScoreModelTest {

    private static Map<String, Object> factor() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", "failed");
        f.put("dataset", "topups");
        f.put("key", "msisdn");
        f.put("measure", "count");
        f.put("filters", List.of(Map.of("field", "status", "op", "=", "value", "FAILED")));
        f.put("weight", 10);
        f.put("evidence", List.of("topup_id"));
        return f;
    }

    private static Map<String, Object> model(Map<String, Object> factor) {
        Map<String, Object> m = new HashMap<>();
        m.put("entityType", "subscriber");
        m.put("highThreshold", 70);
        m.put("factors", List.of(factor));
        return m;
    }

    private static String refused(Map<String, Object> m) {
        return assertThrows(IllegalArgumentException.class, () -> RiskScoreModel.fromMap("subs", m)).getMessage();
    }

    /** ASSURE-ENTITY-LISTS-1: the watch-list feed is optional, and only an expiring (1..24 h) feed is accepted (D-P5). */
    @Test
    void aWatchListFeedIsAListIdAndAWholeTtlOfAtMost24Hours() {
        assertNull(RiskScoreModel.fromMap("subs", model(factor())).watchList(), "absent = no feed");
        Map<String, Object> m = model(factor());
        m.put("watchList", Map.of("list", "mule-watch", "ttlHours", 24));
        assertEquals(new RiskScoreModel.WatchList("mule-watch", 24), RiskScoreModel.fromMap("subs", m).watchList());
        for (Object ttl : List.of(0, 25, 1.5, -1, "x")) {
            m.put("watchList", Map.of("list", "mule-watch", "ttlHours", ttl));
            assertTrue(refused(m).contains("ttlHours"), "ttlHours " + ttl);
        }
        m.put("watchList", Map.of("list", "Mule Watch", "ttlHours", 1));
        assertTrue(refused(m).contains("watchList.list"));
        m.put("watchList", Map.of("list", "w", "ttlHours", 1, "purpose", "block"));
        assertTrue(refused(m).contains("unknown key 'purpose'"));
        m.put("watchList", "w");
        assertTrue(refused(m).contains("must be an object"));
    }

    @Test
    void aValidModelParsesWithDefaults() {
        RiskScoreModel m = RiskScoreModel.fromMap("subs", model(factor()));
        assertEquals("risk_scores_subs", m.scoresDataset());
        assertEquals(70.0, m.highThreshold());
        assertNull(m.factors().get(0).cap());
        assertEquals(Map.of("topups", java.util.Set.of("msisdn", "status", "topup_id")), m.referencedColumns());
    }

    @Test
    void freeFormEntityTypesAreAccepted() {
        Map<String, Object> m = model(factor());
        m.put("entityType", "merchant_terminal");
        assertEquals("merchant_terminal", RiskScoreModel.fromMap("x", m).entityType());
    }

    @Test
    void aNonNumericWeightIsRefused() {
        Map<String, Object> f = factor();
        f.put("weight", "heavy");
        assertTrue(refused(model(f)).contains("weight must be numeric"));
        f.put("weight", Double.NaN);
        assertTrue(refused(model(f)).contains("finite"));
        f.remove("weight");
        assertTrue(refused(model(f)).contains("weight is required"));
        f.put("weight", "2.5");
        assertEquals(2.5, RiskScoreModel.fromMap("s", model(f)).factors().get(0).weight(), "a numeric string is a number");
    }

    @Test
    void aNegativeCapAndABadThresholdAreRefused() {
        Map<String, Object> f = factor();
        f.put("cap", -1);
        assertTrue(refused(model(f)).contains("cap must be >= 0"));
        Map<String, Object> m = model(factor());
        m.put("highThreshold", 150);
        assertTrue(refused(m).contains("highThreshold"));
    }

    @Test
    void theIndicatorIsCompiledAtSave() {
        Map<String, Object> f = factor();
        f.put("measure", "median(amount)");
        assertTrue(refused(model(f)).contains("unknown aggregation"), "an aggregate the compiler lacks");
        f.put("measure", "sum(amount); DROP TABLE x");
        assertThrows(IllegalArgumentException.class, () -> RiskScoreModel.fromMap("s", model(f)));
        f.put("measure", "count");
        f.put("filters", List.of(Map.of("field", "status", "op", "~", "value", "x")));
        assertTrue(refused(model(f)).contains("unknown filter op"), "the operator is compiled, not concatenated");
        f.put("filters", List.of(Map.of("field", "status\" OR 1=1 --", "op", "=", "value", "x")));
        assertTrue(refused(model(f)).contains("unsafe filter field"));
    }

    @Test
    void unsafeIdentifiersUnknownKeysAndDuplicatesAreRefused() {
        Map<String, Object> f = factor();
        f.put("key", "msisdn; --");
        assertTrue(refused(model(f)).contains(".key"));
        Map<String, Object> g = factor();
        g.put("evidence", List.of("../etc"));
        assertTrue(refused(model(g)).contains(".evidence"));
        Map<String, Object> h = factor();
        h.put("sql", "SELECT 1");
        assertTrue(refused(model(h)).contains("unknown key 'sql'"), "no raw SQL door");
        Map<String, Object> m = model(factor());
        m.put("factors", List.of(factor(), factor()));
        assertTrue(refused(m).contains("duplicate factor id"));
        m.put("factors", List.of());
        assertTrue(refused(m).contains("at least one factor"));
    }
}
