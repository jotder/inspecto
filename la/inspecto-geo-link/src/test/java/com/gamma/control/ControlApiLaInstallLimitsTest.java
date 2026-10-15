package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.entitystore.LaLimits;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.api.IndexRoutes;
import com.gamma.la.storage.IndexBuildService;
import com.gamma.la.storage.IndexBuilder;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.IngestActivity;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LA-CONFIG-LIMITS-1} P4 over REAL HTTP with an ARMED Authenticator: a value the system admin sets with
 * {@code PUT /system/link-analysis/limits} takes effect at its site without a restart - the Link Analysis and embed rate
 * buckets (rebuilt on the PUT, and applied at boot), the index build pool of a LIVE Space service, the index build memory
 * share, and CL-12: a {@code POST /inv/index/builds} made while ingest runs is accepted and WAITS (queued, phase
 * {@code waiting_for_ingest}) until ingest ends. A builder probe that blocks on a semaphore holds builds deterministically.
 */
class ControlApiLaInstallLimitsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner", ADMIN = "Bearer admin";
    private static final String VIEW_SQL = "SELECT * FROM (VALUES ('alice','bob','call','x'),('bob','carol','sms','y')) AS t(who,other,kind,a1)";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    /** Builds that block until a permit is released (or cancelled); records the options each was given. */
    private static final class Gated implements Function<IndexBuilder.Request, IndexBuilder.Result> {
        final Semaphore permits = new Semaphore(0);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<IndexBuilder.Options> options = new AtomicReference<>();

        @Override
        public IndexBuilder.Result apply(IndexBuilder.Request req) {
            calls.incrementAndGet();
            options.set(req.options());
            IndexBuilder.CancelToken token = req.options().cancel();
            try {
                while (!permits.tryAcquire(5, TimeUnit.MILLISECONDS))
                    if (token.isCancelled()) throw new IndexBuilder.CancelledException();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new IndexBuilder.Result(1, req.store().directory(), null, 2, 2, 0, 2, 16, Map.of(), 5);
        }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex", "canRunLinkGraphAnalysis")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canAdminister")));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        IndexRoutes.forTest(null);
        LaLimits.installDocPath(null);
        System.clearProperty("system.config.dir");
    }

    private Ctx open(Path cfg, Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(root.resolve("views")).write(new ViewDefinition("v", "flow-x", List.of(), VIEW_SQL, "2026-10-15T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "ds", Map.of("view", "v", "owner", "analyst-1"));
            return new Ctx(svc, api, api.port(), root);
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

    private void install(Ctx c, String body) throws Exception {
        HttpResponse<String> r = send(c, "PUT", "/system/link-analysis/limits", body, ADMIN);
        assertEquals(200, r.statusCode(), r.body());
    }

    private static String build(String attrs) {
        return "{\"dataset\":\"ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\",\"attrCols\":" + attrs + "}";
    }

    private String accepted(Ctx c, String body) throws Exception {
        HttpResponse<String> r = send(c, "POST", "/inv/index/builds", body, OWNER);
        assertEquals(202, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data").get("buildId").asText();
    }

    private JsonNode buildView(Ctx c, String id) throws Exception {
        HttpResponse<String> r = send(c, "GET", "/inv/index/builds/" + id, null, OWNER);
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void until(Ctx c, String id, String status) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L;
        while (!status.equals(buildView(c, id).get("status").asText())) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + id + " to be " + status);
            Thread.sleep(10);
        }
    }

    /** How many of {@code n} requests pass the rate limiter (any answer but 429). */
    private int passed(Ctx c, String method, String path, String auth, int n) throws Exception {
        int ok = 0;
        for (int i = 0; i < n; i++) if (send(c, method, path, method.equals("GET") ? null : "{}", auth).statusCode() != 429) ok++;
        return ok;
    }

    // ── CL-13 rate limits ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aLoweredLinkAnalysisAndEmbedBurstRefusesSoonerFromTheNextRequest(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            c.api.useRateLimitClock(() -> 0L);                                   // no refill: only the burst counts
            assertEquals(3, passed(c, "POST", "/inv/graph/runs", OWNER, 3), "the shipped burst (30) passes three");

            install(c, "{\"rate.link_analysis_burst\":2,\"rate.embed_burst\":2}");
            assertEquals(2, passed(c, "POST", "/inv/graph/runs", OWNER, 4), "the lowered burst, from the next request");
            assertEquals(2, passed(c, "GET", "/public/investigations/no-such-token", null, 4), "one embed link's lowered burst");

            install(c, "{\"rate.link_analysis_burst\":null}");                   // cleared: the shipped burst again, a full bucket
            assertEquals(5, passed(c, "POST", "/inv/graph/runs", OWNER, 5));
        }
    }

    @Test
    void aRateStatedBeforeBootAppliesFromTheFirstRequest(@TempDir Path cfg, @TempDir Path root, @TempDir Path sys) throws Exception {
        System.setProperty("system.config.dir", sys.toString());
        Files.writeString(sys.resolve(LaLimits.INSTALL_FILE), "rate:\n  link_analysis_burst: 2\n");
        try (Ctx c = open(cfg, root)) {
            c.api.useRateLimitClock(() -> 0L);
            assertEquals(2, passed(c, "POST", "/inv/graph/runs", OWNER, 4));
        }
    }

    // ── index builds: the live pool, the memory share, and waiting for ingest ─────────────────────────────────────

    @Test
    void theIndexPoolOfALiveServiceFollowsTheInstallValueAndTheBuildTakesItsMemoryShare(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Gated probe = new Gated();
        IndexRoutes.forTest(probe);
        try (Ctx c = open(cfg, root)) {
            String first = accepted(c, build("[]"));                            // creates the Space's service at the shipped 1 + 4
            until(c, first, "RUNNING");
            assertEquals(IndexBuildService.memoryLimit(80, DuckDbUtil.physicalMemoryBytes()), probe.options.get().memoryLimit(),
                    "the shipped share: 80 % of the RAM the JVM sees");

            install(c, "{\"index.queue\":1,\"index.build_memory_pct\":50}");
            String second = accepted(c, build("[\"kind\"]"));                    // the one waiting place left
            HttpResponse<String> full = send(c, "POST", "/inv/index/builds", build("[\"a1\"]"), OWNER);
            assertEquals(503, full.statusCode(), full.body());
            assertTrue(full.body().contains("(1 running, 1 waiting)"), full.body());

            probe.permits.release();
            until(c, first, "COMPLETED");
            until(c, second, "RUNNING");
            assertEquals(IndexBuildService.memoryLimit(50, DuckDbUtil.physicalMemoryBytes()), probe.options.get().memoryLimit(),
                    "the changed share applies to the next build that starts");
            probe.permits.release();
            until(c, second, "COMPLETED");
        } finally {
            probe.permits.release(100);
        }
    }

    @Test
    void aBuildPostedWhileIngestRunsIsAcceptedAndWaitsUntilIngestEnds(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Gated probe = new Gated();
        IndexRoutes.forTest(probe);
        IngestActivity.Scope ingest = IngestActivity.begin();
        try (Ctx c = open(cfg, root)) {
            String id = accepted(c, build("[]"));                               // accepted (202), not refused
            long end = System.nanoTime() + 30_000_000_000L;
            JsonNode v;
            while (!"waiting_for_ingest".equals((v = buildView(c, id)).at("/progress/phase").asText())) {
                if (System.nanoTime() > end) throw new AssertionError("the build never reported the ingest wait: " + v);
                Thread.sleep(10);
            }
            assertEquals("QUEUED", v.get("status").asText());
            assertEquals(0, probe.calls.get(), "the builder is never reached while ingest runs");

            ingest.close();
            until(c, id, "RUNNING");
            long end2 = System.nanoTime() + 30_000_000_000L;
            while (probe.calls.get() < 1)                                       // RUNNING is set just before the builder is called
                if (System.nanoTime() > end2) throw new AssertionError("the builder was never reached"); else Thread.sleep(5);
            assertEquals(1, probe.calls.get());
            assertNotEquals("waiting_for_ingest", buildView(c, id).at("/progress/phase").asText());
            probe.permits.release();
            until(c, id, "COMPLETED");
        } finally {
            ingest.close();
            probe.permits.release(100);
        }
    }
}
