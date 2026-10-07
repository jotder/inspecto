package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Incident-promoting half of {@code POST /decision-rules/{name}/apply}, split out of
 * {@code ControlApiDecisionRulesTest} when that class moved to {@code inspecto}
 * (EDITION-GATED-TESTS-IN-WRONG-HOME-1). The routes and the {@code when} evaluation are core and now run in
 * the default reactor; these three cases cannot follow, because every one of them reads back through
 * {@code GET /objects} — a route that only exists when {@code inspecto-ops} is installed.
 *
 * <p>🔴 Do not "simplify" this by moving them back. Measured 2026-09-17 in a default build:
 * {@code applyCreateAlertWarningStaysSignalOnly} fails (it expects zero Incidents and the object-less
 * envelope answers one), and {@code applyCreateAlertPromotesCriticalToIncidentDeduped} <b>passes for the
 * wrong reason</b> — the same envelope happens to satisfy its {@code == 1}. A test that cannot see an
 * Incident cannot guard Incident promotion.
 */
class ControlApiDecisionRuleApplyTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    // ── apply: create-alert consequence → Incident promotion (critical/error only) ──

    @Test
    void applyCreateAlertPromotesCriticalToIncidentDeduped(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String rule = "{\"name\":\"cost_breach\",\"targetType\":\"pipeline\",\"target\":\"orders\","
                    + "\"consequences\":[{\"action\":\"create-alert\","
                    + "\"params\":{\"rule\":\"cost_alert\",\"severity\":\"critical\"}}]}";
            send(c.port, "POST", "/decision-rules", rule);

            JsonNode consequence = json(send(c.port, "POST", "/decision-rules/cost_breach/apply", null))
                    .get("executed").get(0);
            assertEquals("executed", consequence.get("status").asText());
            assertTrue(consequence.get("detail").asText().contains("Incident"), consequence.get("detail").asText());

            assertEquals(1, json(send(c.port, "GET", "/objects?type=INCIDENT", null)).size(),
                    "a critical create-alert consequence opens a managed Incident");

            // re-apply while the Incident is open → deduped, still one
            send(c.port, "POST", "/decision-rules/cost_breach/apply", null);
            assertEquals(1, json(send(c.port, "GET", "/objects?type=INCIDENT", null)).size(),
                    "one open Incident per rule — re-apply does not clone it");
        }
    }

    @Test
    void applyCreateAlertWarningStaysSignalOnly(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String rule = "{\"name\":\"soft\",\"targetType\":\"pipeline\",\"target\":\"orders\","
                    + "\"consequences\":[{\"action\":\"create-alert\","
                    + "\"params\":{\"rule\":\"soft_alert\",\"severity\":\"warning\"}}]}";
            send(c.port, "POST", "/decision-rules", rule);
            json(send(c.port, "POST", "/decision-rules/soft/apply", null));
            assertEquals(0, json(send(c.port, "GET", "/objects?type=INCIDENT", null)).size(),
                    "a warning create-alert stays a ledger signal — no Incident");
        }
    }

    // ── apply: create-incident consequence (any-severity, deduped, no Alert Rule) ──

    @Test
    void applyCreateIncidentOpensIncidentAtAnySeverityDeduped(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            // A plain 'warning' severity — which create-alert would leave signal-only — opens an
            // Incident here, because create-incident is the explicit, any-severity generalization.
            String rule = "{\"name\":\"watch_orders\",\"targetType\":\"pipeline\",\"target\":\"orders\","
                    + "\"consequences\":[{\"action\":\"create-incident\","
                    + "\"params\":{\"title\":\"Orders under watch\",\"severity\":\"warning\"}}]}";
            send(c.port, "POST", "/decision-rules", rule);

            JsonNode consequence = json(send(c.port, "POST", "/decision-rules/watch_orders/apply", null))
                    .get("executed").get(0);
            assertEquals("executed", consequence.get("status").asText());
            assertTrue(consequence.get("detail").asText().contains("Orders under watch"),
                    consequence.get("detail").asText());

            assertEquals(1, json(send(c.port, "GET", "/objects?type=INCIDENT", null)).size(),
                    "create-incident opens a managed Incident regardless of severity");

            // re-apply while the Incident is open → deduped, still one
            send(c.port, "POST", "/decision-rules/watch_orders/apply", null);
            assertEquals(1, json(send(c.port, "GET", "/objects?type=INCIDENT", null)).size(),
                    "one open Incident per rule — re-apply does not clone it");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }
}
