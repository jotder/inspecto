package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The machine agent over real HTTP (AGENT-KIT-1 AK-2c, plan {@code 2026-11-20-agent-kit-ak2-plan.md} §4.5, §6.2 lane A):
 * a machine client listed in {@code -Dauth.oidc.agentMachineClients} is a Subject that is both a machine and an agent. Its
 * config write is held (202) like a delegated person's agent; it never approves; another person's approval replays the
 * change as that machine — still admitted by the Space and holding only what a machine agent keeps ({@code authorNow}).
 */
class ControlApiAgentMachineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** BOT = the machine agent ops-bot, its roles granting everything (admin); CHECKER = a person who approves. */
    private static final String BOT = "Bearer bot", CHECKER = "Bearer checker", ADMIN = "Bearer admin";
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    static Path auditDir;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            Roles.Def admin = Roles.effective(ex).get("admin");
            ComponentAccess.heldRoles(ex, Set.of("admin"));
            return switch (h) {
                case BOT -> Optional.of(new Subject("client:ops-bot", admin.capabilities(), null, Map.of(), null, null,
                        "svc-ops", "ops-bot"));
                case CHECKER -> Optional.of(new Subject("checker-1", admin.capabilities()));
                case ADMIN -> Optional.of(new Subject("admin-1", admin.capabilities()));
                default -> Optional.empty();
            };
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        Path root = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("admin-1", "checker-1"))));
            Roles.write(root, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String pack(String id) {
        return "{\"id\":\"" + id + "\",\"title\":\"Carousel\"}";
    }

    private boolean exists(Ctx c, String id) {
        return new ComponentStore(c.root.resolve("registry")).exists("pattern-pack", id);
    }

    @Test
    void aMachineAgentsWriteIsHeldItNeverApprovesAndAnotherPersonsApprovalAppliesItAsTheMachine(@TempDir Path dir)
            throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode held = data(send(c, "POST", "/components/pattern-pack", pack("bot-pack"), BOT), 202);
            assertEquals("ops-bot", held.at("/pendingChange/agentClient").asText(), held.toString());
            assertEquals("client:ops-bot", held.at("/pendingChange/author").asText(), held.toString());
            assertEquals("svc-ops", held.at("/pendingChange/authorMachine").asText(),
                    "the record keeps the machine, so its approved replay is re-admitted and filtered (authorNow)");
            assertFalse(exists(c, "bot-pack"), "nothing written");
            String id = held.at("/pendingChange/id").asText();

            HttpResponse<String> byBot = send(c, "POST", "/pending-changes/" + id + "/approve", "{}", BOT);
            assertEquals(403, byBot.statusCode(), "a machine agent never approves: " + byBot.body());
            assertEquals(403, send(c, "PUT", "/settings/approval", "{\"approval\":{}}", BOT).statusCode(),
                    "nor administers, whatever its roles grant");

            JsonNode approved = data(send(c, "POST", "/pending-changes/" + id + "/approve", "{}", CHECKER), 200);
            assertTrue(approved.get("applied").asBoolean(), approved.toString());
            assertTrue(exists(c, "bot-pack"), "another person's approval applies it");
        }
    }

    @Test
    void anApprovedMachineChangeReplaysWithTheMachineFilterAndOnlyWhileTheSpaceAdmitsTheMachine(@TempDir Path dir)
            throws Exception {
        Roles.write(dir, Map.of(), List.of(), List.of("ops-bot"));
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("author", "client:ops-bot");
        rec.put("authorRoles", List.of("admin"));
        rec.put("agentClient", "ops-bot");
        rec.put("authorMachine", "svc-ops");
        Subject replayed = PendingChanges.authorNow(rec, dir);
        assertTrue(replayed.machine(), "it replays as the machine");
        assertEquals("ops-bot", replayed.agentClient());
        Set<String> keeps = new java.util.HashSet<>(Roles.MACHINE_CAPABILITIES);
        keeps.addAll(Roles.AGENT_MACHINE_CAPABILITIES);
        assertTrue(keeps.containsAll(replayed.capabilities()), "never more than a machine agent keeps: " + replayed.capabilities());
        assertFalse(replayed.capabilities().contains(Roles.CAN_ADMINISTER), "the admin role's grants are filtered");
        assertTrue(replayed.capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH));

        rec.remove("authorMachine");
        assertTrue(PendingChanges.authorNow(rec, dir).capabilities().contains(Roles.CAN_ADMINISTER),
                "precondition: without the machine record the same roles replay unfiltered");
        rec.put("authorMachine", "svc-ops");

        Roles.write(dir, Map.of(), List.of(), List.of("other"));
        assertEquals(403, assertThrows(ApiException.class, () -> PendingChanges.authorNow(rec, dir)).status,
                "a Space that no longer names the machine client refuses its replay");
    }
}
