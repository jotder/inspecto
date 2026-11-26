package com.gamma.control;

import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentGate#DATA_ROUTES} against the routes an agent may call (AGENT-KIT-1 W6 S0, design §6 "seam parity"): every
 * data route is agent-allowed and weighted; every other agent-allowed write route is named here as answering no Dataset
 * value, so a new allowed route is classified before it ships; every data route has the real-HTTP test that proves an
 * agent's answer is masked and that an unstamped one is withheld; and the weight and budget key apply to agents only.
 * ⚠ GET routes are all allowed to agents and are not on the list (design §3.1 scope: the POST data reads).
 */
class AgentDataRoutesParityTest {

    /** The agent-allowed non-GET routes that answer no value read from a Dataset or Reference, and why. */
    private static final Map<String, String> NO_DATASET_VALUES = new LinkedHashMap<>();
    static {
        NO_DATASET_VALUES.put("POST /mcp", "the endpoint itself; each tool's replay passes the gate as its own route");
        NO_DATASET_VALUES.put("POST /validate", "config findings");
        NO_DATASET_VALUES.put("POST /pipelines/([^/]+)/graph/validate", "config findings");
        NO_DATASET_VALUES.put("POST /pipelines/authored/([^/]+)/dry-run", "a draft's plan, no rows");
        NO_DATASET_VALUES.put("POST /bundle/export", "config files");
        NO_DATASET_VALUES.put("POST /promotion/preview", "a config diff");
        NO_DATASET_VALUES.put("POST /pending-changes/([^/]+)/withdraw", "its own proposal");
        NO_DATASET_VALUES.put("POST /pending-actions/([^/]+)/withdraw", "its own proposal");
        NO_DATASET_VALUES.put("POST /action-requests", "a request held for approval");
        NO_DATASET_VALUES.put("POST /pipelines/authored/([^/]+)/run", "a run id");
        NO_DATASET_VALUES.put("POST /tests/run", "verdicts: counts, a checksum, a min or max (W6 group C)");
        for (String p : AgentGate.AUTHORING_PREVIEWS) NO_DATASET_VALUES.put(p, "rows of the caller's own sample text (W6 group A)");
        for (String p : AgentGate.LA_READS_WITHOUT_VALUES) NO_DATASET_VALUES.put(p, "names, counts or verdicts");
    }

    /** Each data route → the test file (from the repo root) that proves it over real HTTP, and the text it probes it by. */
    private static final Map<String, String> PROOF_FILE = Map.of(
            "POST /db/query", "features/inspecto-mcp/src/test/java/com/gamma/mcp/ControlApiAgentDataSeamTest.java",
            "POST /inv/", "la/inspecto-geo-link/src/test/java/com/gamma/control/ControlApiAgentLinkAnalysisSeamTest.java");

    private static Path repo() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("la"))) p = p.getParent();
        assertNotNull(p, "repo root not found from " + Path.of("").toAbsolutePath());
        return p;
    }

    private static String methodOf(String key) {
        return key.substring(0, key.indexOf(' '));
    }

    private static String patternOf(String key) {
        return key.substring(key.indexOf(' ') + 1);
    }

    @Test
    void everyDataRouteIsAgentAllowedAndWeighted() {
        assertFalse(AgentGate.DATA_ROUTES.isEmpty());
        for (Map.Entry<String, Integer> e : AgentGate.DATA_ROUTES.entrySet()) {
            assertEquals(AgentGate.Bucket.ALLOWED, AgentGate.bucket(methodOf(e.getKey()), patternOf(e.getKey())), e.getKey());
            assertTrue(e.getValue() >= 1, e.getKey() + " weighs at least 1");
        }
        assertTrue(AgentGate.LA_READS.containsAll(AgentGate.LA_READS_WITHOUT_VALUES));
    }

    @Test
    void everyAllowedWriteRouteIsADataRouteOrNamedAsAnsweringNoDatasetValue() {
        Set<String> allowed = new TreeSet<>(AgentGate.ALLOWED);
        Set<String> placed = new TreeSet<>(AgentGate.DATA_ROUTES.keySet());
        placed.addAll(NO_DATASET_VALUES.keySet());
        assertEquals(allowed, placed, "classify a new agent-allowed route: a data route (stamped and weighted) or no Dataset value");
        for (String k : AgentGate.DATA_ROUTES.keySet())
            assertFalse(NO_DATASET_VALUES.containsKey(k), k + " is on both lists");
    }

    @Test
    void everyDataRouteHasItsRealHttpProof() throws Exception {
        Path repo = repo();
        for (String route : AgentGate.DATA_ROUTES.keySet()) {
            String file = route.startsWith("POST /inv/") ? PROOF_FILE.get("POST /inv/") : PROOF_FILE.get(route);
            assertNotNull(file, route + " has no proving test - add one (masked column, unstamped 500) and list it here");
            String src = Files.readString(repo.resolve(file));
            String probe = route.startsWith("POST /inv/") ? "\"" + route + "\"" : patternOf(route);
            assertTrue(src.contains(probe), file + " does not probe " + route);
            assertTrue(src.contains("ignoreStampsForTest(true)") && src.contains("MASKING_NOT_PROVEN"),
                    file + " proves the unstamped answer is withheld");
        }
    }

    @Test
    void theWeightAndTheBudgetKeyApplyToAgentsOnly() {
        Subject person = new Subject("analyst-1", Set.of());
        Subject agent = new Subject("analyst-1", Set.of(), null, Map.of(), null, null, null, "claude-code");
        Subject other = new Subject("analyst-1", Set.of(), null, Map.of(), null, null, null, "cursor");
        Subject machine = new Subject("client:bot", Set.of(), null, Map.of(), null, null, "svc", "bot");
        assertEquals(0, AgentGate.agentDataWeight(person, "POST", "/db/query"), "a person spends no agent budget");
        assertEquals(0, AgentGate.agentDataWeight(null, "POST", "/db/query"));
        assertEquals(1, AgentGate.agentDataWeight(agent, "POST", "/db/query"));
        assertEquals(0, AgentGate.agentDataWeight(agent, "POST", "/validate"), "no data route, no weight");
        assertNull(AgentGate.agentBudgetKey(person));
        assertEquals("agent:claude-code:analyst-1", AgentGate.agentBudgetKey(agent));
        assertNotEquals(AgentGate.agentBudgetKey(agent), AgentGate.agentBudgetKey(other), "two clients of one person never share");
        assertNotEquals("analyst-1", AgentGate.agentBudgetKey(agent), "never the person's own key");
        assertEquals("agent:bot:client:bot", AgentGate.agentBudgetKey(machine));
    }
}
