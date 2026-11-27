package com.gamma.anomaly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetRelation;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ANOMALY-DETECTION-1 S4 over real HTTP with an ARMED Subject (a test with no Subject would make
 * {@code withCapability} a no-op): {@code GET /anomaly-scores/{model}/{entityKey}} returns the latest score with its
 * explanation and the recent runs; {@code POST /anomaly-scores/preview} scores one entity and writes nothing. Every
 * gate: the capability, unsaved content needing {@code canAuthorWorkbench}, data scopes and the existence-hiding
 * 404s, the body shape, and D-P8 key masking unless {@code canRevealLinkEntities}.
 */
class ControlApiAnomalyScoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private static final Set<String> CAPS = Set.of("canWorkIncidents", "canAuthorWorkbench");

    /** analyst: unscoped, no reveal · fraud / billing: data-scoped · nocap: no canWorkIncidents · revealer: reads only. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer analyst".equals(auth)) return Optional.of(new Subject("ana", CAPS));
        if ("Bearer fraud".equals(auth)) return Optional.of(new Subject("fay", CAPS, Set.of("fraud")));
        if ("Bearer billing".equals(auth)) return Optional.of(new Subject("bo", CAPS, Set.of("billing")));
        if ("Bearer nocap".equals(auth)) return Optional.of(new Subject("nc", Set.of("canAuthorWorkbench")));
        if ("Bearer revealer".equals(auth))
            return Optional.of(new Subject("rev", Set.of("canWorkIncidents", "canRevealLinkEntities")));
        if ("Bearer agent".equals(auth)) return Optional.of(agentFor("rev", Set.of("canWorkIncidents", "canRevealLinkEntities")));
        if ("Bearer agentauthor".equals(auth)) return Optional.of(agentFor("ana", CAPS));
        if ("Bearer machine".equals(auth)) return Optional.of(new Subject("client:bot", CAPS, null, Map.of(), null, null, "svc", "bot"));
        return Optional.empty();
    };

    /** The person's Subject through an MCP agent client, narrowed as {@code OidcAuthenticator} narrows it (AK-2a). */
    private static Subject agentFor(String id, Set<String> personCaps) {
        Set<String> kept = new java.util.HashSet<>(personCaps);
        kept.removeAll(com.gamma.access.Roles.AGENT_NEVER_CAPABILITIES);
        return new Subject(id, Set.copyOf(kept), null, Map.of(), null, null, null, "claude-code");
    }

    /**
     * W3 (AK-2 widening plan §3, check before building {@code get_anomaly_score}): an agent acting for a person who may
     * reveal reads the key masked, with the same token the person's screen shows a caller without reveal.
     * Falsified: an agent Subject that kept {@code canRevealLinkEntities} reads the raw key.
     */
    @Test
    void anAgentReadsTheKeyMaskedEvenForAPersonWhoMayReveal(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            score(c);
            JsonNode person = data(send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "revealer"));
            assertEquals("spike", person.get("entityKey").asText(), "precondition: the person sees the raw key");
            JsonNode agent = data(send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "agent"));
            assertTrue(agent.get("keyMasked").asBoolean(), agent.toString());
            assertEquals(data(send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "analyst")).get("entityKey"),
                    agent.get("entityKey"), "the token a person without reveal sees");
            assertFalse(agent.toString().contains("\"spike\""), "the raw key is nowhere in the body: " + agent);
        }
    }

    /**
     * AGENT-GET-DATA-READS-1 (D-AK13): "GET /anomaly-scores/([^/]+)/([^/]+)" is an agent data route. The agent's answer
     * is stamped by {@code EntityKeyMasking.put}; with every stamp a no-op the agent gets 500 with no key while a person
     * still gets the score. Falsified: removing the stamp in {@code EntityKeyMasking.put}.
     */
    @Test
    void anAgentsAnomalyScoreIsProvenMaskedOrWithheld(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            score(c);
            String path = "/spaces/s1/anomaly-scores/usage/spike";
            assertEquals(200, send(c.port, "GET", path, null, "agent").statusCode());
            com.gamma.util.AnswerMasking.ignoreStampsForTest(true);
            try {
                HttpResponse<String> agent = send(c.port, "GET", path, null, "agent");
                assertEquals(500, agent.statusCode(), agent.body());
                assertTrue(agent.body().contains(ControlApi.MASKING_NOT_PROVEN), agent.body());
                assertFalse(agent.body().contains("spike") || agent.body().contains("masked:"), agent.body());
                assertEquals(200, send(c.port, "GET", path, null, "revealer").statusCode(), "a person is unchanged");
            } finally {
                com.gamma.util.AnswerMasking.ignoreStampsForTest(false);
            }
        }
    }

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config, Path data) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** One Space {@code s1} over the golden corpus, with the model saved as {@code usage} (optionally data-scoped). */
    private Ctx open(Path root, String scope) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Path data = base.resolve("data");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Map<String, Object> model = new LinkedHashMap<>(AnomalyCorpus.plant(config, data));
        if (scope != null) {
            model.put("dataScope", scope);
            new ComponentStore(config.resolve("registry")).write(AnomalyModel.KIND, AnomalyCorpus.MODEL, model);
        }
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config, data);
    }

    /** Two production-writer runs: the earlier scores 2026-09-29 (all normal), the later 2026-09-30 (spike high). */
    private static void score(Ctx c) throws Exception {
        ComponentStore store = new ComponentStore(c.config.resolve("registry"));
        Map<String, Object> content = store.get(AnomalyModel.KIND, AnomalyCorpus.MODEL).orElseThrow().content();
        AnomalyModel model = AnomalyModel.fromMap(AnomalyCorpus.MODEL, content);
        String v = AnomalyScoreEvaluator.version(content);
        int run = 0;
        for (LocalDate asOf : List.of(LocalDate.parse("2026-09-30"), AnomalyCorpus.AS_OF)) {
            AnomalyScoreEvaluator.Run r = AnomalyScoreEvaluator.evaluate(model, asOf, id -> DatasetRelation.relationSql(
                    store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), c.data, null));
            AnomalyScoreEvaluator.write(c.data, model, v, "r" + (++run), Instant.parse("2026-10-0" + run + "T00:00:00Z"), r);
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (who != null) b.header("Authorization", "Bearer " + who);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        JsonNode n = JSON.readTree(r.body());
        return n.has("data") ? n.get("data") : n;
    }

    private static long parquetFiles(Path data) throws Exception {
        if (!Files.isDirectory(data)) return 0;
        try (Stream<Path> s = Files.walk(data)) {
            return s.filter(p -> p.toString().endsWith(".parquet")).count();
        }
    }

    @Test
    void theLatestScoreComesBackWithItsExplanationAndRecentRunsAndTheKeyMasked(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            score(c);
            HttpResponse<String> r = send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "revealer");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = data(r);
            assertEquals("spike", d.get("entityKey").asText(), "a caller with canRevealLinkEntities sees the raw key");
            assertFalse(d.get("keyMasked").asBoolean());
            assertEquals("r2", d.get("runId").asText(), "the latest run, not the first");
            assertEquals("high", d.get("band").asText());
            assertTrue(d.get("score").asDouble() >= d.get("highThreshold").asDouble());
            assertEquals("subscriber", d.get("entityType").asText());
            assertEquals(2, d.get("features").size());
            double[] re = AnomalyScorer.recompute(JSON.convertValue(d.get("features"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {}), 10, 3);
            assertEquals(d.get("score").asDouble(), re[1], 1e-9, "the served explanation recomputes to the score");
            JsonNode history = d.get("history");
            assertEquals(2, history.size(), "one point per run, for the sparkline");
            assertEquals("r2", history.get(0).get("runId").asText(), "newest first");
            assertEquals("normal", history.get(1).get("band").asText(), "the earlier day was normal");

            JsonNode masked = data(send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "analyst"));
            assertTrue(masked.get("keyMasked").asBoolean(), "D-P8: no canRevealLinkEntities, masked on read");
            assertTrue(masked.get("entityKey").asText().startsWith("masked:"), masked.toString());
            assertFalse(masked.toString().contains("\"spike\""), "the raw key is nowhere in the body: " + masked);
        }
    }

    @Test
    void theCapabilityScopesAndExistenceHidingHold(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, "fraud")) {
            score(c);
            assertEquals(403, send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "nocap").statusCode());
            assertEquals(200, send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "fraud").statusCode(),
                    "a caller holding the model's scope reads it");
            HttpResponse<String> billing = send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "billing");
            HttpResponse<String> unknownModel = send(c.port, "GET", "/spaces/s1/anomaly-scores/nope/spike", null, "fraud");
            HttpResponse<String> unknownKey = send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/nobody", null, "fraud");
            for (HttpResponse<String> r : List.of(billing, unknownModel, unknownKey))
                assertEquals(404, r.statusCode(), r.body());
            assertEquals(200, send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "analyst").statusCode(),
                    "an unscoped caller reads a scoped model");
        }
    }

    @Test
    void aNotYetScoredModelIs404(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            assertEquals(404, send(c.port, "GET", "/spaces/s1/anomaly-scores/usage/spike", null, "analyst").statusCode());
        }
    }

    @Test
    void thePreviewScoresOneEntityAndWritesNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            long before = parquetFiles(c.data);
            HttpResponse<String> r = send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"spike\",\"asOf\":\"2026-10-01\"}", "revealer");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = data(r);
            assertTrue(d.get("found").asBoolean());
            assertTrue(d.get("saved").asBoolean());
            assertEquals("high", d.get("band").asText());
            assertEquals("2026-09-30", d.get("periodStart").asText());
            assertEquals(2, d.get("features").size());
            assertEquals("spike", d.get("entityKey").asText());
            JsonNode series = d.get("series");
            assertEquals(2, series.size(), "one per-day series per feature");
            JsonNode mb = series.get("data_mb");
            JsonNode pts = mb.get("points");
            assertTrue(pts.size() >= 28, "the history window plus the scored day");
            assertEquals("2026-09-30", pts.get(pts.size() - 1).get("day").asText(), "the scored day is last");
            assertEquals(3000.0, pts.get(pts.size() - 1).get("value").asDouble(), 1e-9);
            assertTrue(mb.get("low").asDouble() < mb.get("median").asDouble());
            assertTrue(mb.get("median").asDouble() < mb.get("high").asDouble());
            assertTrue(pts.get(pts.size() - 1).get("value").asDouble() > mb.get("high").asDouble(),
                    "the spike sits above the band");
            assertEquals(3.0, mb.get("bandSpreads").asDouble(), 0);
            assertEquals(before, parquetFiles(c.data), "no scores Dataset is written");
            assertFalse(Files.exists(c.data.resolve("anomaly_scores_usage")));

            JsonNode masked = data(send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"spike\",\"asOf\":\"2026-10-01\"}", "analyst"));
            assertTrue(masked.get("keyMasked").asBoolean());
            assertTrue(masked.get("entityKey").asText().startsWith("masked:"));

            JsonNode none = data(send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"nobody\",\"asOf\":\"2026-10-01\"}", "analyst"));
            assertFalse(none.get("found").asBoolean(), "an entity no feature names is not found");
            assertTrue(none.get("score").isNull());
            assertEquals(0, none.get("series").size(), "no series for an entity no feature names");
        }
    }

    @Test
    void thePreviewCarriesEvidenceRowsMaskedAtWriteTime(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            new ComponentStore(c.config.resolve("registry")).write("dataset", "usage", Map.of("physicalRef", "usage",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"))));
            Map<String, Object> content = AnomalyCorpus.model();
            for (Object f : (List<?>) content.get("features")) {
                @SuppressWarnings("unchecked") Map<String, Object> fm = (Map<String, Object>) f;
                fm.put("evidence", List.of("msisdn", "mb"));
            }
            HttpResponse<String> r = send(c.port, "POST", "/spaces/s1/anomaly-scores/preview", JSON.writeValueAsString(
                    Map.of("content", content, "entityKey", "spike", "asOf", "2026-10-01")), "analyst");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode top = data(r).get("features").get(0);
            assertTrue(top.get("evidence").size() >= 1);
            String masked = top.get("evidence").get(0).get("msisdn").asText();
            assertTrue(masked.startsWith("masked:"), "a classified evidence column is a token in the response");
            assertFalse(top.toString().contains("\"msisdn\":\"spike\""));
            assertTrue(top.get("evidence").get(0).has("mb"));
        }
    }

    /**
     * AGENT-KIT-1 W6 group E (D-AK13, D-AK15): "POST /anomaly-scores/preview" is an agent data route. The agent sends the
     * raw key (allowed); the answer names it only as the Space token and carries the classified evidence masked. With every
     * stamp a no-op the agent gets 500 with no key or evidence while a person still gets the preview.
     */
    @Test
    void anAgentsScorePreviewIsProvenMaskedOrWithheld(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            ComponentStore store = new ComponentStore(c.config.resolve("registry"));
            store.write("dataset", "usage", Map.of("physicalRef", "usage",
                    "columns", List.of(Map.of("name", "msisdn", "classification", "MSISDN"))));
            Map<String, Object> content = AnomalyCorpus.model();
            for (Object f : (List<?>) content.get("features")) {
                @SuppressWarnings("unchecked") Map<String, Object> fm = (Map<String, Object>) f;
                fm.put("evidence", List.of("msisdn", "mb"));
            }
            store.write(AnomalyModel.KIND, AnomalyCorpus.MODEL, content);
            String path = "/spaces/s1/anomaly-scores/preview";
            String body = "{\"model\":\"" + AnomalyCorpus.MODEL + "\",\"entityKey\":\"spike\",\"asOf\":\"2026-10-01\"}";
            HttpResponse<String> r = send(c.port, "POST", path, body, "agent");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = data(r);
            assertTrue(d.get("found").asBoolean(), "the raw key an agent sends is scored (D-AK15): " + r.body());
            assertTrue(d.get("keyMasked").asBoolean());
            assertTrue(d.get("entityKey").asText().startsWith("masked:"), r.body());
            assertTrue(d.at("/features/0/evidence/0/msisdn").asText().startsWith("masked:"), r.body());
            assertFalse(r.body().contains("\"spike\""), "no raw key leaves: " + r.body());
            com.gamma.util.AnswerMasking.ignoreStampsForTest(true);
            try {
                HttpResponse<String> agent = send(c.port, "POST", path, body, "agent");
                assertEquals(500, agent.statusCode(), agent.body());
                assertTrue(agent.body().contains(ControlApi.MASKING_NOT_PROVEN), agent.body());
                assertFalse(agent.body().contains("spike") || agent.body().contains("masked:"), agent.body());
                assertEquals(200, send(c.port, "POST", path, body, "revealer").statusCode(), "a person is unchanged");
            } finally {
                com.gamma.util.AnswerMasking.ignoreStampsForTest(false);
            }
        }
    }

    /**
     * D-AK23 (operator 2026-11-26): a DRAFT preview is raw for a builder. A person's agent holding canAuthorWorkbench gets
     * the same features and daily series the person gets; a machine agent's draft is 403. Falsified: removing the machine
     * refusal in {@code AnomalyScoreRoutes.preview}.
     */
    @Test
    void aDraftPreviewIsRawForABuildersAgentAndRefusedToAMachine(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            com.gamma.access.Roles.write(c.config, Map.of(), List.of(), List.of("bot"));   // the Space admits client 'bot'
            String draft = JSON.writeValueAsString(Map.of("content", AnomalyCorpus.model(), "entityKey", "spike",
                    "asOf", "2026-10-01"));
            HttpResponse<String> person = send(c.port, "POST", "/spaces/s1/anomaly-scores/preview", draft, "analyst");
            assertEquals(200, person.statusCode(), person.body());
            HttpResponse<String> agent = send(c.port, "POST", "/spaces/s1/anomaly-scores/preview", draft, "agentauthor");
            assertEquals(200, agent.statusCode(), agent.body());
            assertEquals(data(person).get("series"), data(agent).get("series"), "the daily series as the person gets it");
            assertTrue(data(agent).get("keyMasked").asBoolean(), "the key still leaves masked");

            HttpResponse<String> machine = send(c.port, "POST", "/spaces/s1/anomaly-scores/preview", draft, "machine");
            assertEquals(403, machine.statusCode(), machine.body());
            assertTrue(machine.body().contains("saved model only"), machine.body());
        }
    }

    @Test
    void thePreviewGatesFailClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root, null)) {
            String content = JSON.writeValueAsString(Map.of("content", AnomalyCorpus.model(), "entityKey", "spike",
                    "asOf", "2026-10-01"));
            assertEquals(403, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"spike\"}", "nocap").statusCode());
            assertEquals(403, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview", content, "revealer").statusCode(),
                    "unsaved content needs canAuthorWorkbench");
            HttpResponse<String> unsaved = send(c.port, "POST", "/spaces/s1/anomaly-scores/preview", content, "analyst");
            assertEquals(200, unsaved.statusCode(), unsaved.body());
            assertFalse(data(unsaved).get("saved").asBoolean());
            assertEquals("high", data(unsaved).get("band").asText());

            Map<String, Object> bad = new LinkedHashMap<>(AnomalyCorpus.model());
            bad.put("window", 500);
            assertEquals(422, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    JSON.writeValueAsString(Map.of("content", bad, "entityKey", "spike")), "analyst").statusCode());
            Map<String, Object> scoped = new LinkedHashMap<>(AnomalyCorpus.model());
            scoped.put("dataScope", "fraud");
            assertEquals(403, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    JSON.writeValueAsString(Map.of("content", scoped, "entityKey", "spike")), "billing").statusCode(),
                    "a data-scoped caller previews only content carrying its scope");
            assertEquals(400, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"content\":{},\"entityKey\":\"spike\"}", "analyst").statusCode());
            assertEquals(400, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\"}", "analyst").statusCode());
            assertEquals(400, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"usage\",\"entityKey\":\"spike\",\"asOf\":\"yesterday\"}", "analyst").statusCode());
            assertEquals(404, send(c.port, "POST", "/spaces/s1/anomaly-scores/preview",
                    "{\"model\":\"nope\",\"entityKey\":\"spike\"}", "analyst").statusCode());
        }
    }
}
