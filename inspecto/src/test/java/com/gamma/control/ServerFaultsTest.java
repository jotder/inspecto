package com.gamma.control;

import com.gamma.spi.auth.ErrorCodes;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ERR-5XX-ROUTE-BODIES (operator, 2026-09-28), over real HTTP: a route-built 500/502/503 names a curated
 * reason and the correlation id, and never the exception text. Each probe route throws exactly what the
 * shipped routes throw ({@code ServerFaults.internal} — exchange snapshot, workbook render, scratch root;
 * {@code curated(502, …)} — connection explore/sample; {@code curated(503, …)} — every query-sandbox
 * route), over an exception whose message carries {@link #MARKER}.
 */
class ServerFaultsTest {

    private static final String MARKER = "LEAK-7f3a91 db.internal.example:5432 SELECT secret FROM t";
    private final HttpClient client = HttpClient.newHttpClient();

    private void probe(Path dir, String path, Supplier<RuntimeException> thrown, int status, String reason)
            throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ServerFaults.class);
        logger.addAppender(logs);
        try (CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
             ControlApi api = new ControlApi(svc, 0)) {
            api.start();
            api.get(path, (ex, m) -> { throw thrown.get(); });
            String cid = "cid-" + path.replace('/', '-');
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + api.port() + "/api/v1" + path))
                    .header("Correlation-ID", cid).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(status, r.statusCode(), r.body());
            assertFalse(r.body().contains("LEAK-7f3a91"), "exception text on the wire: " + r.body());
            assertFalse(r.body().contains("db.internal.example"), r.body());
            assertFalse(r.body().contains("SELECT"), r.body());
            assertTrue(r.body().contains(reason), r.body());
            assertTrue(r.body().contains("correlation id " + cid), r.body());
            ILoggingEvent line = logs.list.stream()
                    .filter(e -> e.getFormattedMessage().contains(cid)).findFirst().orElseThrow();
            assertNotNull(line.getThrowableProxy(), "the full detail must be logged");
            assertTrue(line.getThrowableProxy().getMessage().contains("LEAK-7f3a91")
                    || line.getThrowableProxy().getCause().getMessage().contains("LEAK-7f3a91"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    void internalIsGeneric(@TempDir Path dir) throws Exception {
        probe(dir, "/probe-faults/internal",
                () -> ServerFaults.internal("snapshot failed", new IllegalStateException(MARKER)),
                500, "Internal error — correlation id");
    }

    @Test
    void connectionRefused(@TempDir Path dir) throws Exception {
        probe(dir, "/probe-faults/refused", () -> ServerFaults.curated(502, ErrorCodes.INTERNAL, "explore failed",
                "upstream error", new RuntimeException(MARKER, new ConnectException(MARKER))), 502, "explore failed: connection refused");
    }

    @Test
    void timedOut(@TempDir Path dir) throws Exception {
        probe(dir, "/probe-faults/timeout", () -> ServerFaults.curated(502, ErrorCodes.INTERNAL, "sample failed",
                "upstream error", new RuntimeException(MARKER, new SocketTimeoutException(MARKER))), 502, "sample failed: timed out");
    }

    @Test
    void hostNotFound(@TempDir Path dir) throws Exception {
        probe(dir, "/probe-faults/host", () -> ServerFaults.curated(502, ErrorCodes.INTERNAL, "explore failed",
                "upstream error", new RuntimeException(MARKER, new UnknownHostException(MARKER))), 502, "host not found");
    }

    @Test
    void authenticationFailed(@TempDir Path dir) throws Exception {
        probe(dir, "/probe-faults/auth", () -> ServerFaults.curated(502, ErrorCodes.INTERNAL, "explore failed",
                "upstream error", new RuntimeException(MARKER + " Auth fail")), 502, "authentication failed");
    }

    @Test
    void sandboxBusy(@TempDir Path dir) throws Exception {
        probe(dir, "/probe-faults/busy", () -> ServerFaults.curated(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "query sandbox unavailable", "sandbox could not be opened",
                new java.io.UncheckedIOException(new IOException(MARKER + " Could not set lock on file"))),
                503, "query sandbox unavailable: sandbox busy");
    }

    @Test
    void unclassifiedFallsBackToTheRoutesReason(@TempDir Path dir) throws Exception {
        probe(dir, "/probe-faults/other", () -> ServerFaults.curated(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "query sandbox unavailable", "sandbox could not be opened", new RuntimeException(MARKER)),
                503, "query sandbox unavailable: sandbox could not be opened");
    }
}
