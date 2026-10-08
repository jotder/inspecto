package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.cases.CaseOperations;
import com.gamma.ops.cases.CaseRule;
import com.gamma.ops.tag.TagRule;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-PER-ENTITY-ALERTS-1 against the REAL object engine and a Case Rule: an existing Case Rule groups the
 * per-key Incidents of a grouped-measure Alert Rule into one Case. Moved here from {@code PerEntityAlertObjectsTest}
 * (inspecto-ops) with the Case Rule registry (MODULE-REORG-P7); the helpers are that test's, copied.
 */
class PerEntityAlertCaseRuleTest {

    private static ConfigSource noPipelines() {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private static DatasetMeasureProbe.Breaches offenders(int from, int to) {
        List<DatasetMeasureProbe.Breach> keys = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("msisdn", "m" + i);
            keys.add(new DatasetMeasureProbe.Breach(key, 1000));
        }
        return new DatasetMeasureProbe.Breaches(keys, keys.size());
    }

    private static final AlertRule RULE = AlertRule.fromMap(Map.of("name", "high-spend", "dataset", "usage",
            "measure", "sum(amount)", "by", List.of("msisdn"), "comparator", "gt", "threshold", 500,
            "severity", "CRITICAL", "description", "High spend"));

    private final InMemoryAlertStore alerts = new InMemoryAlertStore();

    private AlertService svc(ObjectService objects) {
        Optional<com.gamma.objects.ObjectAccess> ops = Optional.of(objects.access());
        return new AlertService(List.of(RULE), noPipelines(), emptyStore(), AlertRecords.of(alerts, ops),
                AlertRecords.incidentsOf(ops));
    }

    @Test
    void anExistingCaseRuleGroupsTheFortyPerKeyIncidentsIntoOneCase() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AlertService svc = svc(objects);
        svc.groupedMeasureProbe(r -> Optional.of(offenders(1, 40)));
        svc.evaluateRules();

        // No Case Rule change: the per-key Incident titles all start with the rule's description, which the
        // Case Rule's existing `q` (title + description substring) criterion matches.
        CaseOperations cases = CaseOperations.of(objects);
        cases.registerCaseRule(new CaseRule("spend-ring", "High-spend offenders",
                new TagRule.Filter("INCIDENT", "High spend", null, null, null, null), 1, 0, null, null,
                System.currentTimeMillis()));
        CaseOperations.CaseRuleEvaluation eval = cases.evaluateCaseRule("spend-ring");

        assertEquals(40, eval.grouped());
        assertTrue(eval.opened());
        assertEquals(1, objects.query(ObjectQuery.builder().objectType(ObjectType.CASE).build()).size(),
                "one Case holds all forty");
        assertEquals(0, cases.evaluateCaseRule("spend-ring").grouped(), "re-evaluation is idempotent");
    }
}
