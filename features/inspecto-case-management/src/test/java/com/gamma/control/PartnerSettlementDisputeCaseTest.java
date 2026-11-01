package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.alert.AlertRecords;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.alert.InMemoryAlertStore;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.StatusStore;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.cases.CaseOperations;
import com.gamma.ops.cases.CaseRule;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.service.CollectorService;
import com.gamma.util.ToonHelper;
import com.gamma.workflow.EscalationRule;
import com.gamma.workflow.ObjectType;
import com.gamma.workflow.SlaPolicy;
import com.gamma.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-PARTNER-SETTLEMENT-1} slice S5, the Case half (decision PS-O5: an administrator applies the dispute governance through
 * its own {@code canAdminister} routes; a Space Template never carries it). Over real HTTP and the real object engine: the SHIPPED
 * workflow, SLA Policy and Escalation Rule are accepted by {@code /components/*}; the shipped Alert Rules turn the ten planted dispute
 * lines (and one closing window) into Incidents that the shipped Case Rule groups into one Case per partner and cycle; a Case moves
 * {@code INVESTIGATING → DISPUTED → AGREED → CREDIT_PENDING → CLOSED}; and the Impact reads as PS-D6 maps it. Which lines exist is
 * pinned by {@code PartnerSettlementDisputeGoldenTest} (module {@code inspecto-reconciliation}); here the grouped Measure probe
 * answers with those keys, as {@code BypassBoxCaseRuleTest} does.
 */
class PartnerSettlementDisputeCaseTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "partner-settlement", "config").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The ten dispute lines of the corpus as {@code dispute_key → item_key}s (the golden in inspecto-reconciliation pins them). */
    private static final Map<String, List<String>> LINES = new LinkedHashMap<>();
    static {
        LINES.put("P1|2026-05-01|2026-05-31", List.of("AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-05-10", "AG-VOICE-A1|we_pay|VOICE|FIXED|2026-05-20"));
        LINES.put("P1|2026-06-01|2026-06-30", List.of("AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-20", "AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-21",
                "AG-VOICE-A1|we_pay|VOICE|MOBILE|2026-06-26"));
        LINES.put("P1|2026-07-01|2026-07-31", List.of("AG-VOICE-A1|we_pay|VOICE|FIXED|2026-07-15"));
        LINES.put("P3|2026-06-16|2026-06-30", List.of("AG-SMS-C1|they_pay|SMS|NATIONAL|2026-06-18", "AG-SMS-C1|they_pay|SMS|NATIONAL|2026-06-22"));
        LINES.put("P3|2026-07-01|2026-07-15", List.of("AG-SMS-C1|they_pay|SMS|NATIONAL|2026-07-02"));
        LINES.put("P2|2026-06-01|2026-06-30", List.of("AG-VOICE-D1|we_pay|VOICE|NATIONAL|2026-06-04"));
    }

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void theShippedDisputeWorkflowIsValidAndASupersetOfTheBuiltInCaseStates() throws Exception {
        Workflow wf = Workflow.fromComponent("case", body("case-workflow.json"));
        assertEquals(List.of(), wf.problems());
        assertEquals(ObjectType.CASE, wf.objectType());
        assertEquals("OPEN", wf.initialState());
        assertTrue(wf.states().containsAll(Workflow.defaultFor(ObjectType.CASE).states()),
                "one workflow per object type per Space replaces the built-in for every Case: no built-in state may be lost");
        assertTrue(wf.states().containsAll(Set.of("DISPUTED", "AGREED", "REJECTED", "CREDIT_PENDING")));
        for (Workflow.Transition t : Workflow.defaultFor(ObjectType.CASE).transitions())
            assertTrue(wf.transitions().contains(t), "a built-in move still works: " + t);
        // the SLA Policy and the Escalation Rule parse as their routes parse them
        assertEquals(ObjectType.CASE, SlaPolicy.fromComponent("case", body("case-sla-policy.json")).objectType());
        EscalationRule esc = EscalationRule.fromComponent("ps-dispute-not-sent", body("case-escalation-age.json"));
        assertNotNull(esc.matchTree(), "the age escalation is narrowed to settlement Cases not yet disputed");
    }

    @Test
    void plantedDisputeLinesBecomeOneCasePerPartnerCycleThatMovesThroughTheDisputeAndCarriesItsImpact(@TempDir Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        System.setProperty("assist.write.root", dir.resolve("wr").toString());
        ControlApi api;
        try {
            api = new ControlApi(svc, 0);
        } finally {
            System.clearProperty("assist.write.root");
        }
        api.start();
        try {
            int port = api.port();
            // ---- PS-O5: the administrator apply step, the shipped bodies through their own routes
            for (String[] g : List.of(new String[]{"workflow", "case-workflow.json"}, new String[]{"sla-policy", "case-sla-policy.json"},
                    new String[]{"escalation-rule", "case-escalation-age.json"})) {
                HttpResponse<String> r = send(port, "POST", "/components/" + g[0], Files.readString(TEMPLATE.resolve("governance").resolve(g[1])));
                assertEquals(200, r.statusCode(), g[0] + ": " + r.body());
            }

            // ---- the shipped Alert Rules: one Incident per dispute line, one per closing window, and the agreement-level finding rule
            ObjectService objects = TestOpsEngine.of(svc);
            Optional<com.gamma.objects.ObjectAccess> ops = Optional.of(objects.access());
            AlertRule line = shipped("ps_dispute_line"), window = shipped("ps_dispute_window"), found = shipped("ps_findings_found");
            AlertService alerts = new AlertService(List.of(line, window, found), noPipelines(), emptyStore(),
                    AlertRecords.of(new InMemoryAlertStore(), ops), AlertRecords.incidentsOf(ops));
            alerts.groupedMeasureProbe(r -> Optional.of(switch (r.name()) {
                case "ps_dispute_line" -> lineKeys();
                case "ps_dispute_window" -> keys("dispute_key", List.of("P2|2026-06-01|2026-06-30"));
                default -> keys("agreement_id", List.of("AG-VOICE-A1", "AG-SMS-C1", "AG-VOICE-D1"));
            }));
            alerts.evaluateRules();
            List<OperationalObject> incidents = objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build());
            assertEquals(10 + 1, incidents.size(), "ten dispute lines and one closing window; ps_findings_found is a WARNING and opens no Incident");
            // an Incident of another kind in the same Space (no dispute_key) must never join a dispute
            objects.open(ObjectType.INCIDENT, "unrelated", "d", "HIGH", null, Map.of());
            for (OperationalObject i : incidents)
                if ("ps_dispute_line".equals(i.attributes().get("rule"))) {
                    String key = String.valueOf(i.attributes().get("key.dispute_key"));
                    assertTrue(LINES.containsKey(key), "a line Incident carries its dispute: " + i.attributes());
                    assertTrue(LINES.get(key).contains(String.valueOf(i.attributes().get("key.item_key"))), i.attributes().toString());
                }

            // ---- the shipped Case Rule: one Case per partner and cycle; the window Incident joins its cycle's Case, the finding rule's never
            CaseRule rule = CaseRule.load(TEMPLATE.resolve("ops").resolve("ps_dispute_caserule.toon"));
            assertEquals("dispute_key", rule.groupBy());
            CaseOperations cases = CaseOperations.of(objects);
            cases.registerCaseRule(rule);
            CaseOperations.CaseRuleEvaluation eval = cases.evaluateCaseRule(rule.name());
            assertEquals(11, eval.grouped(), "the ten lines and the window reminder; the unrelated Incident carries no dispute_key");
            long now = System.currentTimeMillis();
            objects.sweepIncidentSla(now);   // the applied SLA Policy stamps its deadlines on the sweep
            List<OperationalObject> disputes = objects.query(ObjectQuery.builder().objectType(ObjectType.CASE).build());
            assertEquals(LINES.size(), disputes.size(), "one dispute Case per partner and cycle");
            Set<String> groups = new TreeSet<>();
            for (OperationalObject c : disputes) {
                assertEquals("OPEN", c.status());
                assertEquals("partner-settlement", c.attributes().get("category"), "the escalation narrows on it");
                assertNotNull(c.attributes().get("dueAt"), "the applied SLA Policy stamps the Case: " + c.attributes());
                assertNotNull(c.attributes().get("responseDueAt"));
                groups.add(String.valueOf(c.attributes().get("caseGroup")));
            }
            assertEquals(LINES.size(), groups.size());
            assertEquals(0, cases.evaluateCaseRule(rule.name()).grouped(), "re-evaluation is idempotent");

            // ---- the dispute lifecycle over the shared object route, and the Impact as PS-D6 maps it
            String id = disputes.get(0).id();
            assertEquals(422, send(port, "POST", "/objects/" + id + "/transition", "{\"action\":\"agree\"}").statusCode(), "nothing is agreed before it is disputed");
            for (String[] step : List.of(new String[]{"investigate", "INVESTIGATING"}, new String[]{"dispute", "DISPUTED"})) move(port, id, step[0], step[1]);
            impact(port, id, "{\"suspected\":\"120.5000\",\"currency\":\"USD\",\"basis\":\"disputed lines sent to the partner\"}");
            // a Case of another desk (no partner-settlement category) is never escalated by the settlement rule
            OperationalObject foreign = objects.open(ObjectType.CASE, "another desk", "d", "HIGH", null, Map.of());
            // the age escalation: four days on, a Case not yet sent to the partner is escalated once, the DISPUTED one is not
            objects.sweepIncidentSla(now + 4L * 24 * 3600 * 1000);
            String waiting = disputes.get(2).id();
            assertTrue(String.valueOf(objects.get(waiting).orElseThrow().attributes().get("escalations")).contains("ps-dispute-not-sent"),
                    "an OPEN settlement Case is escalated: " + objects.get(waiting).orElseThrow().attributes());
            assertFalse(String.valueOf(objects.get(id).orElseThrow().attributes().get("escalations")).contains("ps-dispute-not-sent"),
                    "a DISPUTED Case is past the escalation's states");
            assertFalse(String.valueOf(objects.get(foreign.id()).orElseThrow().attributes().get("escalations")).contains("ps-dispute-not-sent"),
                    "only partner-settlement Cases are escalated by the settlement rule");
            move(port, id, "agree", "AGREED");
            impact(port, id, "{\"suspected\":\"120.5000\",\"confirmed\":\"100.0000\",\"currency\":\"USD\",\"basis\":\"credit accepted by the partner\"}");
            move(port, id, "await_credit", "CREDIT_PENDING");
            move(port, id, "close", "CLOSED");
            impact(port, id, "{\"suspected\":\"120.5000\",\"confirmed\":\"100.0000\",\"recovered\":\"100.0000\",\"currency\":\"USD\",\"basis\":\"credit accepted by the partner\"}");
            JsonNode closed = V1Body.of(send(port, "GET", "/objects/" + id, null).body());
            JsonNode stored = JSON.readTree(closed.get("attributes").get("impact").asText());
            assertEquals(0, new java.math.BigDecimal("120.5").compareTo(new java.math.BigDecimal(stored.get("suspected").asText())), "suspected = disputed");
            assertEquals(0, new java.math.BigDecimal("100").compareTo(new java.math.BigDecimal(stored.get("confirmed").asText())), "confirmed = the accepted credit");
            assertEquals(0, new java.math.BigDecimal("100").compareTo(new java.math.BigDecimal(stored.get("recovered").asText())), "recovered = the credit note received, recorded on the closed Case");
            assertFalse(send(port, "PUT", "/objects/" + id + "/impact",
                    "{\"impact\":{\"suspected\":\"120.5000\",\"confirmed\":\"90.0000\",\"recovered\":\"100.0000\",\"currency\":\"USD\",\"basis\":\"credit accepted by the partner\"}}")
                    .statusCode() == 200, "a closed dispute's agreed credit is not rewritten");

            // a rejected dispute can be escalated again or closed, and a built-in resolution still works
            String other = disputes.get(1).id();
            move(port, other, "investigate", "INVESTIGATING");
            move(port, other, "dispute", "DISPUTED");
            move(port, other, "reject", "REJECTED");
            move(port, other, "escalate", "ESCALATED");
            move(port, other, "resolve", "RESOLVED");
            move(port, other, "close", "CLOSED");
        } finally {
            api.close();
            svc.close();
        }
    }

    private void move(int port, String id, String action, String to) throws Exception {
        HttpResponse<String> r = send(port, "POST", "/objects/" + id + "/transition", "{\"action\":\"" + action + "\",\"actor\":\"analyst\"}");
        assertEquals(200, r.statusCode(), action + ": " + r.body());
        assertEquals(to, V1Body.of(r.body()).get("status").asText(), action);
    }

    private void impact(int port, String id, String body) throws Exception {
        HttpResponse<String> r = send(port, "PUT", "/objects/" + id + "/impact", "{\"impact\":" + body + "}");
        assertEquals(200, r.statusCode(), r.body());
    }

    private static AlertRule shipped(String name) throws Exception {
        return AlertRule.fromMap(ToonHelper.load(TEMPLATE.resolve("registry").resolve("alert-rules").resolve(name + ".toon").toString()));
    }

    private static DatasetMeasureProbe.Breaches lineKeys() {
        List<DatasetMeasureProbe.Breach> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : LINES.entrySet())
            for (String item : e.getValue()) {
                Map<String, Object> key = new LinkedHashMap<>();
                key.put("dispute_key", e.getKey());
                key.put("item_key", item);
                out.add(new DatasetMeasureProbe.Breach(key, 1));
            }
        return new DatasetMeasureProbe.Breaches(out, out.size());
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

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static Map<String, Object> body(String file) throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> m = JSON.readValue(Files.readString(TEMPLATE.resolve("governance").resolve(file)), Map.class);
        return m;
    }
}
