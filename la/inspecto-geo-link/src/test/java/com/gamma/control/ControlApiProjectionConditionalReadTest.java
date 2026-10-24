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
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-P4-2 - the conditional read of {@code POST /inv/projection} and {@code /inv/projection/neighbors} over the real
 * dispatcher with an ARMED authenticator (every request carries a Subject, so the R3 view gate is live). A 304 must come
 * only from a cached answer of the SAME viewer, Space, body, data and settings, and only after every gate has passed.
 */
class ControlApiProjectionConditionalReadTest {

    @BeforeAll static void raiseBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "tok-owner", VIEWER = "tok-viewer", STRANGER = "tok-stranger";
    private static final String BODY = "{\"dataset\":\"d_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"}";
    private static final String NEIGHBOURS = "{\"dataset\":\"d_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\",\"value\":\"B\"}";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, Path root, Path dir) implements AutoCloseable {
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
            case VIEWER -> Optional.of(new Subject("analyst-2", Set.of()));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of()));
            default -> Optional.empty();
        });
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            String store = "condread_" + System.nanoTime();
            new ComponentStore(root.resolve("registry")).write("dataset", "d_ds", Map.of("physicalRef", store, "owner", "analyst-1",
                    "shares", List.of(Map.of("subjectType", "user", "subjectId", "analyst-2", "access", "view"))));
            Ctx c = new Ctx(svc, api, root, Path.of("database").resolve(store));
            plant(c.dir().resolve("p1.parquet"), "('A','B'),('A','B'),('B','C')", 1_700_000_000_000L);
            return c;
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static void plant(Path file, String rows, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("condread_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + rows + ") AS v(s,t)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    private HttpResponse<String> post(Ctx c, String route, String body, String token, String ifNoneMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api().port() + "/api/v1/" + route))
                .header("Content-Type", "application/json").header("Authorization", token)
                .method("POST", BodyPublishers.ofString(body));
        if (ifNoneMatch != null) b.header("If-None-Match", ifNoneMatch);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static String etag(HttpResponse<String> r) {
        return r.headers().firstValue("ETag").orElse(null);
    }

    private static JsonNode rows(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data").get("rows");
    }

    @Test
    void aSecondIdenticalReadIs304WithNoBodyAndNoDatasetRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            for (String[] rq : new String[][] {{"inv/projection", BODY}, {"inv/projection/neighbors", NEIGHBOURS}}) {
                HttpResponse<String> first = post(c, rq[0], rq[1], OWNER, null);
                assertEquals(2, rows(first).size(), first.body());
                String tag = etag(first);
                assertNotNull(tag, rq[0] + " must publish an ETag");
                long reads = ProjectionCache.reads();
                HttpResponse<String> again = post(c, rq[0], rq[1], OWNER, tag);
                assertEquals(304, again.statusCode(), again.body());
                assertEquals("", again.body());
                assertEquals(tag, etag(again));
                assertEquals(reads, ProjectionCache.reads(), rq[0] + ": a 304 must not read the Dataset");
                HttpResponse<String> plain = post(c, rq[0], rq[1], OWNER, null);   // no validator: the cached body, still no read
                assertEquals(JSON.readTree(first.body()).get("data"), JSON.readTree(plain.body()).get("data"));
                assertEquals(reads, ProjectionCache.reads());
            }
        }
    }

    @Test
    void aNewPartitionFileAnswersAFresh200(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> first = post(c, "inv/projection", BODY, OWNER, null);
            assertEquals(2, rows(first).size());
            plant(c.dir().resolve("p2.parquet"), "('C','D')", 1_700_000_100_000L);
            long reads = ProjectionCache.reads();
            HttpResponse<String> after = post(c, "inv/projection", BODY, OWNER, etag(first));
            assertEquals(3, rows(after).size(), "the added file's rows are read");
            assertNotEquals(etag(first), etag(after));
            assertEquals(reads + 1, ProjectionCache.reads());
        }
    }

    /** A different viewer never gets the owner's cached entry: its own key, its own read, its own ETag. */
    @Test
    void anotherPermittedViewerNeverGetsTheOwnersCachedAnswer(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> owner = post(c, "inv/projection", BODY, OWNER, null);
            assertEquals(200, owner.statusCode());
            long reads = ProjectionCache.reads();
            HttpResponse<String> viewer = post(c, "inv/projection", BODY, VIEWER, etag(owner));
            assertEquals(200, viewer.statusCode(), "the owner's ETag must not be a 304 for another viewer");
            assertNotEquals(etag(owner), etag(viewer));
            assertEquals(reads + 1, ProjectionCache.reads(), "the viewer's answer is its own read, not the owner's cached body");
        }
    }

    /** The view gate runs before the cache: a viewer the Dataset is not shared with gets the R3 404, never a 304. */
    @Test
    void aViewerWithoutAccessSendingTheOwnersETagGets404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> owner = post(c, "inv/projection", BODY, OWNER, null);
            assertEquals(200, owner.statusCode());
            assertEquals(304, post(c, "inv/projection", BODY, OWNER, etag(owner)).statusCode(), "control: the owner's ETag is live");
            HttpResponse<String> stranger = post(c, "inv/projection", BODY, STRANGER, etag(owner));
            assertEquals(404, stranger.statusCode(), stranger.body());
            assertTrue(etag(stranger) == null, "a refusal carries no ETag");
            HttpResponse<String> strangerNb = post(c, "inv/projection/neighbors", NEIGHBOURS, STRANGER, "*");
            assertEquals(404, strangerNb.statusCode(), strangerNb.body());
        }
    }

    @Test
    void aDifferentBodyIsADifferentETag(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String a = etag(post(c, "inv/projection", BODY, OWNER, null));
            HttpResponse<String> limited = post(c, "inv/projection", BODY.replace("}", ",\"limit\":1}"), OWNER, a);
            assertEquals(200, limited.statusCode());
            assertEquals(1, rows(limited).size());
            assertNotEquals(a, etag(limited));
            // key order of the body does not matter: the canonical body is hashed
            String reordered = "{\"targetCol\":\"t\",\"sourceCol\":\"s\",\"dataset\":\"d_ds\"}";
            assertEquals(304, post(c, "inv/projection", reordered, OWNER, a).statusCode());
        }
    }

    @Test
    void aMaskingSettingsChangeInvalidates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> plain = post(c, "inv/projection", BODY, OWNER, null);
            assertEquals("A", rows(plain).get(0).get("source").asText());
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: all\n");
            HttpResponse<String> masked = post(c, "inv/projection", BODY, OWNER, etag(plain));
            assertEquals(200, masked.statusCode(), "a settings change must not answer 304 for the unmasked body");
            assertNotEquals("A", rows(masked).get(0).get("source").asText(), "ids are aliases now");
            assertNotEquals(etag(plain), etag(masked));
        }
    }
}
