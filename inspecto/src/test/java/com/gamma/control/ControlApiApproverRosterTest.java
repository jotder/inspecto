package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET/PUT /settings/approvers} over real HTTP with real Subjects ({@link ApproverRoster}): the canAdminister
 * gate, every 422, the absent-key-keeps-its-list rule, and the fail-closed reads (absent / unreadable = empty).
 * The decide-side behaviour is covered where the decisions live (ControlApiPendingChangesTest,
 * ControlApiActionRequestsTest).
 */
class ControlApiApproverRosterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN = "Bearer admin", OPS = "Bearer ops";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String[] who = switch (h) {
                case ADMIN -> new String[] {"admin-1", "admin"};
                case OPS -> new String[] {"ops-1", "operations"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private static Ctx open(Path cfg, Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/settings/approvers"))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void anAbsentRosterReadsEmptyAndAppliesUnderAnIdpWithoutADirectory(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            JsonNode v = data(send(c, "GET", null, OPS), 200);
            assertEquals(0, v.get("users").size());
            assertEquals(0, v.get("groups").size());
            assertTrue(v.get("applies").asBoolean());
        }
    }

    @Test
    void writingNeedsCanAdministerAndEveryBadBodyIs422(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            assertEquals(403, send(c, "PUT", "{\"users\":[\"ops-1\"]}", OPS).statusCode(), "no canAdminister");
            assertFalse(Files.exists(c.root.resolve(ApproverRoster.FILE)));
            for (String bad : List.of("{\"approvers\":[]}", "{\"users\":\"ops-1\"}", "{\"users\":[1]}",
                    "{\"users\":[\"  \"]}", "{\"groups\":[\"a\\u0001b\"]}", "{\"groups\":[\"" + "x".repeat(257) + "\"]}",
                    "{\"users\":[" + String.join(",", java.util.Collections.nCopies(501, "\"u\"")) + "]}"))
                assertEquals(422, send(c, "PUT", bad, ADMIN).statusCode(), bad);
            assertFalse(Files.exists(c.root.resolve(ApproverRoster.FILE)), "a refused write writes nothing");
        }
    }

    @Test
    void aPutReplacesTheListsItNamesAndKeepsTheOther(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            JsonNode v = data(send(c, "PUT", "{\"users\":[\" ana \",\"ana\",\"bo\"],\"groups\":[\"ra-approvers\"]}", ADMIN), 200);
            assertEquals(List.of("ana", "bo"), JSON.convertValue(v.get("users"), List.class), "trimmed, de-duplicated");
            v = data(send(c, "PUT", "{\"users\":[]}", ADMIN), 200);
            assertEquals(0, v.get("users").size());
            assertEquals(List.of("ra-approvers"), JSON.convertValue(v.get("groups"), List.class), "an absent key keeps its list");
            assertEquals(new ApproverRoster.Roster(List.of(), List.of("ra-approvers")), ApproverRoster.load(c.root));
        }
    }

    @Test
    void anUnreadableRosterReadsEmptySoNobodyMayApprove(@TempDir Path cfg, @TempDir Path tmp) throws Exception {
        try (Ctx c = open(cfg, Files.createDirectories(tmp.resolve("config")))) {
            Files.writeString(c.root.resolve(ApproverRoster.FILE), "users: [unterminated\n  :: ::\n");
            ApproverRoster.Roster r = ApproverRoster.load(c.root);
            assertTrue(r.empty(), r.toString());
            assertEquals(0, data(send(c, "GET", null, ADMIN), 200).get("users").size());
        }
    }

    @Test
    void aSubjectIsAdmittedByItsIdOrAnyGroupClaim() {
        ApproverRoster.Roster r = new ApproverRoster.Roster(List.of("ana"), List.of("ra-approvers"));
        assertTrue(r.admits(new Subject("ana", Set.of())));
        assertTrue(r.admits(new Subject("bo", Set.of(), null, Map.of("groups", List.of("x", "ra-approvers")))));
        assertTrue(r.admits(new Subject("bo", Set.of(), null, Map.of("groups", "ra-approvers"))));
        assertFalse(r.admits(new Subject("bo", Set.of(), null, Map.of("groups", List.of("ra")))));
        assertFalse(r.admits(new Subject("bo", Set.of(), null, Map.of("team", "ra-approvers"))), "only the groups claim");
        assertFalse(new ApproverRoster.Roster(List.of(), List.of()).admits(new Subject("ana", Set.of())), "empty = nobody");
        assertFalse(r.admits(null));
    }
}
