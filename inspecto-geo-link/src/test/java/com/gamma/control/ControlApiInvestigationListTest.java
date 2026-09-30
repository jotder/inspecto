package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /inv/investigations} (operator 2026-09-30): the Investigations the caller may READ — their own, plus
 * those shared read-only through an open linked Case (LA-24) — with the PDP able only to narrow. Real HTTP, armed
 * Authenticator (with no Subject nothing is enforced, so nothing would be proven).
 */
class ControlApiInvestigationListTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", MEMBER = "Bearer member", STRANGER = "Bearer stranger";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        CaseTeamObjectEngine.CASES = null;
        Authenticators.forTest(null);
        AccessDeciders.forTest(null);
    }

    private static void subjects() {
        Set<String> all = Set.of("canManageIncidents");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", all));
            case MEMBER -> Optional.of(new Subject("analyst-2", all));     // the Case's assignee
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
                    "SELECT caller, callee FROM (VALUES ('a','b'),('a','c')) AS t(caller,callee)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port());
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
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void create(Ctx c, String id, String caseRef) throws Exception {
        ok(c, "POST", "/inv/investigations", "{\"id\":\"" + id + "\",\"title\":\"T " + id + "\",\"purpose\":\"FR-1\","
                + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\""
                + (caseRef == null ? "" : ",\"caseRef\":\"" + caseRef + "\"") + "}", OWNER);
    }

    private List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (JsonNode i : list.get("items")) out.add(i.get("id").asText());
        return out;
    }

    @Test
    void ownerSeesOwnCaseMemberSeesSharedReadOnlyStrangerSeesNoneAndAClosedCaseDropsIt(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        subjects();
        Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.arm();
        cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", false));
        try (Ctx c = open(cfg, root)) {
            create(c, "inv-private", null);
            create(c, "inv-shared", "CASE-1");
            ok(c, "POST", "/inv/investigations/inv-shared/ops", "{\"op\":\"seed\",\"ids\":[\"a\"]}", OWNER);

            JsonNode mine = ok(c, "GET", "/inv/investigations", null, OWNER);
            assertEquals(Set.of("inv-private", "inv-shared"), Set.copyOf(ids(mine)), mine.toString());
            assertEquals(2, mine.get("total").asInt());
            assertFalse(mine.get("truncated").asBoolean());
            for (JsonNode i : mine.get("items")) {
                assertEquals("owner", i.get("access").asText());
                assertEquals("analyst-1", i.get("owner").asText());
                assertEquals("calls_ds", i.get("dataset").asText());
                assertEquals("T " + i.get("id").asText(), i.get("title").asText());
                assertTrue(i.hasNonNull("createdAt"), i.toString());
                assertEquals(i.get("id").asText().equals("inv-shared") ? 1 : 0, i.get("headStep").asInt(), i.toString());
                assertEquals(i.get("id").asText().equals("inv-shared"), i.has("caseRef"), i.toString());
            }

            JsonNode shared = ok(c, "GET", "/inv/investigations", null, MEMBER);
            assertEquals(List.of("inv-shared"), ids(shared), shared.toString());
            JsonNode item = shared.at("/items/0");
            assertEquals("case-member", item.get("access").asText());
            assertTrue(item.get("readOnly").asBoolean());
            assertEquals("CASE-1", item.get("caseRef").asText());

            assertEquals(List.of(), ids(ok(c, "GET", "/inv/investigations", null, STRANGER)), "a stranger sees none");

            cases.put("CASE-1", CaseTeamObjectEngine.caseOf("CASE-1", "lead-1", "analyst-2", true));
            assertEquals(List.of(), ids(ok(c, "GET", "/inv/investigations", null, MEMBER)), "a closed Case shares nothing");
            assertEquals(2, ok(c, "GET", "/inv/investigations", null, OWNER).get("total").asInt(), "the owner is untouched");
        }
    }

    @Test
    void aPolicyDenyHidesAnInvestigationEvenFromItsOwner(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            create(c, "inv-a", null);
            create(c, "inv-b", null);
            AtomicBoolean deny = new AtomicBoolean(true);
            AccessDeciders.forTest((ex, subject, action, route, kind, resource) ->
                    deny.get() && "investigation".equals(kind) && "inv-b".equals(resource.get("id"))
                            ? AccessDecider.Decision.DENY : AccessDecider.Decision.ABSTAIN);
            assertEquals(List.of("inv-a"), ids(ok(c, "GET", "/inv/investigations", null, OWNER)));
            deny.set(false);
            assertEquals(2, ok(c, "GET", "/inv/investigations", null, OWNER).get("total").asInt());
        }
    }

    @Test
    void pagesNewestFirstWithATrueTotalAndRefusesABadPage(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root)) {
            for (String id : List.of("inv-1", "inv-2", "inv-3")) {
                create(c, id, null);
                Thread.sleep(5);   // distinct createdAt
            }
            JsonNode p1 = ok(c, "GET", "/inv/investigations?limit=2", null, OWNER);
            assertEquals(List.of("inv-3", "inv-2"), ids(p1), p1.toString());
            assertEquals(3, p1.get("total").asInt());
            assertTrue(p1.get("truncated").asBoolean());
            JsonNode p2 = ok(c, "GET", "/inv/investigations?limit=2&offset=2", null, OWNER);
            assertEquals(List.of("inv-1"), ids(p2));
            assertFalse(p2.get("truncated").asBoolean());
            assertEquals(422, send(c, "GET", "/inv/investigations?offset=-1", null, OWNER).statusCode());
            assertEquals(422, send(c, "GET", "/inv/investigations?limit=x", null, OWNER).statusCode());
        }
    }
}
