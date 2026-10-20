package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.cases.CaseOperations;
import com.gamma.ops.cases.CaseRule;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.util.ToonHelper;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TELCO-BYPASS-SIMBOX-1} slice B3 (decision D-BP3: the box Incident is the Case anchor). The SHIPPED
 * {@code telco-bypass} Alert Rules and Case Rule, read from the template, run against the REAL object engine: each box
 * (one {@code cluster_key}) opens one Incident, the Case Rule groups it into its own Case, and the SIM Incidents of the
 * per-SIM rule (which carry {@code key.entity_key}, not {@code key.cluster_key}) never join a box Case.
 */
class BypassBoxCaseRuleTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "telco-bypass", "config").toAbsolutePath().normalize();

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

    private static DatasetMeasureProbe.Breaches keys(String column, List<String> values) {
        List<DatasetMeasureProbe.Breach> out = new ArrayList<>();
        for (String v : values) {
            Map<String, Object> key = new LinkedHashMap<>();
            key.put(column, v);
            out.add(new DatasetMeasureProbe.Breach(key, 1));
        }
        return new DatasetMeasureProbe.Breaches(out, out.size());
    }

    private static AlertRule shipped(String name) throws Exception {
        return AlertRule.fromMap(ToonHelper.load(TEMPLATE.resolve("registry/alert-rules/" + name + ".toon").toString()));
    }

    @Test
    void eachBoxIncidentBecomesItsOwnCaseAndSimIncidentsNeverJoinOne() throws Exception {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        Optional<com.gamma.objects.ObjectAccess> ops = Optional.of(objects.access());
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        AlertRule box = shipped("tb_box_suspected"), sim = shipped("tb_sim_high");
        AlertService svc = new AlertService(List.of(box, sim), noPipelines(), emptyStore(), AlertRecords.of(alerts, ops), AlertRecords.incidentsOf(ops));
        List<String> boxKeys = List.of("imei:990000000000001", "imei:990000000000002", "cell:C0003");
        svc.groupedMeasureProbe(r -> Optional.of(r.name().equals("tb_box_suspected")
                ? keys("cluster_key", boxKeys) : keys("entity_key", List.of("99971010000", "99971010001", "99971010002", "99971010003", "99971010004"))));
        svc.evaluateRules();

        List<OperationalObject> incidents = objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build());
        assertEquals(boxKeys.size() + 5, incidents.size(), "one Incident per box and one per alerting SIM");
        for (OperationalObject i : incidents)
            if ("tb_box_suspected".equals(i.attributes().get("rule")))
                assertTrue(boxKeys.contains(i.attributes().get("key.cluster_key")), "the box Incident carries the box as its key: " + i.attributes());

        CaseRule rule = CaseRule.load(TEMPLATE.resolve("ops/tb_box_caserule.toon"));
        assertEquals("cluster_key", rule.groupBy());
        CaseOperations cases = CaseOperations.of(objects);
        cases.registerCaseRule(rule);
        CaseOperations.CaseRuleEvaluation eval = cases.evaluateCaseRule(rule.name());
        assertTrue(eval.opened());
        assertEquals(boxKeys.size(), eval.grouped(), "only the box Incidents match: the SIM Incidents have no cluster_key");
        assertEquals(boxKeys.size(), objects.query(ObjectQuery.builder().objectType(ObjectType.CASE).build()).size(), "one Case per box");
        assertEquals(0, cases.evaluateCaseRule(rule.name()).grouped(), "re-evaluation is idempotent");
    }

    @Test
    void theShippedCaseRuleIsGroupedByTheClusterKeyAndAScopeTheSimRuleCannotMatch() throws Exception {
        CaseRule rule = CaseRule.load(TEMPLATE.resolve("ops/tb_box_caserule.toon"));
        assertEquals("tb_box_case", rule.name());
        assertEquals(1, rule.threshold(), "one box Incident is already a Case");
        assertEquals("cluster_key", rule.groupBy());
        assertEquals(List.of("cluster_key"), shipped("tb_box_suspected").by());
        assertEquals(List.of("entity_key"), shipped("tb_sim_high").by());
    }
}
