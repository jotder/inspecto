package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET|POST /pipelines/authored/{id}/inbox} over real HTTP (INBOX-UPLOAD-1): every upload gate in its
 * fail-closed order — capability 403 (through a real Subject: without one {@code withCapability} is a no-op and
 * would prove nothing) · write root 503 · 422 · path jail 403 · 413 · 409 · the atomic write — plus the listing.
 */
class ControlApiPipelineInboxTest {

    /** A bare path literal, deliberately: the auth-gate coverage guard matches armed tests by it. */
    private static final String INBOX = "/pipelines/authored/mini_etl/inbox";
    private static final String CSV = "ID,AMT,EVENT_DATE\nr1,1.0,2020-04-03\n";
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer author".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")));
        if ("Bearer operator".equals(auth)) return Optional.of(new Subject("olly", Set.of("canOperateRuns")));
        return Optional.empty();
    };

    @BeforeEach
    void setUp() { Authenticators.forTest(FAKE); }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path inbox) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** The mini_etl pipeline, inbox {@code <dir>/inbox}; {@code writable=false} boots with no write root. */
    private Ctx open(Path dir, boolean writable) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        if (writable) System.setProperty("assist.write.root", Files.createDirectories(dir.resolve("cfg")).toString());
        else System.clearProperty("assist.write.root");
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), dir.resolve("inbox"));
    }

    private HttpResponse<String> upload(Ctx c, String file, String query, String body, String token) throws Exception {
        String q = (file == null ? "" : "?file=" + URLEncoder.encode(file, StandardCharsets.UTF_8))
                + (query == null ? "" : (file == null ? "?" : "&") + query);
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + c.port + "/api/v1" + INBOX + q))
                .header("Content-Type", "application/octet-stream")
                .POST(BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> list(Ctx c, String id) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                + "/api/v1/pipelines/authored/" + id + "/inbox")).header("Authorization", "Bearer operator").GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void uploadIsGatedOnCanAuthorWorkbench(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(401, upload(c, "a.csv", null, CSV, null).statusCode());
            HttpResponse<String> denied = upload(c, "a.csv", null, CSV, "operator");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("canAuthorWorkbench"), denied.body());
            assertFalse(Files.exists(c.inbox.resolve("a.csv")), "a refused upload writes nothing");
        }
    }

    @Test
    void noWriteRootIs503AndWritesNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            HttpResponse<String> r = upload(c, "a.csv", null, CSV, "author");
            assertEquals(503, r.statusCode(), r.body());
            assertFalse(Files.exists(c.inbox.resolve("a.csv")));
        }
    }

    @Test
    void uploadsIntoThePollDirectoryAndTheListingShowsIt(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = upload(c, "matches.csv", null, CSV, "author");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode b = V1Body.of(r.body());
            assertEquals("matches.csv", b.get("file").asText());
            assertEquals(CSV.length(), b.get("size").asInt());
            assertFalse(b.get("replaced").asBoolean());
            assertEquals(CSV, Files.readString(c.inbox.resolve("matches.csv")));
            try (Stream<Path> s = Files.list(c.inbox)) {
                assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no staging file is left behind");
            }

            HttpResponse<String> l = list(c, "mini_etl");
            assertEquals(200, l.statusCode(), l.body());
            JsonNode lb = V1Body.of(l.body());
            assertEquals(1, lb.get("total").asInt(), lb.toString());
            assertFalse(lb.get("truncated").asBoolean());
            assertEquals("matches.csv", lb.get("files").get(0).get("name").asText());
            assertEquals(CSV.length(), lb.get("files").get(0).get("size").asInt());
        }
    }

    @Test
    void aMissingNameOrAnEmptyBodyIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(422, upload(c, null, null, CSV, "author").statusCode());
            assertEquals(422, upload(c, "  ", null, CSV, "author").statusCode());
            assertEquals(422, upload(c, "a.csv", null, "", "author").statusCode());
            assertFalse(Files.exists(c.inbox.resolve("a.csv")));
        }
    }

    /** The reason for the jail: the name is caller-supplied, so without it this route is an arbitrary-file-write. */
    @Test
    void aNameThatIsAPathOrEscapesTheInboxIs403(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            for (String bad : List.of("../escape.csv", "..\\escape.csv", "sub/a.csv", "..", ".",
                    dir.resolve("escape.csv").toAbsolutePath().toString(), "C:escape.csv", "a.csv:stream")) {
                HttpResponse<String> r = upload(c, bad, null, CSV, "author");
                assertEquals(403, r.statusCode(), "'" + bad + "' must be refused: " + r.body());
            }
            assertFalse(Files.exists(dir.resolve("escape.csv")), "nothing lands outside the inbox");
            assertFalse(Files.exists(c.inbox.resolve("sub")));
        }
    }

    /**
     * Over the cap is refused on the DECLARED length, before a byte of the body is buffered. ⚠ Sent over a raw
     * socket: {@code HttpClient} forbids setting Content-Length, and actually streaming 64 MiB at a server that
     * has already answered and closed fails the client with "header parser received no bytes".
     */
    @Test
    void aBodyOverTheCapIs413(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true);
             java.net.Socket s = new java.net.Socket("localhost", c.port)) {
            s.setSoTimeout(10_000);
            String rq = "POST /api/v1" + INBOX + "?file=big.csv HTTP/1.1\r\n"
                    + "Host: localhost\r\nAuthorization: Bearer author\r\nContent-Type: application/octet-stream\r\n"
                    + "Content-Length: " + (PipelineInboxRoutes.MAX_UPLOAD_BYTES + 1L) + "\r\n\r\n";
            s.getOutputStream().write(rq.getBytes(StandardCharsets.US_ASCII));
            // Some body, but nowhere near the declared length: the JDK server drains up to its drain amount
            // (64 KiB) on close before it flushes the response, so with NO body bytes it would wait forever.
            s.getOutputStream().write(new byte[128 * 1024]);
            s.getOutputStream().flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(
                    s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertNotNull(status);
            assertTrue(status.contains(" 413"), status);
            assertFalse(Files.exists(c.inbox.resolve("big.csv")));
        }
    }

    @Test
    void anExistingFileIs409UnlessOverwrite(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            Files.createDirectories(c.inbox);
            Files.writeString(c.inbox.resolve("a.csv"), "old");
            HttpResponse<String> r = upload(c, "a.csv", null, CSV, "author");
            assertEquals(409, r.statusCode(), r.body());
            assertEquals("old", Files.readString(c.inbox.resolve("a.csv")), "a refused upload leaves the file as it was");

            HttpResponse<String> ok = upload(c, "a.csv", "overwrite=true", CSV, "author");
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(V1Body.of(ok.body()).get("replaced").asBoolean());
            assertEquals(CSV, Files.readString(c.inbox.resolve("a.csv")));
        }
    }

    @Test
    void anUnknownPipelineIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(404, list(c, "ghost").statusCode());
            HttpRequest rq = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                            + "/api/v1/pipelines/authored/ghost/inbox?file=a.csv"))
                    .header("Authorization", "Bearer author").POST(BodyPublishers.ofString(CSV)).build();
            assertEquals(404, client.send(rq, BodyHandlers.ofString()).statusCode());
        }
    }
}
