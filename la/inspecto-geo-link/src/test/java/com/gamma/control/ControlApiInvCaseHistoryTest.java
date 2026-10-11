package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /inv/case-history} over real HTTP: every gate (503 write root · 503 Case management absent · 422 body ·
 * 404 Investigation absent or caller not a member · 422 unsearchable key without naming it), alias and Investigation
 * pseudonym resolution, a raw key under masking answered exactly like an unknown alias (no oracle), the masking-coverage
 * rule (an alias resolves only for a covered Entity Type), the Entity Type taken from the Investigation, title re-masking,
 * and no raw id in any body. Case management is {@link CaseTeamObjectEngine}, which records the RAW key each call asked.
 */
class ControlApiInvCaseHistoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003";
    private static final List<String> RAW = List.of(A, B, C);
    private static final String ROUTE = "/inv/case-history";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        CaseTeamObjectEngine.arm();
        CaseTeamObjectEngine.HISTORY = List.of(
                Map.of("kind", "case", "id", "CASE-1", "title", "ring around +" + A, "status", "OPEN", "createdAt", 2L,
                        "matchedOn", "member", "via", "INC-1"),
                Map.of("kind", "incident", "id", "INC-1", "title", "calls spike " + A + " -> " + B, "status", "OPEN",
                        "createdAt", 1L, "matchedOn", "key.caller_msisdn"));
        CaseTeamObjectEngine.HISTORY_TRUNCATED = false;
        CaseTeamObjectEngine.ASKED.clear();
    }

    @AfterEach
    void reset() {
        CaseTeamObjectEngine.CASES = null;
        CaseTeamObjectEngine.HISTORY = null;
        CaseTeamObjectEngine.HISTORY_TRUNCATED = false;
        CaseTeamObjectEngine.ASKED.clear();
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                Files.createDirectories(writeRoot);
                Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                        "SELECT caller, callee FROM (VALUES ('" + A + "','" + B + "'),('" + B + "','" + C + "')) AS t(caller,callee)",
                        "2026-10-11T00:00:00Z"));
                new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            }
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static void settings(Ctx c, String s) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), s);
    }

    private HttpResponse<String> send(Ctx c, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = body == null ? b.GET() : b.POST(BodyPublishers.ofString(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Ctx c, String path, String body) throws Exception {
        return send(c, path, body, null);
    }

    /** A 200 whose body carries none of the raw ids (unless {@code rawShown}). */
    private JsonNode ok(Ctx c, String body, boolean rawShown) throws Exception {
        HttpResponse<String> r = send(c, ROUTE, body);
        assertEquals(200, r.statusCode(), body + " -> " + r.body());
        if (!rawShown) for (String raw : RAW) assertFalse(r.body().contains(raw), body + " leaked " + raw + ": " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private String aliasOfA(Ctx c) throws Exception {
        return aliasOfA(c, null);
    }

    private String aliasOfA(Ctx c, String auth) throws Exception {
        HttpResponse<String> r = send(c, "/inv/projection", "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", auth);
        assertEquals(200, r.statusCode(), r.body());
        // A appears only as a source, B as both: A's alias is the source never seen as a target
        JsonNode rows = JSON.readTree(r.body()).at("/data/rows");
        Set<String> targets = new java.util.HashSet<>();
        rows.forEach(n -> targets.add(n.get("target").asText()));
        for (JsonNode n : rows) if (!targets.contains(n.get("source").asText())) return n.get("source").asText();
        throw new AssertionError("no alias for A in " + rows);
    }

    @Test
    void aMaskedKeyResolvesServerSideAndNothingRawComesBack(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            String aliasA = aliasOfA(c);
            assertTrue(aliasA.startsWith("masked:"));
            CaseTeamObjectEngine.HISTORY_TRUNCATED = true;
            JsonNode d = ok(c, "{\"key\":\"" + aliasA + "\",\"status\":\"open\",\"limit\":1}", false);
            assertEquals(List.of("msisdn|" + A + "|open|1"), CaseTeamObjectEngine.ASKED, "the host was asked the RAW key");
            assertTrue(d.get("resolved").asBoolean(), d.toString());
            assertEquals("msisdn", d.get("entityType").asText());
            assertEquals(1, d.get("objects").size(), "the limit is the host's");
            assertEquals(2, d.get("total").asInt(), "total is every visible match");
            assertTrue(d.get("truncated").asBoolean(), "the host's truncation passes through");
            assertEquals("ring around " + aliasA, d.at("/objects/0/title").asText(), "the raw id in a title is the key as asked");
            assertTrue(d.at("/masking/masked").asBoolean());
            assertFalse(d.has("key"), "the key is never echoed");

            JsonNode all = ok(c, "{\"key\":\"" + aliasA + "\"}", false);
            String title = all.at("/objects/1/title").asText();
            assertTrue(title.startsWith("calls spike " + aliasA + " -> masked:"), "another number in a title is aliased too: " + title);
            assertEquals("INC-1", all.at("/objects/0/via").asText());
        }
    }

    @Test
    void aRawKeyUnderMaskingIsAnsweredLikeAnUnknownAliasAndNeverLookedUp(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            JsonNode raw = ok(c, "{\"key\":\"" + A + "\"}", false);
            JsonNode unknown = ok(c, "{\"key\":\"masked:0000000000000000\"}", false);
            assertFalse(raw.get("resolved").asBoolean());
            assertEquals(unknown, raw, "a refused raw key and an unknown alias are indistinguishable: no oracle");
            assertEquals(0, raw.get("objects").size());
            assertTrue(CaseTeamObjectEngine.ASKED.isEmpty(), "nothing was looked up");
        }
    }

    @Test
    void anAliasResolvesOnlyForAnEntityTypeTheMaskingCovers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            String aliasA = aliasOfA(c);
            settings(c, "masking_mode: typed\n");
            // cell is an unmasked Entity Type: its raw ids are shown, so an alias must not be searched as one
            assertEquals(200, send(c, "/inv/investigations", "{\"id\":\"cells\",\"purpose\":\"case history\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"entityType\":\"cell\"}").statusCode());
            JsonNode refused = ok(c, "{\"key\":\"" + aliasA + "\",\"investigationId\":\"cells\"}", false);
            assertFalse(refused.get("resolved").asBoolean(), refused.toString());
            assertEquals("cell", refused.get("entityType").asText(), "the Entity Type is the Investigation's");
            assertTrue(CaseTeamObjectEngine.ASKED.isEmpty());

            JsonNode cell = ok(c, "{\"key\":\"CELL-0042\",\"investigationId\":\"cells\"}", true);
            assertTrue(cell.get("resolved").asBoolean(), "a raw key of an uncovered type is looked up as it is");
            assertEquals(List.of("cell|CELL-0042|all|50"), CaseTeamObjectEngine.ASKED);
            assertFalse(cell.at("/masking/masked").asBoolean());

            // msisdn IS covered under typed: the alias resolves again without an Investigation
            assertTrue(ok(c, "{\"key\":\"" + aliasA + "\"}", false).get("resolved").asBoolean());
            settings(c, "masking_mode: none\n");
            assertFalse(ok(c, "{\"key\":\"" + aliasA + "\"}", false).get("resolved").asBoolean(), "none covers nothing");
            JsonNode plain = ok(c, "{\"key\":\"" + A + "\"}", true);
            assertTrue(plain.get("resolved").asBoolean());
            assertEquals("calls spike " + A + " -> " + B, plain.at("/objects/1/title").asText(), "none shows raw");
        }
    }

    @Test
    void anInvestigationPseudonymResolvesForAReaderAndANonMemberGets404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
                case "Bearer stranger" -> Optional.of(new Subject("analyst-3", Set.of("canManageIncidents")));
                default -> Optional.empty();
            });
            String own = "Bearer owner";
            String aliasA = aliasOfA(c, own);
            assertEquals(200, send(c, "/inv/investigations", "{\"id\":\"inv-a\",\"purpose\":\"case history\","
                    + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", own).statusCode());
            assertEquals(200, send(c, "/inv/investigations/inv-a/ops", "{\"op\":\"seed\",\"ids\":[\"" + aliasA + "\"]}", own).statusCode());
            String pseudonym = JSON.readTree(send(c, "/inv/investigations/inv-a/working-set", null, own).body())
                    .at("/data/rows/0/entityId").asText();
            assertNotEquals(aliasA, pseudonym);

            String body = "{\"key\":\"" + pseudonym + "\",\"investigationId\":\"inv-a\"}";
            HttpResponse<String> r = send(c, ROUTE, body, own);
            assertEquals(200, r.statusCode(), r.body());
            for (String raw : RAW) assertFalse(r.body().contains(raw), r.body());
            JsonNode d = JSON.readTree(r.body()).get("data");
            assertTrue(d.get("resolved").asBoolean(), d.toString());
            assertTrue(d.at("/objects/0/title").asText().endsWith(pseudonym), "re-masked with the pseudonym as asked");
            assertEquals(List.of("msisdn|" + A + "|all|50"), CaseTeamObjectEngine.ASKED);

            assertFalse(JSON.readTree(send(c, ROUTE, "{\"key\":\"" + pseudonym + "\"}", own).body())
                    .at("/data/resolved").asBoolean(), "a pseudonym needs its Investigation");
            HttpResponse<String> stranger = send(c, ROUTE, body, "Bearer stranger");
            assertEquals(404, stranger.statusCode(), "a non-member is told nothing exists: " + stranger.body());
            assertEquals(401, send(c, ROUTE, body, "Bearer nobody").statusCode());
        }
    }

    @Test
    void gates503422And404(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            for (String bad : List.of("{}", "{\"key\":\" \"}", "{\"key\":1}", "{\"key\":\"" + "9".repeat(257) + "\"}",
                    "{\"key\":\"" + A + "\",\"status\":\"pending\"}", "{\"key\":\"" + A + "\",\"limit\":0}",
                    "{\"key\":\"" + A + "\",\"limit\":201}", "{\"key\":\"" + A + "\",\"limit\":\"x\"}"))
                assertEquals(422, send(c, ROUTE, bad).statusCode(), bad);
            assertEquals(404, send(c, ROUTE, "{\"key\":\"" + A + "\",\"investigationId\":\"ghost\"}").statusCode());
            // the host's 422 names the folded key: the route replaces the message
            HttpResponse<String> shortKey = send(c, ROUTE, "{\"key\":\"7713\"}");
            assertEquals(422, shortKey.statusCode(), shortKey.body());
            assertFalse(shortKey.body().contains("7713"), "an error never names the key: " + shortKey.body());
            assertEquals("msisdn|7713|all|50", CaseTeamObjectEngine.ASKED.get(CaseTeamObjectEngine.ASKED.size() - 1),
                    "the 422 is the host's, after the lookup");
        }
        CaseTeamObjectEngine.CASES = null;   // a bundle without Case management
        try (Ctx c = open(cfg2, root.resolve("absent"), "masking_mode: none\n")) {
            HttpResponse<String> r = send(c, ROUTE, "{\"key\":\"" + A + "\"}");
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("not installed"), r.body());
        }
    }

    @Test
    void noWriteRootIs503(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null, "")) {
            assertEquals(503, send(c, ROUTE, "{\"key\":\"" + A + "\"}").statusCode());
        }
    }
}
