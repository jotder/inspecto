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
import com.gamma.workflow.ObjectType;
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
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code USER-HANDBOOK-1} P2 lane D, the Case half of the Handbook's worked example (the {@code order-to-cash} Space Template).
 * Over real HTTP and the real object engine: the SHIPPED collections workflow is accepted by {@code /components/workflow}; the
 * SHIPPED Alert Rule turns the six alerting keys of the ground truth into six Incidents, which the SHIPPED Case Rule groups into
 * one collections Case per customer (five: customer C-007 holds two); a Case moves through the write-off approval step. Which keys
 * the Alert Rule raises over the real data is pinned by {@code OrderToCashGoldenTest} (module {@code inspecto-reconciliation});
 * here the grouped Measure probe answers with those keys, read from the same ground-truth file.
 */
class OrderToCashCaseTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "order-to-cash", "config").toAbsolutePath().normalize();
    private static final Path TRUTH = Path.of("..", "..", "tools", "gen-order-to-cash-data.ground-truth.json").toAbsolutePath().normalize();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void theShippedCollectionsWorkflowIsValidAndKeepsEveryBuiltInCaseMove() throws Exception {
        Workflow wf = Workflow.fromComponent("case", body());
        assertEquals(List.of(), wf.problems());
        assertEquals(ObjectType.CASE, wf.objectType());
        assertEquals("OPEN", wf.initialState());
        assertTrue(wf.states().containsAll(Workflow.defaultFor(ObjectType.CASE).states()),
                "one workflow per object type per Space replaces the built-in for every Case: no built-in state may be lost");
        assertTrue(wf.states().containsAll(Set.of("PENDING_APPROVAL", "WRITE_OFF_APPROVED")));
        for (Workflow.Transition t : Workflow.defaultFor(ObjectType.CASE).transitions())
            assertTrue(wf.transitions().contains(t), "a built-in move still works: " + t);
    }

    @Test
    void theAlertingKeysBecomeOneCollectionsCasePerCustomerThatMovesThroughTheWriteOffApproval(@TempDir Path dir) throws Exception {
        JsonNode truth = JSON.readTree(TRUTH.toFile());
        Map<String, Set<String>> wantCases = new TreeMap<>();
        truth.get("cases").fields().forEachRemaining(e -> {
            Set<String> ids = new TreeSet<>();
            e.getValue().forEach(n -> ids.add(n.asText()));
            wantCases.put(e.getKey(), ids);
        });

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
            // ---- the administrator applies the shipped workflow through its own route (a Space Template carries no governance)
            HttpResponse<String> applied = send(port, "POST", "/components/workflow",
                    Files.readString(TEMPLATE.resolve("governance").resolve("case-workflow.json")));
            assertEquals(200, applied.statusCode(), applied.body());

            // ---- the shipped Alert Rule: one Incident per alerting order
            ObjectService objects = TestOpsEngine.of(svc);
            Optional<com.gamma.objects.ObjectAccess> ops = Optional.of(objects.access());
            AlertRule rule = AlertRule.fromMap(ToonHelper.load(TEMPLATE.resolve("registry").resolve("alert-rules")
                    .resolve("o2c_unmatched_value.toon").toString()));
            AlertService alerts = new AlertService(List.of(rule), noPipelines(), emptyStore(),
                    AlertRecords.of(new InMemoryAlertStore(), ops), AlertRecords.incidentsOf(ops));
            alerts.groupedMeasureProbe(r -> Optional.of(incidentKeys(truth)));
            alerts.evaluateRules();
            List<OperationalObject> incidents = objects.query(ObjectQuery.builder().objectType(ObjectType.INCIDENT).build());
            assertEquals(truth.get("incidents").size(), incidents.size(), "one Incident per alerting order");
            // an Incident of another kind in the same Space (no customer_id) must never join a collections Case
            objects.open(ObjectType.INCIDENT, "unrelated", "d", "HIGH", null, Map.of());

            // ---- the shipped Case Rule: one collections Case per customer
            CaseRule caseRule = CaseRule.load(TEMPLATE.resolve("ops").resolve("o2c_collections_caserule.toon"));
            assertEquals("customer_id", caseRule.groupBy());
            CaseOperations cases = CaseOperations.of(objects);
            cases.registerCaseRule(caseRule);
            assertEquals(truth.get("incidents").size(), cases.evaluateCaseRule(caseRule.name()).grouped(),
                    "every alerting order; the unrelated Incident carries no customer_id");
            List<OperationalObject> opened = objects.query(ObjectQuery.builder().objectType(ObjectType.CASE).build());
            assertEquals(wantCases.size(), opened.size(), "one collections Case per customer: " + wantCases);
            Set<String> groups = new TreeSet<>();
            for (OperationalObject c : opened) {
                assertEquals("OPEN", c.status());
                assertEquals("order-to-cash", c.attributes().get("category"));
                groups.add(String.valueOf(c.attributes().get("caseGroup")));
            }
            assertEquals(wantCases.size(), groups.size());
            assertEquals(0, cases.evaluateCaseRule(caseRule.name()).grouped(), "re-evaluation is idempotent");

            // ---- the write-off approval step: proposed, sent back, proposed again, approved, closed; nothing skips it
            String id = opened.get(0).id();
            assertEquals(422, send(port, "POST", "/objects/" + id + "/transition", "{\"action\":\"approve_write_off\"}").statusCode(),
                    "nothing is approved before it is investigated and proposed");
            move(port, id, "investigate", "INVESTIGATING");
            assertEquals(422, send(port, "POST", "/objects/" + id + "/transition", "{\"action\":\"approve_write_off\"}").statusCode(),
                    "a write-off is approved only once proposed");
            move(port, id, "propose_write_off", "PENDING_APPROVAL");
            move(port, id, "send_back", "INVESTIGATING");
            move(port, id, "propose_write_off", "PENDING_APPROVAL");
            move(port, id, "approve_write_off", "WRITE_OFF_APPROVED");
            move(port, id, "close", "CLOSED");
            // a built-in resolution still works on another Case
            String other = opened.get(1).id();
            move(port, other, "investigate", "INVESTIGATING");
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

    private static DatasetMeasureProbe.Breaches incidentKeys(JsonNode truth) {
        List<DatasetMeasureProbe.Breach> out = new ArrayList<>();
        for (JsonNode i : truth.get("incidents")) {
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("customer_id", i.get("customer_id").asText());
            key.put("order_id", i.get("order_id").asText());
            out.add(new DatasetMeasureProbe.Breach(key, i.get("unmatched_value").asDouble()));
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

    private static Map<String, Object> body() throws Exception {
        @SuppressWarnings("unchecked") Map<String, Object> m = JSON.readValue(
                Files.readString(TEMPLATE.resolve("governance").resolve("case-workflow.json")), Map.class);
        return m;
    }
}
