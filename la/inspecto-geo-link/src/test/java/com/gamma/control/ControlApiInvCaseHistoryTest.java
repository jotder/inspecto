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
    /**
     * The caps the cap tests run under: the Space STATES them (case_history.max_linked_investigations / max_attachments,
     * LA-CONFIG-LIMITS-1), below the catalogue defaults of 200 / 1,000, so these tests also prove the route reads the Space's value.
     */
    private static final int CAP_INVESTIGATIONS = 100, CAP_ATTACHMENTS = 500;
    private static final String CAPS = "case_history:\n  max_linked_investigations: " + CAP_INVESTIGATIONS
            + "\n  max_attachments: " + CAP_ATTACHMENTS + "\n";
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
            assertEquals("calls spike " + aliasA + " -> [number]", title, "another number is a placeholder, not an alias");
            assertEquals("INC-1", all.at("/objects/0/via").asText());
        }
    }

    @Test
    void titlesAreScrubbedFailClosedAndRowsCarryOnlyAllowlistedFields(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            String aliasA = aliasOfA(c);
            List<String[]> cases = List.of(
                    new String[]{"+27 820 000 001 called", aliasA + " called"},
                    new String[]{"27-820-000-001 called", aliasA + " called"},
                    new String[]{"27.820.000.001 called", aliasA + " called"},
                    new String[]{"(27) 820 000001 called", "(" + aliasA + " called"},
                    new String[]{"+27820000001", aliasA},
                    new String[]{"imei 35-209900-176148-1", "imei [number]"},
                    new String[]{"mail ana.k@example.com now", "mail [email] now"},
                    new String[]{"ref 1234 short", "ref 1234 short"},
                    new String[]{"longer 127820000001 x", "longer [number] x"},
                    new String[]{"doc AB-123456", "doc AB-[number]"});
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (int i = 0; i < cases.size(); i++)
                rows.add(Map.of("kind", "incident", "id", "INC-" + i, "title", cases.get(i)[0], "status", "OPEN",
                        "createdAt", (long) (100 - i), "matchedOn", "key.msisdn", "secret", "leak-" + A));
            CaseTeamObjectEngine.HISTORY = rows;
            java.lang.reflect.Field f = Class.forName("com.gamma.la.api.ExplorationMasking").getDeclaredField("BOOK");
            f.setAccessible(true);
            int before = ((Map<?, ?>) f.get(null)).size();
            JsonNode d = ok(c, "{\"key\":\"" + aliasA + "\"}", false);
            assertEquals(before, ((Map<?, ?>) f.get(null)).size(), "titles mint no aliases");
            for (int i = 0; i < cases.size(); i++) {
                assertEquals(cases.get(i)[1], d.at("/objects/" + i + "/title").asText(), cases.get(i)[0]);
                assertFalse(d.at("/objects/" + i).has("secret"), "only allowlisted fields are copied");
            }
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
    void anExplicitEntityTypeWinsAndAnAliasStillResolvesOnlyWhenThatTypeIsCovered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            String aliasA = aliasOfA(c);
            settings(c, "masking_mode: typed\n");
            // account is a masked type: the Entity page of a masked account looks its alias up AS an account, not an msisdn
            JsonNode acct = ok(c, "{\"key\":\"" + aliasA + "\",\"entityType\":\"ACCOUNT\"}", false);
            assertTrue(acct.get("resolved").asBoolean(), acct.toString());
            assertEquals("account", acct.get("entityType").asText(), "the canonical id of the type asked");
            assertEquals(List.of("account|" + A + "|all|50"), CaseTeamObjectEngine.ASKED);
            CaseTeamObjectEngine.ASKED.clear();

            // cell is not masked: an alias asked as a cell never resolves (no oracle), even with no Investigation
            JsonNode cell = ok(c, "{\"key\":\"" + aliasA + "\",\"entityType\":\"cell\"}", false);
            assertFalse(cell.get("resolved").asBoolean());
            assertTrue(CaseTeamObjectEngine.ASKED.isEmpty());

            // the explicit type beats the Investigation's header type
            assertEquals(200, send(c, "/inv/investigations", "{\"id\":\"cells\",\"purpose\":\"case history\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"entityType\":\"cell\"}").statusCode());
            JsonNode over = ok(c, "{\"key\":\"" + aliasA + "\",\"investigationId\":\"cells\",\"entityType\":\"account\"}", false);
            assertTrue(over.get("resolved").asBoolean(), over.toString());
            assertEquals("account", over.get("entityType").asText());

            HttpResponse<String> unknown = send(c, ROUTE, "{\"key\":\"" + aliasA + "\",\"entityType\":\"spaceship\"}");
            assertEquals(422, unknown.statusCode(), unknown.body());
            assertFalse(unknown.body().contains(A));
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

    /** The live walk-through defect: Attach to Case registers no entity reference, so the host's read alone misses the Case. */
    @Test
    void aCaseTheGraphWasAttachedToIsListedViaSnapshotAndInvestigationLinkOnlyForAReader(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
                case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
                case "Bearer stranger" -> Optional.of(new Subject("analyst-3", Set.of("canManageIncidents")));
                default -> Optional.empty();
            });
            String own = "Bearer owner";
            CaseTeamObjectEngine.HISTORY = List.of();   // the host knows no Incident for the entity
            Map<String, Map<String, Object>> cases = CaseTeamObjectEngine.CASES;
            Map<String, Object> linkedCase = new java.util.HashMap<>(CaseTeamObjectEngine.caseOf("CASE-9", "analyst-1", "analyst-2", false));
            linkedCase.put("title", "fraud ring +" + A);
            cases.put("CASE-9", linkedCase);
            cases.put("CASE-8", CaseTeamObjectEngine.caseOf("CASE-8", "analyst-1", "analyst-2", false));
            String aliasA = aliasOfA(c, own);
            assertEquals(200, send(c, "/inv/investigations", "{\"id\":\"inv-a\",\"purpose\":\"case history\","
                    + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", own).statusCode());
            assertEquals(200, send(c, "/inv/investigations/inv-a/ops", "{\"op\":\"seed\",\"ids\":[\"" + aliasA + "\"]}", own).statusCode());

            // one-step Attach to Case: seal, attach, link
            HttpResponse<String> sealed = send(c, "/inv/snapshots", "{\"id\":\"snap-a\",\"investigationId\":\"inv-a\","
                    + "\"manifestHash\":\"h\",\"nodes\":[{\"id\":\"" + aliasA + "\"}],\"edges\":[]}", own);
            assertEquals(200, sealed.statusCode(), sealed.body());
            assertEquals(200, send(c, "/inv/snapshots/attach", "{\"snapshotId\":\"snap-a\",\"caseId\":\"CASE-8\"}", own).statusCode());
            HttpRequest put = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/inv/investigations/inv-a/case"))
                    .header("Content-Type", "application/json").header("Authorization", own)
                    .PUT(BodyPublishers.ofString("{\"caseRef\":\"CASE-9\"}")).build();
            HttpResponse<String> linked = client.send(put, BodyHandlers.ofString());
            assertEquals(200, linked.statusCode(), linked.body());

            String body = "{\"key\":\"" + aliasA + "\",\"investigationId\":\"inv-a\",\"status\":\"all\"}";
            HttpResponse<String> r = send(c, ROUTE, body, own);
            assertEquals(200, r.statusCode(), r.body());
            for (String raw : RAW) assertFalse(r.body().contains(raw), "leaked " + raw + ": " + r.body());
            JsonNode d = JSON.readTree(r.body()).get("data");
            assertEquals(2, d.get("objects").size(), d.toString());
            assertEquals(2, d.get("total").asInt());
            Map<String, JsonNode> byId = new java.util.HashMap<>();
            d.get("objects").forEach(o -> byId.put(o.get("id").asText(), o));
            assertEquals("investigation", byId.get("CASE-9").get("matchedOn").asText());
            assertEquals("inv-a", byId.get("CASE-9").get("via").asText());
            assertEquals("fraud ring " + aliasA, byId.get("CASE-9").get("title").asText(), "a linked Case's title is scrubbed too");
            assertEquals("snapshot", byId.get("CASE-8").get("matchedOn").asText());
            assertEquals("snap-a", byId.get("CASE-8").get("via").asText());
            assertFalse(byId.get("CASE-8").has("owner"), "only allowlisted fields");

            // an entity outside the graph matches nothing; an open-only read keeps open Cases; closed-only drops them
            assertEquals(0, JSON.readTree(send(c, ROUTE, "{\"key\":\"" + aliasA + "\",\"status\":\"closed\"}", own).body())
                    .at("/data/objects").size());

            // a non-member cannot read inv-a (nor the snapshot anchored to it): neither Case is revealed
            HttpResponse<String> stranger = send(c, ROUTE, "{\"key\":\"" + aliasA + "\"}", "Bearer stranger");
            assertEquals(200, stranger.statusCode(), stranger.body());
            assertEquals(0, JSON.readTree(stranger.body()).at("/data/objects").size(), stranger.body());
            assertFalse(stranger.body().contains("CASE-") || stranger.body().contains("inv-a") || stranger.body().contains("snap-a"),
                    stranger.body());
        }
    }

    private void armCaps(Ctx c) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer stranger" -> Optional.of(new Subject("analyst-3", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-4", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
        CaseTeamObjectEngine.HISTORY = List.of();
        CaseTeamObjectEngine.CASES.put("CASE-O", CaseTeamObjectEngine.caseOf("CASE-O", "analyst-1", "analyst-2", false));
        CaseTeamObjectEngine.CASES.put("CASE-S", CaseTeamObjectEngine.caseOf("CASE-S", "analyst-3", "analyst-2", false));
    }

    /** An Investigation {@code id} of {@code auth} seeded with A's alias, linked to {@code caseRef}; returns A's alias for that caller. */
    private String linked(Ctx c, String auth, String alias, String id, String caseRef) throws Exception {
        create(c, auth, id);
        assertEquals(200, send(c, "/inv/investigations/" + id + "/ops", "{\"op\":\"seed\",\"ids\":[\"" + alias + "\"]}", auth).statusCode());
        linkCase(c, auth, id, caseRef);
        return alias;
    }

    private void create(Ctx c, String auth, String id) throws Exception {
        assertEquals(200, send(c, "/inv/investigations", "{\"id\":\"" + id + "\",\"purpose\":\"caps\",\"dataset\":\"calls_ds\","
                + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", auth).statusCode());
    }

    private void linkCase(Ctx c, String auth, String id, String caseRef) throws Exception {
        HttpRequest put = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1/inv/investigations/" + id + "/case"))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .PUT(BodyPublishers.ofString("{\"caseRef\":\"" + caseRef + "\"}")).build();
        assertEquals(200, client.send(put, BodyHandlers.ofString()).statusCode());
    }

    private JsonNode history(Ctx c, String alias, String auth) throws Exception {
        HttpResponse<String> r = send(c, ROUTE, "{\"key\":\"" + alias + "\",\"status\":\"all\"}", auth);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** Item (6): the caps count only records the caller may read, so hidden Investigations neither crowd out nor flag. */
    @Test
    void hiddenCaseLinkedInvestigationsNeitherConsumeTheCapNorSetTruncated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n" + CAPS)) {
            armCaps(c);
            // the stranger's readable one is the OLDEST id; 101 hidden ones (> the cap of 100) follow
            String alias = aliasOfA(c, "Bearer stranger"), ownerAlias = aliasOfA(c, "Bearer owner");
            linked(c, "Bearer stranger", alias, "inv-0-own", "CASE-S");
            for (int i = 0; i < CAP_INVESTIGATIONS + 1; i++) linked(c, "Bearer owner", ownerAlias, "inv-h-" + i, "CASE-O");
            JsonNode d = history(c, alias, "Bearer stranger");
            assertEquals(1, d.get("objects").size(), d.toString());
            assertEquals("CASE-S", d.at("/objects/0/id").asText());
            assertFalse(d.get("truncated").asBoolean(), "hidden records must not set the flag: " + d);
        }
    }

    @Test
    void boundaryPinInvestigationsTruncateAtOneAdmittedBeyondTheCap(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n" + CAPS)) {
            armCaps(c);
            String alias = aliasOfA(c, "Bearer stranger");
            int cap = CAP_INVESTIGATIONS;
            for (int i = 0; i < cap; i++) linked(c, "Bearer stranger", alias, "inv-s-" + i, "CASE-S");
            assertFalse(history(c, alias, "Bearer stranger").get("truncated").asBoolean(), "exactly the cap is not a cut");
            linked(c, "Bearer stranger", alias, "inv-s-extra", "CASE-S");
            assertTrue(history(c, alias, "Bearer stranger").get("truncated").asBoolean(), "one admitted beyond the cap is a cut");
        }
    }

    @Test
    void hiddenSnapshotAttachmentsNeitherConsumeTheCapNorSetTruncated(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n" + CAPS)) {
            armCaps(c);
            String alias = aliasOfA(c, "Bearer stranger");
            create(c, "Bearer stranger", "inv-s");   // NOT linked to a Case: CASE-S is reachable only via the snapshot attachment
            linked(c, "Bearer owner", aliasOfA(c, "Bearer owner"), "inv-o", "CASE-O");
            var store = new com.gamma.la.core.SnapshotStore(root);
            store.create("snap-s", "{\"investigationId\":\"inv-s\",\"nodes\":[{\"id\":\"" + alias + "\"}]}");
            store.create("snap-h", "{\"investigationId\":\"inv-o\",\"nodes\":[{\"id\":\"x\"}]}");
            store.attach("snap-s", "CASE-S", "2026-10-12T00:00:00Z");                       // the OLDEST line
            for (int i = 0; i < CAP_ATTACHMENTS + 1; i++) store.attach("snap-h", "CASE-O-" + i, "2026-10-12T00:00:01Z");
            JsonNode d = history(c, alias, "Bearer stranger");
            assertEquals(1, d.get("objects").size(), d.toString());
            assertEquals("CASE-S", d.at("/objects/0/id").asText(), d.toString());
            assertEquals("snapshot", d.at("/objects/0/matchedOn").asText(), "reached via the snapshot path only: " + d);
            assertFalse(d.get("truncated").asBoolean(), "hidden attachments must not set the flag: " + d);
        }
    }

    @Test
    void boundaryPinAttachmentsTruncateAtOneAdmittedBeyondTheCap(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n" + CAPS)) {
            armCaps(c);
            String alias = aliasOfA(c, "Bearer stranger");
            assertEquals(200, send(c, "/inv/investigations", "{\"id\":\"inv-s\",\"purpose\":\"caps\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", "Bearer stranger").statusCode());
            var store = new com.gamma.la.core.SnapshotStore(root);
            store.create("snap-s", "{\"investigationId\":\"inv-s\",\"nodes\":[{\"id\":\"" + alias + "\"}]}");
            store.attach("snap-s", "CASE-S", "2026-10-12T00:00:00Z");
            for (int i = 0; i < CAP_ATTACHMENTS - 1; i++) store.attach("snap-s", "CASE-S-" + i, "2026-10-12T00:00:01Z");
            assertFalse(history(c, alias, "Bearer stranger").get("truncated").asBoolean(), "exactly the cap is not a cut");
            store.attach("snap-s", "CASE-S-extra", "2026-10-12T00:00:02Z");
            assertTrue(history(c, alias, "Bearer stranger").get("truncated").asBoolean(), "an admitted record beyond the cap is a cut");
        }
    }

    /** The 5x DoS bound: readable records beyond the scan window are not seen; the flag follows the documented rule. */
    @Test
    void investigationScanBoundSetsTruncatedOnlyForACallerWithAnAdmittedRecord(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n" + CAPS)) {
            armCaps(c);
            String alias = aliasOfA(c, "Bearer stranger"), ownerAlias = aliasOfA(c, "Bearer owner"), otherAlias = aliasOfA(c, "Bearer other");
            linked(c, "Bearer stranger", alias, "inv-0-own", "CASE-S");                          // inside the window
            for (int i = 0; i < CAP_INVESTIGATIONS * 5 + 1; i++) {                               // hidden: fills the whole window
                create(c, "Bearer owner", "inv-m-" + i);
                linkCase(c, "Bearer owner", "inv-m-" + i, "CASE-O");
            }
            create(c, "Bearer stranger", "inv-z-own");
            linkCase(c, "Bearer stranger", "inv-z-own", "CASE-S");                              // beyond the window
            JsonNode d = history(c, alias, "Bearer stranger");
            assertEquals(1, d.get("objects").size(), d.toString());
            assertTrue(d.get("truncated").asBoolean(), "a caller with an admitted record is told the scan was cut: " + d);
            JsonNode none = history(c, otherAlias, "Bearer other");
            assertEquals(0, none.get("objects").size(), none.toString());
            assertFalse(none.get("truncated").asBoolean(), "a caller with nothing admitted learns nothing: " + none);
        }
    }

    @Test
    void attachmentScanBoundSetsTruncatedOnlyForACallerWithAnAdmittedRecord(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n" + CAPS)) {
            armCaps(c);
            String alias = aliasOfA(c, "Bearer stranger"), otherAlias = aliasOfA(c, "Bearer other");
            create(c, "Bearer stranger", "inv-s");
            linked(c, "Bearer owner", aliasOfA(c, "Bearer owner"), "inv-o", "CASE-O");
            var store = new com.gamma.la.core.SnapshotStore(root);
            store.create("snap-s", "{\"investigationId\":\"inv-s\",\"nodes\":[{\"id\":\"" + alias + "\"}]}");
            store.create("snap-h", "{\"investigationId\":\"inv-o\",\"nodes\":[{\"id\":\"x\"}]}");
            store.attach("snap-s", "CASE-S", "2026-10-12T00:00:00Z");                                   // oldest: beyond the window
            for (int i = 0; i < CAP_ATTACHMENTS * 5; i++) store.attach("snap-h", "CASE-O-" + i, "2026-10-12T00:00:01Z");
            store.attach("snap-s", "CASE-S", "2026-10-12T00:00:02Z");                                   // newest: admitted, in the window
            JsonNode d = history(c, alias, "Bearer stranger");
            assertEquals(1, d.get("objects").size(), d.toString());
            assertEquals("snapshot", d.at("/objects/0/matchedOn").asText());
            assertTrue(d.get("truncated").asBoolean(), "a caller with an admitted record is told the scan was cut: " + d);
            JsonNode none = history(c, otherAlias, "Bearer other");
            assertEquals(0, none.get("objects").size(), none.toString());
            assertFalse(none.get("truncated").asBoolean(), "a caller with nothing admitted learns nothing: " + none);
        }
    }

    @Test
    void gates503422And404(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            for (String bad : List.of("{}", "{\"key\":\" \"}", "{\"key\":1}", "{\"key\":\"" + "9".repeat(257) + "\"}",
                    "{\"key\":\"" + A + "\",\"status\":\"pending\"}", "{\"key\":\"" + A + "\",\"limit\":0}",
                    "{\"key\":\"" + A + "\",\"limit\":401}", "{\"key\":\"" + A + "\",\"limit\":\"x\"}"))
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
