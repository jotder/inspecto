package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Phase-1 Event Viewer routes over real HTTP: the live-tail feed
 * ({@code /events}), filtered search, event-by-id, CSV/JSON export, and saved-view CRUD. A pipeline
 * trigger generates a {@code BATCH_COMMITTED} domain event (plus captured INFO logs) to query against.
 *
 * <p><b>Moved here from {@code inspecto} with {@link EventRoutes}</b> (EDG-01 cell 6, 2026-09-08), not
 * deleted: these assertions are the feed's contract and still hold — just no longer of the DEFAULT
 * (Personal) build, where the module is absent and {@code AbsentEventsRoutes} answers 503. The halves are
 * paired on purpose: this class proves the feed WITH the module,
 * {@code NoExchangeShipsInThePersonalBuildTest} proves the 503 WITHOUT it, so a path that drifted out of
 * step fails one of them.
 *
 * <p>⚠ Lives in package {@code com.gamma.control} so it can construct the package-private
 * {@code ControlApi} — the same split-package arrangement {@code inspecto-metrics} and
 * {@code inspecto-exchange} use.
 */
class ControlApiEventsTest {

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
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), "test_etl");
    }

    @Test
    void eventsFeedSearchDetailAndExport(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // W5b: the trigger is accepted asynchronously — wait for the run before reading its events.
            HttpResponse<String> run = send(c.port, "POST", "/runs/" + c.name + "/trigger", null);
            assertEquals(202, run.statusCode(), run.body());
            assertEquals("SUCCESS", awaitRun(c.port, "/runs/runs/" + json(run).get("runId").asText()));

            // live-tail feed: non-empty, carries a BATCH_COMMITTED domain event
            JsonNode recent = json(send(c.port, "GET", "/events?limit=200", null));
            assertTrue(recent.isArray() && recent.size() > 0, "events recorded");
            String commitId = null;
            for (JsonNode e : recent) {
                if ("BATCH_COMMITTED".equals(e.get("type").asText())) commitId = e.get("eventId").asText();
            }
            assertNotNull(commitId, "a BATCH_COMMITTED event is present");

            // filtered search by type
            JsonNode byType = json(send(c.port, "GET", "/events/search?type=BATCH_COMMITTED", null));
            assertTrue(byType.size() >= 1);
            assertEquals("BATCH_COMMITTED", byType.get(0).get("type").asText());
            assertEquals(c.name, byType.get(0).get("pipeline").asText());
            assertFalse(byType.get(0).get("correlationId").asText().isBlank(), "batchId threaded as correlationId");

            // event-by-id: hit + miss
            assertEquals(200, send(c.port, "GET", "/events/" + commitId, null).statusCode());
            assertEquals(404, send(c.port, "GET", "/events/no-such-id", null).statusCode());

            // CSV export
            HttpResponse<String> csv = send(c.port, "GET",
                    "/events/export?format=csv&type=BATCH_COMMITTED", null);
            assertEquals(200, csv.statusCode());
            assertTrue(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
            assertTrue(csv.body().startsWith("timestamp,level,type,source,pipeline,correlationId,message"));
            assertTrue(csv.body().contains("BATCH_COMMITTED"), "exported row present");
            assertEquals("timestamp,level,type,source,pipeline,correlationId,message",
                    csv.body().lines().findFirst().orElse(""),
                    "a non-audit export keeps the base 7-column shape");

            // audit-shaped CSV (AUDIT-CSV-1 / compliance G10): type=AUDIT appends one column per
            // AuditAttrs key, derived from AuditAttrs.ALL — the plain projection silently dropped
            // actor/action/target/ip/policy. The POST trigger above is an audited mutation, so the
            // store already holds a real AUDIT row carrying the default actor.
            HttpResponse<String> auditCsv = send(c.port, "GET", "/events/export?format=csv&type=AUDIT", null);
            assertEquals(200, auditCsv.statusCode());
            assertEquals("timestamp,level,type,source,pipeline,correlationId,message,"
                            + String.join(",", com.gamma.event.AuditAttrs.ALL),
                    auditCsv.body().lines().findFirst().orElse(""),
                    "audit-shaped header derives its columns from AuditAttrs.ALL");
            assertTrue(auditCsv.body().contains("appUser"),
                    "the actor attribute reaches the CSV (auth-free core default identity)");
            assertTrue(auditCsv.body().contains("pipeline.triggered"),
                    "the action attribute reaches the CSV (classify maps /runs to pipeline)");
        }
    }

    @Test
    void savedViewsCrud(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode created = json(send(c.port, "POST", "/events/views",
                    "{\"name\":\"errors\",\"level\":\"ERROR\",\"q\":\"fail\"}"));
            assertEquals("errors", created.get("name").asText());
            assertEquals("ERROR", created.get("filters").get("level").asText());

            JsonNode list = json(send(c.port, "GET", "/events/views", null));
            assertTrue(list.isArray() && list.size() == 1);
            assertEquals("errors", list.get(0).get("name").asText());

            assertEquals(200, send(c.port, "POST", "/events/views/errors/delete", null).statusCode());
            assertEquals(0, json(send(c.port, "GET", "/events/views", null)).size());
            assertEquals(404, send(c.port, "POST", "/events/views/errors/delete", null).statusCode(),
                    "deleting a missing view → 404");

            assertEquals(400, send(c.port, "POST", "/events/views", "{}").statusCode(),
                    "name is required");
        }
    }

    /**
     * ROUTE-UNGATED-DEFAULT-1 (gated 2026-09-15): a saved view is server-wide — {@code SavedView} carries no
     * subject and there is one store per service — so writing or deleting one is authoring, not a personal
     * convenience. Both writes take {@code canAuthorWorkbench}; the read stays open (reads are open by policy
     * on every edition). ⚠ Three statuses are asserted on purpose: 401 is authentication, 403 is the GATE —
     * a present Subject lacking the capability — and only the 403 case distinguishes a gated route from one
     * that merely requires a login. This file had no capability gate of any kind before this test.
     */
    @Test
    void savedViewWritesRequireCanAuthorWorkbench(@TempDir Path dir) throws Exception {
        Authenticators.forTest(ex -> "Bearer valid".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")))
                : "Bearer plain".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("nobody", Set.of()))
                : Optional.empty());
        try (Ctx c = open(dir)) {
            String view = "{\"name\":\"errors\",\"level\":\"ERROR\"}";

            assertEquals(401, send(c.port, "POST", "/events/views", view).statusCode(),
                    "no credential is a clean 401, never a 500");

            HttpResponse<String> denied = sendAs(c.port, "POST", "/events/views", view, "Bearer plain");
            assertEquals(403, denied.statusCode(), "a Subject WITHOUT the capability is refused: " + denied.body());
            assertTrue(denied.body().contains("canAuthorWorkbench"), "the refusal names the capability");
            assertEquals(403, sendAs(c.port, "POST", "/events/views/errors/delete", null, "Bearer plain").statusCode(),
                    "the POST-shaped delete is gated identically — the verb changes nothing");

            assertEquals(200, sendAs(c.port, "GET", "/events/views", null, "Bearer plain").statusCode(),
                    "reads stay open by policy: the same capability-less Subject may LIST views");

            assertEquals(200, sendAs(c.port, "POST", "/events/views", view, "Bearer valid").statusCode());
            assertEquals(200, sendAs(c.port, "POST", "/events/views/errors/delete", null, "Bearer valid").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return sendAs(port, method, path, body, null);
    }

    private HttpResponse<String> sendAs(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return json(r.body());
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

    /**
     * Parse a v1 response and peel the envelope's {@code data} (mirrors the control module's
     * {@code V1Body.of}, which lives in inspecto's TEST tree and so is not visible from another module —
     * the inspecto-policy / inspecto-exchange precedent).
     */
    private static JsonNode json(String raw) throws Exception {
        JsonNode n = JSON.readTree(raw);
        return n.has("data") ? n.get("data") : n;
    }
}
