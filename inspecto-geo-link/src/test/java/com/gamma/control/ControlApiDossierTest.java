package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-12 — the Dossier of an Investigation over real HTTP ({@code GET /inv/investigations/{id}/dossier},
 * {@code POST /inv/investigations/{id}/dossier/verify}): the sections, the three renderings and their negative space
 * (G-E10), the SHA-256 manifest (G-R6) and the chain of custody — a tampered stored artefact is REPORTED.
 *
 * <p>Fixture as {@code ControlApiInvestigationsTest}: {@code alice–bob, alice–carol, bob–dave, bob–erin, carol–frank}.
 */
class ControlApiDossierTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = "('alice','bob','sms'),('alice','bob','sms'),('alice','carol','call'),"
            + "('bob','dave','call'),('bob','erin','sms'),('carol','frank','call')";
    private static final String CREATE =
            "{\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-09-23T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return send(c.port, "GET", path, null, null);
    }

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c.port, "POST", path, body, null);
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static Path invDir(Path root, String id) {
        return root.resolve("audit").resolve("snapshots").resolve("investigations").resolve(id);
    }

    /** seed alice → expand → exclude bob (reason) → hide carol: four steps, one exclusion. */
    private void build(Ctx c) throws Exception {
        data(post(c, "/inv/investigations", CREATE));
        data(post(c, "/inv/investigations/case-a/ops", "{\"op\":\"seed\",\"ids\":[\"alice\"],\"entityType\":\"subscriber\"}"));
        data(post(c, "/inv/investigations/case-a/ops", "{\"op\":\"expand\"}"));
        data(post(c, "/inv/investigations/case-a/ops", "{\"op\":\"exclude\",\"ids\":[\"bob\"],\"reason\":\"marketing number\"}"));
        data(post(c, "/inv/investigations/case-a/ops", "{\"op\":\"hide\",\"ids\":[\"carol\"]}"));
    }

    private JsonNode verify(Ctx c, JsonNode manifest) throws Exception {
        return data(post(c, "/inv/investigations/case-a/dossier/verify", "{\"manifest\":" + manifest + "}"));
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }

    // ── the dossier ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theDossierCarriesEverySectionAndAManifestOverEveryStoredArtefact(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode d = data(get(c, "/inv/investigations/case-a/dossier"));

            assertEquals(4, d.at("/summary/at").asInt());
            assertEquals("calls_ds", d.at("/summary/dataset").asText());
            assertTrue(d.at("/summary/datasetVersion").isNull(), "D-E3: nothing pinned");
            assertEquals(2, d.at("/summary/entities").asInt(), "alice + carol survive; bob excluded");
            assertEquals(1, d.at("/summary/excluded").asInt());
            assertEquals(1, d.at("/topology/perHop/0").asInt());
            assertEquals(1, d.at("/topology/perHop/1").asInt());
            assertEquals(4, d.get("ledger").size());
            assertEquals("expand", d.at("/ledger/1/op").asText());
            assertEquals(3, d.at("/ledger/1/entitiesAfter").asInt());
            assertTrue(d.at("/integrity/intact").asBoolean(), d.get("integrity").toString());
            assertTrue(d.at("/scores/tables").isEmpty());
            assertTrue(d.at("/scores/note").asText().contains("snapshot"), "an absent table says why");

            JsonNode m = d.get("manifest");
            assertEquals("SHA-256", m.get("algorithm").asText());
            assertTrue(m.get("root").asText().matches("sha256:[0-9a-f]{64}"), m.toString());
            List<String> paths = new ArrayList<>();
            for (JsonNode a : m.get("artefacts")) paths.add(a.get("path").asText());
            assertEquals(List.of("header.json", "log.jsonl#1", "sets/1.json", "log.jsonl#2", "sets/2.json",
                    "log.jsonl#3", "sets/3.json", "log.jsonl#4", "sets/4.json"), paths);
            for (String k : List.of("entities", "links", "excluded", "scores"))
                assertTrue(m.at("/content/" + k).asText().startsWith("sha256:"), "G-R6: " + k + " is hashed");

            JsonNode again = data(get(c, "/inv/investigations/case-a/dossier"));
            assertEquals(m.get("root"), again.at("/manifest/root"), "the root does not depend on when it was built");

            JsonNode prefix = data(get(c, "/inv/investigations/case-a/dossier?at=2"));
            assertEquals(2, prefix.get("ledger").size());
            assertEquals(5, prefix.at("/manifest/artefacts").size(), "header + two log lines + two sets");
            assertEquals(3, prefix.at("/summary/entities").asInt());
        }
    }

    /** G-E10 — a narrative cannot omit an exclusion: the excluded id, count and reason appear in ALL three renderings. */
    @Test
    void everyRenderingCarriesTheNegativeSpace(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode d = data(get(c, "/inv/investigations/case-a/dossier"));

            JsonNode json = d.at("/renderings/json");
            assertEquals(4, json.get("log").size());
            assertFalse(json.at("/log/1/read").has("rows"), "the sealed rows stay in the store, covered by the manifest");
            assertEquals(1, json.at("/negativeSpace/excludedCount").asInt());
            assertEquals("bob", json.at("/negativeSpace/excluded/0/id").asText());
            assertEquals("marketing number", json.at("/negativeSpace/excluded/0/reason").asText());
            assertEquals(List.of("carol"), texts(json.at("/negativeSpace/hidden")));
            assertFalse(json.at("/negativeSpace/coverage/assessed").asBoolean());

            String steps = String.join("\n", texts(d.at("/renderings/steps")));
            String method = d.at("/renderings/method").asText();
            for (String rendering : List.of(steps, method)) {
                assertTrue(rendering.contains("bob — step 3, reason: marketing number"), rendering);
                assertTrue(rendering.contains("Excluded: 1 entity"), rendering);
                assertTrue(rendering.contains("Hidden from display"), rendering);
                assertTrue(rendering.contains("Coverage: NOT assessed"), rendering);
                assertTrue(rendering.contains("Dataset version: not pinned"), rendering);
            }
            assertTrue(steps.startsWith("1. ["), steps);
            assertTrue(method.contains(d.at("/manifest/root").asText()), "the method statement cites the custody hash");

            HttpResponse<String> plain = get(c, "/inv/investigations/case-a/dossier?format=steps");
            assertEquals(200, plain.statusCode());
            assertTrue(plain.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            assertTrue(plain.body().contains("reason: marketing number"), plain.body());
            HttpResponse<String> statement = get(c, "/inv/investigations/case-a/dossier?format=method");
            assertTrue(statement.body().startsWith("Method statement — Investigation case-a"), statement.body());
        }
    }

    @Test
    void anUndoneStepStaysIntactAndIsNamedAsNegativeSpace(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            data(post(c, "/inv/investigations/case-a/undo", ""));
            JsonNode d = data(get(c, "/inv/investigations/case-a/dossier"));
            assertTrue(d.at("/integrity/intact").asBoolean(), "positions are the prefix recorded at append time: "
                    + d.get("integrity"));
            assertEquals(4, d.at("/negativeSpace/undone/0/step").asInt());
            assertEquals(5, d.at("/negativeSpace/undone/0/undoneBy").asInt());
            assertTrue(d.at("/renderings/steps/3").asText().endsWith("(undone by step 5)"), d.at("/renderings/steps").toString());
        }
    }

    // ── chain of custody ───────────────────────────────────────────────────────────────────────────────

    @Test
    void anUntouchedStoreVerifiesAndATamperedStepIsReported(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode manifest = data(get(c, "/inv/investigations/case-a/dossier")).get("manifest");

            JsonNode clean = verify(c, manifest);
            assertTrue(clean.get("verified").asBoolean(), clean.toString());
            assertEquals(manifest.get("root"), clean.get("currentRoot"));

            // Tamper with one sealed Working Set on disk: an entity is renamed.
            Path set = invDir(root, "case-a").resolve("sets").resolve("2.json");
            Files.writeString(set, Files.readString(set).replace("\"carol\"", "\"mallory\""));

            JsonNode tampered = verify(c, manifest);
            assertFalse(tampered.get("verified").asBoolean(), tampered.toString());
            assertEquals(List.of("sets/2.json"), texts(tampered.get("changed")));
            assertFalse(tampered.get("intact").asBoolean(), "the set no longer hashes to its own recorded hash");
            assertEquals("sets/2.json", tampered.at("/integrity/failures/0/artefact").asText(), tampered.toString());

            // The FIRST dossier issued after the tamper already says so — no earlier manifest needed.
            JsonNode after = data(get(c, "/inv/investigations/case-a/dossier"));
            assertFalse(after.at("/integrity/intact").asBoolean());
            assertTrue(after.at("/renderings/method").asText().contains("INTEGRITY FAILURE"));
        }
    }

    /**
     * A tamper that leaves every INTERNAL hash consistent — the header is covered by no recorded hash — is caught by
     * the external manifest alone; and an edited manifest cannot vouch for itself.
     */
    @Test
    void theManifestCatchesWhatTheRecordedHashesCannotAndCannotBeEdited(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode manifest = data(get(c, "/inv/investigations/case-a/dossier")).get("manifest");

            Path header = invDir(root, "case-a").resolve("header.json");
            Files.writeString(header, Files.readString(header).replace("\"title\":null", "\"title\":\"rewritten\""));
            JsonNode v = verify(c, manifest);
            assertTrue(v.get("intact").asBoolean(), "no recorded hash covers the header");
            assertFalse(v.get("verified").asBoolean(), v.toString());
            assertEquals(List.of("header.json"), texts(v.get("changed")));

            // Removing the last log line is reported as MISSING, never refused.
            Path log = invDir(root, "case-a").resolve("log.jsonl");
            List<String> lines = Files.readAllLines(log);
            Files.write(log, lines.subList(0, 3));
            JsonNode cut = verify(c, manifest);
            assertTrue(texts(cut.get("missing")).containsAll(List.of("log.jsonl#4", "sets/4.json")), cut.toString());

            ObjectNode edited = manifest.deepCopy();
            ((ObjectNode) edited.get("artefacts").get(0)).put("sha256", "sha256:" + "0".repeat(64));
            assertFalse(verify(c, edited).get("selfConsistent").asBoolean(), "an edited manifest fails its own root");
        }
    }

    // ── snapshots as exhibits ──────────────────────────────────────────────────────────────────────────

    @Test
    void anAnchoredSnapshotBringsItsScoreVectorsIntoTheManifest(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            data(post(c, "/inv/snapshots", "{\"id\":\"snp-1\",\"investigationId\":\"case-a\",\"manifestHash\":\"h\","
                    + "\"createdAt\":\"2026-09-23T10:00:00Z\",\"nodes\":[{\"id\":\"alice\"},{\"id\":\"carol\"}],"
                    + "\"edges\":[],\"origin\":{\"dataset\":\"calls_ds\"},"
                    + "\"metrics\":{\"betweenness\":{\"alice\":0.9,\"carol\":0.1}}}"));
            data(post(c, "/inv/snapshots", "{\"id\":\"snp-other\",\"investigationId\":\"case-z\",\"manifestHash\":\"h\"}"));

            JsonNode d = data(get(c, "/inv/investigations/case-a/dossier?snapshots=snp-1"));
            JsonNode table = d.at("/scores/tables/0");
            assertEquals("betweenness", table.get("metric").asText());
            assertEquals("snp-1", table.get("snapshot").asText());
            assertEquals("alice", table.at("/rows/0/id").asText(), "highest first");
            assertEquals("2026-09-23T10:00:00Z", table.get("computedAt").asText());
            JsonNode last = d.at("/manifest/artefacts").get(d.at("/manifest/artefacts").size() - 1);
            assertEquals("snapshots/snp-1.json", last.get("path").asText());
            assertTrue(String.join("\n", texts(d.at("/renderings/steps"))).contains("[betweenness]"));

            JsonNode without = data(get(c, "/inv/investigations/case-a/dossier"));
            assertFalse(without.at("/manifest/content/scores").equals(d.at("/manifest/content/scores")));

            HttpResponse<String> foreign = get(c, "/inv/investigations/case-a/dossier?snapshots=snp-other");
            assertEquals(422, foreign.statusCode(), foreign.body());
            assertTrue(foreign.body().contains("not anchored"), foreign.body());
            assertEquals(404, get(c, "/inv/investigations/case-a/dossier?snapshots=snp-ghost").statusCode());
            assertEquals(422, get(c, "/inv/investigations/case-a/dossier?snapshots=..%2Fx").statusCode());

            Path snap = root.resolve("audit").resolve("snapshots").resolve("snp-1.json");
            Files.writeString(snap, Files.readString(snap).replace("0.9", "0.2"));
            JsonNode v = verify(c, d.get("manifest"));
            assertEquals(List.of("snapshots/snp-1.json"), texts(v.get("changed")));
            assertEquals(List.of("scores"), texts(v.get("contentChanged")));
        }
    }

    // ── gates ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void refusesEachBadRequest(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c, "/inv/investigations/nope/dossier").statusCode());
            assertEquals(404, post(c, "/inv/investigations/nope/dossier/verify", "{}").statusCode());
            assertEquals(422, get(c, "/inv/investigations/.bad/dossier").statusCode());
            build(c);
            assertEquals(422, get(c, "/inv/investigations/case-a/dossier?at=9").statusCode());
            assertEquals(422, get(c, "/inv/investigations/case-a/dossier?at=x").statusCode());
            assertEquals(422, get(c, "/inv/investigations/case-a/dossier?format=pdf").statusCode());
            assertEquals(422, post(c, "/inv/investigations/case-a/dossier/verify", "{}").statusCode());
            JsonNode manifest = data(get(c, "/inv/investigations/case-a/dossier")).get("manifest");
            ObjectNode other = manifest.deepCopy();
            other.put("investigation", "case-z");
            assertEquals(422, post(c, "/inv/investigations/case-a/dossier/verify", other.toString()).statusCode());
            assertEquals(200, post(c, "/inv/investigations/case-a/dossier/verify", manifest.toString()).statusCode(),
                    "a bare manifest is accepted as well as {manifest}");
        }
    }

    /** Owner-only with an ARMED Authenticator — with no Subject ownership is unenforced, so this is the only proof. */
    @Test
    void onlyTheOwnerGetsTheDossier(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/inv/investigations", CREATE, "Bearer owner").statusCode());
            assertEquals(200, send(c.port, "POST", "/inv/investigations/case-a/ops",
                    "{\"op\":\"seed\",\"ids\":[\"alice\"]}", "Bearer owner").statusCode());
            HttpResponse<String> mine = send(c.port, "GET", "/inv/investigations/case-a/dossier", null, "Bearer owner");
            JsonNode manifest = data(mine).get("manifest");
            assertEquals("analyst-1", data(mine).at("/summary/owner").asText());

            assertEquals(404, send(c.port, "GET", "/inv/investigations/case-a/dossier", null, "Bearer other").statusCode());
            assertEquals(404, send(c.port, "POST", "/inv/investigations/case-a/dossier/verify",
                    manifest.toString(), "Bearer other").statusCode());

            // R3: the bound Dataset shared away from the owner makes the dossier read as absent too.
            new ComponentStore(root.resolve("registry")).write("dataset", "calls_ds",
                    Map.of("view", "calls_view", "owner", "someone-else", "shares", List.of()));
            assertEquals(404, send(c.port, "GET", "/inv/investigations/case-a/dossier", null, "Bearer owner").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }
}
