package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
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
 * The {@code temporal} OP (LA-INVESTIGATION-OPS-DEFERRED-1: a burst / periodicity finding set sealed into the Investigation
 * log), over real HTTP. Fixture: ann to bob has a six-event burst (25 s) and a late event; bob to ann one; pat to quin is hourly
 * (periodic); mel to nan irregular; zed to wil is a six-event burst that is regular too but is NEVER admitted. Seed ann + pat,
 * one expand: the Working Set holds ann, bob, pat, quin.
 */
class ControlApiInvestigationTemporalOpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('ann','bob','voice','2026-01-01 10:00:00')", "('ann','bob','voice','2026-01-01 10:00:05')",
            "('ann','bob','voice','2026-01-01 10:00:10')", "('ann','bob','voice','2026-01-01 10:00:15')",
            "('ann','bob','voice','2026-01-01 10:00:20')", "('ann','bob','voice','2026-01-01 10:00:25')",
            "('ann','bob','voice','2026-01-01 12:00:00')", "('bob','ann','voice','2026-01-01 10:00:01')",
            "('pat','quin','sms','2026-01-01 08:00:00')", "('pat','quin','sms','2026-01-01 09:00:00')",
            "('pat','quin','sms','2026-01-01 10:00:00')", "('pat','quin','sms','2026-01-01 11:00:00')",
            "('pat','quin','sms','2026-01-01 12:00:00')",
            "('mel','nan','sms','2026-01-01 08:00:00')", "('mel','nan','sms','2026-01-01 08:05:00')",
            "('mel','nan','sms','2026-01-01 11:00:00')", "('mel','nan','sms','2026-01-01 11:01:00')",
            "('mel','nan','sms','2026-01-02 03:00:00')",
            "('zed','wil','voice','2026-01-01 10:00:00')", "('zed','wil','voice','2026-01-01 10:00:02')",
            "('zed','wil','voice','2026-01-01 10:00:04')", "('zed','wil','voice','2026-01-01 10:00:06')",
            "('zed','wil','voice','2026-01-01 10:00:08')", "('zed','wil','voice','2026-01-01 10:00:10')");
    private static final String BURST = "{\"op\":\"temporal\",\"mode\":\"burst\",\"windowSeconds\":60,\"minEvents\":5}";
    private static final String PERIODIC = "{\"op\":\"temporal\",\"mode\":\"periodicity\",\"minEvents\":5,\"maxCv\":0.1}";
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
            + "\"targetCol\":\"callee\",\"linkKindCol\":\"channel\",\"timeCol\":\"ts\"}";
    private static final String OPS = "/inv/investigations/case-a/ops";
    private static final String LOG = "audit/snapshots/investigations/case-a/log.jsonl";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, CAST(ts AS TIMESTAMP) AS ts FROM (VALUES " + ROWS
                            + ") AS t(caller,callee,channel,ts)", "2026-10-05T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return ok(send(c, "POST", path, body, null));
    }

    private JsonNode get(Ctx c, String path) throws Exception {
        return ok(send(c, "GET", path, null, null));
    }

    private void build(Ctx c) throws Exception {
        post(c, "/inv/investigations", CREATE);
        post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"ann\",\"pat\"]}");
        post(c, OPS, "{\"op\":\"expand\"}");
    }

    private static Set<String> names(JsonNode results, String field) {
        Set<String> out = new TreeSet<>();
        for (JsonNode f : results) out.add(f.get(field).asText());
        return out;
    }

    @Test
    void sealsTheFindingsIntoTheLogWithoutMovingTheWorkingSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            String hashBefore = get(c, "/inv/investigations/case-a/log").at("/entries/1/workingSetHash").asText();

            JsonNode r = post(c, OPS, BURST);
            assertEquals("temporal", r.get("op").asText());
            assertEquals(3, r.get("step").asInt());
            assertTrue(r.at("/temporalFindings/sealed").asBoolean(), r.toString());
            assertEquals(1, r.at("/temporalFindings/count").asInt(), r.toString());
            assertEquals("ann", r.at("/temporalFindings/results/0/source").asText());
            assertEquals("bob", r.at("/temporalFindings/results/0/target").asText());
            assertEquals(6, r.at("/temporalFindings/results/0/events").asInt(), "six in 25 s; the 12:00 event and the reverse link are not in it");
            assertEquals(1, r.at("/temporalFindings/outsideWorkingSet").asInt(), "zed to wil is a burst nobody admitted: dropped, counted");
            assertFalse(r.toString().contains("zed") || r.toString().contains("wil"), "never admitted, never named: " + r);
            assertEquals(0, r.at("/delta/entitiesAdded").size() + r.at("/delta/entitiesRemoved").size(), "the Working Set does not move");

            JsonNode log = get(c, "/inv/investigations/case-a/log");
            JsonNode entry = log.at("/entries/2");
            assertEquals(hashBefore, entry.get("workingSetHash").asText(), "a marker leaves the Working Set hash untouched");
            assertTrue(entry.get("text").asText().contains("Scanned") && entry.get("text").asText().contains("1 finding"), entry.get("text").asText());
            assertEquals(1, entry.at("/temporalFindings/count").asInt());
            assertTrue(entry.at("/temporalFindings/results").isMissingNode(), "the log view carries counts, not the sealed findings: " + entry);

            String line = Files.readAllLines(root.resolve(LOG)).get(2);
            assertTrue(line.contains("\"temporalFindings\"") && line.contains("\"fingerprint\""), line);
            assertTrue(post(c, "/inv/investigations/case-a/replay", "{}").get("equivalent").asBoolean());

            // Periodicity: pat to quin is hourly, cv 0; ann to bob is not regular.
            JsonNode p = post(c, OPS, PERIODIC);
            assertEquals(1, p.at("/temporalFindings/count").asInt(), p.toString());
            assertEquals("pat", p.at("/temporalFindings/results/0/source").asText());
            assertEquals(3600.0, p.at("/temporalFindings/results/0/periodSeconds").asDouble());
            assertEquals(1, p.at("/temporalFindings/outsideWorkingSet").asInt(), "zed to wil: regular, outside");
            assertNotEquals(r.at("/temporalFindings/fingerprint").asText(), p.at("/temporalFindings/fingerprint").asText());

            // Same inputs, same fingerprint (readAt is not in the content).
            assertEquals(r.at("/temporalFindings/fingerprint").asText(), post(c, OPS, BURST).at("/temporalFindings/fingerprint").asText());
            assertTrue(post(c, "/inv/investigations/case-a/replay", "{}").get("equivalent").asBoolean());
        }
    }

    @Test
    void anEntitySeriesPoolsEveryEventAnEntityTakesPartIn(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode r = post(c, OPS, BURST.replace("\"mode\"", "\"series\":\"entity\",\"mode\""));
            assertEquals("entity", r.at("/temporalFindings/params/series").asText());
            assertEquals(Set.of("ann", "bob"), names(r.at("/temporalFindings/results"), "entity"), r.toString());
            assertEquals(2, r.at("/temporalFindings/outsideWorkingSet").asInt(), "zed and wil");
            assertTrue(get(c, "/inv/investigations/case-a/log").at("/entries/2/text").asText().contains("entities"));
        }
    }

    @Test
    void aForkReSealsOverItsOwnWorkingSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            post(c, OPS, BURST);
            // Order seed, temporal, expand: when the scan runs the Working Set holds no links yet.
            JsonNode fork = post(c, "/inv/investigations/case-a/reorder", "{\"id\":\"case-b\",\"order\":[1,3,2]}");
            assertEquals(3, fork.get("steps").asInt());
            List<String> lines = Files.readAllLines(root.resolve("audit/snapshots/investigations/case-b/log.jsonl"));
            JsonNode sealed = JSON.readTree(lines.get(1)).get("temporalFindings");
            assertEquals(0, sealed.get("count").asInt(), "re-sealed over the new state, not copied: " + lines.get(1));
            assertEquals(1, JSON.readTree(Files.readAllLines(root.resolve(LOG)).get(2)).at("/temporalFindings/count").asInt(), "the source is untouched");
            assertTrue(post(c, "/inv/investigations/case-b/replay", "{}").get("equivalent").asBoolean());
        }
    }

    @Test
    void aTemplateDropsItAsCaseEvidence(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            post(c, OPS, BURST);
            JsonNode tpl = post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl-1\",\"title\":\"ring\"}");
            assertEquals("temporal", tpl.at("/dropped/0/op").asText(), tpl.toString());
            assertEquals(3, tpl.at("/dropped/0/step").asInt());
            for (JsonNode op : tpl.get("ops")) assertNotEquals("temporal", op.get("op").asText());
            assertFalse(Files.readString(root.resolve("audit/snapshots/investigation-templates/tpl-1.json")).contains("temporalFindings"));
        }
    }

    @Test
    void aDossierCarriesTheSealedFindingsInCustodyAndItsRootIsDeterministic(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode plain = get(c, "/inv/investigations/case-a/dossier");
            assertTrue(plain.path("temporalFindings").isMissingNode(), "no temporal op, no section");
            post(c, OPS, BURST);

            JsonNode d1 = get(c, "/inv/investigations/case-a/dossier");
            JsonNode d2 = get(c, "/inv/investigations/case-a/dossier");
            assertEquals(1, d1.get("temporalFindings").size(), d1.toString());
            assertEquals(3, d1.at("/temporalFindings/0/step").asInt());
            assertEquals("ann", d1.at("/temporalFindings/0/findings/results/0/source").asText());
            assertEquals(d1.at("/manifest/root").asText(), d2.at("/manifest/root").asText(), "the root is deterministic");
            assertTrue(d1.at("/integrity/intact").asBoolean(), d1.at("/integrity").toString());
            assertTrue(d1.at("/renderings/steps").toString().contains("Scanned"));
            assertTrue(post(c, "/inv/investigations/case-a/dossier/verify", d1.get("manifest").toString()).get("verified").asBoolean());

            // Tamper with a sealed event count on disk: custody and integrity must both notice.
            Path logFile = root.resolve(LOG);
            List<String> lines = Files.readAllLines(logFile);
            String forged = lines.get(2).replaceFirst("\"events\":6", "\"events\":99");
            assertNotEquals(lines.get(2), forged, "the probe edited nothing: " + lines.get(2));
            lines.set(2, forged);
            Files.write(logFile, lines);

            JsonNode afterVerify = post(c, "/inv/investigations/case-a/dossier/verify", d1.get("manifest").toString());
            assertFalse(afterVerify.get("verified").asBoolean(), afterVerify.toString());
            assertTrue(afterVerify.get("changed").toString().contains("log.jsonl#3"), afterVerify.toString());
            JsonNode d3 = get(c, "/inv/investigations/case-a/dossier");
            assertFalse(d3.at("/integrity/intact").asBoolean(), "the sealed fingerprint no longer matches the content");
            assertTrue(d3.at("/integrity/failures").toString().contains("sealed temporal findings"), d3.at("/integrity").toString());
        }
    }

    @Test
    void masksTheSealedFindingsAndRefusesWhatItCannotSeal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
        try (Ctx c = open(cfg, root)) {
            build(c);
            String body = post(c, OPS, BURST).toString();
            for (String raw : List.of("\"ann\"", "\"bob\"", "\"pat\"", "\"quin\"", "\"ann>"))
                assertFalse(body.contains(raw), "a raw id leaked under masking_mode all: " + body);
            assertTrue(body.contains("\"events\":6"), "the counts are not masked: " + body);
            String dossier = get(c, "/inv/investigations/case-a/dossier").get("temporalFindings").toString();
            assertFalse(dossier.contains("\"ann\"") || dossier.contains("\"bob\""), dossier);

            assertEquals(422, send(c, "POST", OPS, "{\"op\":\"temporal\"}", null).statusCode(), "no mode");
            assertEquals(422, send(c, "POST", OPS, BURST.replace("burst", "bogus"), null).statusCode(), "bad mode");
            assertEquals(422, send(c, "POST", OPS, BURST.replace("\"mode\"", "\"series\":\"node\",\"mode\""), null).statusCode(), "bad series");
            assertEquals(422, send(c, "POST", OPS, BURST.replace("\"minEvents\":5", "\"minEvents\":1"), null).statusCode(), "minEvents below the floor");
            assertEquals(422, send(c, "POST", OPS, PERIODIC.replace("0.1", "2"), null).statusCode(), "maxCv above 1");
            assertEquals(422, send(c, "POST", OPS, BURST.replace("\"mode\"", "\"ids\":[\"ann\"],\"mode\""), null).statusCode(), "ids");
            post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"timeless\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}");
            assertEquals(422, send(c, "POST", "/inv/investigations/timeless/ops", BURST, null).statusCode(), "no time column");
            assertEquals(3, Files.readAllLines(root.resolve(LOG)).size(), "refusals append nothing");
        }
    }

    @Test
    void isGatedOnCanManageIncidents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer plain" -> Optional.of(new Subject("analyst-2", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c, "POST", OPS, "{\"op\":\"seed\",\"ids\":[\"ann\"]}", "Bearer owner").statusCode());
            assertEquals(401, send(c, "POST", OPS, BURST, null).statusCode());
            assertEquals(403, send(c, "POST", OPS, BURST, "Bearer plain").statusCode());
            assertEquals(200, send(c, "POST", OPS, BURST, "Bearer owner").statusCode());
        }
    }

    @Test
    void aDraftRebaseAndPromoteReSealOverTheNewWorkingSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Set<String> caps = Set.of("canManageIncidents", "canRunLinkGraphAnalysis", "canApproveLinkExpansions", "canRevealLinkEntities");
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer lead" -> Optional.of(new Subject("lead-1", caps));
            case "Bearer analyst" -> Optional.of(new Subject("analyst-2", caps));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
            String inv = "/inv/investigations/case-a";
            assertEquals(200, send(c, "POST", "/inv/investigations", CREATE, "Bearer lead").statusCode());
            assertEquals(200, send(c, "POST", OPS, "{\"op\":\"seed\",\"ids\":[\"ann\"]}", "Bearer lead").statusCode());
            assertEquals(200, send(c, "POST", OPS, "{\"op\":\"expand\"}", "Bearer lead").statusCode());
            assertEquals(200, send(c, "POST", inv + "/members", "{\"subject\":\"analyst-2\",\"role\":\"analyst\"}", "Bearer lead").statusCode());
            HttpResponse<String> forked = send(c, "POST", inv + "/drafts", "{}", "Bearer analyst");
            assertEquals(201, forked.statusCode(), forked.body());
            String draft = inv + "/drafts/" + JSON.readTree(forked.body()).at("/data/draftId").asText();
            // In the Draft the Working Set is ann and bob only, so pat to quin (hourly) is outside it: nothing found.
            JsonNode inDraft = ok(send(c, "POST", draft + "/ops", PERIODIC, "Bearer analyst"));
            assertEquals(0, inDraft.at("/temporalFindings/count").asInt(), inDraft.toString());
            // Main moves on and admits pat and quin; the Draft rebases onto it, then promotes.
            assertEquals(200, send(c, "POST", OPS, "{\"op\":\"seed\",\"ids\":[\"pat\"]}", "Bearer lead").statusCode());
            assertEquals(200, send(c, "POST", OPS, "{\"op\":\"expand\"}", "Bearer lead").statusCode());
            HttpResponse<String> rebased = send(c, "POST", draft + "/rebase", "{\"confirm\":[]}", "Bearer analyst");
            assertEquals(200, rebased.statusCode(), rebased.body());
            JsonNode own = JSON.readTree(Files.readAllLines(root.resolve("audit/snapshots/investigations/case-a/drafts/"
                    + draft.substring(draft.lastIndexOf('/') + 1) + "/log.jsonl")).get(0));
            assertEquals(1, own.at("/temporalFindings/count").asInt(), "re-sealed over the NEW base: " + own);
            assertEquals("pat", own.at("/temporalFindings/results/0/source").asText());
            assertEquals(200, send(c, "POST", draft + "/promote", "{}", "Bearer analyst").statusCode());
            JsonNode main = JSON.readTree(Files.readAllLines(root.resolve(LOG)).get(4));
            assertEquals("temporal", main.get("op").asText());
            assertEquals(1, main.at("/temporalFindings/count").asInt(), "promote re-seals over the main Working Set: " + main);
            assertTrue(ok(send(c, "POST", inv + "/replay", "{}", "Bearer lead")).get("equivalent").asBoolean());
        }
    }
}
