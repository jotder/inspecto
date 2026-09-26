package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.objects.ObjectType;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.TagRule;
import com.gamma.query.DatasetMeasureProbe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-PER-ENTITY-ALERTS-1 against the REAL object engine: a healed key's Incident moves through the real
 * workflow ({@code resolve}, then {@code reopen} on a relapse), and an existing Case Rule groups the per-key
 * Incidents into one Case. The per-key SQL itself is covered over real DuckDB by the engine's
 * {@code PerEntityAlertTest}; here the breaching keys are stated directly.
 */
class PerEntityAlertObjectsTest {

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

    private static List<OperationalObject> incidents(ObjectService objects) {
        return objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).limit(ObjectQuery.MAX_LIMIT).build());
    }

    private static OperationalObject incidentFor(ObjectService objects, String msisdn) {
        return incidents(objects).stream().filter(o -> msisdn.equals(o.attributes().get("key.msisdn")))
                .findFirst().orElseThrow();
    }

    @Test
    void aHealedKeyResolvesItsRealIncidentAndARelapseReopensIt() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AtomicReference<DatasetMeasureProbe.Breaches> now = new AtomicReference<>(offenders(1, 40));
        AlertService svc = new AlertService(List.of(RULE), noPipelines(), emptyStore(), objects.access());
        svc.groupedMeasureProbe(r -> Optional.of(now.get()));

        assertEquals(40, svc.evaluateRules().size());
        assertEquals(40, incidents(objects).size());

        now.set(offenders(2, 40));                                   // m1 heals
        assertEquals(0, svc.evaluateRules().size());
        assertEquals(39, openAlerts(objects), "the healed key's Alert resolved; the other 39 stay open");
        // ⚠ The real Incident workflow refuses `resolve` until the postmortem is complete (I1) — a machine heal
        // does not bypass that, so the healed key's Incident stays open for its operator.
        assertEquals("IDENTIFIED", incidentFor(objects, "m1").status());

        now.set(offenders(1, 40));                                   // m1 relapses while its Incident is open
        assertEquals(1, svc.evaluateRules().size(), "the relapse is a new breach edge");
        assertEquals(40, incidents(objects).size(), "no second Incident for the same key");
        OperationalObject stillOpen = incidentFor(objects, "m1");
        OperationalObject relapseAlert = objects.query(ObjectQuery.builder().objectType(ObjectType.ALERT).status("OPEN")
                        .limit(ObjectQuery.MAX_LIMIT).build()).stream()
                .filter(o -> "m1".equals(o.attributes().get("key.msisdn"))).findFirst().orElseThrow();
        assertTrue(objects.linksOf(stillOpen.id()).stream().anyMatch(l -> l.fromId().equals(stillOpen.id())
                        && l.toId().equals(relapseAlert.id()) && "ESCALATED_FROM".equalsIgnoreCase(l.relationship())),
                "the relapse Alert is linked to the Incident still being worked");
        assertEquals("IDENTIFIED", stillOpen.status(), "an Incident never resolved is not 'reopened'");

        // Once the operator has written the postmortem, the next heal resolves the Incident too …
        objects.saveAttributes(incidentFor(objects, "m1").id(), Map.of("postmortem", COMPLETE_POSTMORTEM,
                ObjectService.ATTR_DUE_AT, Long.toString(System.currentTimeMillis() + 3_600_000)), "dana", "postmortem");
        now.set(offenders(2, 40));
        svc.evaluateRules();
        assertEquals("RESOLVED", incidentFor(objects, "m1").status());
        assertEquals("IDENTIFIED", incidentFor(objects, "m2").status(), "no other key's Incident moved");

        // … and a relapse after that REOPENS it rather than hiding behind the resolved one.
        now.set(offenders(1, 40));
        assertEquals(1, svc.evaluateRules().size());
        assertEquals(40, incidents(objects).size());
        assertEquals("DIAGNOSING", incidentFor(objects, "m1").status(), "its Incident is reopened");
    }

    private static final String COMPLETE_POSTMORTEM = "{\"timeline\":[{\"time\":\"10:00\",\"text\":\"detected\"}],"
            + "\"causeAnalysis\":[\"tariff misconfigured\"],\"actions\":[{\"text\":\"fix the tariff\"}]}";

    private static int openAlerts(ObjectService objects) {
        return objects.query(ObjectQuery.builder().objectType(ObjectType.ALERT).status("OPEN")
                .limit(ObjectQuery.MAX_LIMIT).build()).size();
    }

    @Test
    void anExistingCaseRuleGroupsTheFortyPerKeyIncidentsIntoOneCase() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AlertService svc = new AlertService(List.of(RULE), noPipelines(), emptyStore(), objects.access());
        svc.groupedMeasureProbe(r -> Optional.of(offenders(1, 40)));
        svc.evaluateRules();

        // No Case Rule change: the per-key Incident titles all start with the rule's description, which the
        // Case Rule's existing `q` (title + description substring) criterion matches.
        objects.registerCaseRule(new CaseRule("spend-ring", "High-spend offenders",
                new TagRule.Filter("INCIDENT", "High spend", null, null, null, null), 1, 0, null, null,
                System.currentTimeMillis()));
        ObjectService.CaseRuleEvaluation eval = objects.evaluateCaseRule("spend-ring");

        assertEquals(40, eval.grouped());
        assertTrue(eval.opened());
        assertEquals(1, objects.query(ObjectQuery.builder().objectType(ObjectType.CASE).build()).size(),
                "one Case holds all forty");
        assertEquals(0, objects.evaluateCaseRule("spend-ring").grouped(), "re-evaluation is idempotent");
    }
}
