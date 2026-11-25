package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The analyst agent in Link Analysis over real HTTP (AGENT-KIT-1 AK-2f, plan §6.6, operator 2026-11-25): a person's agent
 * runs Link Analysis queries — masked, as it never reveals — and works in a Draft at once, but never promotes it or edits
 * the Investigation directly; a machine agent that a lead added as a member has its Draft writes held as Pending Actions
 * that run as the machine once a change approver approves them; a machine that is no member gets nothing from an
 * approval; a machine never attaches an Investigation to a Case, starts a graph run or promotes, and leaves no record.
 */
class ControlApiAgentLinkAnalysisTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RAW = "alice-001";
    private static final String CREATE = "{\"id\":\"%s\",\"purpose\":\"Fraud referral FR-9\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String BASE = "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"";
    /** LEAD = a person; AGENT = analyst-2 through agent client claude-code; BOT = machine agent la-bot (drafts only). */
    private static final String LEAD = "Bearer lead", AGENT = "Bearer agent", BOT = "Bearer bot", CHECKER = "Bearer checker";
    private final HttpClient client = HttpClient.newHttpClient();
    /** The machine's authored Space role: the Draft right, and two rights a machine agent never keeps. */
    private static final String MACHINE_ROLE = "la-drafter";

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        Set<String> person = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canRevealLinkEntities",
                "canApproveLinkExpansions", "canApproveChanges", "canWorkIncidents");
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            Set<String> agentCaps = new HashSet<>(person);
            agentCaps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);
            return switch (h) {
                case LEAD -> Optional.of(new Subject("lead-1", person));
                case CHECKER -> Optional.of(new Subject("checker-1", person));
                case AGENT -> Optional.of(new Subject("analyst-2", agentCaps, null, Map.of(), null, null, null, "claude-code"));
                // its Space role grants more; Roles.admitMachine leaves it only canDraftInvestigations of these. The role is
                // recorded as OidcAuthenticator does, so an approved replay re-derives the machine's rights from it.
                case BOT -> {
                    com.gamma.access.ComponentAccess.heldRoles(ex, Set.of(MACHINE_ROLE));
                    yield Optional.of(new Subject("client:la-bot", Roles.effective(ex).get(MACHINE_ROLE).capabilities(), null,
                            Map.of(), null, null, "svc-la", "la-bot"));
                }
                default -> Optional.empty();
            };
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot, String masking) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            Files.writeString(writeRoot.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("lead-1", "checker-1"))));
            Roles.write(writeRoot, Map.of(MACHINE_ROLE, new Roles.Def(Set.of(Roles.CAN_DRAFT_INVESTIGATIONS,
                    Roles.CAN_MANAGE_INCIDENTS, Roles.CAN_RUN_LINK_GRAPH_ANALYSIS), null)), List.of(), List.of("la-bot"));
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            Files.writeString(writeRoot.resolve("link-analysis.toon"), "masking_mode: " + masking + "\n");
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES ('alice-001','bob-002','voice'),('alice-001','carol-003','sms'),"
                            + "('bob-002','dave-004','voice'),('erin-005','frank-006','voice')) AS t(caller,callee,channel)",
                    "2026-10-03T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
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
        assertEquals(status, r.statusCode(), r.request().method() + " " + r.request().uri() + " -> " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** An Investigation {@code id} owned by the lead, seeded, with analyst-2 (and the machine, when asked) as analysts. */
    private void investigation(Ctx c, String id, boolean machineMember) throws Exception {
        data(send(c, "POST", "/inv/investigations", String.format(CREATE, id), LEAD), 200);
        data(send(c, "POST", "/inv/investigations/" + id + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice-001\"]}", LEAD), 200);
        data(send(c, "POST", "/inv/investigations/" + id + "/members", "{\"subject\":\"analyst-2\",\"role\":\"analyst\"}", LEAD), 200);
        if (machineMember)   // the operator's answer: a lead adds the machine as a member, Investigation by Investigation
            data(send(c, "POST", "/inv/investigations/" + id + "/members", "{\"subject\":\"client:la-bot\",\"role\":\"analyst\"}",
                    LEAD), 200);
    }

    private int pendingActions(Ctx c) throws Exception {
        return data(send(c, "GET", "/pending-actions", null, CHECKER), 200).get("total").asInt();
    }

    @Test
    void aPersonsAgentQueriesMaskedAndDraftsAtOnceButNeverPromotesOrEditsTheInvestigation(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root, "all")) {
            HttpResponse<String> projection = send(c, "POST", "/inv/projection", "{" + BASE + "}", AGENT);
            assertEquals(200, projection.statusCode(), "a Link Analysis query is open to an agent: " + projection.body());
            assertFalse(projection.body().contains(RAW), "an agent never reveals: " + projection.body());

            investigation(c, "inv-a", false);
            String draft = data(send(c, "POST", "/inv/investigations/inv-a/drafts", "{}", AGENT), 201).get("draftId").asText();
            data(send(c, "POST", "/inv/investigations/inv-a/drafts/" + draft + "/ops",
                    "{\"op\":\"seed\",\"ids\":[\"erin-005\"]}", AGENT), 200);
            assertEquals(0, pendingActions(c), "a person's agent is never held");

            assertEquals(403, send(c, "POST", "/inv/investigations/inv-a/drafts/" + draft + "/promote", "{}", AGENT).statusCode(),
                    "promote stays a person's");
            assertEquals(403, send(c, "POST", "/inv/investigations/inv-a/ops", "{\"op\":\"expand\"}", AGENT).statusCode(),
                    "no direct Investigation edit");
        }
    }

    @Test
    void aMemberMachinesDraftIsHeldThenRunsAsTheMachineOnceApproved(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c, "inv-a", true);
            JsonNode held = data(send(c, "POST", "/inv/investigations/inv-a/drafts", "{}", BOT), 202);
            assertFalse(held.get("ran").asBoolean(), held.toString());
            String pa = held.at("/pendingAction/id").asText();
            assertEquals(0, data(send(c, "GET", "/inv/investigations/inv-a/drafts", null, LEAD), 200).get("items").size(),
                    "nothing opened while it waits");
            assertEquals(403, send(c, "POST", "/pending-actions/" + pa + "/approve", "{}", BOT).statusCode(),
                    "a machine agent never approves");

            JsonNode approved = data(send(c, "POST", "/pending-actions/" + pa + "/approve", "{}", CHECKER), 200);
            assertTrue(approved.get("ran").asBoolean(), approved.toString());
            JsonNode drafts = data(send(c, "GET", "/inv/investigations/inv-a/drafts", null, LEAD), 200).get("items");
            assertEquals(1, drafts.size(), drafts.toString());
            assertEquals("client:la-bot", drafts.get(0).get("actor").asText(), "the replay runs as the machine");
        }
    }

    @Test
    void aMachineThatIsNoMemberGetsNothingFromAnApproval(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c, "inv-b", false);
            String pa = data(send(c, "POST", "/inv/investigations/inv-b/drafts", "{}", BOT), 202).at("/pendingAction/id").asText();
            JsonNode approved = data(send(c, "POST", "/pending-actions/" + pa + "/approve", "{}", CHECKER), 200);
            assertFalse(approved.get("ran").asBoolean(), approved.toString());
            assertEquals(404, approved.at("/result/status").asInt(), "membership is checked again as the machine");
            assertEquals(0, data(send(c, "GET", "/inv/investigations/inv-b/drafts", null, LEAD), 200).get("items").size());
        }
    }

    @Test
    void aMachineNeverAttachesStartsAGraphRunOrPromotesAndLeavesNoRecord(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "none")) {
            investigation(c, "inv-a", true);
            assertEquals(403, send(c, "PUT", "/inv/investigations/inv-a/case", "{\"caseRef\":\"CASE-1\"}", BOT).statusCode(),
                    "attach needs canManageIncidents, which a machine agent never keeps");
            assertEquals(403, send(c, "POST", "/inv/graph/runs", "{}", BOT).statusCode(), "nor canRunLinkGraphAnalysis");
            assertEquals(403, send(c, "POST", "/inv/investigations/inv-a/drafts/d-1/promote", "{}", BOT).statusCode(),
                    "promote is refused to every agent");
            assertEquals(0, pendingActions(c), "refused before the hold: no record");
        }
    }
}
