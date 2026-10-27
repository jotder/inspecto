package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
import com.gamma.la.core.FsInvestigationStore;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-INVESTIGATION-DELETE-1 — {@code DELETE /inv/investigations/{id}} over real HTTP with an ARMED Authenticator (with no Subject
 * {@code withCapability} is a no-op): 401 without a credential, 403 without {@code canManageIncidents}, 404 for a Case member or a
 * stranger, 409 while linked to an open Case or with a pending four-eyes approval, 200 for the owner, and then nothing is left.
 */
class ControlApiInvestigationDeleteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"id\":\"inv-a\",\"title\":\"Rehearsal run\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String OWNER = "Bearer owner", MEMBER = "Bearer member", STRANGER = "Bearer stranger", NOCAP = "Bearer nocap";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        CaseTeamObjectEngine.CASES = null;
        Authenticators.forTest(null);
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", all));
            case NOCAP -> Optional.of(new Subject("analyst-1", Set.of()));
            case MEMBER -> Optional.of(new Subject("analyst-2", all));   // the Case's assignee
            case STRANGER -> Optional.of(new Subject("analyst-3", all));
            default -> Optional.empty();
        });
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('a','b','voice'),('a','c','sms'),('b','d','voice'))"
                            + " AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, auth);
        assertTrue(r.statusCode() / 100 == 2, method + " " + path + " → " + r.statusCode() + " " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private int status(Ctx c, String method, String path, String body, String auth) throws Exception {
        return send(c, method, path, body, auth).statusCode();
    }

    /** An Investigation with a sealed expand and one open Draft, so there is a log and a Draft to remove. */
    private void investigation(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE, OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", OWNER);
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}", OWNER);
        ok(c, "POST", INV + "/drafts", "{}", OWNER);
    }

    private static Path dir(Ctx c) {
        return c.root.resolve("audit/snapshots/investigations/inv-a");
    }

    @Test
    void theGatesAreArmedAndTheOwnerDeletesLogDraftsAndLeavesAnAuditRecord(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            assertTrue(Files.isDirectory(dir(c).resolve("drafts")));
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                assertEquals(401, status(c, "DELETE", INV, null, null), "no credential");
                assertEquals(403, status(c, "DELETE", INV, null, NOCAP), "no canManageIncidents");
                assertEquals(404, status(c, "DELETE", INV, null, STRANGER), "not a member");
                assertTrue(Files.isRegularFile(dir(c).resolve("header.json")), "a refusal removes nothing");

                JsonNode out = ok(c, "DELETE", INV, null, OWNER);
                assertTrue(out.get("deleted").asBoolean());
                assertEquals(2, out.get("steps").asInt());
                assertEquals(1, out.get("drafts").asInt());
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertFalse(Files.exists(dir(c)), "header, log, sets and Drafts are all gone");
            try (var left = Files.list(dir(c).getParent())) {
                assertEquals(0, left.count(), "no tombstone left behind");
            }
            assertEquals(404, status(c, "GET", INV + "/log", null, OWNER));
            assertEquals(404, status(c, "DELETE", INV, null, OWNER), "a second delete finds nothing");
            assertFalse(ok(c, "GET", "/inv/investigations", null, OWNER).toString().contains("inv-a"), "the list no longer shows it");

            List<Event> mine = seen.stream().filter(e -> e.type().equals(LinkEventTypes.LINK_INVESTIGATION_DELETED)).toList();
            assertEquals(1, mine.size());
            assertEquals("inv-a", mine.get(0).attributes().get("investigationId"));
            assertEquals("Rehearsal run", mine.get(0).attributes().get("title"));
            assertEquals("analyst-1", mine.get(0).attributes().get(com.gamma.audit.AuditAttrs.ACTOR));
            ok(c, "POST", "/inv/investigations", CREATE, OWNER);   // the id is free again
        }
    }

    @Test
    void anOpenCaseAndAPendingApprovalBlockItAndAReadOnlyCaseMemberCannot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            investigation(c);
            ok(c, "PUT", INV + "/case", "{\"caseRef\":\"CASE-1\"}", OWNER);
            assertEquals(404, status(c, "DELETE", INV, null, MEMBER), "a Case member reads, never deletes");
            assertEquals(409, status(c, "DELETE", INV, null, OWNER), "open Case");
            assertTrue(Files.isRegularFile(dir(c).resolve("header.json")));

            cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", true));   // closed: shares nothing, blocks nothing
            new FsInvestigationStore(root).writePending("inv-a", "p1", "{\"id\":\"p1\",\"status\":\"pending\"}");
            assertEquals(409, status(c, "DELETE", INV, null, OWNER), "pending four-eyes approval");

            new FsInvestigationStore(root).writePending("inv-a", "p1", "{\"id\":\"p1\",\"status\":\"denied\"}");
            ok(c, "DELETE", INV, null, OWNER);
            assertFalse(Files.exists(dir(c)));
        }
    }
}
