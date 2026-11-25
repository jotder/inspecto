package com.gamma.mcp;

import com.gamma.access.CapabilityManifest;
import com.gamma.control.AgentGateProbe;
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
            calls.addAll(proposeCalls());
        }
        return calls;
    }

    /** {@code propose_change} for every kind × the operations that kind offers (W2 step 2: per kind). */
    private static List<McpTools.Call> proposeCalls() {
        McpTools.Tool t = McpTools.tool("propose_change");
        List<McpTools.Call> calls = new ArrayList<>();
        for (String kind : McpTools.CHANGE_ROUTES.keySet())
            for (String op : McpTools.operations(kind)) {
                Map<String, Object> a = new LinkedHashMap<>(Map.of("kind", kind, "operation", op, "id", "x1"));
                if ("restore".equals(op)) a.put("version", 2L);
                else if (!"delete".equals(op)) a.put("content", Map.of());
                calls.add(t.router().route(t.validate(a)));
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

    /**
     * AK-2 widening plan W2: every route {@code propose_change} can replay is on {@code PendingChanges.REPLAYABLE}, so an
     * agent's proposal is always held, never written. A kind whose route cannot be held is not on the allowlist.
     */
    @Test
    void everyProposeChangeRouteReachesTheHold() {
        List<McpTools.Call> calls = proposeCalls();
        for (McpTools.Call c : calls)
            assertTrue(AgentGateProbe.reachesTheHold(c.method(), c.path()),
                    c.method() + " " + c.path() + " is not on PendingChanges.REPLAYABLE");
        assertEquals(8 * 3 + 2, calls.size(), "every kind × the operations it offers was checked");
    }

    /** W2 step 2: a kind refuses an operation it does not offer, and only a restore takes a version, before any replay. */
    @Test
    void aKindRefusesAnOperationItDoesNotOffer() {
        McpTools.Tool t = McpTools.tool("propose_change");
        assertEquals(List.of("create"), McpTools.operations("requirement-kpi"));
        assertEquals(List.of("restore"), McpTools.operations("pipeline-restore"));
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("kind", "requirement-kpi", "operation", "update", "id", "r1", "content", Map.of()),
                Map.of("kind", "requirement-kpi", "operation", "create", "content", Map.of()),            // no Requirement
                Map.of("kind", "pipeline-restore", "operation", "create", "id", "p1", "content", Map.of()),
                Map.of("kind", "pipeline-restore", "operation", "restore", "id", "p1"),                   // no version
                Map.of("kind", "pipeline-restore", "operation", "restore", "id", "p1", "version", 2L, "content", Map.of()),
                Map.of("kind", "widget", "operation", "restore", "id", "w1", "version", 2L),
                Map.of("kind", "widget", "operation", "create", "content", Map.of(), "version", 2L)))
            assertThrows(McpTools.BadArgs.class, () -> t.router().route(t.validate(bad)), bad.toString());
        McpTools.Call restore = t.router().route(t.validate(
                Map.of("kind", "pipeline-restore", "operation", "restore", "id", "orders", "version", 3L)));
        assertEquals("POST /pipelines/orders/history/3/restore", restore.method() + " " + restore.path());
    }

    @Test
    void noToolRouterCanProduceARefusedPath() {
        for (McpTools.Call c : everyCall()) assertFalse(McpTools.refused(c), c.method() + " " + c.path());
    }

    /**
     * AK-2 widening plan W10: no tool ships whose route the host's agent gate refuses to a person's agent. Each call is
     * bucketed under every manifest pattern it matches (an ungated GET has none and is its own pattern), through
     * {@link AgentGateProbe} because {@code AgentGate} is package-private in {@code com.gamma.control}.
     */
    @Test
    void noToolRouteIsRefusedToAPersonsAgent() {
        for (McpTools.Call c : everyCall()) {
            String route = c.path().contains("?") ? c.path().substring(0, c.path().indexOf('?')) : c.path();
            List<String> patterns = new ArrayList<>();
            for (CapabilityManifest.Entry e : CapabilityManifest.ENTRIES)
                if (e.method().equals(c.method()) && Pattern.matches(e.pattern(), route)) patterns.add(e.pattern());
            for (CapabilityManifest.Exemption e : CapabilityManifest.EXEMPTIONS)
                if (e.method().equals(c.method()) && Pattern.matches(e.pattern(), route)) patterns.add(e.pattern());
            if (patterns.isEmpty()) patterns.add(route);
            for (String p : patterns)
                assertNull(AgentGateProbe.refusalForPersonsAgent(c.method(), p), c.method() + " " + route + " as " + p);
        }
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

    /**
     * W3 (widening plan §3): each Space health, storage and score tool replays the one GET route the plan names, and that
     * route's manifest gate is the capability the plan states — so the route's own 403 is what a caller without it meets.
     * The never-list's Screening Hit reveal has no tool.
     */
    @Test
    void everySpaceHealthAndScoreToolReplaysItsGatedGetRoute() {
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("get_space_integrity", new String[] {"/integrity", "canAuthorWorkbench"});
        expected.put("get_space_origin", new String[] {"/origin", "canAuthorWorkbench"});
        expected.put("preview_template_upgrade", new String[] {"/origin/upgrade", "canAuthorWorkbench"});
        expected.put("get_dataset_storage", new String[] {"/datasets/x1/storage", "canUploadData"});
        expected.put("verify_audit_chain", new String[] {"/audit/verify", "canAdminister"});
        expected.put("get_risk_score", new String[] {"/risk-scores/x1/x1", "canWorkIncidents"});
        expected.put("get_anomaly_score", new String[] {"/anomaly-scores/x1/x1", "canWorkIncidents"});
        expected.put("get_prediction", new String[] {"/predictions/churn-model/x1/x1", "canWorkIncidents"});
        assertEquals(List.copyOf(expected.keySet()), McpTools.SPACE_HEALTH_TOOLS.stream().map(McpTools.Tool::name).toList());
        for (McpTools.Tool t : McpTools.SPACE_HEALTH_TOOLS) {
            assertFalse(t.write(), t.name() + " only reads");
            McpTools.Call c = t.router().route(t.validate(sample(t)));
            String[] want = expected.get(t.name());
            assertEquals("GET", c.method(), t.name());
            assertEquals(want[0], c.path(), t.name());
            assertTrue(CapabilityManifest.ENTRIES.stream().anyMatch(e -> e.method().equals("GET")
                    && Pattern.matches(e.pattern(), c.path()) && want[1].equals(e.capability())),
                    t.name() + " replays a route gated " + want[1] + ": " + c.path());
        }
        McpTools.Tool risk = McpTools.tool("get_risk_score");
        assertEquals("/risk-scores/m1/masked:ab12?investigation=inv-1", risk.router().route(risk.validate(new LinkedHashMap<>(
                Map.of("model", "m1", "key", "masked:ab12", "investigation", "inv-1")))).path(),
                "a masked alias and its Investigation travel as the route takes them");
        for (String kind : McpTools.PREDICTION_KINDS) {
            McpTools.Tool p = McpTools.tool("get_prediction");
            String path = p.router().route(p.validate(new LinkedHashMap<>(Map.of("kind", kind, "model", "m1", "key", "k1")))).path();
            assertTrue(CapabilityManifest.ENTRIES.stream().anyMatch(e -> e.method().equals("GET")
                    && Pattern.matches(e.pattern(), path)), "every prediction kind reaches the route: " + path);
        }
        for (McpTools.Call c : everyCall())
            assertFalse(c.path().matches("/screening/hits/[^/]+/reveal"), "no tool reveals a Screening Hit: " + c.path());
    }

    @Test
    void theEndpointIsReplayGated() {
        assertEquals("replay-gated", CapabilityManifest.exemptionFor("POST", "/mcp").category());
    }
}
