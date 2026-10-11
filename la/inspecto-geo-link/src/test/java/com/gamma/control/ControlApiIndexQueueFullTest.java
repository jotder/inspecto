package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DEMO-GUARDS-1 / DR-T7 - the index-build queue bound over REAL HTTP. A Space's {@link IndexBuildService} runs
 * {@code index.threads} builds (default 1) and queues {@code index.queue} more (default 4); the 6th concurrent
 * {@code POST /inv/index/builds} is refused 503 {@code STORE_BUSY} with a plain "queue is full" message, and one finished
 * build frees a slot. A builder probe that blocks on a semaphore holds builds in flight deterministically - one permit
 * ends exactly one build.
 */
class ControlApiIndexQueueFullTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "Bearer owner";
    private static final String VIEW_SQL = "SELECT * FROM (VALUES ('alice','bob','call','x','y'),('bob','carol','sms','x','y'))"
            + " AS t(who,other,kind,a1,a2)";
    /** Six distinct mappings over ONE Dataset (the duplicate guard is per (Dataset, mapping), so none is a 409). */
    private static final List<String> BUILDS = List.of(
            build(null, "[]"), build("kind", "[]"), build(null, "[\"a1\"]"),
            build(null, "[\"a2\"]"), build(null, "[\"a1\",\"a2\"]"), build("kind", "[\"a1\"]"),
            build("kind", "[\"a2\"]"));
    private final HttpClient client = HttpClient.newHttpClient();

    private static String build(String kind, String attrs) {
        return "{\"dataset\":\"ds\",\"sourceCol\":\"who\",\"targetCol\":\"other\"" + (kind == null ? "" : ",\"kindCol\":\"" + kind + "\"")
                + ",\"attrCols\":" + attrs + "}";
    }

    /** Builds that block until a permit is released (or cancelled), then answer a canned result. */
    private static final class Gated implements Function<IndexBuilder.Request, IndexBuilder.Result> {
        final Semaphore permits = new Semaphore(0);

        @Override
        public IndexBuilder.Result apply(IndexBuilder.Request req) {
            IndexBuilder.CancelToken token = req.options().cancel();
            try {
                while (!permits.tryAcquire(5, java.util.concurrent.TimeUnit.MILLISECONDS))
                    if (token.isCancelled()) throw new IndexBuilder.CancelledException();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new IndexBuilder.Result(1, req.store().directory(), null, 2, 2, 0, 2, 16, Map.of(), 5);
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        IndexRoutes.forTest(null);
    }

    private HttpResponse<String> post(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/index/builds"))
                .header("Content-Type", "application/json").header("Authorization", OWNER)
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private String status(int port, String id) throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/index/builds/" + id))
                .header("Authorization", OWNER).GET().build(), BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data").get("status").asText();
    }

    private void await(int port, String id, String want) throws Exception {
        long end = System.nanoTime() + 30_000_000_000L;
        while (!want.equals(status(port, id))) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + id + " to be " + want);
            Thread.sleep(10);
        }
    }

    private static String accepted(HttpResponse<String> r) throws Exception {
        assertEquals(202, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data").get("buildId").asText();
    }

    @Test
    void theSixthConcurrentBuildIs503AndAFinishedBuildFreesASlot(@TempDir Path cfg, @TempDir Path root) throws Exception {
        IndexBuildService.Limits std = IndexBuildService.Limits.standard();
        assertEquals(1, std.threads(), "documented default: 1 build thread per Space");
        assertEquals(4, std.queue(), "documented default: 4 queued builds per Space");
        int inFlight = std.threads() + std.queue();

        Authenticators.forTest(ex -> OWNER.equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("analyst-1", Set.of("canBuildLinkIndex"))) : Optional.empty());
        Gated probe = new Gated();
        IndexRoutes.forTest(probe);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        CollectorService svc;
        ControlApi api;
        try {
            svc = new CollectorService(List.of(pipe), 3600, 1);
            api = new ControlApi(svc, 0);
            api.start();
        } finally {
            System.clearProperty("assist.write.root");
        }
        try {
            new ViewStore(root.resolve("views")).write(new ViewDefinition("v", "flow-x", List.of(), VIEW_SQL, "2026-10-02T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "ds", Map.of("view", "v", "owner", "analyst-1"));
            int port = api.port();

            List<String> ids = new ArrayList<>();
            ids.add(accepted(post(port, BUILDS.get(0))));
            await(port, ids.get(0), "RUNNING");                                  // the one worker is now held
            for (int i = 1; i < inFlight; i++) ids.add(accepted(post(port, BUILDS.get(i))));
            for (int i = 1; i < inFlight; i++) assertEquals("QUEUED", status(port, ids.get(i)));

            HttpResponse<String> refused = post(port, BUILDS.get(inFlight));     // the 6th
            assertEquals(503, refused.statusCode(), refused.body());
            String msg = refused.body();
            assertTrue(msg.contains("STORE_BUSY"), msg);
            assertTrue(msg.contains("the index build queue is full (1 running, 4 waiting) - try again shortly"), msg);

            probe.permits.release();                                             // exactly one build ends
            await(port, ids.get(0), "COMPLETED");
            await(port, ids.get(1), "RUNNING");
            ids.add(accepted(post(port, BUILDS.get(inFlight))));                 // the freed slot takes the refused build
            HttpResponse<String> again = post(port, BUILDS.get(inFlight + 1));   // and the queue is full once more
            assertEquals(503, again.statusCode(), again.body());

            probe.permits.release(ids.size());
            for (String id : ids) await(port, id, "COMPLETED");
        } finally {
            probe.permits.release(100);
            api.close();
            svc.close();
        }
    }
}
