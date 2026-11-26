package com.gamma.control;

import com.gamma.access.Roles;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.AnswerMasking;
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
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static com.gamma.control.ControlApiDatasetDiscoverTest.A;
import static com.gamma.control.ControlApiDatasetDiscoverTest.RAW;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-KIT-1 W6 S0 (D-AK13) over real HTTP, the Link Analysis reads on {@code AgentGate.DATA_ROUTES}: an agent's call to
 * each one answers 200 with no raw classified id, because its masking step stamped the answer; with every stamp made a
 * no-op ({@code AnswerMasking.ignoreStampsForTest}) the same call answers 500 {@code masking not proven} and carries no
 * value; a person's call is unchanged either way. The probes below cover every Link Analysis data route
 * ({@link #everyLinkAnalysisDataRouteIsProbed}).
 */
class ControlApiAgentLinkAnalysisSeamTest {

    private static final String PERSON = "Bearer person", AGENT = "Bearer agent";
    private static final String COLS = "\"dataset\":\"pays_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"";
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void arm() {
        CaseTeamObjectEngine.arm();   // case management for the case-history probe; its titles name the raw id
        CaseTeamObjectEngine.HISTORY = java.util.List.of(Map.of("kind", "case", "id", "CASE-1", "title", "ring around +" + A,
                "status", "OPEN", "createdAt", 2L, "matchedOn", "member", "via", "INC-1"));
        Set<String> person = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canWorkIncidents",
                "canDraftInvestigations");
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            Set<String> agentCaps = new HashSet<>(person);
            agentCaps.removeAll(Roles.AGENT_NEVER_CAPABILITIES);
            return switch (h) {
                case PERSON -> Optional.of(new Subject("analyst-1", person));
                case AGENT -> Optional.of(new Subject("analyst-1", agentCaps, null, Map.of(), null, null, null, "claude-code"));
                default -> Optional.empty();
            };
        });
    }

    @AfterEach
    void disarm() {
        AnswerMasking.ignoreStampsForTest(false);
        CaseTeamObjectEngine.CASES = null;
        CaseTeamObjectEngine.HISTORY = null;
        CaseTeamObjectEngine.ASKED.clear();
        Authenticators.forTest(null);
    }

    /** Route pattern (as {@code AgentGate} lists it) → the concrete path and body that drives it to a 200 answer. */
    private static Map<String, String[]> probes() {
        return probes("masked:0000000000000000");
    }

    /** {@code alias}: the Space alias of {@code A}, for the routes a masked Space feeds aliases only. */
    private static Map<String, String[]> probes(String alias) {
        Map<String, String[]> p = new LinkedHashMap<>();
        p.put("POST /inv/projection", new String[] {"/inv/projection", "{" + COLS + "}"});
        p.put("POST /inv/projection/neighbors", new String[] {"/inv/projection/neighbors", "{" + COLS + ",\"value\":\"" + A + "\"}"});
        p.put("POST /inv/projection/neighbors/preview", new String[] {"/inv/projection/neighbors/preview",
                "{" + COLS + ",\"ids\":[\"" + A + "\"]}"});
        p.put("POST /inv/projection/multi", new String[] {"/inv/projection/multi",
                "{\"edges\":[{\"dataset\":\"pays_ds\",\"sourceColumn\":\"caller\",\"targetColumn\":\"callee\"}]}"});
        p.put("POST /inv/traversal/recursive-paths", new String[] {"/inv/traversal/recursive-paths",
                "{" + COLS + ",\"startNode\":\"" + A + "\"}"});
        p.put("POST /inv/reference-rows", new String[] {"/inv/reference-rows",
                "{\"dataset\":\"pays_ds\",\"keyCol\":\"caller\",\"keys\":[\"" + A + "\"]}"});
        p.put("POST /inv/expand/preview", new String[] {"/inv/expand/preview",
                "{" + COLS + ",\"steps\":[{\"op\":\"seed\",\"ids\":[\"" + alias + "\"]}]}"});
        p.put("POST /inv/case-history", new String[] {"/inv/case-history", "{\"key\":\"" + alias + "\"}"});
        p.put("POST /inv/entities/([^/]+)/resolve", new String[] {"/inv/entities/msisdn/resolve", "{\"key\":\"" + A + "\"}"});
        p.put("POST /inv/entities/([^/]+)/suggest", new String[] {"/inv/entities/msisdn/suggest", "{\"prefix\":\"\"}"});
        p.put("POST /inv/deep-links/resolve", new String[] {"/inv/deep-links/resolve", "{\"entity\":\"msisdn:" + alias + "\"}"});
        p.put("POST /inv/presets/([^/]+)/run", new String[] {"/inv/presets/top_senders/run", "{\"dataset\":\"pays_ds\"}"});
        p.put("POST /inv/datasets/([^/]+)/query", new String[] {"/inv/datasets/pays_ds/query", "{\"query\":{}}"});
        p.put("POST /inv/pattern/branching", new String[] {"/inv/pattern/branching",
                "{" + COLS + ",\"stages\":[{\"shape\":\"fan-out\",\"minBranches\":1}]}"});
        p.put("POST /inv/pattern/temporal", new String[] {"/inv/pattern/temporal",
                "{" + COLS + ",\"timeCol\":\"ts\",\"mode\":\"burst\",\"minEvents\":2}"});
        p.put("POST /inv/investigations/([^/]+)/replay", new String[] {"/inv/investigations/inv-s0/replay", "{}"});
        return p;
    }

    private HttpResponse<String> send(ControlApiDatasetDiscoverTest.Ctx c, String path, String body, String auth) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static void assertNoRaw(String what, String body) {
        for (String raw : RAW) assertFalse(body.contains(raw), what + " leaked the raw id " + raw + ": " + body);
    }

    /** The alias the Space shows for {@code A}, as the person's resolve answers it. */
    private String alias(ControlApiDatasetDiscoverTest.Ctx c) throws Exception {
        HttpResponse<String> r = send(c, "/inv/entities/msisdn/resolve", "{\"key\":\"" + A + "\"}", PERSON);
        assertEquals(200, r.statusCode(), r.body());
        String alias = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.body()).at("/data/key").asText();
        assertTrue(alias.startsWith("masked:"), r.body());
        return alias;
    }

    private ControlApiDatasetDiscoverTest.Ctx open(Path cfg, Path root) throws Exception {
        ControlApiDatasetDiscoverTest.Ctx c = ControlApiDatasetDiscoverTest.open(cfg, root, "masking_mode: typed\n");
        // the Investigation the replay probe reads, owned by the person whose agent asks
        HttpResponse<String> created = send(c, "/inv/investigations", "{\"id\":\"inv-s0\",\"purpose\":\"Referral S0\","
                + COLS + "}", PERSON);
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(200, send(c, "/inv/investigations/inv-s0/ops", "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}", PERSON).statusCode());
        return c;
    }

    @Test
    void everyLinkAnalysisDataRouteIsProbed() {
        Set<String> la = new TreeSet<>();
        for (String r : AgentGate.DATA_ROUTES.keySet()) if (r.startsWith("POST /inv/")) la.add(r);
        assertEquals(la, new TreeSet<>(probes().keySet()), "one probe per Link Analysis agent data route");
    }

    @Test
    void anAgentsLinkAnalysisReadIsStampedAndMaskedAndAnUnstampedOneIsWithheld(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (ControlApiDatasetDiscoverTest.Ctx c = open(cfg, root)) {
            Map<String, String[]> probes = probes(alias(c));
            for (Map.Entry<String, String[]> p : probes.entrySet()) {
                String path = p.getValue()[0], body = p.getValue()[1];
                HttpResponse<String> agent = send(c, path, body, AGENT);
                assertEquals(200, agent.statusCode(), p.getKey() + " as the agent: " + agent.body());
                assertNoRaw(p.getKey() + " as the agent", agent.body());
            }
            AnswerMasking.ignoreStampsForTest(true);   // every masking step's stamp is now a no-op: the seam must hold
            for (Map.Entry<String, String[]> p : probes.entrySet()) {
                String path = p.getValue()[0], body = p.getValue()[1];
                HttpResponse<String> agent = send(c, path, body, AGENT);
                assertEquals(500, agent.statusCode(), p.getKey() + " unstamped, as the agent: " + agent.body());
                assertTrue(agent.body().contains(ControlApi.MASKING_NOT_PROVEN), agent.body());
                assertNoRaw(p.getKey() + " unstamped", agent.body());
                assertFalse(agent.body().contains("masked:"), "no row at all leaves: " + agent.body());
                HttpResponse<String> person = send(c, path, body, PERSON);
                assertEquals(200, person.statusCode(), p.getKey() + " as the person, unchanged: " + person.body());
            }
        }
    }
}
