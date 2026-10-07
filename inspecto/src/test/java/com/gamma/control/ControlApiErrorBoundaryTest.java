package com.gamma.control;

import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.audit.Event;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ERR-BOUNDARY-ERROR-1 (2026-09-28), over real HTTP: a route that throws a server-side {@link Error} is
 * answered with the v1 500 error envelope and logged at ERROR with the Correlation-ID and the stack. Before
 * the fix the Error escaped {@code ControlApi.errorBoundary} to the JDK HttpServer, which dropped the
 * connection without a log line; the client read "header parser received no bytes".
 *
 * <p>AUDIT-ERRORED-REQUEST-UNRECORDED-1 (2026-09-28): a mutating request that fails with a 5xx writes one
 * AUDIT row, "(failed, HTTP n)", with its Correlation-ID; the audit tests at the foot of this class pin it.
 *
 * <p>Each probe route is registered on the live context under a path nothing else owns, so no route
 * inventory or API contract changes.
 */
class ControlApiErrorBoundaryTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, ListAppender<ILoggingEvent> logs)
            implements AutoCloseable {
        public void close() {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ControlApi.class)).detachAppender(logs);
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ControlApi.class)).addAppender(logs);
        return new Ctx(svc, api, api.port(), logs);
    }

    /** Register {@code GET path} throwing what {@code thrown} supplies. */
    private static void throwing(Ctx c, String path, Supplier<Throwable> thrown) {
        c.api.get(path, (ex, m) -> { throw sneaky(thrown.get()); });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T { throw (T) t; }

    private HttpResponse<String> get(Ctx c, String path, String cid) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Correlation-ID", cid).timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                BodyHandlers.ofString());
    }

    /** Assert the v1 500 envelope, and ONE ERROR log line naming the path and the Correlation-ID, carrying the stack. */
    private static void assert500Logged(Ctx c, HttpResponse<String> r, String path, String cid,
                                        Class<? extends Throwable> kind, String messageFragment) throws Exception {
        assertEquals(500, r.statusCode(), r.body());
        assertEquals(cid, r.headers().firstValue("Correlation-ID").orElse(null));
        JsonNode err = V1Body.envelope(r.body()).get("error");
        assertNotNull(err, r.body());
        assertEquals(ErrorCodes.INTERNAL, err.get("errorCode").asText(), r.body());
        assertEquals(cid, err.get("correlationId").asText(), r.body());
        assertFalse(err.get("recoverable").asBoolean(), r.body());
        // ERR-500-GENERIC (operator, 2026-09-28): generic text + the id; the exception text stays in the log only.
        assertEquals("Internal error — correlation id " + cid, err.get("message").asText(), r.body());
        assertFalse(r.body().contains(messageFragment), r.body());
        assertFalse(r.body().contains(kind.getName()), r.body());

        List<ILoggingEvent> lines = c.logs.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains(path)).toList();
        assertEquals(1, lines.size(), "one ERROR line for " + path + ": " + c.logs.list);
        ILoggingEvent line = lines.get(0);
        assertTrue(line.getFormattedMessage().contains(cid), line.getFormattedMessage());
        assertNotNull(line.getThrowableProxy(), "the stack is logged");
        assertEquals(kind.getName(), line.getThrowableProxy().getClassName());
        assertTrue((line.getThrowableProxy().getClassName() + ": " + line.getThrowableProxy().getMessage()).contains(messageFragment),
                "the exception text is kept in the log");
    }

    @Test
    void aLinkageErrorIsAnswered500AndLogged(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String path = "/test-error-boundary/linkage";
            // The real-world shape: a jdk.* module missing from the jlinked bundle runtime.
            throwing(c, path, () -> new NoClassDefFoundError("com/sun/management/OperatingSystemMXBean"));
            String cid = UUID.randomUUID().toString();
            assert500Logged(c, get(c, path, cid), path, cid, NoClassDefFoundError.class,
                    "com/sun/management/OperatingSystemMXBean");
        }
    }

    @Test
    void stackOverflowAndAssertionErrorsAreAnswered500AndLogged(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            throwing(c, "/test-error-boundary/overflow", StackOverflowError::new);
            throwing(c, "/test-error-boundary/assertion", () -> new AssertionError("invariant broken"));
            String cid1 = UUID.randomUUID().toString(), cid2 = UUID.randomUUID().toString();
            assert500Logged(c, get(c, "/test-error-boundary/overflow", cid1), "/test-error-boundary/overflow",
                    cid1, StackOverflowError.class, "StackOverflowError");
            assert500Logged(c, get(c, "/test-error-boundary/assertion", cid2), "/test-error-boundary/assertion",
                    cid2, AssertionError.class, "invariant broken");
        }
    }

    /**
     * An OutOfMemoryError is not swallowed: it is logged, answered with a best-effort 500, and then RETHROWN
     * out of the handler. The JDK HttpServer is where it lands ({@code ServerImpl.Exchange.run} catches every
     * Throwable and logs it at TRACE on {@code com.sun.net.httpserver}), so that TRACE record is the observable
     * proof of the rethrow. The server keeps serving. The probe is a mutating POST, so it also proves the
     * failed attempt reached the audit trail before the Error was rethrown.
     */
    @Test
    void anOutOfMemoryErrorIsLoggedAnsweredAndRethrown(@TempDir Path dir) throws Exception {
        java.util.logging.Logger jdk = java.util.logging.Logger.getLogger("com.sun.net.httpserver");
        java.util.logging.Level previous = jdk.getLevel();
        List<Throwable> reachedTheServer = new CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) {
                if (r.getThrown() != null) reachedTheServer.add(r.getThrown());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        capture.setLevel(java.util.logging.Level.ALL);
        jdk.setLevel(java.util.logging.Level.ALL);
        jdk.addHandler(capture);
        try (Ctx c = open(dir)) {
            String path = "/test-error-boundary/oom";
            c.api.post(path, ApiContext.withCapability("canAdminister",
                    (ex, m) -> { throw new OutOfMemoryError("probe heap"); }));
            String cid = UUID.randomUUID().toString();
            assert500Logged(c, post(c, path, cid), path, cid, OutOfMemoryError.class, "probe heap");
            // AUDIT-ERRORED-REQUEST-UNRECORDED-1: the failed mutation is recorded BEFORE the rethrow.
            assertOneFailedRow(c, path, cid, 500);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (reachedTheServer.stream().noneMatch(ControlApiErrorBoundaryTest::isProbeOom)
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(reachedTheServer.stream().anyMatch(ControlApiErrorBoundaryTest::isProbeOom),
                    "the OOM was rethrown to the HttpServer, not swallowed: " + reachedTheServer);
            assertEquals(200, client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/health")).GET().build(),
                    BodyHandlers.ofString()).statusCode());
        } finally {
            jdk.removeHandler(capture);
            jdk.setLevel(previous);
        }
    }

    private static boolean isProbeOom(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof OutOfMemoryError && "probe heap".equals(c.getMessage())) return true;
        }
        return false;
    }

    /**
     * Audit parity: an errored mutation leaves the same audit trace whether it failed with an Exception or an
     * Error, and since AUDIT-ERRORED-REQUEST-UNRECORDED-1 (2026-09-28) that trace is ONE failed AUDIT row with
     * the 500. Until then it was none: {@code routeDispatch} recorded only after the handler returned.
     */
    @Test
    void anErrorLeavesTheSameAuditTraceAsAnException(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String viaException = "/test-error-boundary/audit-exception";
            String viaError = "/test-error-boundary/audit-error";
            c.api.post(viaException, ApiContext.withCapability("canAdminister",
                    (ex, m) -> { throw new IllegalStateException("probe failure"); }));
            c.api.post(viaError, ApiContext.withCapability("canAdminister",
                    (ex, m) -> { throw new NoClassDefFoundError("probe/Missing"); }));
            for (String p : List.of(viaException, viaError)) {
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + c.port + "/api/v1" + p))
                        .POST(BodyPublishers.noBody()).build(), BodyHandlers.ofString());
                assertEquals(500, r.statusCode(), p + " " + r.body());
            }
            assertEquals(List.of("AUDIT:500"), auditTrace(c, viaException));
            assertEquals(auditTrace(c, viaException), auditTrace(c, viaError));
        }
    }

    /** A mutation that throws writes exactly one AUDIT row: the 5xx, "(failed, ...)" not "(refused, ...)", and
     *  the request's Correlation-ID, which is the one on the ERROR log line. */
    @Test
    void aMutationThatThrowsWritesOneFailedRowJoinedToItsLogLine(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String path = "/test-error-boundary/mutation-fails";
            c.api.post(path, ApiContext.withCapability("canAdminister",
                    (ex, m) -> { throw new IllegalStateException("probe failure"); }));
            String cid = UUID.randomUUID().toString();
            assert500Logged(c, post(c, path, cid), path, cid, IllegalStateException.class, "probe failure");
            assertOneFailedRow(c, path, cid, 500);
        }
    }

    /**
     * A failed request gets the same classification as a successful one, so a read-shaped POST that fails
     * writes no row. Two shapes: a manifest read-shaped POST ({@code /inv/projection}, answered 503 by the
     * absent Link Analysis module's stub) and a literal diagnostic POST that throws. The mutating sibling of
     * the first, {@code POST /inv/investigations}, fails the same way and IS recorded: the probe that would
     * otherwise succeed.
     */
    @Test
    void aReadShapedPostThatFailsWritesNoRow(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(503, post(c, "/inv/projection", UUID.randomUUID().toString()).statusCode());
            assertEquals(List.of(), auditTrace(c, "/inv/projection"));

            String preview = "/test-error-boundary/read/preview";
            c.api.post(preview, ApiContext.withCapability("canAdminister",
                    (ex, m) -> { throw new IllegalStateException("probe failure"); }));
            assertEquals(500, post(c, preview, UUID.randomUUID().toString()).statusCode());
            assertEquals(List.of(), auditTrace(c, preview));

            String cid = UUID.randomUUID().toString();
            assertEquals(503, post(c, "/inv/investigations", cid).statusCode());
            assertOneFailedRow(c, "/inv/investigations", cid, 503);
        }
    }

    /** A successful mutation still writes ONE row, not two: the failure record must not also fire on success. */
    @Test
    void aSuccessfulMutationStillWritesOneRow(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String path = "/test-error-boundary/mutation-ok";
            c.api.post(path, ApiContext.withCapability("canAdminister", (ex, m) -> Map.of("ok", true)));
            String cid = UUID.randomUUID().toString();
            assertEquals(200, post(c, path, cid).statusCode());
            List<Map<String, Object>> rows = auditRows(c, path);
            assertEquals(List.of("AUDIT:200"), auditTrace(c, path), rows.toString());
            assertFalse(String.valueOf(rows.get(0).get("message")).contains("HTTP"), rows.toString());
            assertEquals(cid, rows.get(0).get("correlationId"));
        }
    }

    private HttpResponse<String> post(Ctx c, String path, String cid) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Correlation-ID", cid).timeout(java.time.Duration.ofSeconds(10))
                .POST(BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    /** Exactly one AUDIT row for {@code path}: the 5xx, worded as a failure, joined by Correlation-ID. */
    private static void assertOneFailedRow(Ctx c, String path, String cid, int status) {
        List<Map<String, Object>> rows = auditRows(c, path);
        assertEquals(List.of("AUDIT:" + status), auditTrace(c, path), rows.toString());
        String message = String.valueOf(rows.get(0).get("message"));
        assertTrue(message.endsWith("(failed, HTTP " + status + ")"), message);
        assertFalse(message.contains("refused"), message);
        assertEquals(cid, rows.get(0).get("correlationId"), rows.toString());
    }

    /** Every AUDIT / ACCESS_DENIED event whose http_path is {@code path}, as maps. */
    private static List<Map<String, Object>> auditRows(Ctx c, String path) {
        return c.svc.events().page(500, null, null).stream().map(Event::toMap)
                .filter(e -> e.get("attributes") instanceof Map<?, ?> a && path.equals(a.get("http_path")))
                .toList();
    }

    /** The {@code type:status} of every AUDIT / ACCESS_DENIED event whose http_path is {@code path}. */
    private static List<String> auditTrace(Ctx c, String path) {
        return auditRows(c, path).stream()
                .map(e -> e.get("type") + ":" + ((Map<?, ?>) e.get("attributes")).get("http_status"))
                .toList();
    }
}
