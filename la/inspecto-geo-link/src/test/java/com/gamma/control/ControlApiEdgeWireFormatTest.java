package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.la.api.ProjectionCache;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-P10-1 - the compact edge wire format of {@code POST /inv/projection} and {@code /inv/projection/neighbors} over the
 * real dispatcher with an ARMED authenticator: the versioned media type, column names once and rows as arrays, masking off
 * and on, the 304 path, and the R3 404 (plain JSON, no ETag) for a viewer the Dataset is not shared with.
 */
class ControlApiEdgeWireFormatTest {

    @BeforeAll static void raiseBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String V2 = "application/vnd.inspecto.la-edges.v2+json";
    private static final String OWNER = "tok-owner", STRANGER = "tok-stranger";
    private static final String BODY = "{\"dataset\":\"d_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"linkKindCol\":\"k\",\"attrCols\":[\"v\"]}";
    private static final String[][] ROUTES = {{"inv/projection", BODY}, {"inv/projection/neighbors",
            "{\"dataset\":\"d_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"linkKindCol\":\"k\",\"attrCols\":[\"v\"],\"value\":\"B\"}"}};
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        InputFingerprintCache.forTest(null, 0);
        ProjectionCache.forTest(null, 0);
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        ProjectionCache.forTest(null, 0);
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of()));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of()));
            default -> Optional.empty();
        });
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            String store = "edgewire_" + System.nanoTime();
            new ComponentStore(root.resolve("registry")).write("dataset", "d_ds", Map.of("physicalRef", store, "owner", "analyst-1",
                    "shares", List.of(Map.of("subjectType", "user", "subjectId", "analyst-2", "access", "view"))));
            Path file = Path.of("database").resolve(store).resolve("p1.parquet");
            Files.createDirectories(file.getParent());
            DuckDbUtil.loadDriver();
            java.io.File db = DuckDbUtil.tempDbFile("edgewire_");
            try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * FROM (VALUES ('A','B','voice','x'),('A','B','voice','x'),('B','C','sms',NULL)) AS r(s,t,k,v)) TO '"
                        + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            } finally {
                DuckDbUtil.deleteTempDb(db);
            }
            return new Ctx(svc, api, root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(Ctx c, String route, String body, String token, String ifNoneMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1/" + route))
                .header("Content-Type", "application/json").header("Authorization", token)
                .method("POST", BodyPublishers.ofString(body));
        if (ifNoneMatch != null) b.header("If-None-Match", ifNoneMatch);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static String type(HttpResponse<String> r) {
        return r.headers().firstValue("Content-Type").orElse(null);
    }

    @Test
    void bothRoutesAnswerTheCompactShapeUnderTheVersionedMediaType(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: none\n");
            for (String[] rq : ROUTES) {
                HttpResponse<String> r = post(c, rq[0], rq[1], OWNER, null);
                assertEquals(200, r.statusCode(), r.body());
                assertEquals(V2, type(r), rq[0]);
                JsonNode d = JSON.readTree(r.body()).get("data");
                assertEquals(JSON.readTree("[\"source\",\"target\",\"kind\",\"count\",\"v\"]"), d.get("columns"), rq[0]);
                assertEquals(JSON.readTree("[\"v\"]"), d.get("attrCols"));
                assertEquals(JSON.readTree("[\"A\",\"B\",\"voice\",2,\"x\"]"), d.get("rows").get(0), "heaviest first, one array per row");
                assertEquals(JSON.readTree("[\"B\",\"C\",\"sms\",1,null]"), d.get("rows").get(1));
                assertFalse(d.get("masking").get("masked").asBoolean());
                assertTrue(d.has("truncated") && d.has("columnTypes"));
                // decoded, it is the old object row
                assertEquals("A", EdgeWireTestRows.decoded(d).get("rows").get(0).get("source").asText());
                assertEquals("x", EdgeWireTestRows.decoded(d).get("rows").get(0).get("attrs").get("v").asText());
            }
        }
    }

    @Test
    void aMaskedReadCarriesAliasesInTheArrays(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: all\n");
            for (String[] rq : ROUTES) {
                String body = rq[0].endsWith("neighbors") ? rq[1].replace("\"value\":\"B\"", "\"value\":\"" + aliasOfB(c) + "\"") : rq[1];
                HttpResponse<String> r = post(c, rq[0], body, OWNER, null);
                assertEquals(200, r.statusCode(), r.body());
                assertEquals(V2, type(r));
                JsonNode d = JSON.readTree(r.body()).get("data");
                assertTrue(d.get("masking").get("masked").asBoolean());
                for (JsonNode row : d.get("rows")) {
                    assertTrue(row.isArray());
                    assertNotEquals("A", row.get(0).asText());
                    assertNotEquals("B", row.get(1).asText());
                    assertNotEquals("C", row.get(1).asText());
                    assertTrue(row.get(0).asText().startsWith("masked:"), row.toString());
                }
            }
        }
    }

    private String aliasOfB(Ctx c) throws Exception {
        JsonNode d = JSON.readTree(post(c, "inv/projection", BODY, OWNER, null).body()).get("data");
        return d.get("rows").get(0).get(1).asText();   // (A,B) is the heaviest row: its target is B's alias
    }

    @Test
    void the304PathStillWorksAndARevalidated200IsCompact(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (String[] rq : ROUTES) {
                HttpResponse<String> first = post(c, rq[0], rq[1], OWNER, null);
                String tag = first.headers().firstValue("ETag").orElse(null);
                assertNotNull(tag, rq[0]);
                HttpResponse<String> again = post(c, rq[0], rq[1], OWNER, tag);
                assertEquals(304, again.statusCode(), again.body());
                assertEquals("", again.body());
                HttpResponse<String> plain = post(c, rq[0], rq[1], OWNER, null);   // the cached body, re-served compact
                assertEquals(V2, type(plain));
                assertEquals(JSON.readTree(first.body()).get("data"), JSON.readTree(plain.body()).get("data"));
            }
        }
    }

    @Test
    void aViewerWithoutAccessGetsAPlainJson404WithNoETag(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (String[] rq : ROUTES) {
                HttpResponse<String> r = post(c, rq[0], rq[1], STRANGER, "*");
                assertEquals(404, r.statusCode(), r.body());
                assertEquals("application/json", type(r), "an error is not the edges media type");
                assertTrue(r.headers().firstValue("ETag").isEmpty());
            }
            HttpResponse<String> unknown = post(c, "inv/projection", BODY.replace("d_ds", "nope"), OWNER, null);
            assertEquals(404, unknown.statusCode());
            assertEquals("application/json", type(unknown));
        }
    }

    /** The read itself fails (a measure column the Dataset lacks) AFTER the cache lookup: still plain JSON, no ETag. */
    @Test
    void aReadThatFailsAfterTheCacheLookupIsPlainJsonWithNoETag(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (String[] rq : ROUTES) {
                HttpResponse<String> r = post(c, rq[0], rq[1].replace("\"attrCols\"", "\"measureCol\":\"no_such_col\",\"attrCols\""), OWNER, null);
                assertEquals(422, r.statusCode(), r.body());
                assertTrue(r.body().contains("projection failed"), r.body());
                assertEquals("application/json", type(r), rq[0] + ": an error is not the edges media type");
                assertTrue(r.headers().firstValue("ETag").isEmpty(), rq[0]);
            }
        }
    }

    @Test
    void anUnauthenticatedRequestIs401(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (String[] rq : ROUTES) assertEquals(401, post(c, rq[0], rq[1], "nobody", null).statusCode());
        }
    }
}
