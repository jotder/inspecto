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
        assertEquals(8, McpTools.RUN_ACTIONS.size(), "the eight run actions of plan §4.6");
    }

    @Test
    void theEndpointIsReplayGated() {
        assertEquals("replay-gated", CapabilityManifest.exemptionFor("POST", "/mcp").category());
    }
}
