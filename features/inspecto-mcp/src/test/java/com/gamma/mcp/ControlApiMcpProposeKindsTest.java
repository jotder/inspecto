package com.gamma.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AK-2 widening plan W2 over real HTTP ({@code 2026-11-25-agent-kit-ak2-widening-plan.md} §3 W2, D-AK9): every kind that
 * joined {@code propose_change} is held. Per kind an agent's proposal answers a Pending Change and writes nothing, another
 * person's approval applies it, the same token's direct REST call is held the same way, and a machine agent's proposal is
 * held too; a kind outside the allowlist is refused by the tool before any replay.
 */
class ControlApiMcpProposeKindsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = dev-1 (pipeline-developer) through agent client claude-code; BOT = the machine agent ops-bot; CHECKER approves. */
    private static final String AGENT = "Bearer agent", BOT = "Bearer bot", CHECKER = "Bearer checker";
    /** LEAD_AGENT = lead-1, who holds the admin role (so canManageIncidents and canManageExclusionCategories), through claude-code. */
    private static final String LEAD_AGENT = "Bearer lead-agent";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }

        ComponentStore registry() {
            return new ComponentStore(root.resolve("registry"));
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            switch (h) {
                case AGENT -> {
                    Roles.Def def = Roles.effective(ex).get("pipeline-developer");
                    ComponentAccess.heldRoles(ex, Set.of("pipeline-developer"));
                    return Optional.of(new Subject("dev-1", def.capabilities(), def.dataScopes(), Map.of(), null, null, null,
                            "claude-code"));
                }
                case BOT -> {
                    // what OidcAuthenticator admits for a machine agent: its roles' grants, filtered to the machine agent set
                    Roles.Def admin = Roles.effective(ex).get("admin");
                    ComponentAccess.heldRoles(ex, Set.of("admin"));
                    Set<String> keeps = new LinkedHashSet<>(Roles.MACHINE_CAPABILITIES);
                    keeps.addAll(Roles.AGENT_MACHINE_CAPABILITIES);
                    Set<String> caps = new LinkedHashSet<>(admin.capabilities());
                    caps.retainAll(keeps);
                    return Optional.of(new Subject("client:ops-bot", caps, null, Map.of(), null, null, "svc-ops", "ops-bot"));
                }
                case LEAD_AGENT -> {
                    // what OidcAuthenticator leaves an agent of a person: the person's grants minus the never-list
                    Roles.Def def = Roles.effective(ex).get("admin");
                    ComponentAccess.heldRoles(ex, Set.of("admin"));
                    Set<String> caps = new LinkedHashSet<>(def.capabilities());
                    caps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);
                    return Optional.of(new Subject("lead-1", caps, def.dataScopes(), Map.of(), null, null, null, "claude-code"));
                }
                case CHECKER -> {
                    Roles.Def def = Roles.effective(ex).get("admin");
                    ComponentAccess.heldRoles(ex, Set.of("admin"));
                    return Optional.of(new Subject("checker-1", def.capabilities(), def.dataScopes()));
                }
                default -> {
                    return Optional.empty();
                }
            }
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir) throws Exception {
        return open(dir, false);
    }

    /** {@code pipelineInRoot}: the Pipeline lives in the write root (inactive), so its saves keep versions to restore. */
    private Ctx open(Path dir, boolean pipelineInRoot) throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path pipe = pipelineInRoot ? PipelineConfigBatchTest.writePipeline(root, "", false)
                : PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("cfg")), "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            Files.writeString(root.resolve("approvers.toon"), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("checker-1", "dev-1"))));
            Roles.write(root, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> rpc(int port, String auth, Map<String, Object> params) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>(params);
        p.put("_meta", Map.of(McpRoutes.META_VERSION, McpRoutes.MODERN,
                McpRoutes.META_CLIENT, Map.of("name", "TestAgent", "version", "1.0")));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/mcp"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .header("MCP-Protocol-Version", McpRoutes.MODERN).header("Mcp-Method", "tools/call")
                .header("Mcp-Name", String.valueOf(p.get("name")))
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(
                        Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", p))));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** {@code propose_change} → the JSON-RPC {@code result}. */
    private JsonNode propose(int port, String auth, Map<String, Object> args) throws Exception {
        HttpResponse<String> res = rpc(port, auth, Map.of("name", "propose_change", "arguments", args));
        assertEquals(200, res.statusCode(), res.body());
        JsonNode result = JSON.readTree(res.body()).get("result");
        assertNotNull(result, res.body());
        return result;
    }

    private HttpResponse<String> rest(Ctx c, String method, String path, Object body, String auth) throws Exception {
        String json = body == null ? "" : JSON.writeValueAsString(body);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .method(method, BodyPublishers.ofString(json)).build(), BodyHandlers.ofString());
    }

    /** The Pending Change id of a held tool result; fails when it was not held. */
    private static String heldId(JsonNode r) {
        assertFalse(r.get("isError").asBoolean(), r.toString());
        JsonNode held = r.at("/structuredContent/data");
        assertEquals("pending", held.get("status").asText(), r.toString());
        return held.at("/pendingChange/id").asText();
    }

    private void approve(Ctx c, String changeId) throws Exception {
        HttpResponse<String> approved = rest(c, "POST", "/pending-changes/" + changeId + "/approve", Map.of(), CHECKER);
        assertEquals(200, approved.statusCode(), approved.body());
    }

    private static Map<String, Object> args(String kind, String op, String id, Map<String, Object> content) {
        Map<String, Object> a = new LinkedHashMap<>(Map.of("kind", kind, "operation", op, "reason", "W2 " + kind + " " + op));
        if (id != null) a.put("id", id);
        if (content != null) a.put("content", content);
        return a;
    }

    /**
     * One create-update-delete kind's whole round: the agent's create is held and nothing is live; the checker's approval
     * applies it; the agent's update and delete are held and leave it as approved; the same token's direct create is held
     * the same way; a machine agent's create is held too.
     */
    private void crudKindIsHeldEveryWay(Ctx c, String kind, String type, String restPath, String id,
                                        java.util.function.Function<String, Map<String, Object>> content) throws Exception {
        String pc = heldId(propose(c.port, AGENT, args(kind, "create", null, content.apply(id))));
        assertFalse(c.registry().exists(type, id), kind + ": nothing is live before approval");
        approve(c, pc);
        assertTrue(c.registry().exists(type, id), kind + ": another person's approval applies it");

        String update = heldId(propose(c.port, AGENT, args(kind, "update", id, content.apply(id))));
        // one pending change per target: take the update back before the delete is proposed
        HttpResponse<String> withdrawn = rest(c, "POST", "/pending-changes/" + update + "/withdraw", Map.of(), AGENT);
        assertEquals(200, withdrawn.statusCode(), withdrawn.body());
        heldId(propose(c.port, AGENT, args(kind, "delete", id, null)));
        assertTrue(c.registry().exists(type, id), kind + ": a held delete removes nothing");

        HttpResponse<String> direct = rest(c, "POST", restPath, content.apply(id + "_direct"), AGENT);
        assertEquals(202, direct.statusCode(), kind + ": the same token's direct call is held: " + direct.body());
        assertFalse(c.registry().exists(type, id + "_direct"), kind + ": the direct call wrote nothing");

        JsonNode byBot = propose(c.port, BOT, args(kind, "create", null, content.apply(id + "_bot")));
        heldId(byBot);
        assertEquals("ops-bot", byBot.at("/structuredContent/data/pendingChange/agentClient").asText(), byBot.toString());
        assertFalse(c.registry().exists(type, id + "_bot"), kind + ": a machine agent's proposal wrote nothing");
    }

    // ── W2 step 1: Decision Rule and Notification Rule ─────────────────────────────────────────

    private static Map<String, Object> decisionRule(String name) {
        return Map.of("name", name, "targetType", "pipeline", "target", "orders",
                "consequences", List.of(Map.of("action", "emit-signal", "params", Map.of("type", "agent.proposed"))));
    }

    private static Map<String, Object> notificationRule(String id) {
        return Map.of("id", id, "eventType", "BATCH_FAILED", "category", "pipeline", "minLevel", "ERROR");
    }

    @Test
    void aDecisionRuleIsHeldEveryWay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            crudKindIsHeldEveryWay(c, "decision-rule", "decision-rule", "/decision-rules", "late_orders",
                    ControlApiMcpProposeKindsTest::decisionRule);
        }
    }

    @Test
    void aNotificationRuleIsHeldEveryWay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            crudKindIsHeldEveryWay(c, "notification-rule", "notification-rule", "/notifications/rules", "failed_batches",
                    ControlApiMcpProposeKindsTest::notificationRule);
        }
    }

    /** Operator 2026-11-25: a Notification Channel joins too — it carries no secret field to read back. */
    @Test
    void aNotificationChannelIsHeldEveryWay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            crudKindIsHeldEveryWay(c, "notification-channel", "channel", "/notifications/channels", "oncall_hook",
                    id -> Map.of("id", id, "kind", "WEBHOOK", "target", "https://hooks.example.test/oncall"));
        }
    }

    // ── W2 step 2: requirement-kpi (create only) and pipeline-restore (restore only) ───────────

    private static final Map<String, Object> FROM_REQ =
            Map.of("dataset", "orders", "measure", "sum(amount)", "timeField", "order_date", "grain", "month");

    /** A delivered kpi Requirement over a Dataset 'orders' (a person raises, accepts and delivers it). */
    private void deliveredRequirement(Ctx c, String id) throws Exception {
        for (String[] step : List.of(new String[] {"/requirements", "{\"id\":\"" + id + "\",\"title\":\"Refund exposure\","
                        + "\"kind\":\"kpi\",\"target\":35,\"comparator\":\"<=\",\"unit\":\"SAR\"}"},
                new String[] {"/requirements/" + id + "/decision", "{\"accept\":true}"},
                new String[] {"/requirements/" + id + "/deliver", "{}"})) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + step[0]))
                    .header("Content-Type", "application/json").header("Authorization", CHECKER)
                    .POST(BodyPublishers.ofString(step[1])).build(), BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), step[0] + ": " + r.body());
        }
    }

    private static void seedOrders(Ctx c) throws Exception {
        new com.gamma.pipeline.ViewStore(c.root.resolve("views")).write(new com.gamma.pipeline.ViewDefinition("orders_view",
                "flow-x", List.of(), "SELECT * FROM (VALUES (DATE '2026-07-02', 10.0), (DATE '2026-08-01', 20.0)) AS t(order_date, amount)",
                "2026-08-01T00:00:00Z"));
        c.registry().write("dataset", "orders", Map.of("view", "orders_view"));
    }

    @Test
    void aRequirementsKpiIsHeldEveryWay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seedOrders(c);
            for (String r : List.of("refunds", "refunds_direct", "refunds_bot")) deliveredRequirement(c, r);

            String pc = heldId(propose(c.port, AGENT, args("requirement-kpi", "create", "refunds", FROM_REQ)));
            assertFalse(c.registry().exists("kpi", "refunds"), "nothing is live before approval");
            approve(c, pc);
            assertTrue(c.registry().exists("kpi", "refunds"), "another person's approval creates the KPI");

            HttpResponse<String> direct = rest(c, "POST", "/requirements/refunds_direct/kpi", FROM_REQ, AGENT);
            assertEquals(202, direct.statusCode(), "the same token's direct call is held: " + direct.body());
            assertFalse(c.registry().exists("kpi", "refunds_direct"));

            heldId(propose(c.port, BOT, args("requirement-kpi", "create", "refunds_bot", FROM_REQ)));
            assertFalse(c.registry().exists("kpi", "refunds_bot"), "a machine agent's proposal wrote nothing");
        }
    }

    @Test
    void aPipelineRestoreIsHeldEveryWay(@TempDir Path dir) throws Exception {
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        try (Ctx c = open(dir, true)) {
            for (String d : List.of("first", "second")) {   // two saves by a person: kept versions 1 and 2
                HttpResponse<String> p = rest(c, "POST", "/config/patch",
                        Map.of("type", "pipeline", "name", "mini_etl", "patch", Map.of("description", d)), CHECKER);
                assertEquals(200, p.statusCode(), p.body());
            }
            Path pipe = c.root.resolve("mini_pipeline.toon");
            String before = Files.readString(pipe);
            String v1 = Files.readString(c.root.resolve(".history/pipelines/mini_etl/v1.toon"));
            assertNotEquals(v1, before, "precondition: the file is not version 1");

            Map<String, Object> restore = args("pipeline-restore", "restore", "mini_etl", null);
            restore.put("version", 1);
            String pc = heldId(propose(c.port, AGENT, restore));
            assertEquals(before, Files.readString(pipe), "nothing is restored before approval");
            approve(c, pc);
            assertEquals(v1, Files.readString(pipe), "another person's approval restores version 1");

            HttpResponse<String> direct = rest(c, "POST", "/pipelines/mini_etl/history/2/restore", Map.of(), AGENT);
            assertEquals(202, direct.statusCode(), "the same token's direct call is held: " + direct.body());
            assertEquals(v1, Files.readString(pipe), "the direct call restored nothing");
            String directId = JSON.readTree(direct.body()).at("/data/pendingChange/id").asText();
            assertEquals(200, rest(c, "POST", "/pending-changes/" + directId + "/withdraw", Map.of(), AGENT).statusCode());

            Map<String, Object> byBot = args("pipeline-restore", "restore", "mini_etl", null);
            byBot.put("version", 2);
            heldId(propose(c.port, BOT, byBot));
            assertEquals(v1, Files.readString(pipe), "a machine agent's proposal restored nothing");
        } finally {
            if (priorRoots == null) System.clearProperty("assist.safety.roots");
            else System.setProperty("assist.safety.roots", priorRoots);
        }
    }

    // ── W2 step 3: Entity List edits (members, retire, attributes) ─────────────────────────────

    private void list(Ctx c, String id, String purpose) throws Exception {
        HttpResponse<String> r = rest(c, "POST", "/entity-lists", Map.of("id", id, "title", "List " + id, "purpose", purpose,
                "entityType", "msisdn", "reason", "set up for W2"), CHECKER);
        assertEquals(201, r.statusCode(), r.body());
    }

    private JsonNode entityList(Ctx c, String id) throws Exception {
        HttpResponse<String> r = rest(c, "GET", "/entity-lists/" + id, null, CHECKER);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static Map<String, Object> listEdit(String op, String id, Map<String, Object> content) {
        return args("entity-list", op, id, content);
    }

    @Test
    void anEntityListEditIsHeldEveryWay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            list(c, "wl", "watch");
            list(c, "staff", "exclusion");
            list(c, "direct_wl", "watch");
            list(c, "bot_wl", "watch");
            list(c, "bot_staff", "exclusion");
            Map<String, Object> add = Map.of("add", List.of("+15550001"));

            // members: held (an agent's change never takes the person's 24 h fast path), then applied by another person
            String pc = heldId(propose(c.port, LEAD_AGENT, listEdit("members", "wl", add)));
            assertEquals(0, entityList(c, "wl").get("size").asInt(), "nothing is live before approval");
            approve(c, pc);
            assertEquals(1, entityList(c, "wl").get("size").asInt(), "another person's approval adds the member");

            // attributes (canManageExclusionCategories): held, then applied
            pc = heldId(propose(c.port, LEAD_AGENT, listEdit("attributes", "staff", Map.of("ownerKind", "admin"))));
            assertEquals("analyst", entityList(c, "staff").get("ownerKind").asText(), "nothing is live before approval");
            approve(c, pc);
            assertEquals("admin", entityList(c, "staff").get("ownerKind").asText(), "another person's approval applies it");

            // retire: held, the tool's reason carried into the body the route reads
            JsonNode retire = propose(c.port, LEAD_AGENT, listEdit("retire", "wl", Map.of()));
            pc = heldId(retire);
            assertFalse(entityList(c, "wl").get("retired").asBoolean(), "a held retire retires nothing");
            approve(c, pc);
            assertTrue(entityList(c, "wl").get("retired").asBoolean(), "another person's approval retires it");

            // the same token's direct call is held the same way
            HttpResponse<String> direct = rest(c, "POST", "/entity-lists/direct_wl/members",
                    Map.of("add", List.of("+15550002"), "reason", "sent directly"), LEAD_AGENT);
            assertEquals(202, direct.statusCode(), direct.body());
            assertEquals(0, entityList(c, "direct_wl").get("size").asInt(), "the direct call changed nothing");

            // a machine agent (canManageIncidents and canManageExclusionCategories since W2 step 3): held, every edit
            for (Map.Entry<String, Map<String, Object>> e : Map.of(
                    "members", listEdit("members", "bot_wl", add),
                    "attributes", listEdit("attributes", "bot_staff", Map.of("ownerKind", "admin"))).entrySet()) {
                JsonNode byBot = propose(c.port, BOT, e.getValue());
                heldId(byBot);
                assertEquals("ops-bot", byBot.at("/structuredContent/data/pendingChange/agentClient").asText(), e.getKey());
            }
            assertEquals(0, entityList(c, "bot_wl").get("size").asInt(), "a machine agent's proposal changed nothing");
            assertEquals("analyst", entityList(c, "bot_staff").get("ownerKind").asText());
        }
    }

    /** An Enrichment (not holdable) and a Connection are not on the allowlist: the tool refuses them before any replay. */
    @Test
    void aKindOutsideTheAllowlistIsRefusedBeforeAnyReplay(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String kind : List.of("enrichment", "connection", "case-rule")) {
                HttpResponse<String> res = rpc(c.port, AGENT, Map.of("name", "propose_change", "arguments",
                        args(kind, "create", null, Map.of("id", "ch1", "kind", "WEBHOOK", "target", "https://example.test/h"))));
                assertEquals(McpRoutes.INVALID_PARAMS, JSON.readTree(res.body()).at("/error/code").asInt(), kind + " → " + res.body());
            }
            HttpResponse<String> pending = rest(c, "GET", "/pending-changes", null, CHECKER);
            assertFalse(pending.body().contains("ch1"), "no Pending Change was recorded: " + pending.body());
        }
    }
}
