package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.gamma.spi.http.ApiContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PERF-P4-1: response compression negotiated at the single choke point ({@link ApiContext#respondJson} /
 * {@code respondText}). Real HTTP. The JDK HttpClient never decompresses on its own, so every gzip assertion
 * decodes by hand.
 */
class ControlApiGzipTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private static Map<String, Object> bigBody() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 200; i++) rows.add(Map.of("id", "node-" + i, "label", "Subscriber " + i, "kind", "msisdn"));
        return Map.of("nodes", rows);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.get("/test/big",   (e, m) -> bigBody());
        api.get("/test/small", (e, m) -> Map.of("ok", true));
        api.get("/test/etag",  (e, m) -> ETags.respond(e, bigBody()));
        api.get("/test/csv",   (e, m) -> ApiContext.respondText(e, "a,b\n".repeat(600), "text/csv"));
        api.get("/test/binary", (e, m) -> ApiContext.respondBinary(e, new byte[4096], "application/zip", "x.zip"));
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<byte[]> send(Ctx c, String method, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), BodyHandlers.ofByteArray());
    }

    private static byte[] gunzip(byte[] z) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(z))) { return in.readAllBytes(); }
    }

    /** The envelope carries a per-request timestamp and correlation id, so compare only the resource. */
    private static com.fasterxml.jackson.databind.JsonNode data(byte[] body) throws Exception {
        return ApiContext.JSON.readTree(body).path("data");
    }

    @Test
    void largeJsonIsGzippedWhenAskedAndDecodesToTheSameBody(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<byte[]> plain = send(c, "GET", "/test/big");
            HttpResponse<byte[]> zipped = send(c, "GET", "/test/big", "Accept-Encoding", "gzip");
            assertEquals(200, zipped.statusCode());
            assertEquals("gzip", zipped.headers().firstValue("Content-Encoding").orElse(null));
            assertEquals("Accept-Encoding", zipped.headers().firstValue("Vary").orElse(null));
            assertTrue(zipped.body().length < plain.body().length / 3, "gzip must actually shrink the body");
            assertEquals(data(plain.body()), data(gunzip(zipped.body())));
            System.out.println("GZIP-RATIO identity=" + plain.body().length + " gzip=" + zipped.body().length);
        }
    }

    @Test
    void withoutAcceptEncodingTheBodyIsIdentity(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<byte[]> r = send(c, "GET", "/test/big");
            assertTrue(r.headers().firstValue("Content-Encoding").isEmpty());
            assertEquals("Accept-Encoding", r.headers().firstValue("Vary").orElse(null), "cache key still varies");
            assertDoesNotThrow(() -> ApiContext.JSON.readTree(r.body()));
            HttpResponse<byte[]> refused = send(c, "GET", "/test/big", "Accept-Encoding", "gzip;q=0, identity");
            assertTrue(refused.headers().firstValue("Content-Encoding").isEmpty(), "gzip;q=0 is a refusal");
        }
    }

    @Test
    void smallBodyStaysIdentityEvenWhenGzipRequested(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<byte[]> r = send(c, "GET", "/test/small", "Accept-Encoding", "gzip");
            assertEquals(200, r.statusCode());
            assertTrue(r.headers().firstValue("Content-Encoding").isEmpty());
            assertTrue(r.body().length < ApiContext.GZIP_MIN_BYTES);
        }
    }

    @Test
    void streamsAndBinaryDownloadsAreNeverCompressed(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<byte[]> bin = send(c, "GET", "/test/binary", "Accept-Encoding", "gzip");
            assertEquals(200, bin.statusCode());
            assertTrue(bin.headers().firstValue("Content-Encoding").isEmpty());
            assertEquals(4096, bin.body().length);

            HttpRequest sse = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/notifications/stream"))
                    .header("Accept-Encoding", "gzip").GET().build();
            HttpResponse<java.io.InputStream> r = client.send(sse, BodyHandlers.ofInputStream());
            try (var in = r.body()) {
                assertEquals(200, r.statusCode());
                assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
                assertTrue(r.headers().firstValue("Content-Encoding").isEmpty());
            }
        }
    }

    @Test
    void headAnd304AreUntouchedAndTheEtagIsWeakenedForTheGzipVariant(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<byte[]> head = send(c, "HEAD", "/test/big", "Accept-Encoding", "gzip");
            assertEquals(405, head.statusCode(), "the dispatcher serves no HEAD route: a small 405, never encoded");
            assertTrue(head.headers().firstValue("Content-Encoding").isEmpty());
            assertEquals(0, head.body().length);

            HttpResponse<byte[]> plain = send(c, "GET", "/test/etag");
            String strong = plain.headers().firstValue("ETag").orElseThrow();
            assertFalse(strong.startsWith("W/"), "identity variant keeps the strong validator");

            HttpResponse<byte[]> zipped = send(c, "GET", "/test/etag", "Accept-Encoding", "gzip");
            String weak = zipped.headers().firstValue("ETag").orElseThrow();
            assertEquals("W/" + strong, weak);

            HttpResponse<byte[]> nm = send(c, "GET", "/test/etag", "Accept-Encoding", "gzip", "If-None-Match", weak);
            assertEquals(304, nm.statusCode());
            assertEquals(0, nm.body().length);
            assertTrue(nm.headers().firstValue("Content-Encoding").isEmpty());
        }
    }

    @Test
    void textBodiesAreNegotiatedToo(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<byte[]> r = send(c, "GET", "/test/csv", "Accept-Encoding", "gzip");
            assertEquals("gzip", r.headers().firstValue("Content-Encoding").orElse(null));
            assertEquals("a,b\n".repeat(600), new String(gunzip(r.body()), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void theDisableFlagTurnsCompressionOff(@TempDir Path cfg) throws Exception {
        System.setProperty("control.gzip", "false");
        try (Ctx c = open(cfg)) {
            HttpResponse<byte[]> r = send(c, "GET", "/test/big", "Accept-Encoding", "gzip");
            assertTrue(r.headers().firstValue("Content-Encoding").isEmpty());
        } finally {
            System.clearProperty("control.gzip");
        }
    }
}
