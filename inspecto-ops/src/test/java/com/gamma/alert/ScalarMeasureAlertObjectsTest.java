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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-PER-ENTITY-ALERTS-RESIDUALS-1 (2) against the REAL object engine: a scalar (no-{@code by}) Dataset
 * Measure rule heals like one key of a {@code by} rule ({@code PerEntityAlertObjectsTest}) — its ALERT resolves,
 * its Incident never does (a machine heal records no Disposition), and a relapse links to, or re-opens, that
 * Incident instead of opening a second one.
 */
class ScalarMeasureAlertObjectsTest {

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

    private static final AlertRule RULE = AlertRule.fromMap(Map.of("name", "open-exposure", "dataset", "cases",
            "measure", "sum(exposure)", "comparator", "gt", "threshold", 500, "severity", "CRITICAL"));

    private static List<OperationalObject> of(ObjectService objects, ObjectType type, String status) {
        ObjectQuery.Builder q = ObjectQuery.builder().objectType(type).limit(ObjectQuery.MAX_LIMIT);
        if (status != null) q.status(status);
        return objects.query(q.build());
    }

    private static OperationalObject incident(ObjectService objects) {
        List<OperationalObject> all = of(objects, ObjectType.INCIDENT, null);
        assertEquals(1, all.size(), "one Incident for the rule, however often it relapses");
        return all.get(0);
    }

    @Test
    void aHealResolvesTheAlertNeverTheIncidentAndARelapseReopensOneAHumanResolved() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AtomicReference<OptionalDouble> value = new AtomicReference<>(OptionalDouble.of(900));
        AlertService svc = new AlertService(List.of(RULE), noPipelines(), emptyStore(), objects.access());
        svc.measureProbe((d, m) -> value.get());

        assertEquals(1, svc.evaluateRules().size());
        assertEquals(1, of(objects, ObjectType.ALERT, "OPEN").size());
        assertEquals("IDENTIFIED", incident(objects).status());

        value.set(OptionalDouble.of(100));                              // heals
        assertEquals(0, svc.evaluateRules().size());
        assertEquals(0, of(objects, ObjectType.ALERT, "OPEN").size(), "the healed rule's Alert resolved");
        // ⛔ A machine heal never resolves an Incident (operator standing rule, WS-10).
        assertEquals("IDENTIFIED", incident(objects).status());

        value.set(OptionalDouble.empty());                              // unknown: nothing moves
        svc.evaluateRules();
        assertEquals("IDENTIFIED", incident(objects).status());

        value.set(OptionalDouble.of(900));                              // relapse while the Incident is open
        assertEquals(1, svc.evaluateRules().size(), "the heal cleared the cooldown: the relapse fires at once");
        OperationalObject stillOpen = incident(objects);
        OperationalObject relapseAlert = of(objects, ObjectType.ALERT, "OPEN").get(0);
        assertTrue(objects.linksOf(stillOpen.id()).stream().anyMatch(l -> l.fromId().equals(stillOpen.id())
                        && l.toId().equals(relapseAlert.id()) && "ESCALATED_FROM".equalsIgnoreCase(l.relationship())),
                "the relapse Alert is linked to the Incident still being worked");
        assertEquals("IDENTIFIED", stillOpen.status(), "an Incident never resolved is not 'reopened'");

        value.set(OptionalDouble.of(100));                              // heals again …
        svc.evaluateRules();
        assertEquals("IDENTIFIED", incident(objects).status(), "a heal never resolves an Incident");

        // … the operator completes its postmortem and resolves it with a Disposition; a relapse after that REOPENS it.
        objects.saveAttributes(incident(objects).id(), Map.of("postmortem", COMPLETE_POSTMORTEM,
                ObjectService.ATTR_DUE_AT, Long.toString(System.currentTimeMillis() + 3_600_000)), "dana", "postmortem");
        objects.transition(incident(objects).id(), "resolve", "dana", "CONFIRMED");
        assertEquals("RESOLVED", incident(objects).status());
        value.set(OptionalDouble.of(900));
        assertEquals(1, svc.evaluateRules().size());
        assertEquals("DIAGNOSING", incident(objects).status(), "its Incident is reopened");
    }

    private static final String COMPLETE_POSTMORTEM = "{\"timeline\":[{\"time\":\"10:00\",\"text\":\"detected\"}],"
            + "\"causeAnalysis\":[\"threshold misread\"],\"actions\":[{\"text\":\"fix the feed\"}]}";

    @Test
    void aRestartedServiceStillHealsTheAlertItsPredecessorLeftOpen() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AlertService before = new AlertService(List.of(RULE), noPipelines(), emptyStore(), objects.access());
        before.measureProbe((d, m) -> OptionalDouble.of(900));
        before.evaluateRules();
        assertEquals(1, of(objects, ObjectType.ALERT, "OPEN").size());

        AlertService after = new AlertService(List.of(RULE), noPipelines(), emptyStore(), objects.access());
        after.measureProbe((d, m) -> OptionalDouble.of(100));
        after.evaluateRules();
        assertEquals(0, of(objects, ObjectType.ALERT, "OPEN").size(), "seeded from the still-active Alert");
        assertEquals("IDENTIFIED", incident(objects).status());
    }

    @Test
    void reSavingTheRuleOverAnotherDatasetRetiresItsOpenAlert() {
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AlertService svc = new AlertService(List.of(RULE), noPipelines(), emptyStore(), objects.access());
        svc.measureProbe((d, m) -> OptionalDouble.of(900));
        svc.evaluateRules();

        svc.upsert(AlertRule.fromMap(Map.of("name", "open-exposure", "dataset", "other_cases",
                "measure", "sum(exposure)", "comparator", "gt", "threshold", 500, "severity", "CRITICAL")));
        assertTrue(of(objects, ObjectType.ALERT, "OPEN").stream().noneMatch(a -> "cases".equals(a.attributes().get("dataset"))),
                "the old Dataset's Alert can no longer heal, so it is retired");
        assertEquals("IDENTIFIED", of(objects, ObjectType.INCIDENT, null).stream()
                .filter(i -> "cases".equals(i.attributes().get("dataset"))).findFirst().orElseThrow().status(), "its Incident stays with triage");
    }
}
