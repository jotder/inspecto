package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.service.CollectorService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the security audit trail emitted centrally from {@link ControlApi#dispatch}:
 * a state-changing request produces an append-only {@code AUDIT} event carrying actor/action/ip; the
 * append-only event routes reject mutation methods (405 immutability guard); and a non-GET attempt at
 * an unknown route is recorded as {@code ACCESS_DENIED}.
 */
class ControlApiAuditTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String name) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-02-05\n");
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), "test_etl");
    }

    @Test
    void mutatingRequestEmitsAuditEvent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // W5b: the trigger is accepted asynchronously — wait for the run before reading its audit event.
            HttpResponse<String> run = send(c.port, "POST", "/runs/" + c.name + "/trigger", null);
            assertEquals(202, run.statusCode(), run.body());
            assertEquals("SUCCESS", awaitRun(c.port, "/runs/runs/" + json(run).get("runId").asText()));

            JsonNode events = recentEvents(c);
            JsonNode audit = null;
            for (JsonNode e : events) {
                // The default space's event store is process-global (SpaceRoot.legacy()), so /events can carry
                // pipeline.triggered audits from other integration tests — match this run's own pipeline by id.
                JsonNode a = e.get("attributes");
                if ("AUDIT".equals(e.get("type").asText())
                        && "pipeline.triggered".equals(a.get("action").asText())
                        && c.name.equals(a.path("target_id").asText())) {
                    audit = e;
                }
            }
            assertNotNull(audit, "a pipeline.triggered AUDIT event was recorded");
            JsonNode attrs = audit.get("attributes");
            assertEquals("appUser", attrs.get("actor").asText(), "auth-free default actor");
            assertEquals("data_mutation", attrs.get("action_category").asText());
            assertEquals("pipeline", attrs.get("target_type").asText());
            assertEquals(c.name, attrs.get("target_id").asText());
            assertFalse(attrs.get("ip").asText().isBlank(), "client ip captured");
        }
    }

    @Test
    void honoursCustomActorHeader(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + c.port + "/api/v1" + "/runs/" + c.name + "/pause"))
                    .header("X-Actor", "support_agent")
                    .POST(BodyPublishers.noBody()).build();
            assertEquals(200, client.send(req, BodyHandlers.ofString()).statusCode());

            JsonNode events = recentEvents(c);
            boolean found = false;
            for (JsonNode e : events) {
                if ("AUDIT".equals(e.get("type").asText())
                        && "support_agent".equals(e.get("attributes").path("actor").asText())) found = true;
            }
            assertTrue(found, "X-Actor header threads through as the audit actor");
        }
    }

    @Test
    void forbiddenRouteAttemptIsAudited(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, send(c.port, "DELETE", "/nonexistent-secret-route", null).statusCode());

            JsonNode events = recentEvents(c);
            boolean denied = false;
            for (JsonNode e : events) {
                if ("ACCESS_DENIED".equals(e.get("type").asText())
                        && e.get("attributes").path("http_path").asText().contains("nonexistent-secret-route"))
                    denied = true;
            }
            assertTrue(denied, "a non-GET attempt at an unknown route is recorded as ACCESS_DENIED");
        }
    }

    /**
     * The audit read-out, re-seated off HTTP (EDG-01 cell 6, 2026-09-08). These tests are about the AUDIT
     * TRAIL, not the events feed — they only ever used {@code GET /api/v1/events?limit=200} as a convenient
     * way to see what was recorded. The feed moved to the optional {@code inspecto-events} module, so on
     * this (default, Personal) build that path now answers 503; reading the store directly keeps the
     * assertions and drops the accidental dependency on an edition-gated surface.
     *
     * <p>⛔ This is the point of the cell that must NOT be lost: audit events are still RECORDED on
     * Personal. Had these tests been deleted rather than re-seated, nothing would prove that.
     *
     * <p>{@code page(200, null, null)} is the exact store call the v1 route made — newest-first over the
     * full retained history, not the live-tail ring — so the rows seen here are the rows it served.
     */
    private JsonNode recentEvents(Ctx c) {
        return JSON.valueToTree(c.svc.events().page(200, null, null).stream().map(Event::toMap).toList());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    /** Poll an accepted run (W5b) to its terminal status, or fail after 10s. */
    private String awaitRun(int port, String runPath) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            String status = json(send(port, "GET", runPath, null)).get("status").asText();
            if (!"RUNNING".equals(status)) return status;
            Thread.sleep(50);
        }
        return fail("run " + runPath + " did not reach a terminal status within 10s");
    }
}
