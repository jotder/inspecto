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
import com.gamma.job.BriefPageProvider;
import com.gamma.job.JobConfig;
import com.gamma.job.JobType;
import com.gamma.job.PostgresPublishJobType;
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
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Report Brief page of the real Business Insights module (BI-INSIGHTS-1 P4 BR-4): {@link InsightBriefPages} builds the
 * page from the SAME selection and narration as {@code GET /insights/brief} and {@code POST /insights/brief/narrate}, as the
 * Job's author. Run on the core plus this module only, so "no agent" is a Basic build and a registered fake agent is AI Assist.
 */
class InsightBriefPagesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        return Optional.of(new Subject(a.substring(7), Set.of()));
    };

    private static final class FakeAgent implements AssistAgent {
        final List<AssistRequest> seen = new CopyOnWriteArrayList<>();
        @Override public String name() { return "fake"; }
        @Override public void init(ReadModel service) {}
        @Override public AssistResult assist(AssistRequest r) {
            seen.add(r);
            List<?> ev = (List<?>) r.partialInput().get("evidence");
            List<Map<String, Object>> sentences = new ArrayList<>();
            for (int i = 0; i < ev.size(); i++) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("n", i + 1);
                s.put("text", "[" + (i + 1) + "] model call " + seen.size());
                s.put("cites", List.of(((Map<?, ?>) ev.get(i)).get("insightId")));
                s.put("source", "model");
                sentences.add(s);
            }
            return AssistResult.draft(r.intent(), "x", List.of(), List.of(),
                    Map.of("sentences", sentences, "modelBacked", true, "repaired", false));
        }
    }

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void restore() {
        Authenticators.forTest(null);
        PostgresPublishJobType.installAuthority(null);
        System.clearProperty("assist.write.root");
        System.clearProperty("data.dir");
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
        // the Job side finds the Space the way a running Job does: its config and data roots
        System.setProperty("assist.write.root", root.toString());
        System.setProperty("data.dir", data.toString());
        return new Ctx(spaces, api, api.port(), root, data);
    }

    private static Map<String, Object> row(String id, String band, double score, String slice, Instant at) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("insight_id", id);
        r.put("profile_id", "p");
        r.put("run_id", "r1");
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

    private static void seed(Ctx c, String id, Map<String, Object> envelope) throws Exception {
        Instant at = Instant.now();
        Path dir = InsightStore.claim(c.data(), id);
        InsightStore.write(dir, List.of(row("a" + id, "high", 90, "N", at), row("b" + id, "elevated", 50, "S", at)), at);
        InsightScanJobType.register(new ComponentStore(c.root().resolve("registry")), id, envelope);
    }

    private static BriefPageProvider provider() {
        return ServiceLoader.load(BriefPageProvider.class).stream().map(ServiceLoader.Provider::get)
                .filter(p -> p instanceof InsightBriefPages).findFirst().orElseThrow();
    }

    private static JobConfig job() {
        return new JobConfig("weekly", JobType.REPORT, null, null, true, false, Map.of());
    }

    private JsonNode send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/spaces/s1" + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").header("Authorization", "Bearer viewer");
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void theServiceIsRegisteredForTheEngineToFind() {
        assertDoesNotThrow(InsightBriefPagesTest::provider);
    }

    @Test
    void withoutAnAgentThePageIsTheRoutesOwnLinesAndSaysTemplated(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", Map.of());
            BriefPageProvider.BriefPage page = provider().page("daily", job());
            assertFalse(page.modelWritten());
            assertTrue(page.paragraph().isEmpty());
            JsonNode lines = send(c, "GET", "/insights/brief?profiles=daily", null).get("profiles").get(0).get("lines");
            assertEquals(lines.size(), page.lines().size());
            for (int i = 0; i < lines.size(); i++) {
                assertEquals(lines.get(i).get("sentence").asText(), page.lines().get(i).text(), "page text = the route's sentence");
                assertEquals(lines.get(i).get("band").asText(), page.lines().get(i).band());
            }
        }
    }

    @Test
    void withAnAgentThePageCarriesTheRoutesNarrationAndTwoDeliveriesAreIdentical(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", Map.of());
            FakeAgent a = new FakeAgent();
            c.spaces().current().service().registerAgent(a);
            JsonNode narrated = send(c, "POST", "/insights/brief/narrate", "{\"profiles\":[\"daily\"]}")
                    .get("narration").get("profiles").get(0).get("sentences");
            BriefPageProvider.BriefPage first = provider().page("daily", job());
            BriefPageProvider.BriefPage second = provider().page("daily", job());
            assertTrue(first.modelWritten());
            assertEquals(narrated.size(), first.paragraph().size());
            for (int i = 0; i < narrated.size(); i++)
                assertEquals(narrated.get(i).get("text").asText(), first.paragraph().get(i), "the screen's narration is the PDF's");
            assertEquals(first, second, "a re-run on the same scan reads identically");
            assertEquals(1, a.seen.size(), "one model call served the route and both deliveries");
        }
    }

    @Test
    void aProfileTheJobsAuthorMayNotViewFailsInWords(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "secret", Map.of("owner", "someone-else", "shares", List.of()));
            PostgresPublishJobType.installAuthority(cfg -> new PostgresPublishJobType.Author("author", Set.of(), Set.of(), false));
            SecurityException e = assertThrows(SecurityException.class, () -> provider().page("secret", job()));
            assertTrue(e.getMessage().contains("secret") && e.getMessage().contains("refused"), e.getMessage());
            // and one it may view still works
            seed(c, "daily", Map.of());
            assertEquals(2, provider().page("daily", job()).lines().size());
        }
    }

    @Test
    void aNeverScannedProfileIsAPageWithItsNoteNotAnError(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            InsightScanJobType.register(new ComponentStore(c.root().resolve("registry")), "fresh", Map.of());
            BriefPageProvider.BriefPage page = provider().page("fresh", job());
            assertTrue(page.lines().isEmpty());
            assertTrue(page.note().contains("insight.scan"), page.note());
        }
    }
}
