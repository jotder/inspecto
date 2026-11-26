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
 * Every GET route in the source is classified in {@code agent-get-routes.tsv} (AGENT-GET-DATA-READS-1): a masked value
 * read is on {@link AgentGate#VALUE_GETS}, an unmasked one on {@link AgentGate#RAW_GETS} (refused to agents).
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
            "POST /inv/", "la/inspecto-geo-link/src/test/java/com/gamma/control/ControlApiAgentLinkAnalysisSeamTest.java",
            // D1 (W6 group D): the analytic reads
            "POST /bi/query", "features/inspecto-mcp/src/test/java/com/gamma/mcp/ControlApiAgentAnalyticReadsTest.java",
            "POST /queries/([^/]+)/run", "features/inspecto-mcp/src/test/java/com/gamma/mcp/ControlApiAgentAnalyticReadsTest.java");

    private static final String MCP_PROOF = "features/inspecto-mcp/src/test/java/com/gamma/mcp/ControlApiAgentDataSeamTest.java";
    private static final String LA_PROOF = "la/inspecto-geo-link/src/test/java/com/gamma/control/ControlApiAgentLinkAnalysisSeamTest.java";

    /** A GET data route's prefix → the test file that probes it over real HTTP, by the text {@code "GET <pattern>"}. */
    private static final Map<String, String> GET_PROOF = new LinkedHashMap<>();
    static {
        GET_PROOF.put("GET /inv/", LA_PROOF);
        GET_PROOF.put("GET /public/investigations/", LA_PROOF);
        GET_PROOF.put("GET /datasets/", MCP_PROOF);
        GET_PROOF.put("GET /db/table", MCP_PROOF);
        GET_PROOF.put("GET /kpis/", MCP_PROOF);
        GET_PROOF.put("GET /risk-scores/", "features/inspecto-scoring/src/test/java/com/gamma/control/ControlApiRiskScoreTest.java");
        GET_PROOF.put("GET /risk-score-lists/", "features/inspecto-scoring/src/test/java/com/gamma/control/ControlApiRiskScoreListTest.java");
        GET_PROOF.put("GET /anomaly-scores/", "features/inspecto-anomaly/src/test/java/com/gamma/anomaly/ControlApiAnomalyScoreTest.java");
        GET_PROOF.put("GET /predictions/", "features/inspecto-predictive/src/test/java/com/gamma/predictive/ControlApiPredictionReadTest.java");
        GET_PROOF.put("GET /entity-lists/", "features/inspecto-entity-list/src/test/java/com/gamma/control/ControlApiEntityListTest.java");
        GET_PROOF.put("GET /screening/", "features/inspecto-screening/src/test/java/com/gamma/control/ControlApiScreeningTest.java");
        GET_PROOF.put("GET /regulatory-reports", "features/inspecto-regulatory-reporting/src/test/java/com/gamma/regreporting/ControlApiRegulatoryReportsTest.java");
    }

    /** {@code agent-get-routes.tsv}: {@code GET <pattern>} → its class ({@code data | raw | stored | none}). */
    private static Map<String, String> classifiedGets() throws Exception {
        Map<String, String> out = new java.util.TreeMap<>();
        try (var in = AgentDataRoutesParityTest.class.getResourceAsStream("/agent-get-routes.tsv")) {
            assertNotNull(in, "agent-get-routes.tsv on the test classpath");
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                assertEquals(3, f.length, "pattern, class, reason: " + line);
                assertTrue(Set.of("data", "raw", "stored", "none").contains(f[1]), line);
                assertFalse(f[2].isBlank(), "a reason for " + f[0]);
                assertNull(out.put(f[0], f[1]), "listed twice: " + f[0]);
            }
        }
        return out;
    }

    /**
     * Every {@code .get("/...")} registration in the main sources. Not scanned, and answering no value: ControlApi's bare
     * {@code get(...)} health, readiness and contract routes, and McpRoutes' OAuth metadata route (a constant).
     */
    private static Set<String> registeredGets() throws Exception {
        java.util.regex.Pattern reg = java.util.regex.Pattern.compile("\\.get\\(\"(/[^\"]*)\"");
        Set<String> out = new TreeSet<>();
        Path repo = repo();
        for (String top : java.util.List.of("inspecto", "features", "la", "platform", "providers"))
            try (var files = Files.walk(repo.resolve(top))) {
                for (Path f : files.filter(x -> x.toString().endsWith(".java")
                        && x.toString().replace('\\', '/').contains("/src/main/java/")).toList()) {
                    var m = reg.matcher(Files.readString(f));
                    while (m.find()) out.add("GET " + m.group(1));
                }
            }
        assertTrue(out.size() > 250, "GET routes not seen (" + out.size() + ") - re-anchor the scan");
        return out;
    }

    @Test
    void everyGetRouteIsClassifiedAndTheValueReadsAreOnTheSeamOrRefused() throws Exception {
        Map<String, String> classified = classifiedGets();
        assertEquals(registeredGets(), classified.keySet(),
                "classify every GET route in agent-get-routes.tsv: data (masked, on AgentGate.VALUE_GETS), raw (refused), "
                        + "stored or none");
        Set<String> data = new TreeSet<>(), raw = new TreeSet<>();
        classified.forEach((r, c) -> {
            if ("data".equals(c)) data.add(r);
            if ("raw".equals(c)) raw.add(r);
        });
        assertEquals(data, new TreeSet<>(AgentGate.VALUE_GETS), "the data GETs are exactly AgentGate.VALUE_GETS");
        assertEquals(raw, new TreeSet<>(AgentGate.RAW_GETS.keySet()), "the raw GETs are exactly AgentGate.RAW_GETS");
        for (String r : data) assertTrue(AgentGate.DATA_ROUTES.containsKey(r), r + " is a weighted data route");
        Subject agent = new Subject("analyst-1", Set.of(), null, Map.of(), null, null, null, "claude-code");
        Subject person = new Subject("analyst-1", Set.of());
        for (String r : raw) {
            assertNotNull(AgentGate.refusal(agent, "GET", "^" + patternOf(r) + "$"), r + " is refused to an agent");
            assertNull(AgentGate.refusal(person, "GET", "^" + patternOf(r) + "$"), r + " is unchanged for a person");
        }
        for (String r : data) assertNull(AgentGate.refusal(agent, "GET", "^" + patternOf(r) + "$"), r + " stays open to agents");
    }

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
        Set<String> placed = new TreeSet<>();   // the GET data routes are classified by agent-get-routes.tsv below
        for (String k : AgentGate.DATA_ROUTES.keySet()) if (!k.startsWith("GET ")) placed.add(k);
        placed.addAll(NO_DATASET_VALUES.keySet());
        assertEquals(allowed, placed, "classify a new agent-allowed route: a data route (stamped and weighted) or no Dataset value");
        for (String k : AgentGate.DATA_ROUTES.keySet())
            assertFalse(NO_DATASET_VALUES.containsKey(k), k + " is on both lists");
    }

    @Test
    void everyDataRouteHasItsRealHttpProof() throws Exception {
        Path repo = repo();
        for (String route : AgentGate.DATA_ROUTES.keySet()) {
            String file = route.startsWith("GET ")
                    ? GET_PROOF.entrySet().stream().filter(e -> route.startsWith(e.getKey())).map(Map.Entry::getValue)
                            .findFirst().orElse(null)
                    : route.startsWith("POST /inv/") ? PROOF_FILE.get("POST /inv/") : PROOF_FILE.get(route);
            assertNotNull(file, route + " has no proving test - add one (masked column, unstamped 500) and list it here");
            assertTrue(Files.isRegularFile(repo.resolve(file)), file + ", the proof of " + route + ", exists");
            String src = Files.readString(repo.resolve(file));
            String probe = route.startsWith("GET ") || route.startsWith("POST /inv/") ? "\"" + route + "\"" : patternOf(route);
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
