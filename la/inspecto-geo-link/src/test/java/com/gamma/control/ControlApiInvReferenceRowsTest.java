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
 * {@code POST /inv/reference-rows} over real HTTP: every gate (401 · 403 · 404 · 413 · 422 · 503), raw vs masked Spaces,
 * alias and Investigation-pseudonym resolution, and injection attempts in {@code keyCol} / {@code keys}. Ids are
 * phone-number shaped so a body scan for them cannot match ordinary words.
 */
class ControlApiInvReferenceRowsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003";
    private static final String ALT = "27829990001", CLASH = "real-column-value";
    private static final List<String> RAW = List.of(A, B, C, ALT);
    private static final String ROUTE = "/inv/reference-rows";
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

    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
                ViewStore views = new ViewStore(writeRoot.resolve("views"));
                views.write(new ViewDefinition("calls_view", "flow-x", List.of(),
                        "SELECT caller, callee FROM (VALUES ('" + A + "','" + B + "'),('" + B + "','" + C + "')) AS t(caller,callee)",
                        "2026-10-11T00:00:00Z"));
                views.write(new ViewDefinition("ind_view", "flow-x", List.of(),
                        "SELECT msisdn, score, owner_name, alt_id FROM (VALUES ('" + A + "',80.5,'Alice',CAST(" + ALT + " AS BIGINT)),('"
                                + B + "',10.0,'Bob',NULL),('" + C + "',0.0,'Carol',NULL)) AS t(msisdn,score,owner_name,alt_id)",
                        "2026-10-11T00:00:00Z"));
                views.write(new ViewDefinition("clash_view", "flow-x", List.of(),
                        "SELECT msisdn, '" + CLASH + "' AS __ref_key FROM (VALUES ('" + A + "'),('" + B + "')) AS t(msisdn)",
                        "2026-10-11T00:00:00Z"));
                ComponentStore reg = new ComponentStore(writeRoot.resolve("registry"));
                reg.write("dataset", "calls_ds", Map.of("view", "calls_view"));
                reg.write("dataset", "ind_ds", Map.of("view", "ind_view",
                        "columns", List.of(Map.of("name", "owner_name", "classification", "PII"))));
                reg.write("dataset", "clash_ds", Map.of("view", "clash_view"));
            }
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> send(Ctx c, String path, String body) throws Exception {
        return send(c, path, body, null);
    }

    private JsonNode ok(Ctx c, String path, String body, boolean rawShown) throws Exception {
        HttpResponse<String> r = send(c, path, body);
        assertEquals(200, r.statusCode(), path + " -> " + r.body());
        if (!rawShown)
            for (String raw : RAW) assertFalse(r.body().contains(raw), path + " leaked " + raw + ": " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String ask(String keys) {
        return "{\"dataset\":\"ind_ds\",\"keyCol\":\"msisdn\",\"keys\":" + keys + "}";
    }

    private static String q(String... keys) throws Exception {
        return JSON.writeValueAsString(List.of(keys));
    }

    @Test
    void noneShowsRawRowsForRawKeysAndNarrowsToTheAskedColumns(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode d = ok(c, ROUTE, ask(q(A, C, "27829999999")), true);
            assertEquals(2, d.get("rows").size(), d.toString());
            assertEquals(A, d.at("/rows/0/key").asText());
            assertEquals(80, d.at("/rows/0/row/score").asInt());
            assertEquals("Alice", d.at("/rows/0/row/owner_name").asText(), "none shows even a PII column raw");
            assertEquals(2, d.get("matchedKeys").asInt());
            assertFalse(d.at("/masking/masked").asBoolean());
            assertFalse(d.get("truncated").asBoolean());

            JsonNode narrow = ok(c, ROUTE, "{\"dataset\":\"ind_ds\",\"keyCol\":\"msisdn\",\"keys\":" + q(B)
                    + ",\"columns\":[\"score\"]}", true);
            assertEquals(List.of("msisdn", "score"), names(narrow.get("columns")), "the key column always rides along");
            assertFalse(narrow.at("/rows/0/row").has("owner_name"));
        }
    }

    @Test
    void aMaskedSpaceResolvesAliasesAndMasksTheKeyAndSensitiveColumns(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            JsonNode proj = ok(c, "/inv/projection", "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", false);
            String aliasA = null;
            for (JsonNode r : proj.get("rows")) if (aliasA == null) aliasA = r.get("source").asText();
            // projection rows are heaviest-first then by source; find A's alias as the source of the A->B row
            JsonNode d = ok(c, ROUTE, ask(q(aliasA, A, "masked:0000000000000000")), false);
            assertEquals(1, d.get("rows").size(), d.toString());
            assertEquals(aliasA, d.at("/rows/0/key").asText(), "rows carry the key AS ASKED");
            assertEquals(aliasA, d.at("/rows/0/row/msisdn").asText(), "the key column shows the same Space alias");
            assertTrue(d.at("/rows/0/row/owner_name").asText().startsWith("masked:"), "a PII column is masked");
            assertTrue(d.at("/rows/0/row/score").isNumber(), "a fractional indicator score stays a number");
            assertTrue(d.at("/rows/0/row/alt_id").asText().startsWith("masked:"),
                    "under `all` an unclassified BIGINT may be an id: masked like text, never raw");
            assertEquals(List.of("masked:0000000000000000"), names(d.get("unresolved")), "an unknown alias names nothing");
            assertEquals(1, d.get("rawKeysRefused").asInt(), "a raw key names nothing under masking, and is counted, not echoed");
            assertTrue(d.at("/masking/masked").asBoolean());
            assertTrue(names(d.at("/masking/maskedColumns")).contains("owner_name"));
        }
    }

    @Test
    void typedModeResolvesAnAliasOnlyAgainstAKeyColumnTheMaskingCovers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: typed\n")) {
            ComponentStore reg = new ComponentStore(root.resolve("registry"));
            reg.write("dataset", "calls_ds", Map.of("view", "calls_view", "columns", List.of(
                    Map.of("name", "caller", "classification", "MSISDN"), Map.of("name", "callee", "classification", "MSISDN"))));
            JsonNode proj = ok(c, "/inv/projection", "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", false);
            String aliasA = proj.at("/rows/0/source").asText();
            assertTrue(aliasA.startsWith("masked:"));

            // ind_ds.msisdn is unclassified: matching the alias's raw value there would be a yes/no membership oracle
            JsonNode oracle = ok(c, ROUTE, ask(q(aliasA)), false);
            assertEquals(0, oracle.get("rows").size(), oracle.toString());
            assertEquals(List.of(aliasA), names(oracle.get("unresolved")));

            // positive twin: once the key column is one the masking covers, the same alias resolves
            reg.write("dataset", "ind_ds", Map.of("view", "ind_view", "columns", List.of(
                    Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "owner_name", "classification", "PII"),
                    Map.of("name", "alt_id", "classification", "IMSI"))));   // typed masks what is classified (an IMSI)
            JsonNode covered = ok(c, ROUTE, ask(q(aliasA)), false);
            assertEquals(1, covered.get("rows").size(), covered.toString());
            assertEquals(aliasA, covered.at("/rows/0/row/msisdn").asText());
        }
    }

    @Test
    void aRealColumnNamedLikeTheInternalKeyAliasIsNeitherShadowedNorBreaksTheMatch(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            JsonNode d = ok(c, ROUTE, "{\"dataset\":\"clash_ds\",\"keyCol\":\"msisdn\",\"keys\":" + q(A) + "}", true);
            assertEquals(1, d.get("rows").size(), d.toString());
            assertEquals(A, d.at("/rows/0/key").asText());
            assertEquals(CLASH, d.at("/rows/0/row/__ref_key").asText(), "the real column keeps its own value");
        }
    }

    @Test
    void anInvestigationPseudonymResolvesWithItsInvestigationId(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            JsonNode proj = ok(c, "/inv/projection", "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}", false);
            String alias = proj.at("/rows/0/source").asText();
            assertEquals(200, send(c, "/inv/investigations", "{\"id\":\"ref-inv\",\"purpose\":\"reference rows\","
                    + "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}").statusCode());
            assertEquals(200, send(c, "/inv/investigations/ref-inv/ops", "{\"op\":\"seed\",\"ids\":[\"" + alias + "\"]}").statusCode());
            HttpResponse<String> wsr = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/inv/investigations/ref-inv/working-set")).GET().build(), BodyHandlers.ofString());
            String pseudonym = JSON.readTree(wsr.body()).at("/data/rows/0/entityId").asText();
            assertNotEquals(alias, pseudonym);

            JsonNode without = ok(c, ROUTE, ask(q(pseudonym)), false);
            assertEquals(0, without.get("rows").size(), "an Investigation pseudonym needs its Investigation");
            JsonNode with = ok(c, ROUTE, "{\"dataset\":\"ind_ds\",\"keyCol\":\"msisdn\",\"investigationId\":\"ref-inv\",\"keys\":"
                    + q(pseudonym) + "}", false);
            assertEquals(1, with.get("rows").size(), with.toString());
            assertEquals(pseudonym, with.at("/rows/0/key").asText());
        }
    }

    @Test
    void injectionInKeyColIsRefusedAndInKeysIsJustAValue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            for (String bad : List.of("msisdn\\\" OR 1=1 --", "msisdn; DROP TABLE x", "a b"))
                assertEquals(422, send(c, ROUTE, "{\"dataset\":\"ind_ds\",\"keyCol\":\"" + bad + "\",\"keys\":[\"1\"]}").statusCode(), bad);
            assertEquals(422, send(c, ROUTE, "{\"dataset\":\"ind_ds\",\"keyCol\":\"msisdn\",\"keys\":[\"1\"],\"columns\":[\"score) --\"]}").statusCode());
            JsonNode d = ok(c, ROUTE, ask(q("x' OR '1'='1", "') OR 1=1 --", A + "' OR msisdn IS NOT NULL --")), true);
            assertEquals(0, d.get("rows").size(), "a key is a bound value, never SQL: " + d);
        }
    }

    @Test
    void gates422413404And503(@TempDir Path cfg, @TempDir Path root, @TempDir Path cfg2) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            assertEquals(422, send(c, ROUTE, "{\"keyCol\":\"msisdn\",\"keys\":[\"1\"]}").statusCode(), "no dataset");
            assertEquals(422, send(c, ROUTE, "{\"dataset\":\"ind_ds\",\"keys\":[\"1\"]}").statusCode(), "no keyCol");
            assertEquals(422, send(c, ROUTE, ask("[]")).statusCode(), "no keys");
            assertEquals(422, send(c, ROUTE, ask("[1]")).statusCode(), "a non-string key");
            assertEquals(422, send(c, ROUTE, ask("[\" \"]")).statusCode(), "a blank key");
            assertEquals(422, send(c, ROUTE, "{\"dataset\":\"ind_ds\",\"keyCol\":\"nope\",\"keys\":[\"1\"]}").statusCode(), "unknown keyCol");
            assertEquals(422, send(c, ROUTE, "{\"dataset\":\"ind_ds\",\"keyCol\":\"msisdn\",\"keys\":[\"1\"],\"columns\":[\"nope\"]}").statusCode());
            List<String> many = new ArrayList<>();
            for (int i = 0; i < 501; i++) many.add("k" + i);
            HttpResponse<String> big = send(c, ROUTE, ask(JSON.writeValueAsString(many)));
            assertEquals(413, big.statusCode(), big.body());
            assertEquals(200, send(c, ROUTE, ask(JSON.writeValueAsString(many.subList(0, 500)))).statusCode(), "500 is the cap, not over it");
            assertEquals(404, send(c, ROUTE, "{\"dataset\":\"ghost\",\"keyCol\":\"msisdn\",\"keys\":[\"1\"]}").statusCode());
            assertEquals(404, send(c, ROUTE, "{\"dataset\":\"ind_ds\",\"keyCol\":\"msisdn\",\"investigationId\":\"ghost\",\"keys\":[\"1\"]}").statusCode());
        }
        try (Ctx c = open(cfg2, null, "")) {
            assertEquals(503, send(c, ROUTE, ask(q(A))).statusCode(), "no write root");
        }
    }

    @Test
    void fourEyesBudget403AndUnauthenticated401(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\nfour_eyes_budget_above: 1\n")) {
            HttpResponse<String> held = send(c, ROUTE, ask(q(A, B)));
            assertEquals(403, held.statusCode(), held.body());
            assertEquals(200, send(c, ROUTE, ask(q(A))).statusCode(), "within the budget");

            Authenticators.forTest(ex -> "Bearer analyst".equals(ex.getRequestHeaders().getFirst("Authorization"))
                    ? Optional.of(new Subject("analyst-1", Set.of("canManageIncidents"))) : Optional.empty());
            assertEquals(401, send(c, ROUTE, ask(q(A)), "Bearer nobody").statusCode());
            assertEquals(200, send(c, ROUTE, ask(q(A)), "Bearer analyst").statusCode());
        }
    }

    private static List<String> names(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
