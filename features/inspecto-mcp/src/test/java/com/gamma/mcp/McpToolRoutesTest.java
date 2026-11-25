package com.gamma.mcp;

import com.gamma.access.CapabilityManifest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every MCP Tool's replayed route against the capability manifest (AGENT-KIT-1 AK-2b, plan §4.4 and §4.7): every
 * non-GET route a tool can produce is in {@link CapabilityManifest#ENTRIES} or {@link CapabilityManifest#EXEMPTIONS};
 * no tool router can produce a path on the endpoint's refusal list (the never-list), for any allowlisted kind and
 * operation; the refusal list does catch the never-list's shapes; {@code POST /mcp} is {@code replay-gated}.
 */
class McpToolRoutesTest {

    /** Sample arguments for a tool: every required argument, typed. */
    private static Map<String, Object> sample(McpTools.Tool t) {
        Map<String, Object> a = new LinkedHashMap<>();
        for (McpTools.Arg arg : t.args()) {
            if (!arg.required()) continue;
            a.put(arg.name(), switch (arg.kind()) {
                case ID -> "x1";
                case TEXT, SQL -> "select 1";
                case INT -> 5L;
                case ENUM -> arg.values().get(0);
                case OBJECT -> Map.of();
            });
        }
        return a;
    }

    /** Every call the tool table can produce: one per tool, and propose_change for every kind × operation. */
    private static List<McpTools.Call> everyCall() {
        List<McpTools.Call> calls = new ArrayList<>();
        for (McpTools.Tool t : McpTools.ALL_TOOLS) {
            if (t.name().equals("operate_run")) {   // AK-2d: every run action, not only the first enum value
                for (String action : McpTools.RUN_ACTIONS.keySet())
                    calls.add(t.router().route(t.validate(new LinkedHashMap<>(Map.of("run", "orders", "action", action)))));
                continue;
            }
            if (t.name().equals("run_link_query") || t.name().equals("work_draft")) {   // AK-2f: every kind and action
                java.util.Collection<String> values = t.name().equals("run_link_query") ? McpTools.LINK_QUERIES.keySet()
                        : McpTools.DRAFT_ACTIONS;
                for (String v : values) {
                    Map<String, Object> a = sample(t);
                    a.put(t.name().equals("run_link_query") ? "kind" : "action", v);
                    calls.add(t.router().route(t.validate(a)));
                }
                continue;
            }
            if (t.name().equals("work_incident_or_case") || t.name().equals("work_alert")) {   // AK-2e: every move
                for (String action : t.name().equals("work_alert") ? McpTools.ALERT_MOVES : McpTools.CASE_MOVES) {
                    Map<String, Object> a = sample(t);
                    a.put("action", action);
                    calls.add(t.router().route(t.validate(a)));
                }
                continue;
            }
            if (!t.name().equals("propose_change")) {
                calls.add(t.router().route(t.validate(sample(t))));
                continue;
            }
            for (String kind : McpTools.CHANGE_ROUTES.keySet())
                for (String op : McpTools.OPERATIONS) {
                    Map<String, Object> a = new LinkedHashMap<>(Map.of("kind", kind, "operation", op, "id", "x1"));
                    if (!"delete".equals(op)) a.put("content", Map.of());
                    calls.add(t.router().route(t.validate(a)));
                }
        }
        return calls;
    }

    private static boolean inManifest(String method, String path) {
        String route = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        for (CapabilityManifest.Entry e : CapabilityManifest.ENTRIES)
            if (e.method().equals(method) && Pattern.matches(e.pattern(), route)) return true;
        for (CapabilityManifest.Exemption e : CapabilityManifest.EXEMPTIONS)
            if (e.method().equals(method) && Pattern.matches(e.pattern(), route)) return true;
        return false;
    }

    @Test
    void everyNonGetRouteAToolReplaysIsInTheManifest() {
        List<McpTools.Call> calls = everyCall();
        assertTrue(calls.size() > McpTools.ALL_TOOLS.size(), "propose_change expands to every kind × operation");
        for (McpTools.Call c : calls)
            if (!"GET".equals(c.method())) assertTrue(inManifest(c.method(), c.path()), "not in the manifest: " + c.method() + " " + c.path());
    }

    @Test
    void noToolRouterCanProduceARefusedPath() {
        for (McpTools.Call c : everyCall()) assertFalse(McpTools.refused(c), c.method() + " " + c.path());
    }

    @Test
    void theRefusalListCatchesTheNeverListShapes() {
        for (String line : List.of("POST /pending-changes/pc-1/approve", "POST /pending-changes/pc-1/decline",
                "POST /action-requests/a1/approve", "POST /action-requests/a1/retry", "POST /action-requests/a1/mark-failed",
                "POST /promotion/apply", "PUT /access/roles", "DELETE /access/profiles/p1", "POST /apps", "PUT /apps/a1",
                "POST /agent/approvals/x/decision", "POST /inv/investigations/i1/reveal", "GET /screening/hits/h1/reveal",
                "POST /inv/investigations/i1/pending/p1/approve")) {
            int sp = line.indexOf(' ');
            assertTrue(McpTools.refused(new McpTools.Call(line.substring(0, sp), line.substring(sp + 1), null)), line);
        }
        assertFalse(McpTools.refused(new McpTools.Call("GET", "/apps", null)), "reading Apps is a read");
    }

    @Test
    void everyOperationsToolActionReplaysAnOperationalAction() {
        for (McpTools.Call c : everyCall()) {
            boolean action = c.path().startsWith("/runs/orders/") || c.path().matches("/jobs/[^/]+/trigger")
                    || c.path().matches("/jobs/runs/[^/]+/replay");
            if (!"POST".equals(c.method()) || !action) continue;
            String route = c.path();
            assertTrue(CapabilityManifest.ENTRIES.stream().anyMatch(e -> e.method().equals("POST")
                    && Pattern.matches(e.pattern(), route) && "canOperateRuns".equals(e.capability())),
                    "an operations tool replays a canOperateRuns route: " + route);
        }
        assertEquals(9, McpTools.RUN_ACTIONS.size(), "the eight run actions of plan §4.6 and the quarantine release (W1)");
    }

    /** The host's Link Analysis writes ({@code AgentGate.LA_ACTIONS}) and queries ({@code AgentGate.LA_READS}). */
    private static final List<String> LA_ACTION_ROUTES = List.of(
            "POST /inv/investigations/[^/]+/drafts", "POST /inv/investigations/[^/]+/drafts/[^/]+/ops",
            "POST /inv/investigations/[^/]+/drafts/[^/]+/undo", "POST /inv/investigations/[^/]+/drafts/[^/]+/discard",
            "POST /inv/investigations/[^/]+/drafts/[^/]+/rebase", "PUT /inv/investigations/[^/]+/case",
            "POST /inv/graph/runs", "POST /inv/graph/runs/[^/]+/cancel");

    @Test
    void everyLinkAnalysisWriteToolReplaysAnActionAndEveryActionHasATool() {
        java.util.Set<String> reached = new java.util.TreeSet<>();
        for (McpTools.Call c : everyCall())
            for (String route : LA_ACTION_ROUTES) {
                int sp = route.indexOf(' ');
                if (c.method().equals(route.substring(0, sp)) && c.path().matches(route.substring(sp + 1))) reached.add(route);
            }
        assertEquals(new java.util.TreeSet<>(LA_ACTION_ROUTES), reached, "AK-2f: a tool for every Link Analysis action");
        for (McpTools.Call c : everyCall())
            if (c.path().startsWith("/inv/") && !"GET".equals(c.method())) {
                boolean action = LA_ACTION_ROUTES.stream().anyMatch(r -> c.path().matches(r.substring(r.indexOf(' ') + 1)));
                boolean query = McpTools.LINK_QUERIES.containsValue(c.path()) || c.path().matches("/inv/presets/[^/]+/run");
                assertTrue(action || query, "an /inv write a tool makes is a named action or query: " + c.method() + " " + c.path());
            }
        for (String line : List.of("POST /inv/investigations/i1/drafts/d1/promote", "POST /inv/investigations/i1/pending/p1/deny",
                "POST /inv/investigations/i1/reveal")) {
            int sp = line.indexOf(' ');
            assertTrue(McpTools.refused(new McpTools.Call(line.substring(0, sp), line.substring(sp + 1), null)), line);
        }
    }

    /** The host's case action routes ({@code AgentGate.CASE_ACTIONS}, pinned there by {@code AgentGateParityTest}). */
    private static final List<String> CASE_ACTION_ROUTES = List.of(
            "/objects/[^/]+/comments", "/incidents/[^/]+/briefs", "/objects/[^/]+/ack", "/objects/[^/]+/assign",
            "/objects/[^/]+/resolve", "/objects/[^/]+/transition", "/alerts/[^/]+/ack", "/alerts/[^/]+/resolve",
            "/cases/from-entities");

    @Test
    void everyCaseToolReplaysACaseActionAndEveryCaseActionHasATool() {
        java.util.Set<String> reached = new java.util.TreeSet<>();
        for (McpTools.Tool t : McpTools.CASE_TOOLS) assertTrue(t.write(), t.name() + " acts, so it is a write tool");
        for (McpTools.Call c : everyCall())
            if ("POST".equals(c.method()))
                for (String route : CASE_ACTION_ROUTES) if (c.path().matches(route)) reached.add(route);
        assertEquals(new java.util.TreeSet<>(CASE_ACTION_ROUTES), reached, "AK-2e: a tool for every case action of plan §4.6");
        assertEquals(List.of("add_comment", "build_incident_brief", "work_incident_or_case", "work_alert",
                "open_case_from_entities"), McpTools.CASE_TOOLS.stream().map(McpTools.Tool::name).toList());
    }

    @Test
    void theEndpointIsReplayGated() {
        assertEquals("replay-gated", CapabilityManifest.exemptionFor("POST", "/mcp").category());
    }
}
