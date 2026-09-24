package com.gamma.acquire.connectors;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.job.ArtifactRecorder;
import com.gamma.job.JobConfig;
import com.gamma.job.JobContext;
import com.gamma.job.JobResult;
import com.gamma.job.ObjectStoreExportJobType;
import com.gamma.job.ResultSetMeta;
import com.gamma.job.TriggerInfo;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.RunLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EXPORT-1: the {@code objectstore.export} Job Type end-to-end — the engine job, the ServiceLoader-found
 * {@link S3Connector} export transport, against an in-process fake S3 (JDK {@link HttpServer}). The fake
 * RE-COMPUTES each PUT's SigV4 signature and checks the body against the signed SHA-256 and Content-MD5, so a
 * transport that signed the wrong thing fails here rather than against a real store.
 */
class ObjectStoreExportJobTest {

    private static final String BUCKET_PATH = "/bucket/exp/";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @TempDir Path dataRoot;

    private HttpServer server;
    private int port;
    /** key (path under /bucket/exp/) → stored body. */
    private final Map<String, byte[]> store = new ConcurrentHashMap<>();
    /** "METHOD key" in arrival order. */
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> signatureErrors = new CopyOnWriteArrayList<>();
    /** key → how many more PUTs of it answer 503 (Integer.MAX_VALUE = forever). */
    private final Map<String, Integer> failPuts = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
        ConnectionRegistry.register(new ConnectionProfile("lake", "s3", "127.0.0.1", port, null, "bucket/exp",
                "AKIDEXAMPLE", "test-secret-key", Map.of("region", "us-east-1", "protocol", "http"), null));
        Path db = Files.createDirectories(dataRoot.resolve("orders/database/dt=2026-09-24"));
        Files.writeString(db.resolve("part-0.parquet"), "PAR1-zero");
        Files.writeString(db.resolve("part-1.parquet"), "PAR1-one");
        Files.writeString(db.resolve("part-2.parquet.tmp"), "in flight");
    }

    @AfterEach
    void stop() {
        ConnectionRegistry.remove("lake");
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();          // signed as sent: '=' travels as %3D
        String decoded = ex.getRequestURI().getPath();
        String key = decoded.startsWith(BUCKET_PATH) ? decoded.substring(BUCKET_PATH.length()) : decoded;
        String method = ex.getRequestMethod();
        requests.add(method + " " + key);
        byte[] body = ex.getRequestBody().readAllBytes();
        int status;
        if ("HEAD".equals(method)) {
            byte[] have = store.get(key);
            if (have == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.getResponseHeaders().add("ETag", "\"" + hex("MD5", have) + "\"");
                ex.getResponseHeaders().add("Content-Length", String.valueOf(have.length));
                ex.sendResponseHeaders(200, -1);
            }
            ex.close();
            return;
        } else if ("PUT".equals(method)) {
            verifySignature(ex, path, body);
            int left = failPuts.getOrDefault(key, 0);
            if (left > 0) {
                if (left != Integer.MAX_VALUE) failPuts.put(key, left - 1);
                status = 503;
            } else {
                store.put(key, body);
                status = 200;
            }
        } else {
            status = 400;
        }
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    /** Recompute SigV4 from what arrived; check the signed payload hash and Content-MD5 against the body. */
    private void verifySignature(HttpExchange ex, String rawPath, byte[] body) {
        var h = ex.getRequestHeaders();
        String sha = h.getFirst("x-amz-content-sha256");
        if (!hex("SHA-256", body).equals(sha)) signatureErrors.add(rawPath + ": x-amz-content-sha256 is not the body's");
        String md5 = h.getFirst("Content-MD5");
        Map<String, String> extra = new java.util.LinkedHashMap<>();
        if (md5 != null) {
            if (!md5.equals(Base64.getEncoder().encodeToString(digest("MD5", body))))
                signatureErrors.add(rawPath + ": Content-MD5 is not the body's");
            extra.put("Content-MD5", md5);
        }
        String ct = h.getFirst("Content-Type");
        if (ct != null) extra.put("Content-Type", ct);
        Instant when = Instant.from(AMZ_DATE.parse(h.getFirst("x-amz-date")));
        Map<String, String> expected = AwsSigV4.sign("PUT", URI.create("http://127.0.0.1:" + port + rawPath),
                extra, sha, when, "us-east-1", "s3", "AKIDEXAMPLE", "test-secret-key");
        if (!expected.get("Authorization").equals(h.getFirst("Authorization")))
            signatureErrors.add(rawPath + ": Authorization does not verify — " + h.getFirst("Authorization"));
    }

    private JobResult run(Map<String, String> params, boolean dryRun) {
        var type = new ObjectStoreExportJobType(dataRoot.toString());
        var job = type.create(new JobConfig("push_orders", ObjectStoreExportJobType.TYPE_ID, null, "orders",
                true, false, params, null, null));
        try {
            return job.run(new Ctx(params, dryRun));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, String> params(String... extra) {
        Map<String, String> p = new java.util.LinkedHashMap<>(Map.of("connection", "lake", "local_path", "orders/database",
                "retries", "1"));
        for (int i = 0; i < extra.length; i += 2) p.put(extra[i], extra[i + 1]);
        return p;
    }

    private static final String K0 = "orders/database/dt=2026-09-24/part-0.parquet";
    private static final String K1 = "orders/database/dt=2026-09-24/part-1.parquet";
    private static final String MANIFEST = "orders/database/" + ObjectStoreExportJobType.MANIFEST;

    @Test
    void uploadsEveryFileSignedAndWritesTheManifestLast() {
        JobResult r = run(params(), false);

        assertTrue(r.success(), r.message());
        assertEquals(List.of(), signatureErrors);
        assertArrayEquals("PAR1-zero".getBytes(StandardCharsets.UTF_8), store.get(K0));
        assertArrayEquals("PAR1-one".getBytes(StandardCharsets.UTF_8), store.get(K1));
        assertFalse(store.keySet().stream().anyMatch(k -> k.endsWith(".tmp")), "an in-flight .tmp is never exported");
        List<String> puts = requests.stream().filter(s -> s.startsWith("PUT ")).toList();
        assertEquals(List.of("PUT " + K0, "PUT " + K1, "PUT " + MANIFEST), puts, "manifest is the LAST write");
        String manifest = new String(store.get(MANIFEST), StandardCharsets.UTF_8);
        assertTrue(manifest.contains(K0) && manifest.contains(K1) && manifest.contains(hex("MD5",
                "PAR1-zero".getBytes(StandardCharsets.UTF_8))), manifest);
    }

    @Test
    void aSecondRunSkipsUnchangedFilesAndResendsOnlyAChangedOne() throws IOException {
        assertTrue(run(params(), false).success());
        requests.clear();

        JobResult again = run(params(), false);
        assertTrue(again.success(), again.message());
        assertEquals(List.of("PUT " + MANIFEST), requests.stream().filter(s -> s.startsWith("PUT ")).toList(),
                "nothing unchanged is re-sent");
        assertTrue(again.message().contains("0 uploaded, 2 unchanged"), again.message());

        requests.clear();
        Files.writeString(dataRoot.resolve("orders/database/dt=2026-09-24/part-1.parquet"), "PAR1-one-v2");
        assertTrue(run(params(), false).success());
        assertEquals(List.of("PUT " + K1, "PUT " + MANIFEST),
                requests.stream().filter(s -> s.startsWith("PUT ")).toList());
    }

    @Test
    void aTransientFailureIsRetried() {
        failPuts.put(K0, 1);

        JobResult r = run(params(), false);

        assertTrue(r.success(), r.message());
        assertEquals(2, requests.stream().filter(("PUT " + K0)::equals).count(), "one 503, then the retry");
        assertNotNull(store.get(MANIFEST));
    }

    @Test
    void aPersistentFailureFailsTheRunAndWritesNoManifest() {
        failPuts.put(K1, Integer.MAX_VALUE);

        JobResult r = run(params("retries", "1"), false);

        assertFalse(r.success(), "a delivery that did not happen must not report SUCCESS");
        assertEquals("FAILED", r.status());
        assertTrue(r.message().contains("503"), r.message());
        assertEquals(2, requests.stream().filter(("PUT " + K1)::equals).count(), "1 + retries attempts");
        assertNull(store.get(MANIFEST), "no manifest for a partial export");
    }

    @Test
    void aLocalPathOutsideTheDataRootIsRefusedBeforeAnyRequest() {
        JobResult r = run(params("local_path", "../elsewhere"), false);

        assertFalse(r.success());
        assertTrue(r.message().contains("local_path"), r.message());
        assertEquals(List.of(), requests);
    }

    @Test
    void aDryRunSendsNothing() {
        JobResult r = run(params(), true);

        assertTrue(r.success(), r.message());
        assertTrue(r.message().contains("would upload 2"), r.message());
        assertTrue(requests.stream().noneMatch(s -> s.startsWith("PUT ")), requests.toString());
    }

    private static byte[] digest(String alg, byte[] b) {
        try {
            return MessageDigest.getInstance(alg).digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(String alg, byte[] b) {
        return HexFormat.of().formatHex(digest(alg, b));
    }

    /** A bare JobContext carrying the params — the job reads nothing else. */
    private record Ctx(Map<String, String> params, boolean dryRun) implements JobContext {
        @Override public String runId() { return "run-1"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return TriggerInfo.parse("manual"); }
        @Override public Map<String, String> config() { return params; }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String m, Object... kv) { }
                @Override public void warn(String m, Object... kv) { }
                @Override public void error(String m, Throwable t, Object... kv) { }
            };
        }
        @Override public SignalEmitter signals() { return (type, severity, payload) -> { }; }
        @Override public ArtifactRecorder artifacts() {
            return new ArtifactRecorder() {
                @Override public void dataset(String name, String datasetRef, ResultSetMeta resultSet,
                                              long rows, Instant watermark) { }
                @Override public void file(String name, Path path, long bytes) { }
            };
        }
    }
}
