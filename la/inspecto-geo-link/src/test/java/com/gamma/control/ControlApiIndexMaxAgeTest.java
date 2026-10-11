package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.api.InputFingerprintCache;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-INDEX-MAX-AGE-1 (operator 2026-10-11) - a Dataset whose input files cannot be fingerprinted (view-backed) is never reported stale,
 * so the index may serve it only while it is younger than the Space setting {@code index.max_age_hours} (default 24); older, the flat
 * Dataset answers with the closed Reason {@code stale_unknown}. A fingerprinted (file-backed) Dataset is NOT subject to the age.
 * The build age is moved by rewriting {@code builtAt} in the published manifests.
 */
class ControlApiIndexMaxAgeTest {

    @BeforeAll static void raiseBudget() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterAll static void restoreBudget() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner";
    private static final long T0 = 1_700_000_000_000L;
    private static final String FILE_COLS = "\"dataset\":\"d_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"";
    private static final String VIEW_COLS = "\"dataset\":\"v_ds\",\"sourceCol\":\"s\",\"targetCol\":\"t\"";
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

    /** One Space with two Datasets: {@code d_ds} over parquet files (fingerprinted) and {@code v_ds} over a view (fingerprint unknown). */
    private Ctx open(Path cfg, Path root) throws Exception {
        AtomicLong tick = new AtomicLong();
        InputFingerprintCache.forTest(() -> tick.addAndGet(60_000L), 30_000L);
        Authenticators.forTest(ex -> OWNER.equals(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")))
                ? Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canManageIncidents"))) : Optional.empty());
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();                                                                  // NO link-analysis.toon: the default age applies
            String store = "maxage_" + System.nanoTime();
            ComponentStore reg = new ComponentStore(root.resolve("registry"));
            reg.write("dataset", "d_ds", Map.of("physicalRef", store, "owner", "analyst-1", "shares", List.of()));
            new ViewStore(root.resolve("views")).write(new ViewDefinition("m_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES " + ROWS + ") AS v(s,t,ts)", "2026-10-03T00:00:00Z"));
            reg.write("dataset", "v_ds", Map.of("view", "m_view", "owner", "analyst-1", "shares", List.of()));
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

    private void build(Ctx c, String cols) throws Exception {
        String id = data(send(c, "POST", "/inv/index/builds", "{" + cols + "}")).get("buildId").asText();
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
        java.io.File db = DuckDbUtil.tempDbFile("maxage_");
        try (java.sql.Connection conn = DuckDbUtil.openConnection(db); java.sql.Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + ROWS + ") AS v(s,t,ts)) TO '" + file.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
    }

    /** Move every published manifest's build time to {@code hours} hours before now. */
    private static void ageIndexes(Ctx c, long hours) throws Exception {
        String when = Instant.now().minus(Duration.ofHours(hours)).toString();
        try (var walk = Files.walk(c.root)) {
            for (Path m : walk.filter(x -> x.getFileName().toString().equals("manifest.json")).toList())
                Files.writeString(m, Files.readString(m).replaceFirst("\"builtAt\"\\s*:\\s*\"[^\"]*\"", "\"builtAt\" : \"" + when + "\""));
        }
    }

    private JsonNode source(Ctx c, String cols) throws Exception {
        return data(send(c, "POST", "/inv/projection/neighbors", "{" + cols + ",\"value\":\"A\"}")).get("source");
    }

    @Test
    void aViewBackedDatasetIsServedWhileTheIndexIsYoungerThanMaxAgeAndReadsFlatOnceOlder(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c, VIEW_COLS);
            JsonNode young = source(c, VIEW_COLS);
            assertEquals("index", young.get("kind").asText(), young.toString());
            assertEquals("unknown", young.get("fingerprint").asText(), "the fixture really is fingerprint-unknown");

            ageIndexes(c, 25);                                                               // older than the default 24 h
            JsonNode old = source(c, VIEW_COLS);
            assertEquals("dataset", old.get("kind").asText(), old.toString());
            assertEquals("stale_unknown", old.get("reason").asText());

            Files.writeString(c.root.resolve("link-analysis.toon"), "index:\n  max_age_hours: 48\n");   // the Space allows two days: served again
            assertEquals("index", source(c, VIEW_COLS).get("kind").asText());
            Files.writeString(c.root.resolve("link-analysis.toon"), "index:\n  max_age_hours: 24\n");
            assertEquals("stale_unknown", source(c, VIEW_COLS).get("reason").asText());
        }
    }

    @Test
    void aFingerprintedDatasetIsUnaffectedByMaxAge(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            plant(c.dir.resolve("p1.parquet"), T0);
            build(c, FILE_COLS + ",\"timeCol\":\"ts\"");
            ageIndexes(c, 24 * 30);                                                          // a month old
            JsonNode s = source(c, FILE_COLS);
            assertEquals("index", s.get("kind").asText(), s.toString());
            assertEquals("known", s.get("fingerprint").asText());
            assertFalse(s.get("stale").asBoolean(), "nothing changed in the files: not stale, however old");
        }
    }
}
