package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
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
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-19's operator decisions of 2026-09-24, over real HTTP: the stated {@code purpose} (D-U5), entity masking and
 * per-entity reveal (D-U6), four-eyes on sensitive expands (D-U7), and the Admiralty grade on annotations (D-U9).
 * Every capability gate is exercised WITH an armed Authenticator, because with no Subject {@code withCapability} is a
 * no-op and a test would pass against an ungated route.
 *
 * <p><b>Fixture.</b> Phone-number-shaped ids, deliberately: masking replaces an id wherever it stands as a whole
 * token in prose, so a one-letter id would also match the English article "a".
 */
class ControlApiInvestigationOversightTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003", D = "27820000004";
    private static final String ROWS = String.join(",", "('" + A + "','" + B + "','voice')",
            "('" + A + "','" + C + "','sms')", "('" + B + "','" + D + "','voice')");
    private static final String CREATE = "{\"id\":\"case-a\",\"purpose\":\"Fraud referral FR-7, s.12 warrant\","
            + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String OPS = "/inv/investigations/case-a/ops";
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
                    "SELECT caller, callee, channel FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)",
                    "2026-09-24T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", dataset);
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        return open(configDir, writeRoot, Map.of("view", "calls_view"));
    }

    /** The Space's link-analysis.toon, written where SettingsRoutes writes it (its round trip is ControlApiSettingsTest's). */
    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), toon);
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return data(send(c.port, "POST", path, body, null));
    }

    private JsonNode get(Ctx c, String path) throws Exception {
        return data(send(c.port, "GET", path, null, null));
    }

    private Set<String> entityIds(Ctx c, String auth) throws Exception {
        Set<String> out = new TreeSet<>();
        for (JsonNode r : data(send(c.port, "GET", "/inv/investigations/case-a/working-set", null, auth)).get("rows"))
            out.add(r.get("entityId").asText());
        return out;
    }

    // ── D-U5: purpose ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void purposeIsRequiredRecordedInTheSealedHeaderAndShownInTheDossier(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String noPurpose = CREATE.replace("\"purpose\":\"Fraud referral FR-7, s.12 warrant\",", "");
            HttpResponse<String> missing = send(c.port, "POST", "/inv/investigations", noPurpose, null);
            assertEquals(422, missing.statusCode(), missing.body());
            assertTrue(missing.body().contains("purpose"), missing.body());
            assertEquals(422, send(c.port, "POST", "/inv/investigations",
                    CREATE.replace("Fraud referral FR-7, s.12 warrant", "  "), null).statusCode(), "blank is missing");
            assertEquals(422, send(c.port, "POST", "/inv/investigations",
                    CREATE.replace("Fraud referral FR-7, s.12 warrant", "x".repeat(1_001)), null).statusCode(), "bounded");
            assertFalse(Files.exists(root.resolve("audit/snapshots/investigations/case-a")), "a refusal writes nothing");

            JsonNode header = post(c, "/inv/investigations", CREATE);
            assertEquals("Fraud referral FR-7, s.12 warrant", header.get("purpose").asText());
            assertTrue(Files.readString(root.resolve("audit/snapshots/investigations/case-a/header.json"))
                    .contains("\"purpose\":\"Fraud referral FR-7, s.12 warrant\""), "sealed in the write-once header");

            JsonNode dossier = get(c, "/inv/investigations/case-a/dossier");
            assertEquals("Fraud referral FR-7, s.12 warrant", dossier.at("/summary/purpose").asText());
            assertEquals("Fraud referral FR-7, s.12 warrant", dossier.at("/renderings/json/investigation/purpose").asText());
            String method = send(c.port, "GET", "/inv/investigations/case-a/dossier?format=method", null, null).body();
            assertTrue(method.contains("Stated purpose (legal basis, recorded and not enforced): Fraud referral FR-7, "
                    + "s.12 warrant."), method);

            // A fork inherits it (the parent's header is copied); a template instantiation must state its own.
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
            post(c, OPS, "{\"op\":\"expand\"}");
            JsonNode fork = post(c, "/inv/investigations/case-a/reorder", "{\"id\":\"case-f\",\"order\":[2,1]}");
            assertEquals("case-f", fork.get("id").asText());
            assertTrue(Files.readString(root.resolve("audit/snapshots/investigations/case-f/header.json"))
                    .contains("\"purpose\":\"Fraud referral FR-7, s.12 warrant\""));
            post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl\"}");
            assertEquals(422, send(c.port, "POST", "/inv/investigation-templates/tpl/instantiate",
                    "{\"id\":\"case-t\",\"params\":{\"seed1\":[\"" + A + "\"]}}", null).statusCode());
            assertEquals("second referral", post(c, "/inv/investigation-templates/tpl/instantiate",
                    "{\"id\":\"case-t\",\"purpose\":\"second referral\",\"params\":{\"seed1\":[\"" + A + "\"]}}")
                    .at("/header/purpose").asText());
        }
    }

    // ── D-U9: the Admiralty grade ──────────────────────────────────────────────────────────────────────

    @Test
    void confidenceIsAnAdmiraltyGradeValidatedStoredAndRendered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
            String ungraded = post(c, OPS, "{\"op\":\"annotate\",\"ids\":[\"" + A + "\"],\"note\":\"plain\"}")
                    .at("/workingSet/hash").asText();
            for (String bad : List.of("\"G1\"", "\"A7\"", "\"A\"", "\"2B\"", "0.9", "\"\""))
                assertEquals(422, send(c.port, "POST", OPS, "{\"op\":\"annotate\",\"ids\":[\"" + A + "\"],\"note\":\"n\","
                        + "\"confidence\":" + bad + "}", null).statusCode(), "not an Admiralty grade: " + bad);
            post(c, OPS, "{\"op\":\"annotate\",\"ids\":[\"" + A + "\"],\"note\":\"seen on CCTV\",\"confidence\":\"b2\"}");
            post(c, OPS, "{\"op\":\"annotate\",\"ids\":[\"" + A + "\"],\"note\":\"hearsay\",\"confidence\":\"F6\"}");

            JsonNode replay = post(c, "/inv/investigations/case-a/replay", "{}");
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            JsonNode notes = replay.at("/workingSet/annotations");
            assertEquals(3, notes.size());
            assertTrue(notes.get(0).path("confidence").isMissingNode(), "an ungraded note carries no grade key");
            assertEquals("B2", notes.get(1).get("confidence").asText(), "stored upper case");
            assertEquals("F6", notes.get(2).get("confidence").asText(), "F and 6 — cannot be judged — are on the scale");
            assertNotEquals(ungraded, replay.at("/workingSet/hash").asText());

            JsonNode log = get(c, "/inv/investigations/case-a/log");
            assertEquals("3. Annotated " + A + " graded B2 (source usually reliable, information probably true): "
                    + "\"seen on CCTV\"", log.at("/entries/2/text").asText());
            String steps = send(c.port, "GET", "/inv/investigations/case-a/dossier?format=steps", null, null).body();
            assertTrue(steps.contains("graded F6 (source reliability cannot be judged, information truth cannot be "
                    + "judged): \"hearsay\""), steps);
        }
    }

    // ── D-U6: masking ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void typedMaskingByDefaultMasksEntitiesSeededAsATypedIdentifier(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            JsonNode seeded = post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"],\"entityType\":\"msisdn\"}");
            String token = seeded.at("/delta/admitted/0").asText();
            assertTrue(token.startsWith("masked:"), seeded.toString());
            assertEquals("typed", seeded.at("/masking/mode").asText());
            assertEquals(1, seeded.at("/masking/masked").asInt());
            post(c, OPS, "{\"op\":\"expand\"}");

            // Only the typed seed is masked: its expand neighbours carry no type (entity typing, LA-17, is not built).
            assertEquals(Set.of(token, B, C), entityIds(c, null));
            JsonNode log = get(c, "/inv/investigations/case-a/log");
            assertFalse(log.toString().contains(A), "the log names the typed seed only by its pseudonym: " + log);
            assertEquals("1. Seeded 1 entity of type msisdn: " + token + ".", log.at("/entries/0/text").asText());
            String dossier = send(c.port, "GET", "/inv/investigations/case-a/dossier", null, null).body();
            assertFalse(dossier.contains(A), "nor does the dossier");
            String steps = send(c.port, "GET", "/inv/investigations/case-a/dossier?format=steps", null, null).body();
            assertFalse(steps.contains(A), steps);
            JsonNode manifest = get(c, "/inv/investigations/case-a/dossier").get("manifest");
            assertTrue(post(c, "/inv/investigations/case-a/dossier/verify", "{\"manifest\":" + manifest + "}")
                    .get("verified").asBoolean(), "custody still verifies — the manifest is over the raw store");
            assertTrue(Files.readString(root.resolve("audit/snapshots/investigations/case-a/log.jsonl")).contains(A),
                    "masking is render-time: the sealed log keeps the raw id");

            // The pseudonym is stable across responses, and an op may name it: it resolves to the entity.
            assertEquals(token, post(c, "/inv/investigations/case-a/replay", "{}").at("/workingSet/entities/0/id").asText());
            post(c, OPS, "{\"op\":\"annotate\",\"ids\":[\"" + token + "\"],\"note\":\"hub\"}");   // 200: accepted
            assertTrue(Files.readString(root.resolve("audit/snapshots/investigations/case-a/log.jsonl"))
                    .contains("\"ids\":[\"" + A + "\"],\"note\":\"hub\""), "…and sealed against the real entity");
        }
    }

    @Test
    void maskingModeIsASpaceSettingAndTypedHonoursAColumnClassification(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
            post(c, OPS, "{\"op\":\"expand\"}");
            assertEquals(Set.of(A, B, C), entityIds(c, null), "typed, and nothing is typed ⇒ nothing masked");

            settings(c, "masking_mode: all\n");
            Set<String> all = entityIds(c, null);
            assertEquals(3, all.size());
            assertTrue(all.stream().allMatch(s -> s.startsWith("masked:")), all.toString());
            assertEquals("all", get(c, "/inv/investigations/case-a/working-set").at("/masking/mode").asText());

            settings(c, "masking_mode: none\n");
            assertEquals(Set.of(A, B, C), entityIds(c, null));
            settings(c, "masking_mode: bogus\n");
            assertEquals("typed", get(c, "/inv/investigations/case-a/working-set").at("/masking/mode").asText(),
                    "an unreadable mode falls to the masking default, never to none");
        }
        // typed, with the bound column classified MSISDN on the Dataset: every id is masked (an id has no column).
        try (Ctx c = open(cfg, root.resolve("typed-col"), Map.of("view", "calls_view",
                "columns", List.of(Map.of("name", "caller", "classification", "MSISDN"))))) {
            post(c, "/inv/investigations", CREATE);
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
            post(c, OPS, "{\"op\":\"expand\"}");
            JsonNode ws = get(c, "/inv/investigations/case-a/working-set");
            assertTrue(ws.at("/masking/basis").asText().contains("[caller]"), ws.toString());
            for (JsonNode r : ws.get("rows")) assertTrue(r.get("entityId").asText().startsWith("masked:"), ws.toString());
        }
    }

    @Test
    void revealIsPerEntityGatedOnItsOwnCapabilityAndAudited(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer revealer" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents", "canRevealLinkEntities")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canRevealLinkEntities")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: all\n");
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}", "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", OPS, "{\"op\":\"expand\"}", "Bearer owner").statusCode());
            List<String> tokens = List.copyOf(entityIds(c, "Bearer owner"));
            String body = "{\"tokens\":[\"" + tokens.get(0) + "\",\"masked:0000000000000000\"]}";
            String reveal = "/inv/investigations/case-a/reveal";

            assertEquals(401, send(c.port, "POST", reveal, body, null).statusCode());
            assertEquals(403, send(c.port, "POST", reveal, body, "Bearer owner").statusCode(),
                    "owning the Investigation (canManageIncidents) is not enough to reveal");
            assertEquals(404, send(c.port, "POST", reveal, body, "Bearer other").statusCode(),
                    "the capability does not open someone else's Investigation");
            assertEquals(422, send(c.port, "POST", reveal, "{\"tokens\":[]}", "Bearer revealer").statusCode());

            JsonNode out = data(send(c.port, "POST", reveal, body, "Bearer revealer"));
            assertEquals(1, out.get("revealed").size(), "per entity: exactly the token asked for");
            assertEquals(tokens.get(0), out.at("/revealed/0/token").asText());
            assertTrue(Set.of(A, B, C).contains(out.at("/revealed/0/id").asText()), out.toString());
            assertEquals("[\"masked:0000000000000000\"]", out.get("unknown").toString(), "never guessed");
            assertTrue(entityIds(c, "Bearer revealer").contains(tokens.get(0)), "a reveal changes no later response");
        } finally {
            Authenticators.forTest(null);
        }
    }

    // ── D-U7: four-eyes ────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSensitiveExpandWaitsForADifferentPersonsApproval(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer self" -> Optional.of(new Subject("analyst-1", Set.of("canApproveLinkExpansions")));
            case "Bearer lead" -> Optional.of(new Subject("lead-1", Set.of("canApproveLinkExpansions")));
            case "Bearer plain" -> Optional.of(new Subject("lead-2", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            settings(c, "four_eyes_budget_above: 100\n");
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}", "Bearer owner").statusCode());

            JsonNode pending = data(send(c.port, "POST", OPS, "{\"op\":\"expand\",\"budget\":500}", "Bearer owner"));
            assertEquals("pending", pending.get("status").asText(), pending.toString());
            assertEquals("p1", pending.at("/pending/id").asText());
            assertEquals("[\"budget 500 > 100\"]", pending.at("/pending/sensitivity/exceeded").toString());
            Path log = root.resolve("audit/snapshots/investigations/case-a/log.jsonl");
            assertEquals(1, Files.readAllLines(log).size(), "a pending expand reads nothing and enters no log");
            assertEquals(409, send(c.port, "POST", OPS, "{\"op\":\"expand\",\"budget\":600}", "Bearer owner").statusCode(),
                    "one pending request at a time");
            JsonNode ownersLog = data(send(c.port, "GET", "/inv/investigations/case-a/log", null, "Bearer owner"));
            assertEquals("pending", ownersLog.at("/pending/0/status").asText());

            String approve = "/inv/investigations/case-a/pending/p1/approve";
            assertEquals(401, send(c.port, "POST", approve, "{}", null).statusCode());
            assertEquals(403, send(c.port, "POST", approve, "{}", "Bearer plain").statusCode(), "needs the capability");
            HttpResponse<String> self = send(c.port, "POST", approve, "{}", "Bearer self");
            assertEquals(403, self.statusCode(), "self-approval is refused even WITH the capability: " + self.body());
            assertTrue(self.body().contains("a different person must"), self.body());
            assertEquals(ErrorCodes.PERMISSION_DENIED, JSON.readTree(self.body()).at("/error/errorCode").asText());
            assertEquals(404, send(c.port, "POST", "/inv/investigations/case-a/pending/p9/approve", "{}", "Bearer lead")
                    .statusCode());
            assertEquals(1, Files.readAllLines(log).size(), "no refusal ran the expand");

            JsonNode approved = data(send(c.port, "POST", approve, "{}", "Bearer lead"));
            assertEquals(2, approved.get("step").asInt(), approved.toString());
            assertEquals("lead-1", approved.at("/approval/approvedBy").asText());
            assertEquals(2, approved.at("/delta/admitted").size(), "it ran: " + B + " and " + C + " admitted");
            assertEquals(2, Files.readAllLines(log).size());
            JsonNode after = data(send(c.port, "GET", "/inv/investigations/case-a/log", null, "Bearer owner"));
            assertEquals("analyst-1", after.at("/entries/1/author").asText(), "the requester's op");
            assertTrue(after.at("/entries/1/text").asText().endsWith("Four-eyes: requested by analyst-1, approved by lead-1."),
                    after.at("/entries/1/text").asText());
            assertEquals("approved", after.at("/pending/0/status").asText());
            assertEquals(409, send(c.port, "POST", approve, "{}", "Bearer lead").statusCode(), "decided once");
            assertTrue(data(send(c.port, "POST", "/inv/investigations/case-a/replay", "{}", "Bearer owner"))
                    .get("equivalent").asBoolean(), "an approved step replays like any other");

            // Deny: the request never runs; who denied it and why are recorded.
            assertEquals("pending", data(send(c.port, "POST", OPS, "{\"op\":\"expand\",\"budget\":700}", "Bearer owner"))
                    .get("status").asText());
            String deny = "/inv/investigations/case-a/pending/p2/deny";
            assertEquals(403, send(c.port, "POST", deny, "{}", "Bearer self").statusCode(), "not by the requester either");
            JsonNode denied = data(send(c.port, "POST", deny, "{\"reason\":\"disproportionate\"}", "Bearer lead"));
            assertEquals("denied", denied.at("/pending/status").asText());
            assertEquals("disproportionate", denied.at("/pending/reason").asText());
            assertEquals(2, Files.readAllLines(log).size(), "a denied expand reads nothing");

            // Below the threshold an expand runs at once, as before.
            assertFalse(data(send(c.port, "POST", OPS, "{\"op\":\"expand\",\"budget\":50}", "Bearer owner")).has("pending"));
        } finally {
            Authenticators.forTest(null);
        }
    }

    @Test
    void fourEyesFailsClosedWithoutASubjectAndOnTemplatesAndCountsAnUnboundedFanOut(@TempDir Path cfg,
                                                                                   @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            post(c, "/inv/investigations", CREATE);
            post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
            post(c, OPS, "{\"op\":\"expand\",\"budget\":500}");   // before any threshold: runs
            post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl\"}");

            settings(c, "four_eyes_fan_out_above: 5\n");
            JsonNode held = post(c, OPS, "{\"op\":\"expand\"}");
            assertEquals("pending", held.get("status").asText(), "no maxFanOut = unbounded, which exceeds any threshold");
            HttpResponse<String> noSubject = send(c.port, "POST", "/inv/investigations/case-a/pending/p1/approve", "{}", null);
            assertEquals(403, noSubject.statusCode(), "with no Subject two people cannot be told apart: " + noSubject.body());
            assertEquals(ErrorCodes.PERMISSION_DENIED, JSON.readTree(noSubject.body()).at("/error/errorCode").asText());

            settings(c, "four_eyes_budget_above: 100\n");
            HttpResponse<String> tpl = send(c.port, "POST", "/inv/investigation-templates/tpl/instantiate",
                    "{\"id\":\"case-t\",\"purpose\":\"p\",\"params\":{\"seed1\":[\"" + A + "\"]}}", null);
            assertEquals(422, tpl.statusCode(), "a template's sensitive expand has no frontier to approve: " + tpl.body());
            assertTrue(tpl.body().contains("four-eyes"), tpl.body());
        }
    }

    // ── D-U7 on the stateless reads: neighbours + recursive paths ─────────────────────────────────────

    private static String neighbours(String dataset, String source, int limit) {
        return "{\"dataset\":\"" + dataset + "\",\"sourceCol\":\"" + source + "\",\"targetCol\":\"callee\","
                + "\"value\":\"" + A + "\",\"limit\":" + limit + "}";
    }

    private static String traversal(Integer maxDepth, Integer maxEdgeYield) {
        return "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"startNode\":\"" + A + "\""
                + (maxDepth != null ? ",\"maxDepth\":" + maxDepth : "")
                + (maxEdgeYield != null ? ",\"maxEdgeYield\":" + maxEdgeYield : "") + "}";
    }

    @Test
    void theStatelessReadsAreRefusedAboveTheFourEyesThresholdsBecauseNothingCouldApproveThem(@TempDir Path cfg,
                                                                                         @TempDir Path root) throws Exception {
        String nb = "/inv/projection/neighbors", rp = "/inv/traversal/recursive-paths";
        try (Ctx c = open(cfg, root)) {
            // No threshold (the shipped default): both run, however wide.
            assertEquals(2, post(c, nb, neighbours("calls_ds", "caller", 20_000)).get("rows").size());
            assertTrue(post(c, rp, traversal(null, null)).get("paths").size() > 0);

            settings(c, "four_eyes_budget_above: 100\n");
            HttpResponse<String> wide = send(c.port, "POST", nb, neighbours("calls_ds", "caller", 500), null);
            assertEquals(403, wide.statusCode(), wide.body());
            assertTrue(wide.body().contains("four-eyes") && wide.body().contains("rows 500 > 100"), wide.body());
            assertEquals(ErrorCodes.PERMISSION_DENIED, JSON.readTree(wide.body()).at("/error/errorCode").asText());
            assertEquals(2, post(c, nb, neighbours("calls_ds", "caller", 100)).get("rows").size(),
                    "the same read AT the threshold runs — the 403 above is the gate, not the body");
            HttpResponse<String> deep = send(c.port, "POST", rp, traversal(null, null), null);
            assertEquals(403, deep.statusCode(), "the defaults read 6 × 10 000 rows: " + deep.body());
            assertTrue(deep.body().contains("rows 60000 > 100"), deep.body());
            assertTrue(post(c, rp, traversal(2, 50)).get("paths").size() > 0, "2 × 50 = 100 rows is not above 100");

            // Gate order: an absent Dataset still answers as absent (the 404 is judged first, so the gate cannot
            // reveal that a Dataset the caller cannot see exists). The traversal checks its columns against the
            // relation BEFORE the gate, so a bad one is still malformed; the neighbours read only learns a column
            // is unknown when its query runs, which the gate — reading nothing — now pre-empts with the 403.
            assertEquals(404, send(c.port, "POST", nb, neighbours("nope", "caller", 500), null).statusCode());
            assertEquals(422, send(c.port, "POST", rp, traversal(null, null).replace("\"caller\"", "\"no_such_col\""),
                    null).statusCode());

            settings(c, "four_eyes_fan_out_above: 5\n");
            HttpResponse<String> fan = send(c.port, "POST", nb, neighbours("calls_ds", "caller", 50), null);
            assertEquals(403, fan.statusCode(), fan.body());
            assertTrue(fan.body().contains("fan-out 50 > 5"), fan.body());
            assertEquals(2, post(c, nb, neighbours("calls_ds", "caller", 5)).get("rows").size());
            assertEquals(403, send(c.port, "POST", rp, traversal(2, 6), null).statusCode());
            assertTrue(post(c, rp, traversal(2, 5)).get("paths").size() > 0);
        }
    }

    private static String projection(int limit) {
        return "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"limit\":" + limit + "}";
    }

    private static String multi(int limit, int edgeMappings) {
        String edge = "{\"dataset\":\"calls_ds\",\"sourceColumn\":\"caller\",\"targetColumn\":\"callee\"}";
        return "{\"limit\":" + limit + ",\"edges\":[" + String.join(",", java.util.Collections.nCopies(edgeMappings, edge))
                + "]}";
    }

    @Test
    void theWholeRelationProjectionsAreRefusedAboveTheFourEyesThresholdsToo(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        String pj = "/inv/projection", mp = "/inv/projection/multi";
        try (Ctx c = open(cfg, root)) {
            // No threshold (the shipped default): both run at the maximum limit.
            assertEquals(3, post(c, pj, projection(20_000)).get("rows").size());
            assertEquals(3, post(c, mp, multi(20_000, 1)).get("edges").size());

            settings(c, "four_eyes_budget_above: 100\n");
            HttpResponse<String> wide = send(c.port, "POST", pj, projection(101), null);
            assertEquals(403, wide.statusCode(), wide.body());
            assertTrue(wide.body().contains("four-eyes") && wide.body().contains("rows 101 > 100"), wide.body());
            assertEquals(3, post(c, pj, projection(100)).get("rows").size(),
                    "the same projection AT the threshold runs — the 403 above is the gate, not the body");
            // `limit` is per mapping, so two mappings of 60 read up to 120 rows although each is under 100.
            HttpResponse<String> two = send(c.port, "POST", mp, multi(60, 2), null);
            assertEquals(403, two.statusCode(), two.body());
            assertTrue(two.body().contains("rows 120 > 100"), two.body());
            assertEquals(3, post(c, mp, multi(50, 2)).get("edges").size() / 2, "2 × 50 = 100 rows is not above 100");
            // Fail closed, whole call: an absent Dataset is still a 404 before the gate is judged.
            assertEquals(404, send(c.port, "POST", mp,
                    multi(60, 2).replaceFirst("calls_ds", "nope"), null).statusCode());

            settings(c, "four_eyes_fan_out_above: 5\n");
            assertEquals(403, send(c.port, "POST", pj, projection(6), null).statusCode());
            assertEquals(3, post(c, pj, projection(5)).get("rows").size());
            assertEquals(403, send(c.port, "POST", mp, multi(3, 2), null).statusCode(), "3 links from each of 2 mappings");
            assertEquals(3, post(c, mp, multi(5, 1)).get("edges").size());
        }
    }
}
