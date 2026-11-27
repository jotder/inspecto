package com.gamma.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /insights/brief} over real HTTP under an ARMED authenticator (BI-INSIGHTS-1 P4 BR-0): 401, the Profile the
 * caller may not view OMITTED (never a 404 that hides the others), the stale note, the no-scan 404 in words, and the
 * stored alias reaching a revealer and a non-revealer alike. The module's tests run on the core plus this module only,
 * so a 200 here is a build without {@code inspecto-agent} serving the Brief.
 */
class ControlApiInsightBriefTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALIAS = "ALIAS-4f9c";

    private static final Authenticator FAKE = ex -> {
        String a = ex.getRequestHeaders().getFirst("Authorization");
        if (a == null || !a.startsWith("Bearer ")) return Optional.empty();
        ComponentAccess.heldRoles(ex, Set.of("developer"));
        String user = a.substring(7);
        return Optional.of(new Subject(user, "revealer".equals(user)
                ? Set.of("canAuthorWorkbench", "canRevealLinkEntities") : Set.of()));
    };

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

    private static Map<String, Object> row(String id, String band, double score, String slice, Instant at) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("insight_id", id);
        r.put("profile_id", "p");
        r.put("run_id", "r1");
        r.put("run_at", at.toString());
        r.put("insight_key", "spike|region|" + slice);
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

    /** Stores one run of Profile {@code id}: a high and an elevated Insight and a normal one, scanned at {@code at}. */
    private static void seed(Ctx c, String id, Map<String, Object> envelope, Instant at, String slice) throws Exception {
        Path dir = InsightStore.claim(c.data(), id);
        InsightStore.write(dir, List.of(row("i1" + id, "high", 90, slice, at), row("i2" + id, "elevated", 50, "S", at),
                row("i3" + id, "normal", 10, "E", at)), at);
        InsightScanJobType.register(new ComponentStore(c.root().resolve("registry")), id, envelope);
    }

    private HttpResponse<String> get(Ctx c, String user, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/spaces/s1" + path));
        if (user != null) b.header("Authorization", "Bearer " + user);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    @Test
    void theBriefIsGatedPerProfileAndAnOtherwiseHiddenProfileIsOmittedNotRefused(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", Map.of(), Instant.now(), "N");
            seed(c, "secret", Map.of("owner", "someone-else", "shares", List.of()), Instant.now(), "N");

            assertEquals(401, get(c, null, "/insights/brief?profiles=daily").statusCode());
            JsonNode d = data(get(c, "viewer", "/insights/brief?profiles=daily,secret,nope"));
            assertEquals(1, d.get("profiles").size(), "the Profile the caller may not view, and the unknown one, are omitted");
            JsonNode p = d.get("profiles").get(0);
            assertEquals("daily", p.get("profile").asText());
            assertEquals(2, p.get("lines").size(), "high and elevated; the normal Insight is not briefed");
            assertEquals("high", p.get("lines").get(0).get("band").asText());
            assertEquals("amount for region = N was 400", p.get("lines").get(0).get("sentence").asText());
            assertFalse(p.get("stale").asBoolean());
            assertEquals(1, data(get(c, "viewer", "/insights/brief?profiles=daily&max=1")).get("profiles").get(0).get("lines").size());

            JsonNode none = data(get(c, "viewer", "/insights/brief?profiles=secret"));
            assertEquals(0, none.get("profiles").size(), "nothing viewable is an empty 200, as the feed hides");

            assertEquals(422, get(c, "viewer", "/insights/brief").statusCode());
            assertEquals(422, get(c, "viewer", "/insights/brief?profiles=daily&max=11").statusCode());
            assertEquals(422, get(c, "viewer", "/insights/brief?profiles=a,b,c,d,e,f").statusCode());
        }
    }

    @Test
    void aScanOlderThanTwoGrainsIsBriefedWithItsStaleNoteAndAProfileNeverScannedIsA404InWords(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", Map.of(), Instant.parse("2026-03-02T06:00:00Z"), "N");
            JsonNode p = data(get(c, "viewer", "/insights/brief?profiles=daily")).get("profiles").get(0);
            assertTrue(p.get("stale").asBoolean());
            assertTrue(p.get("note").asText().startsWith("Last scan was "), p.toString());
            assertEquals(2, p.get("lines").size(), "a stale Brief still carries its lines");

            // registered (so viewable) but never scanned: no store directory
            InsightScanJobType.register(new ComponentStore(c.root().resolve("registry")), "fresh", Map.of());
            HttpResponse<String> r = get(c, "viewer", "/insights/brief?profiles=fresh");
            assertEquals(404, r.statusCode(), r.body());
            assertTrue(r.body().contains("insight.scan"), r.body());
            JsonNode both = data(get(c, "viewer", "/insights/brief?profiles=fresh,daily"));
            assertEquals(2, both.get("profiles").size(), "in a list the unscanned Profile is an entry with its note");
            assertTrue(both.get("profiles").get(0).get("note").asText().contains("insight.scan"));
        }
    }

    @Test
    void aStoredAliasReachesARevealerAndANonRevealerAlike(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            seed(c, "daily", Map.of(), Instant.now(), ALIAS);   // the scan stores the alias for a classified slice
            List<String> seen = new ArrayList<>();
            for (String user : List.of("revealer", "viewer")) {
                String body = get(c, user, "/insights/brief?profiles=daily").body();
                assertTrue(body.contains(ALIAS), user + ": " + body);
                seen.add(JSON.readTree(body).get("data").toString());
            }
            assertEquals(seen.get(0), seen.get(1), "a Brief reads identically for a revealer and a reader who may not reveal");
        }
    }
}
