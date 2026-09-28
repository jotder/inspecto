package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the SSE live-push counterpart to {@code GET /signals}
 * ({@code GET /signals/stream}, event-signal-backbone-plan §S3): a Signal emitted after the client
 * connects is pushed as a {@code data:} frame with no polling, and a {@code type}/{@code severity}
 * filter on the stream URL excludes a non-matching signal fired in the same test.
 */
class ControlApiSignalsStreamTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, int baseline) implements AutoCloseable {
        /** ControlApi.close() ends every live stream and drops its EventLog subscriber. */
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), svc.eventLog().subscriberCount());
    }

    private static Signal sig(String type, Severity sev, String correlationId) {
        return new Signal(null, type, Instant.now(), sev, Ref.of("job", "x"), null,
                correlationId, null, null, null, type, Map.of("k", "v"), 1);
    }

    @Test
    void pushesLiveSignalOverSse(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/api/v1" + "/signals/stream")).GET().build();
            HttpResponse<InputStream> resp = client.send(req, BodyHandlers.ofInputStream());

            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));

            // Listener is registered before the response headers are committed, so firing now is safe.
            c.svc.eventLog().emit(sig("job.run.completed", Severity.INFO, "corr-1").toEvent());

            String dataLine = readDataLine(resp);
            assertNotNull(dataLine, "a data: frame arrived over SSE");
            assertTrue(dataLine.contains("\"job.run.completed\""), dataLine);
            assertTrue(dataLine.contains("\"corr-1\""), dataLine);
            resp.body().close();
        }
    }

    @Test
    void typeAndSeverityFilterExcludeNonMatchingSignal(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/api/v1"
                            + "/signals/stream?type=job.run.failed&severity=CRITICAL")).GET().build();
            HttpResponse<InputStream> resp = client.send(req, BodyHandlers.ofInputStream());
            assertEquals(200, resp.statusCode());

            // Non-matching: wrong type AND below the severity floor — must not arrive.
            c.svc.eventLog().emit(sig("job.run.started", Severity.INFO, "corr-2").toEvent());
            // Matching: right type, at the severity floor — must arrive.
            c.svc.eventLog().emit(sig("job.run.failed", Severity.CRITICAL, "corr-2").toEvent());

            String dataLine = readDataLine(resp);
            assertNotNull(dataLine, "the matching CRITICAL job.run.failed signal arrived");
            assertTrue(dataLine.contains("\"job.run.failed\""), dataLine);
            assertFalse(dataLine.contains("\"job.run.started\""), "the filtered-out signal never arrives");
            resp.body().close();
        }
    }

    @Test
    void closeEndsLiveStreamAndDropsItsSubscriber(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/api/v1" + "/signals/stream")).GET().build();
            HttpResponse<InputStream> resp = client.send(req, BodyHandlers.ofInputStream());
            assertEquals(200, resp.statusCode());
            assertEquals(c.baseline + 1, c.svc.eventLog().subscriberCount(), "the stream's subscriber is live");
            BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
            assertEquals(": connected", reader.readLine());

            long t0 = System.nanoTime();
            c.api.close();
            long deadline = t0 + TimeUnit.SECONDS.toNanos(1);
            while (c.svc.eventLog().subscriberCount() > c.baseline && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(c.baseline, c.svc.eventLog().subscriberCount(), "close() dropped the subscriber within 1 s");
            assertEquals(0, c.api.sseStreams().size());
            assertTrue(readsToEnd(reader), "the client sees end-of-stream");
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5), "well inside one 15 s heartbeat");
            c.api.close();   // idempotent
        }
    }

    /** True when the stream reaches EOF (readLine null) within 2 s, with no heartbeat needed. */
    static boolean readsToEnd(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                while (reader.readLine() != null) { /* drain */ }
                return true;
            } catch (java.io.IOException e) {
                return false;
            }
        }).get(2, TimeUnit.SECONDS);
    }

    private static String readDataLine(HttpResponse<InputStream> resp) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        return CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) return line;
                }
            } catch (Exception ignore) { /* stream closed */ }
            return null;
        }).get(5, TimeUnit.SECONDS);
    }
}
