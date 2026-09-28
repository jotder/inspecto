package com.gamma.control;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
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
        assertTrue(err.get("message").asText().contains(messageFragment), r.body());

        List<ILoggingEvent> lines = c.logs.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains(path)).toList();
        assertEquals(1, lines.size(), "one ERROR line for " + path + ": " + c.logs.list);
        ILoggingEvent line = lines.get(0);
        assertTrue(line.getFormattedMessage().contains(cid), line.getFormattedMessage());
        assertNotNull(line.getThrowableProxy(), "the stack is logged");
        assertEquals(kind.getName(), line.getThrowableProxy().getClassName());
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
     * proof of the rethrow. The server keeps serving.
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
            throwing(c, path, () -> new OutOfMemoryError("probe heap"));
            String cid = UUID.randomUUID().toString();
            assert500Logged(c, get(c, path, cid), path, cid, OutOfMemoryError.class, "probe heap");

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
     * Error. Today that trace is NONE: {@code routeDispatch} records after the handler returns, so neither
     * kind of 500 writes an AUDIT row (reported, not changed here).
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
            assertEquals(auditTrace(c, viaException), auditTrace(c, viaError));
        }
    }

    /** The {@code type:status} of every AUDIT / ACCESS_DENIED event whose http_path is {@code path}. */
    private static List<String> auditTrace(Ctx c, String path) {
        return c.svc.events().page(500, null, null).stream().map(Event::toMap)
                .filter(e -> e.get("attributes") instanceof Map<?, ?> a && path.equals(a.get("http_path")))
                .map(e -> e.get("type") + ":" + ((Map<?, ?>) e.get("attributes")).get("http_status"))
                .toList();
    }
}
