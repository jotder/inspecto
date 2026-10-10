package com.gamma.anomaly;

import com.gamma.anomaly.baseline.Seasonality;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** {@link AnomalyModel#fromMap}: the defaults, and every refusal fail-closed with the field named. */
class AnomalyModelTest {

    private static Map<String, Object> with(Consumer<Map<String, Object>> edit) {
        Map<String, Object> m = new LinkedHashMap<>(AnomalyCorpus.model());
        edit.accept(m);
        return m;
    }

    private static Map<String, Object> withFeature(Consumer<Map<String, Object>> edit) {
        Map<String, Object> f = new HashMap<>(((List<Map<String, Object>>) AnomalyCorpus.model().get("features")).get(0));
        edit.accept(f);
        return with(m -> m.put("features", List.of(f)));
    }

    private static void refused(Map<String, Object> m, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AnomalyModel.fromMap("usage", m));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    @Test
    void theCorpusModelParsesWithItsDefaults() {
        AnomalyModel m = AnomalyModel.fromMap("usage", AnomalyCorpus.model());
        assertEquals(Seasonality.NONE, m.seasonality());
        assertEquals(60, m.elevatedThreshold());
        assertEquals(80, m.highThreshold());
        assertEquals(10, m.zCap());
        assertEquals(3, m.scale());
        assertEquals("anomaly_scores_usage", m.scoresDataset());
        assertEquals("anomaly_scores_usage_latest", m.latestDataset());
        assertTrue(m.features().get(0).zeroFilled(), "sum: an empty day is 0 (D-AD8)");
        assertEquals("high", m.band(80));
        assertEquals("elevated", m.band(60));
        assertEquals("normal", m.band(59.99));
    }

    @Test
    void anAvgFeatureIsNotZeroFilled() {
        AnomalyModel m = AnomalyModel.fromMap("usage", withFeature(f -> f.put("measure", "avg(mb)")));
        assertFalse(m.features().get(0).zeroFilled(), "avg: an empty day is absent (D-AD8)");
    }

    @Test
    void structuralRefusals() {
        refused(with(m -> m.put("scoresDataset", "x")), "not authorable");
        refused(with(m -> m.put("bogus", 1)), "unknown key 'bogus'");
        refused(with(m -> m.remove("entityType")), "entityType is required");
        refused(with(m -> m.remove("window")), "window is required");
        refused(with(m -> m.put("window", 91)), "window must be a whole number 1..90");
        refused(with(m -> m.put("window", 5)), "cannot exceed window");
        refused(with(m -> m.put("bucket", "hour")), "'hour' is not built yet");
        refused(with(m -> m.put("scoredPeriod", 2)), "scoredPeriod must be a whole number 1..1");
        refused(with(m -> m.put("seasonality", "hour")), "seasonality must be none or weekday");
        refused(with(m -> m.put("elevatedThreshold", 90)), "0 < elevatedThreshold < highThreshold");
        refused(with(m -> m.put("maxEntities", 2_000_001)), "maxEntities must be a whole number 1..2000000");
        refused(with(m -> m.put("features", List.of())), "at least one feature");
        refused(with(m -> m.put("peers", Map.of())), "peers.by must name 1..3 cohort columns");
        refused(with(m -> m.put("peers", Map.of("by", List.of("a", "b", "c", "d")))), "1..3 cohort columns");
        refused(with(m -> m.put("peers", Map.of("by", "plan", "bogus", 1))), "peers: unknown key 'bogus'");
        refused(with(m -> m.put("peers", Map.of("by", "plan; drop"))), "must be a plain column/name token");
        refused(with(m -> m.put("peers", Map.of("by", "msisdn"))), "cannot include the join key");
        refused(with(m -> m.put("peers", Map.of("by", "plan", "minGroupSize", 1))), "minGroupSize must be a whole number 2..");
        refused(with(m -> m.put("peers", Map.of("by", "plan", "fallback", "merge"))), "fallback must be none or population");
        refused(with(m -> m.put("watchList", Map.of())), "watchList.list must be an Entity List id");
        refused(with(m -> m.put("watchList", Map.of("list", "w", "ttlHours", 25))), "ttlHours must be a whole number in 1..24");
        refused(with(m -> m.put("watchList", Map.of("list", "w", "bogus", 1))), "watchList: unknown key 'bogus'");
        refused(with(m -> m.put("exclusionList", "Not An Id")), "exclusionList must be an Entity List id");
        refused(with(m -> m.put("features", java.util.Collections.nCopies(17, ((List<?>) AnomalyCorpus.model().get("features")).get(0)))),
                "at most 16 features");
        assertThrows(IllegalArgumentException.class, () -> AnomalyModel.fromMap("a-b", AnomalyCorpus.model()), "'-' in an id");
    }

    @Test
    void featureRefusals() {
        refused(withFeature(f -> f.put("measure", "median(mb)")), "features[0]");
        refused(withFeature(f -> f.put("key", "msisdn; drop")), "must be a plain column/name token");
        refused(withFeature(f -> f.remove("time")), "features[0].time");
        refused(withFeature(f -> f.put("time", "msisdn")), "time must differ from key");
        refused(withFeature(f -> f.put("direction", "sideways")), "direction must be up, down or both");
        refused(withFeature(f -> f.put("weight", 0)), "weight must be in (0, 1000]");
        refused(withFeature(f -> f.put("unit", -1)), "unit must be > 0");
        refused(withFeature(f -> f.put("sql", "select 1")), "unknown key 'sql'");
        refused(withFeature(f -> f.put("filters", List.of(Map.of("field", "mb", "op", "~", "value", 1)))), "unknown filter op");
    }

    @Test
    void aDuplicateFeatureIdIsRefused() {
        Object f = ((List<?>) AnomalyCorpus.model().get("features")).get(0);
        refused(with(m -> m.put("features", List.of(f, f))), "duplicate feature id 'data_mb'");
    }

    @Test
    void peersParseWithDefaultsFromTheFirstFeature() {
        AnomalyModel m = AnomalyModel.fromMap("usage", with(x -> x.put("peers", Map.of("by", List.of("plan")))));
        assertEquals(List.of("plan"), m.peers().by());
        assertEquals("usage", m.peers().dataset(), "defaults to the first feature's Dataset");
        assertEquals("msisdn", m.peers().key(), "defaults to the first feature's key");
        assertEquals(30, m.peers().minGroupSize());
        assertFalse(m.peers().populationFallback(), "D-AD11: no silent fallback");
        assertTrue(m.referencedColumns().get("usage").contains("plan"), "the cohort column is checked against the Schema");
        AnomalyModel r = AnomalyModel.fromMap("usage", with(x -> x.put("peers", Map.of("by", "plan", "dataset", "subscribers",
                "key", "sub_id", "minGroupSize", 5, "fallback", "population"))));
        assertEquals(java.util.Set.of("sub_id", "plan"), r.referencedColumns().get("subscribers"));
        assertTrue(r.datasetIds().contains("subscribers"));
        assertTrue(r.peers().populationFallback());
    }

    @Test
    void theListHooksParseWithADefaultTtl() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(AnomalyCorpus.model());
        m.put("watchList", Map.of("list", "usage-watch"));
        m.put("exclusionList", "known-heavy");
        AnomalyModel.fromMap("usage", m);
        AnomalyLists l = AnomalyLists.of(m);
        assertEquals(new AnomalyLists.WatchList("usage-watch", 24), l.watchList());
        assertEquals("known-heavy", l.exclusionList());
        assertEquals(new AnomalyLists(null, null), AnomalyLists.of(AnomalyCorpus.model()));
    }
}
