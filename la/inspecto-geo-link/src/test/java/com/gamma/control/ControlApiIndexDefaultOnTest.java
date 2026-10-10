package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.api.InputFingerprintCache;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DEMO-INDEX-1 (operator decision A, 2026-10-10) - the link index is ON BY DEFAULT, so it must be safe on every read route that can
 * use it. A Space with NO {@code link-analysis.toon} at all: every route ({@code recursive-paths}, {@code neighbors}, the temporal scan)
 * answers from the flat Dataset when no index exists ({@code no_index}), from the index when a fresh one does, and falls back with
 * the closed {@code index_stale_refused} when a file was rewritten or removed; an added file is served flagged stale (the documented
 * decision 6a); an explicit {@code enabled: false} still switches the index off ({@code index_disabled}); and a read NEVER starts a build.
 * (The expand route's twin is pinned by {@code ControlApiInvIndexedExpansionTest}; graph runs read the index only on an explicit
 * {@code input:"index"}.)
 */
class ControlApiIndexDefaultOnTest {

    @BeforeAll static void raiseBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner";
    private static final long T0 = 1_700_000_000_000L;
    private static final String COLS = "\"dataset\":\"d_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"";
    private static final String BUILD = "{" + COLS + ",\"timeCol\":\"ts\"}";
    private static final String ROWS = "('A','B',TIMESTAMP '2026-03-01 09:00:00'),('A','B',TIMESTAMP '2026-03-01 09:00:05'),"
            + "('B','C',TIMESTAMP '2026-03-02 09:00:00')";
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
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        AtomicLong tick = new AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);          // the files move between reads: always re-list
        Authenticators.forTest(ex -> OWNER.equals(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")))
                ? Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents"))) : Optional.empty());
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();                                                                  // NO link-analysis.toon is written anywhere
            String store = "defon_" + System.nanoTime();
            new ComponentStore(root.resolve("registry")).write("dataset", "d_ds",
                    Map.of("physicalRef", store, "owner", "analyst-1", "shares", List.of()));
            return new Ctx(svc, api, root, Path.of("database").resolve(store));
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.api.port() + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", OWNER);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r) throws Exception {
        assertTrue(r.statusCode() == 200 || r.statusCode() == 202, r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void build(Ctx c) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds", BUILD)).get("buildId").asText();
        for (int i = 0; i < 6_000; i++) {
            String st = data(send(c, "GET", "/inv/index/builds/" + id, null)).get("status").asText();
            if (st.equals("COMPLETED")) return;
            assertFalse(st.equals("FAILED") || st.equals("CANCELLED"), st);
            Thread.sleep(10);
        }
        throw new AssertionError("build never completed");
    }

    private static void plant(Path file, long mtime) throws Exception {
        Files.createDirectories(file.getParent());
        DuckDbUtil.loadDriver();
        java.io.File db = DuckDbUtil.tempDbFile("defon_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + ROWS + ") AS v(s,t,ts)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    /** Each read route that can use the index, as the {@code source} object of its answer. */
    private List<JsonNode> sources(Ctx c) throws Exception {
        return List.of(
                data(send(c, "POST", "/inv/traversal/recursive-paths", "{" + COLS + ",\"startNode\":\"A\",\"maxDepth\":2}")).get("source"),
                data(send(c, "POST", "/inv/projection/neighbors", "{" + COLS + ",\"value\":\"A\"}")).get("source"),
                data(send(c, "POST", "/inv/pattern/temporal", "{" + COLS + ",\"timeCol\":\"ts\",\"mode\":\"burst\",\"windowSeconds\":30,\"minEvents\":2}")).get("source"));
    }

    private static void all(List<JsonNode> sources, String kind, String reason) {
        for (int i = 0; i < sources.size(); i++) {
            JsonNode s = sources.get(i);
            assertEquals(kind, s.get("kind").asText(), "route " + i + ": " + s);
            if (reason != null) assertEquals(reason, s.get("reason").asText(), "route " + i + ": " + s);
        }
    }

    @Test
    void withNoSettingEveryRouteServesAFreshIndexAndFallsBackSafelyOtherwise(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Path p1 = c.dir.resolve("p1.parquet");
            plant(p1, T0);

            all(sources(c), "dataset", "no_index");                                          // nothing built: the flat Dataset answers
            assertEquals(0, data(send(c, "GET", "/inv/index", null)).get("total").asInt(), "a read never starts a build");

            build(c);
            all(sources(c), "index", null);                                                  // default ON: a fresh published index is used
            assertTrue(data(send(c, "GET", "/inv/index", null)).get("enabled").asBoolean());

            plant(c.dir.resolve("p2.parquet"), T0 + 1_000);                                  // an ADDED file: served from the index, flagged
            for (JsonNode s : sources(c)) {
                assertEquals("index", s.get("kind").asText(), s.toString());
                assertTrue(s.get("stale").asBoolean(), "the index misses the new file and says so: " + s);
            }
            Files.delete(c.dir.resolve("p2.parquet"));

            Files.setLastModifiedTime(p1, FileTime.fromMillis(T0 + 5_000));                  // a REWRITTEN file: rows may be gone
            all(sources(c), "dataset", "index_stale_refused");
            Files.setLastModifiedTime(p1, FileTime.fromMillis(T0));

            all(sources(c), "index", null);                                                  // restored: served again
            Files.writeString(c.root.resolve("link-analysis.toon"), "index:\n  enabled: false\n");
            all(sources(c), "dataset", "index_disabled");                                    // an explicit false still switches it off

            assertEquals(1, data(send(c, "GET", "/inv/index", null)).get("indexes").get(0).get("version").asInt(),
                    "no read, served or refused, ever built or switched a version");
        }
    }
}
