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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DR-D2 / DR-T4 (operator 2026-10-10: <i>mask the same way everywhere</i>) over real HTTP. A Space that masks shows an
 * ALIAS for an entity on every stateless exploration read — {@code /inv/projection}, {@code /neighbors}, {@code /multi},
 * {@code /traversal/recursive-paths}, {@code /pattern/temporal}, {@code /value-measures} — and an alias handed back
 * (an expand's {@code value}, a traversal's {@code startNode}, an Investigation seed) resolves server-side without any
 * response carrying a raw id. Every response body is SCANNED for the planted raw ids, not just a field of it.
 *
 * <p>Fixture: phone-number-shaped ids ({@code ControlApiInvestigationOversightTest}'s reason: a short id would also match
 * ordinary words in the scan).
 */
class ControlApiExplorationMaskingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003", D = "27820000004";
    private static final List<String> RAW = List.of(A, B, C, D);
    private static final String ROWS = String.join(",",
            "('" + A + "','" + B + "','voice',10,TIMESTAMP '2026-09-24 10:00:00')",
            "('" + A + "','" + B + "','voice',20,TIMESTAMP '2026-09-24 10:00:10')",
            "('" + A + "','" + C + "','sms',5,TIMESTAMP '2026-09-24 11:00:00')",
            "('" + B + "','" + D + "','voice',7,TIMESTAMP '2026-09-24 12:00:00')");
    private static final String BASE = "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot, Map<String, Object> dataset) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, amt, ts FROM (VALUES " + ROWS
                            + ") AS t(caller,callee,channel,amt,ts)", "2026-09-24T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", dataset);
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static Map<String, Object> plain() {
        return Map.of("view", "calls_view");
    }

    private static Map<String, Object> classified(String cls) {
        return Map.of("view", "calls_view", "columns", List.of(Map.of("name", "caller", "classification", cls)));
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), toon);
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /** A 200 answer's body, which the caller scans; the planted raw ids must appear in it only when {@code rawShown}. */
    private JsonNode ok(Ctx c, String method, String path, String body, boolean rawShown) throws Exception {
        HttpResponse<String> r = send(c, method, path, body);
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        if (!rawShown)
            for (String raw : RAW)
                assertFalse(r.body().contains(raw), path + " leaked the raw id " + raw + ": " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String aliasOf(JsonNode projection, int row, String end) {
        return projection.get("rows").get(row).get(end).asText();
    }

    private static final String PROJECTION = "/inv/projection";

    @Test
    void everyExplorationReadShowsAliasesAndNeverARawIdWhenTheSpaceMasks(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, plain())) {
            settings(c, "masking_mode: all\n");

            JsonNode proj = ok(c, "POST", PROJECTION, "{" + BASE + "}", false);
            assertEquals("all", proj.at("/masking/mode").asText());
            assertTrue(proj.at("/masking/masked").asBoolean());
            assertEquals(3, proj.get("rows").size());
            for (JsonNode r : proj.get("rows")) {
                assertTrue(r.get("source").asText().startsWith("masked:"), r.toString());
                assertTrue(r.get("target").asText().startsWith("masked:"), r.toString());
            }

            String aliasA = aliasOf(proj, 0, "source");
            JsonNode nb = ok(c, "POST", PROJECTION + "/neighbors", "{" + BASE + ",\"value\":\"" + aliasA + "\"}", false);
            assertEquals(2, nb.get("rows").size(), "A's neighbours (B, C) are found through its alias");
            List<String> seen = new ArrayList<>();
            for (JsonNode r : nb.get("rows")) {
                seen.add(r.get("source").asText());
                seen.add(r.get("target").asText());
            }
            assertTrue(seen.stream().allMatch(s -> s.startsWith("masked:")), seen.toString());

            JsonNode multi = ok(c, "POST", PROJECTION + "/multi", "{\"nodes\":[{\"dataset\":\"calls_ds\",\"idColumn\":\"caller\","
                    + "\"labelColumn\":\"caller\"}],\"edges\":[{\"dataset\":\"calls_ds\",\"sourceColumn\":\"caller\","
                    + "\"targetColumn\":\"callee\"}]}", false);
            assertTrue(multi.get("nodes").size() > 0);
            for (JsonNode n : multi.get("nodes")) {
                assertTrue(n.get("id").asText().startsWith("masked:"), n.toString());
                assertEquals(n.get("id").asText(), n.get("label").asText(), "the label column must not restate the raw id");
            }
            assertTrue(multi.at("/mappings/0/masking/masked").asBoolean());

            JsonNode paths = ok(c, "POST", "/inv/traversal/recursive-paths",
                    "{" + BASE + ",\"startNode\":\"" + aliasA + "\",\"maxDepth\":3}", false);
            assertTrue(paths.get("paths").size() >= 2, paths.toString());
            for (JsonNode p : paths.get("paths"))
                for (JsonNode n : p.get("nodes")) assertTrue(n.asText().startsWith("masked:"), n.toString());

            JsonNode burst = ok(c, "POST", "/inv/pattern/temporal", "{" + BASE + ",\"timeCol\":\"ts\",\"mode\":\"burst\","
                    + "\"windowSeconds\":60,\"minEvents\":2}", false);
            assertTrue(burst.get("results").size() >= 1, burst.toString());
            assertTrue(burst.get("results").get(0).get("source").asText().startsWith("masked:"));

            JsonNode measure = ok(c, "GET", "/inv/value-measures?dataset=calls_ds&sourceCol=caller&targetCol=callee"
                    + "&linkKindCol=channel&name=valueWeightedLinks&valueCol=amt&timeCol=ts&from=2026-09-24T00:00:00Z&to=2026-09-25T00:00:00Z", null, false);
            assertEquals("all", measure.at("/masking/mode").asText());
            for (JsonNode e : measure.get("entities")) {
                for (String k : List.of("source", "target"))
                    if (e.has(k)) assertTrue(e.get(k).asText().startsWith("masked:"), e.toString());
            }

            // one alias per id across every read (the Space's one key), so the canvas and the picker agree
            assertEquals(aliasA, burst.get("results").get(0).get("source").asText());
        }
    }

    @Test
    void anAliasTheServerNeverServedIsRefusedNotGuessed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, plain())) {
            settings(c, "masking_mode: all\n");
            HttpResponse<String> r = send(c, "POST", PROJECTION + "/neighbors",
                    "{" + BASE + ",\"value\":\"masked:0000000000000000\"}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("not known"), r.body());
            // positive twin: the same call with a served alias succeeds
            JsonNode proj = ok(c, "POST", PROJECTION, "{" + BASE + "}", false);
            assertEquals(200, send(c, "POST", PROJECTION + "/neighbors",
                    "{" + BASE + ",\"value\":\"" + aliasOf(proj, 0, "source") + "\"}").statusCode());
        }
    }

    @Test
    void noneShowsRawAndTypedFollowsTheColumnClassification(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, plain())) {
            settings(c, "masking_mode: none\n");
            JsonNode raw = ok(c, "POST", PROJECTION, "{" + BASE + "}", true);
            assertEquals(A, raw.get("rows").get(0).get("source").asText());
            assertFalse(raw.at("/masking/masked").asBoolean());

            settings(c, "masking_mode: typed\n");
            JsonNode untyped = ok(c, "POST", PROJECTION, "{" + BASE + "}", true);
            assertEquals(A, untyped.get("rows").get(0).get("source").asText(), "typed, and no bound column is typed");
            assertEquals("typed", untyped.at("/masking/mode").asText());
        }
        try (Ctx c = open(cfg, root.resolve("typed-col"), classified("MSISDN"))) {
            JsonNode masked = ok(c, "POST", PROJECTION, "{" + BASE + "}", false);   // the default mode IS typed
            assertEquals("typed", masked.at("/masking/mode").asText());
            assertTrue(masked.get("rows").get(0).get("source").asText().startsWith("masked:"));
        }
        // a column classified as an UNmasked Entity Type is not masked (positive twin of the MSISDN case)
        try (Ctx c = open(cfg, root.resolve("typed-open"), classified("HANDSET"))) {
            JsonNode open = ok(c, "POST", PROJECTION, "{" + BASE + "}", true);
            assertEquals(A, open.get("rows").get(0).get("source").asText());
        }
    }

    @Test
    void anExplorationAliasSeedsAnInvestigationAndTheWorkingSetNamesItsExplorationAlias(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root, plain())) {
            settings(c, "masking_mode: all\n");
            JsonNode proj = ok(c, "POST", PROJECTION, "{" + BASE + "}", false);
            String aliasA = aliasOf(proj, 0, "source");

            assertEquals(200, send(c, "POST", "/inv/investigations", "{\"id\":\"case-a\",\"purpose\":\"DR-D2 seed by alias\","
                    + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}").statusCode());
            HttpResponse<String> seed = send(c, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"" + aliasA + "\"]}");
            assertEquals(200, seed.statusCode(), seed.body());
            for (String raw : RAW) assertFalse(seed.body().contains(raw), "the seed answer leaked " + raw);

            // the SEALED log binds the raw id (replay and custody are unchanged)
            String log = Files.readString(root.resolve("audit/snapshots/investigations/case-a/log.jsonl"));
            assertTrue(log.contains(A), "the sealed log still binds the raw id");
            assertFalse(log.contains(aliasA), "an alias is never sealed");

            JsonNode ws = ok(c, "GET", "/inv/investigations/case-a/working-set", null, false);
            String invAlias = ws.at("/rows/0/entityId").asText();
            assertTrue(invAlias.startsWith("masked:"));
            assertNotEquals(aliasA, invAlias, "an Investigation keeps its own key (§3.4)");
            assertEquals(aliasA, ws.at("/exploreAliases").get(invAlias).asText(),
                    "the Working Set names the exploration alias of each masked entity, so the UI keeps them one entity");
            JsonNode replay = ok(c, "POST", "/inv/investigations/case-a/replay", "{}", false);
            assertEquals(aliasA, replay.at("/workingSet/exploreAliases").get(invAlias).asText(), "the replay carries it too");
        }
    }

    @Test
    void anExplorationAliasTheWorkingSetNamesIsResolvableWithoutAnyExplorationRead(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        // LA-EXPAND-PICK-NEIGHBOURS-1 (live finding): an Investigation seeded by RAW id has never had an exploration read mint
        // its Space alias, yet the Working Set names that alias - the Neighbours list sends it back, so it must resolve.
        try (Ctx c = open(cfg, root, plain())) {
            settings(c, "masking_mode: all\n");
            assertEquals(200, send(c, "POST", "/inv/investigations", "{\"id\":\"case-n\",\"purpose\":\"alias resolves\","
                    + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}").statusCode());
            assertEquals(200, send(c, "POST", "/inv/investigations/case-n/ops",
                    "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}").statusCode());
            JsonNode ws = ok(c, "GET", "/inv/investigations/case-n/working-set", null, false);
            String invAlias = ws.at("/rows/0/entityId").asText();
            String spaceAlias = ws.at("/exploreAliases").get(invAlias).asText();
            JsonNode nb = ok(c, "POST", PROJECTION + "/neighbors", "{" + BASE + ",\"value\":\"" + spaceAlias + "\"}", false);
            assertTrue(nb.get("rows").size() > 0, "the alias the Working Set named resolved to its entity: " + nb);
            for (String raw : RAW) assertFalse(nb.toString().contains(raw), "the neighbours read leaked " + raw);
        }
    }

    /**
     * Live walk-through 6: a pattern run on an Investigation canvas ({@code investigationId}) answers that Investigation's
     * pseudonyms — the Working Set's — so its matches line up with the canvas; a non-member is told nothing exists; and
     * without {@code investigationId} the route still answers the Space's exploration aliases.
     */
    @Test
    void aBranchingPatternRunOnAnInvestigationAnswersItsWorkingSetPseudonyms(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, plain())) {
            settings(c, "masking_mode: all\n");
            Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
                case "Bearer stranger" -> Optional.of(new Subject("analyst-3", Set.of("canManageIncidents")));
                default -> Optional.empty();
            });
            String own = "Bearer owner";
            JsonNode proj = JSON.readTree(send(c, "POST", PROJECTION, "{" + BASE + "}", own).body()).get("data");
            String aliasA = aliasOf(proj, 0, "source");
            assertEquals(200, send(c, "POST", "/inv/investigations", "{\"id\":\"canvas\",\"purpose\":\"pattern on canvas\","
                    + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", own).statusCode());
            assertEquals(200, send(c, "POST", "/inv/investigations/canvas/ops", "{\"op\":\"seed\",\"ids\":[\"" + aliasA + "\"]}", own).statusCode());
            assertEquals(200, send(c, "POST", "/inv/investigations/canvas/ops", "{\"op\":\"expand\",\"ids\":[\"" + aliasA + "\"]}", own).statusCode());
            List<String> wsIds = new ArrayList<>();
            for (JsonNode r : JSON.readTree(send(c, "GET", "/inv/investigations/canvas/working-set", null, own).body()).at("/data/rows"))
                wsIds.add(r.get("entityId").asText());
            assertEquals(3, wsIds.size(), wsIds.toString());   // A, and its neighbours B, C

            String pattern = "{" + BASE + ",\"stages\":[{\"shape\":\"fan-out\",\"minBranches\":2}]";
            HttpResponse<String> onCanvas = send(c, "POST", "/inv/pattern/branching", pattern + ",\"investigationId\":\"canvas\"}", own);
            assertEquals(200, onCanvas.statusCode(), onCanvas.body());
            for (String raw : RAW) assertFalse(onCanvas.body().contains(raw), "leaked " + raw + ": " + onCanvas.body());
            JsonNode m = JSON.readTree(onCanvas.body()).at("/data/matches/0/nodeIds");
            assertTrue(m.size() >= 3, m.toString());
            for (JsonNode id : m) assertTrue(wsIds.contains(id.asText()), id + " is not a Working Set pseudonym " + wsIds);
            assertFalse(wsIds.contains(aliasA), "the canvas never shows the exploration alias");

            HttpResponse<String> stranger = send(c, "POST", "/inv/pattern/branching", pattern + ",\"investigationId\":\"canvas\"}", "Bearer stranger");
            assertEquals(404, stranger.statusCode(), "a non-member is told nothing exists: " + stranger.body());

            HttpResponse<String> plainRun = send(c, "POST", "/inv/pattern/branching", pattern + "}", own);
            assertEquals(200, plainRun.statusCode(), plainRun.body());
            JsonNode pm = JSON.readTree(plainRun.body()).at("/data/matches/0/nodeIds");
            assertTrue(pm.toString().contains(aliasA), "without investigationId the exploration alias is unchanged: " + pm);
            for (JsonNode id : pm) assertFalse(wsIds.contains(id.asText()), id.toString());
        } finally {
            Authenticators.forTest(null);
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    /**
     * DR-T4: a SWEEP, not a sample. On a Space with {@code masking_mode all}, after an Investigation holds a seed, an
     * expand and an annotation over the planted raw ids, every {@code /inv} read below must answer 200 (so a route that
     * quietly started 404/500ing cannot hide a leak) and its whole body is scanned for each raw id.
     */
    @Test
    void everyInvReadOnAMaskedSpaceIsScannedForRawIds(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // Case management present, with a history whose title names raw ids: /inv/case-history must re-mask it
        CaseTeamObjectEngine.arm();
        CaseTeamObjectEngine.HISTORY = List.of(Map.of("kind", "incident", "id", "INC-1", "title", "calls spike " + A + " -> +" + B,
                "status", "OPEN", "createdAt", 1L, "matchedOn", "key.caller_msisdn"));
        try (Ctx c = open(cfg, root, plain())) {
            settings(c, "masking_mode: all\n");
            String aliasA = aliasOf(ok(c, "POST", PROJECTION, "{" + BASE + "}", false), 0, "source");
            assertEquals(200, send(c, "POST", "/inv/investigations", "{\"id\":\"sweep\",\"purpose\":\"DR-T4 sweep\","
                    + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"timeCol\":\"ts\"}").statusCode());
            for (String op : List.of("{\"op\":\"seed\",\"ids\":[\"" + aliasA + "\"]}", "{\"op\":\"expand\",\"ids\":[\"" + aliasA + "\"]}")) {
                HttpResponse<String> r = send(c, "POST", "/inv/investigations/sweep/ops", op);
                assertEquals(200, r.statusCode(), op + " -> " + r.body());
                for (String raw : RAW) assertFalse(r.body().contains(raw), "op answer leaked " + raw + ": " + r.body());
            }
            String inv = "/inv/investigations";
            List<String[]> reads = List.of(
                    new String[]{"GET", inv, null}, new String[]{"GET", inv + "/sweep/log", null},
                    new String[]{"GET", inv + "/sweep/working-set", null}, new String[]{"GET", inv + "/sweep/coverage?from=2026-09-24T00:00:00Z&to=2026-09-25T00:00:00Z", null},
                    new String[]{"GET", inv + "/sweep/members", null}, new String[]{"GET", inv + "/sweep/references", null},
                    new String[]{"POST", inv + "/sweep/replay", "{}"}, new String[]{"GET", inv + "/sweep/dossier", null},
                    new String[]{"GET", inv + "/sweep/drafts", null}, new String[]{"GET", "/inv/entity-identities", null},
                    new String[]{"GET", "/inv/graph/algorithms", null}, new String[]{"GET", "/inv/graph/runs", null},
                    new String[]{"GET", "/inv/index", null}, new String[]{"GET", "/inv/snapshots", null},
                    new String[]{"GET", "/inv/investigation-templates", null}, new String[]{"GET", "/inv/schema/relationships", null},
                    new String[]{"POST", "/inv/reference-rows", "{\"dataset\":\"calls_ds\",\"keyCol\":\"caller\",\"keys\":[\""
                            + aliasA + "\",\"" + A + "\"]}"},
                    new String[]{"POST", "/inv/case-history", "{\"key\":\"" + aliasA + "\"}"},
                    new String[]{"POST", "/inv/case-history", "{\"key\":\"" + aliasA + "\",\"investigationId\":\"sweep\"}"},
                    new String[]{"POST", "/inv/case-history", "{\"key\":\"" + A + "\"}"});
            for (String[] rd : reads) ok(c, rd[0], rd[1], rd[2], false);
        } finally {
            CaseTeamObjectEngine.CASES = null;
            CaseTeamObjectEngine.HISTORY = null;
        }
    }
}
