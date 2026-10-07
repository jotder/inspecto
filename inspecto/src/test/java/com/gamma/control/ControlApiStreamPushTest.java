package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.Idempotency;
import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.service.CollectorService;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import com.gamma.access.AuditTrail;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for {@code POST /streams/{id}/records} (ASSURE-PUSH-INGEST-1), ARMED: a fake
 * {@link Authenticator} attaches a {@link Subject}, so the {@code canOperateRuns} gate is really enforced (without a
 * Subject {@code withCapability} is a no-op and a gate test would pass against an ungated route).
 */
class ControlApiStreamPushTest {

    private final HttpClient client = HttpClient.newHttpClient();

    /** "op" holds canOperateRuns; any other bearer holds nothing. */
    private static final Authenticator FAKE_AUTH = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String who = auth.substring(7);
        return Optional.of(new Subject(who, who.startsWith("op") ? Set.of("canOperateRuns") : Set.of()));
    };

    private static final String CSV = "a1,1.5,2026-01-01\na2,2.5,2026-01-02\n";

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("streams.push.max_bytes");
        System.clearProperty("streams.push.max_records");
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path inbox, String stream) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE_AUTH);
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        String stream = svc.collectors().get(0).get("id").toString();
        return new Ctx(svc, api, api.port(), dir.resolve("inbox"), stream);
    }

    private HttpResponse<String> push(Ctx c, String subject, String contentType, String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + c.port + "/api/v1/streams/" + c.stream + "/records"))
                .header("Content-Type", contentType).POST(BodyPublishers.ofString(body));
        if (subject != null) b.header("Authorization", "Bearer " + subject);
        if (key != null) b.header("Idempotency-Key", key);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static List<Path> landed(Path inbox) throws Exception {
        if (!Files.isDirectory(inbox)) return List.of();
        try (Stream<Path> s = Files.list(inbox)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void aCsvPushLandsOneFileTheNormalCollectorPathIngests(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = push(c, "op1", "text/csv", CSV, null);
            assertEquals(201, r.statusCode(), r.body());
            JsonNode data = V1Body.of(r.body());
            assertEquals(2, data.get("records").asInt());
            assertEquals(CSV.length(), data.get("bytes").asInt());
            List<Path> files = landed(c.inbox);
            assertEquals(1, files.size(), files.toString());
            assertEquals(data.get("file").asText(), files.get(0).getFileName().toString());
            assertTrue(files.get(0).getFileName().toString().matches("push-\\d{8}T\\d{9}-[0-9a-f]{8}\\.csv"));
            assertEquals(CSV, Files.readString(files.get(0)));

            PipelineConfig cfg = c.svc.configFor(c.svc.pipelines().get(0).name()).orElseThrow();
            assertEquals(1, CollectorProcessor.countPending(cfg));
            var run = c.svc.runPipeline(cfg.identity().pipelineName()).orElseThrow();
            assertEquals(0, run.failed(), "the pushed file ingests on the ordinary path");
            assertEquals(0, CollectorProcessor.countPending(cfg), "ingested — no longer pending");
        }
    }

    @Test
    void theAuditRowCarriesCountAndBytesNeverThePayload(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(201, push(c, "op1", "text/csv", CSV, null).statusCode());
            List<Event> rows = c.svc.events().page(500, null, null).stream()
                    .filter(e -> EventType.AUDIT.equals(e.type())
                            && "stream.records_pushed".equals(e.attributes().get(AuditAttrs.ACTION)))
                    .toList();
            assertEquals(1, rows.size(), "one AUDIT row per push");
            Event row = rows.get(0);
            assertEquals("2", String.valueOf(row.attributes().get("records")));
            assertEquals(String.valueOf(CSV.length()), String.valueOf(row.attributes().get("bytes")));
            assertEquals("canOperateRuns", String.valueOf(row.attributes().get(AuditAttrs.CAPABILITY)));
            assertFalse(row.attributes().toString().contains("a1,1.5"), "the payload never reaches the audit row");
            assertFalse(String.valueOf(row.message()).contains("a1,1.5"));
        }
    }

    @Test
    void aReplayedKeyReturnsTheFirstResultAndLandsNothingMore(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> first = push(c, "op1", "text/csv", CSV, "batch-7");
            assertEquals(201, first.statusCode(), first.body());
            HttpResponse<String> again = push(c, "op1", "text/csv", CSV, "batch-7");
            assertEquals(201, again.statusCode());
            assertEquals("true", again.headers().firstValue("Idempotency-Replayed").orElse(null));
            assertEquals(V1Body.of(first.body()).get("file").asText(), V1Body.of(again.body()).get("file").asText());
            assertEquals(1, landed(c.inbox).size(), "a replay lands nothing");
            assertEquals(201, push(c, "op1", "text/csv", CSV, "batch-8").statusCode());
            assertEquals(2, landed(c.inbox).size(), "a new key is a new batch");
        }
    }

    @Test
    void concurrentDuplicatesOfOneKeyLandExactlyOnce(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // A large body keeps the first request in flight long enough for the others to collide with it.
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 25_000; i++) big.append("id").append(i).append(",1.0,2026-01-01\n");
            String body = big.toString();
            int n = 8;
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                List<Future<Integer>> fs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    Callable<Integer> call = () -> { go.await(); return push(c, "op1", "text/csv", body, "dup-1").statusCode(); };
                    fs.add(pool.submit(call));
                }
                go.countDown();
                for (Future<Integer> f : fs) {
                    int s = f.get();
                    assertTrue(s == 201 || s == 409, "a duplicate is a replay (201) or in-flight (409), got " + s);
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, landed(c.inbox).size(), "one key, one batch, however many arrive at once");
        }
    }

    @Test
    void theByteCapIs413AndLandsNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            System.setProperty("streams.push.max_bytes", "20");
            assertEquals(413, push(c, "op1", "text/csv", CSV, null).statusCode());
            assertTrue(landed(c.inbox).isEmpty());
        }
    }

    @Test
    void theRecordCapIs413AndLandsNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            System.setProperty("streams.push.max_records", "1");
            HttpResponse<String> r = push(c, "op1", "text/csv", CSV, null);
            assertEquals(413, r.statusCode(), r.body());
            assertTrue(landed(c.inbox).isEmpty());
            System.setProperty("streams.push.max_records", "1");
            assertEquals(413, push(c, "op1", "application/x-ndjson", "{\"a\":1}\n{\"a\":2}\n", null).statusCode());
            assertTrue(landed(c.inbox).isEmpty());
        }
    }

    @Test
    void aMalformedCsvLineIs422NamingTheLineAndLandsNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = push(c, "op1", "text/csv", "a1,1.5,2026-01-01\na2,2.5\na3,3.5,2026-01-03\n", null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("line 2"), r.body());
            HttpResponse<String> q = push(c, "op1", "text/csv", "a1,\"open,2026-01-01\n", null);
            assertEquals(422, q.statusCode(), q.body());
            assertTrue(landed(c.inbox).isEmpty(), "nothing lands from a malformed batch");
        }
    }

    @Test
    void aMalformedNdjsonLineIs422AndLandsNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = push(c, "op1", "application/x-ndjson", "{\"a\":1}\n{\"a\":\n{\"a\":3}\n", null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("line 2"), r.body());
            assertEquals(422, push(c, "op1", "application/x-ndjson", "[1,2]\n", null).statusCode(), "an array is not a record");
            assertTrue(landed(c.inbox).isEmpty());
        }
    }

    @Test
    void anNdjsonPushTheCsvPipelineWouldNeverIngestIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = push(c, "op1", "application/x-ndjson", "{\"a\":1}\n", null);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("file_pattern"), r.body());
            assertTrue(landed(c.inbox).isEmpty());
        }
    }

    @Test
    void theOtherRefusals(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(415, push(c, "op1", "application/json", "{}", null).statusCode());
            assertEquals(422, push(c, "op1", "text/csv", "\n\n", null).statusCode(), "no records");
            HttpResponse<String> ghost = client.send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + c.port + "/api/v1/streams/ghost/records"))
                    .header("Authorization", "Bearer op1").header("Content-Type", "text/csv")
                    .POST(BodyPublishers.ofString(CSV)).build(), BodyHandlers.ofString());
            assertEquals(404, ghost.statusCode());
            assertTrue(landed(c.inbox).isEmpty());
        }
    }

    @Test
    void aSubjectWithoutCanOperateRunsIs403(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(403, push(c, "viewer", "text/csv", CSV, null).statusCode());
            assertTrue(landed(c.inbox).isEmpty());
        }
    }

    @Test
    void theCallerIsRateLimited(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            boolean limited = false;
            // 415s are refused past the limiter, so they spend tokens without landing files.
            for (int i = 0; i < 300 && !limited; i++)
                limited = push(c, "op-noisy", "application/json", "{}", null).statusCode() == 429;
            assertTrue(limited, "a noisy caller hits 429");
            assertEquals(201, push(c, "op-quiet", "text/csv", CSV, null).statusCode(), "the bucket is per caller");
        }
    }

    @Test
    void twoCallersWithTheSameKeyEachLandTheirOwnBatch(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> a = push(c, "op-a", "text/csv", CSV, "shared-key");
            HttpResponse<String> b = push(c, "op-b", "text/csv", CSV, "shared-key");
            assertEquals(201, a.statusCode());
            assertEquals(201, b.statusCode());
            assertNull(b.headers().firstValue("Idempotency-Replayed").orElse(null), "b never sees a's result");
            assertEquals(2, landed(c.inbox).size(), "the key is scoped to the caller");
        }
    }

    @Test
    void aKeyedPushOverTheHashWindowIs413AndLandsNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            StringBuilder big = new StringBuilder();
            while (big.length() <= Idempotency.MAX_REQUEST_BYTES) big.append("id,1.0,2026-01-01\n");
            HttpResponse<String> r = push(c, "op1", "text/csv", big.toString(), "big-1");
            assertEquals(413, r.statusCode(), r.body());
            assertTrue(landed(c.inbox).isEmpty(), "an unkeyable keyed push would double-land on retry — refused");
            assertEquals(201, push(c, "op1", "text/csv", big.toString(), null).statusCode(), "unkeyed it is fine");
        }
    }

    @Test
    void aPausedPipelineIs409PipelinePausedAndLandsNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertTrue(c.svc.pause(c.svc.pipelines().get(0).name()));
            HttpResponse<String> r = push(c, "op1", "text/csv", CSV, null);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("pipeline paused"), r.body());
            assertTrue(landed(c.inbox).isEmpty());
            c.svc.resume(c.svc.pipelines().get(0).name());
            assertEquals(201, push(c, "op1", "text/csv", CSV, null).statusCode());
        }
    }

    @Test
    void anInactivePipelineIs409(@TempDir Path dir) throws Exception {
        Authenticators.forTest(FAKE_AUTH);
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "", false);
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try (Ctx c = new Ctx(svc, api, api.port(), dir.resolve("inbox"), svc.collectors().get(0).get("id").toString())) {
            HttpResponse<String> r = push(c, "op1", "text/csv", CSV, null);
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(r.body().contains("pipeline paused"), r.body());
        }
    }

    @Test
    void aUtf8BomIsStrippedForCsvAndNdjsonAlike(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = push(c, "op1", "text/csv", "\uFEFF" + CSV, null);
            assertEquals(201, r.statusCode(), r.body());
            byte[] written = Files.readAllBytes(landed(c.inbox).get(0));
            assertEquals(CSV, new String(written, java.nio.charset.StandardCharsets.UTF_8), "the BOM is not written");
            assertEquals(CSV.length(), V1Body.of(r.body()).get("bytes").asInt());
        }
        assertArrayEquals("{}".getBytes(), StreamPushRoutes.stripBom(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}));
        assertEquals(1, StreamPushRoutes.validateNdjson(new String(StreamPushRoutes.stripBom(
                "\uFEFF{\"a\":1}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.nio.charset.StandardCharsets.UTF_8), 10), "NDJSON's first line parses once the BOM is gone");
        assertThrows(ApiException.class, () -> StreamPushRoutes.validateNdjson("\uFEFF{\"a\":1}\n", 10),
                "the BOM left in would break line 1 — stripping is what makes it pass");
    }

    @Test
    void theRouteIsAuditedAsAStreamMutation() {
        AuditTrail.Action a = AuditTrail.classify("POST", "/streams/feed-a/records");
        assertEquals("stream.records_pushed", a.name());
        assertEquals("data_mutation", a.category());
    }
}
