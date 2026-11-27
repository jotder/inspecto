package com.gamma.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.ReadModel;
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
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /insights/brief/narrate} over real HTTP under an ARMED authenticator (BI-INSIGHTS-1 P4 BR-2). The module's
 * tests run on the core plus this module only (no {@code inspecto-agent}), so "no agent" is the real Basic shape and the
 * agent-present cases register a counting fake {@link AssistAgent} on the Space's service.
 */
class ControlApiInsightBriefNarrateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        return Optional.of(new Subject(a.substring(7), Set.of()));
    };

    /** The capability stand-in: numbers each answer, so a cached answer is recognisable as the SAME text. */
    private static final class FakeAgent implements AssistAgent {
        final List<AssistRequest> seen = new CopyOnWriteArrayList<>();
        @Override public String name() { return "fake"; }
        @Override public void init(ReadModel service) {}
        @Override public AssistResult assist(AssistRequest r) {
            seen.add(r);
            int call = seen.size();
            List<?> ev = (List<?>) r.partialInput().get("evidence");
            List<Map<String, Object>> sentences = new ArrayList<>();
            for (int i = 0; i < ev.size(); i++) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("n", i + 1);
                s.put("text", "[" + (i + 1) + "] model call " + call);
                s.put("cites", List.of(((Map<?, ?>) ev.get(i)).get("insightId")));
                s.put("source", "model");
                sentences.add(s);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sentences", sentences);
            data.put("modelBacked", true);
            data.put("repaired", false);
            return AssistResult.draft(r.intent(), "x", List.of(), List.of(), data);
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void restore() {
        Authenticators.forTest(null);
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root, Path data) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path base = dir.resolve("s1"), root = base.resolve("config"), data = base.resolve("data");
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, root.resolve("etl_pipeline.toon"));
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(dir);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), root, data);
    }

    private static Map<String, Object> row(String id, String run, String band, double score, String slice, Instant at) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("insight_id", id);
        r.put("profile_id", "p");
        r.put("run_id", run);
        r.put("run_at", at.toString());
        r.put("insight_key", "spike|region|" + slice + "|" + id);
        r.put("insight_type", "spike");
        r.put("measure", "amount");
        r.put("dimension", "region");
        r.put("slice_value", slice);
        r.put("period", "2026-03-01");
        r.put("value", 400.0);
        r.put("expected", 100.0);
        r.put("delta", 300.0);
        r.put("score", score);
        r.put("band", band);
        r.put("explanation", InsightStore.json(Map.of("sentence", "amount for region = " + slice + " was 400")));
        r.put("query", InsightStore.json(Map.of()));
        r.put("first_seen", at.toString());
        return r;
    }

    /** One run {@code run} of Profile {@code id}: two briefed Insights. The Profile is registered once. */
    private static void seed(Ctx c, String id, String run, Map<String, Object> envelope, Instant at) throws Exception {
        Path dir = InsightStore.claim(c.data(), id);
        InsightStore.write(dir, List.of(row("a" + run + id, run, "high", 90, "N", at),
                row("b" + run + id, run, "elevated", 50, "S", at)), at);
        InsightScanJobType.register(new ComponentStore(c.root().resolve("registry")), id, envelope);
    }

    private FakeAgent agent(Ctx c) {
        FakeAgent a = new FakeAgent();
        c.spaces().current().service().registerAgent(a);
        return a;
    }

    private HttpResponse<String> send(Ctx c, String method, String user, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/spaces/s1" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> narrate(Ctx c, String user, String body) throws Exception {
        return send(c, "POST", user, "/insights/brief/narrate", body);
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void withoutAnAgentTheNarrateRouteIs401Then503ButTheTemplatedBriefStillAnswers(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", "r1", Map.of(), Instant.now());
            assertEquals(401, narrate(c, null, "{\"profiles\":[\"daily\"]}").statusCode());
            HttpResponse<String> r = narrate(c, "viewer", "{\"profiles\":[\"daily\"]}");
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("CAPABILITY_UNAVAILABLE"), r.body());
            JsonNode brief = data(send(c, "GET", "viewer", "/insights/brief?profiles=daily", null));
            assertEquals(2, brief.get("profiles").get(0).get("lines").size(), "the templated Brief is unchanged without an agent");
            assertEquals("amount", brief.get("profiles").get(0).get("lines").get(0).get("measure").asText(),
                    "each line carries its Profile's measure, which the narrator's evidence needs");
        }
    }

    @Test
    void anAgentNarratesTheCallersBriefAndEveryLineSaysWhereItCameFrom(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", "r1", Map.of(), Instant.now());
            FakeAgent a = agent(c);
            JsonNode d = data(narrate(c, "viewer", "{\"profiles\":[\"daily\"],\"max\":5}"));
            assertEquals(2, d.get("brief").get("profiles").get(0).get("lines").size());
            JsonNode n = d.get("narration");
            assertEquals("model", n.get("mode").asText());
            JsonNode p = n.get("profiles").get(0);
            assertEquals("daily", p.get("profile").asText());
            assertEquals("r1", p.get("runId").asText());
            assertTrue(p.get("modelBacked").asBoolean());
            assertEquals("model", p.get("sentences").get(0).get("source").asText());
            assertEquals("insight-brief", a.seen.get(0).intent());
        }
    }

    @Test
    void evidenceInTheBodyIsIgnoredTheRouteBuildsItAsTheCaller(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", "r1", Map.of(), Instant.now());
            FakeAgent a = agent(c);
            String forged = "{\"profiles\":[\"daily\"],\"evidence\":[{\"insightId\":\"FORGED\",\"sentence\":\"FORGED\"}],"
                    + "\"partialInput\":{\"evidence\":[{\"insightId\":\"FORGED\"}]}}";
            String body = narrate(c, "viewer", forged).body();
            assertFalse(body.contains("FORGED"), body);
            List<?> ev = (List<?>) a.seen.get(0).partialInput().get("evidence");
            assertEquals(2, ev.size());
            assertEquals("ar1daily", ((Map<?, ?>) ev.get(0)).get("insightId"));
            assertEquals("amount", ((Map<?, ?>) ev.get(0)).get("measure"));
        }
    }

    @Test
    void theSameRunIsNarratedOnceAndANewRunIdMisses(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", "r1", Map.of(), Instant.now());
            FakeAgent a = agent(c);
            String first = data(narrate(c, "viewer", "{\"profiles\":[\"daily\"]}")).get("narration").toString();
            String second = data(narrate(c, "viewer", "{\"profiles\":[\"daily\"]}")).get("narration").toString();
            assertEquals(first, second, "a cache hit returns identical text");
            assertEquals(1, a.seen.size(), "and costs no second model call");

            seed(c, "daily", "r2", Map.of(), Instant.now().plusSeconds(60));
            String third = data(narrate(c, "viewer", "{\"profiles\":[\"daily\"]}")).get("narration").toString();
            assertEquals(2, a.seen.size(), "a new run_id misses");
            assertNotEquals(first, third);
            assertTrue(third.contains("model call 2"), third);
        }
    }

    @Test
    void aCachedNarrationIsServedOnlyToACallerWhoMayViewTheProfile(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "secret", "r1", Map.of("owner", "someone-else", "shares", List.of()), Instant.now());
            seed(c, "daily", "r1", Map.of(), Instant.now());
            FakeAgent a = agent(c);
            // a viewer of 'daily' warms its entry; the entry for 'secret' exists only after someone who may view it asks
            data(narrate(c, "viewer", "{\"profiles\":[\"daily\"]}"));
            JsonNode d = data(narrate(c, "viewer", "{\"profiles\":[\"daily\",\"secret\"]}"));
            assertEquals(1, d.get("brief").get("profiles").size(), "the hidden Profile is omitted from the Brief");
            assertEquals(1, d.get("narration").get("profiles").size(), "and from the narration, cached or not");
            assertFalse(d.toString().contains("secret"), d.toString());
            JsonNode only = data(narrate(c, "viewer", "{\"profiles\":[\"secret\"]}"));
            assertEquals(0, only.get("narration").get("profiles").size());
            assertEquals(1, a.seen.size(), "the hidden Profile was never read nor narrated for this caller");
        }
    }

    @Test
    void theStandardRateLimitBucketApplies(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", "r1", Map.of(), Instant.now());
            agent(c);
            int limited = 0;
            for (int i = 0; i < 30; i++)
                if (narrate(c, "viewer", "{\"profiles\":[\"daily\"]}").statusCode() == 429) limited++;
            assertTrue(limited > 0, "a burst of 30 narrations must hit the standard bucket");
        }
    }

    @Test
    void aBadBodyIs422AndTheDirectAssistPathIsNeitherCachedNorPartOfTheBrief(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", "r1", Map.of(), Instant.now());
            FakeAgent a = agent(c);
            assertEquals(422, narrate(c, "viewer", "{}").statusCode());
            assertEquals(422, narrate(c, "viewer", "{\"profiles\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}").statusCode());
            assertEquals(0, a.seen.size());

            String own = "{\"partialInput\":{\"profile\":\"daily\",\"evidence\":[{\"insightId\":\"x\",\"sentence\":\"mine\"}]}}";
            assertEquals(200, send(c, "POST", "viewer", "/assist/insight-brief", own).statusCode());
            assertEquals(200, send(c, "POST", "viewer", "/assist/insight-brief", own).statusCode());
            assertEquals(2, a.seen.size(), "a direct /assist call is not cached");

            data(narrate(c, "viewer", "{\"profiles\":[\"daily\"]}"));
            assertEquals(3, a.seen.size(), "and it did not warm the narrate route's cache");
            JsonNode brief = data(send(c, "GET", "viewer", "/insights/brief?profiles=daily", null));
            assertFalse(brief.toString().contains("narration"), "the templated Brief never carries a narration");
        }
    }
}
