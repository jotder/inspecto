package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceManager;
import com.gamma.util.DuckDbUtil;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-RISK-SCORE-RESIDUALS-1 S3 over real HTTP with an ARMED Subject: {@code POST /risk-scores/preview} scores ONE
 * entity under a saved or unsaved model and writes nothing; the read gate, the extra authoring gate for unsaved
 * content, data scopes, body shape and the masked entity key all hold.
 */
class ControlApiRiskScorePreviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private static final Set<String> CAPS = Set.of("canWorkIncidents", "canAuthorWorkbench");
    private static final String PREVIEW = "/risk-scores/preview";

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer analyst".equals(auth)) return Optional.of(new Subject("ana", CAPS));
        if ("Bearer revealer".equals(auth))
            return Optional.of(new Subject("rev", Set.of("canWorkIncidents", "canAuthorWorkbench", "canRevealLinkEntities")));
        if ("Bearer reader".equals(auth)) return Optional.of(new Subject("rd", Set.of("canWorkIncidents")));
        if ("Bearer billing".equals(auth)) return Optional.of(new Subject("bo", CAPS, Set.of("billing")));
        if ("Bearer nocap".equals(auth)) return Optional.of(new Subject("nc", Set.of("canAuthorWorkbench")));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config, Path data) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); }
    }

    /** Two entities in one Dataset: m1 (two FAILED top-ups, 25 spent) and m2 (one OK top-up, 7 spent). */
    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("s1");
        Path config = base.resolve("config");
        Path data = base.resolve("data");
        Files.createDirectories(config.resolve("inbox"));
        Files.createDirectories(base.resolve("duckdb"));
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        DuckDbUtil.loadDriver();
        Files.createDirectories(data.resolve("topups"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('m1','FAILED',10.0,'t1'), ('m1','FAILED',15.0,'t2'), "
                    + "('m2','OK',7.0,'t3')) AS v(msisdn, status, amount, topup_id)) TO '"
                    + data.resolve("topups").resolve("data.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        new ComponentStore(config.resolve("registry")).write("dataset", "topups", Map.of("physicalRef", "topups"));
        Authenticators.forTest(FAKE);
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config, data);
    }

    private static Map<String, Object> model(String id, String scope) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) m.put("id", id);
        m.put("entityType", "subscriber");
        m.put("highThreshold", 50);
        if (scope != null) m.put("dataScope", scope);
        m.put("factors", List.of(
                Map.of("id", "failed", "dataset", "topups", "key", "msisdn", "measure", "count",
                        "filters", List.of(Map.of("field", "status", "op", "=", "value", "FAILED")),
                        "weight", 30, "cap", 45, "evidence", List.of("topup_id")),
                Map.of("id", "spend", "dataset", "topups", "key", "msisdn", "measure", "sum(amount)", "weight", 1)));
        return m;
    }

    private void save(Ctx c, String id, String scope) throws Exception {
        new ComponentStore(c.config.resolve("registry")).write("risk-score", id, model(null, scope));
    }

    private HttpResponse<String> preview(Ctx c, Map<String, Object> body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/s1" + PREVIEW))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (who != null) b.header("Authorization", "Bearer " + who);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static List<String> dataDirs(Ctx c) throws Exception {
        try (Stream<Path> s = Files.list(c.data)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aSavedModelPreviewsOneEntityAndWritesNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            save(c, "subs", null);
            HttpResponse<String> r = preview(c, Map.of("model", "subs", "entityKey", "m1"), "revealer");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertEquals(70.0, d.get("score").asDouble(), 1e-9, "min(30x2, 45) + 1x25, over m1's rows only");
            assertTrue(d.get("high").asBoolean());
            assertTrue(d.get("found").asBoolean());
            assertTrue(d.get("saved").asBoolean());
            assertEquals("m1", d.get("entityKey").asText(), "the unmask capability sees the raw key");
            assertEquals(2, d.get("factors").get(0).get("evidence").size(), "m1's evidence rows only");
            assertEquals(List.of("topups"), dataDirs(c), "no scores Dataset, _latest or anything else written");
        }
    }

    @Test
    void unsavedContentPreviewsForAnAuthorAndTheKeyIsMaskedWithoutTheUnmaskCapability(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> r = preview(c, Map.of("content", model("draft", null), "entityKey", "m2"), "analyst");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertFalse(d.get("saved").asBoolean());
            assertEquals(7.0, d.get("score").asDouble(), 1e-9, "no FAILED top-up, 7 spent");
            assertTrue(d.get("keyMasked").asBoolean());
            assertTrue(d.get("entityKey").asText().startsWith("masked:"), d.get("entityKey").asText());
            assertFalse(r.body().contains("\"m2\""), "the raw key never leaves the server");
            assertFalse(new ComponentStore(c.config.resolve("registry")).exists("risk-score", "draft"), "nothing saved");
            assertEquals(List.of("topups"), dataDirs(c));

            assertEquals(403, preview(c, Map.of("content", model("draft", null), "entityKey", "m2"), "reader").statusCode(),
                    "unsaved content needs canAuthorWorkbench");
            Map<String, Object> bad = model("bad", null);
            bad.put("factors", List.of(Map.of("id", "x", "dataset", "topups", "key", "imsi", "measure", "count", "weight", 1)));
            HttpResponse<String> b = preview(c, Map.of("content", bad, "entityKey", "m1"), "analyst");
            assertEquals(422, b.statusCode(), b.body());
            assertTrue(b.body().contains("imsi"), "the save-time message, verbatim: " + b.body());
        }
    }

    @Test
    void aSavedModelPreviewNeedsOnlyTheReadGate(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            save(c, "subs", null);
            assertEquals(200, preview(c, Map.of("model", "subs", "entityKey", "m1"), "reader").statusCode());
        }
    }

    @Test
    void gatesAndBodyShapeFailClosed(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            save(c, "subs", null);
            Map<String, Object> ok = Map.of("model", "subs", "entityKey", "m1");
            assertEquals(401, preview(c, ok, null).statusCode());
            assertEquals(403, preview(c, ok, "nocap").statusCode());
            assertEquals(400, preview(c, Map.of("entityKey", "m1"), "analyst").statusCode(), "neither model nor content");
            assertEquals(400, preview(c, Map.of("model", "subs", "content", model("x", null), "entityKey", "m1"), "analyst")
                    .statusCode(), "both");
            assertEquals(400, preview(c, Map.of("model", "subs"), "analyst").statusCode(), "no key");
            assertEquals(400, preview(c, Map.of("model", "subs", "entityKey", "k".repeat(257)), "analyst").statusCode());
            assertEquals(404, preview(c, Map.of("model", "nope", "entityKey", "m1"), "analyst").statusCode());
        }
    }

    @Test
    void scopesHideModelsAndTheKeyIsALiteralNotSql(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            save(c, "fraud_subs", "fraud");
            assertEquals(404, preview(c, Map.of("model", "fraud_subs", "entityKey", "m1"), "billing").statusCode(),
                    "out of scope reads as absence");
            assertEquals(403, preview(c, Map.of("content", model("d", "fraud"), "entityKey", "m1"), "billing").statusCode(),
                    "content outside the caller's scopes");
            assertEquals(200, preview(c, Map.of("content", model("d", "billing"), "entityKey", "m1"), "billing").statusCode());

            save(c, "subs", null);
            HttpResponse<String> r = preview(c, Map.of("model", "subs", "entityKey", "m1' OR '1'='1"), "revealer");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode d = V1Body.of(r.body());
            assertFalse(d.get("found").asBoolean(), "the key is compared, never spliced");
            assertEquals(0.0, d.get("score").asDouble(), 1e-9);
        }
    }
}
