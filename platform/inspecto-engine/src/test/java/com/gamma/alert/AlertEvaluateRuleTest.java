package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code alert.evaluate}'s {@code rule} parameter: ONLY the named rule is evaluated; the others are not touched. */
class AlertEvaluateRuleTest {

    private static AlertRule stale(String name, String dataset) {
        return new AlertRule(name, null, null, 0, null, "WARNING", null, dataset, null, null, "1h");
    }

    private static AlertService service(Path dir, AlertRule... rules) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        ConfigSource configs = new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
        StatusStore store = new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };
        return new AlertService(List.of(rules), configs, store);
    }

    @Test
    void onlyTheNamedRuleIsEvaluated(@TempDir Path dir) throws Exception {
        AlertService svc = service(dir, stale("rule_a", "ds_a"), stale("rule_b", "ds_b"));
        List<String> probed = new ArrayList<>();
        svc.freshnessProbe(ds -> { probed.add(ds); return OptionalLong.of(0L); });   // both datasets very stale

        List<Alert> fired = svc.evaluateRule("rule_a");

        assertEquals(List.of("rule_a"), fired.stream().map(Alert::rule).toList());
        assertEquals(List.of("ds_a"), probed, "rule_b was not evaluated at all");

        // rule_b never advanced a cooldown, so a full sweep still fires it (and only it: rule_a is cooling down)
        assertEquals(List.of("rule_b"), svc.evaluateRules().stream().map(Alert::rule).toList());
    }

    @Test
    void anUnknownRuleNameFailsAndEvaluatesNothing(@TempDir Path dir) throws Exception {
        AlertService svc = service(dir, stale("rule_a", "ds_a"));
        List<String> probed = new ArrayList<>();
        svc.freshnessProbe(ds -> { probed.add(ds); return OptionalLong.of(0L); });

        var e = assertThrows(IllegalArgumentException.class, () -> svc.evaluateRule("nope"));

        assertTrue(!e.getMessage().contains("nope"), "value-free message");
        assertTrue(probed.isEmpty());
    }
}
