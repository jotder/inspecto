package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An error response of ANY status never carries an ETag. {@code ETags.respond} sets the header and returns the body for the
 * framework to serialise; if that serialisation fails late, the 500 that replaces it must not keep the tag of a body that was
 * never sent. {@link ControlApi#respond} (the single writer of every error answer) therefore withdraws it.
 */
class ControlApiErrorNeverTaggedTest {

    @Test
    void anErrorStatusWithdrawsATagAnEarlierStepSetAndASuccessKeepsIt(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        try (CollectorService svc = new CollectorService(List.of(pipe), 3600, 1)) {
            ControlApi api = new ControlApi(svc, 0);
            try {
                for (int status : new int[] {400, 404, 409, 422, 500, 503}) {
                    Probe ex = new Probe();
                    ETags.set(ex, "\"sha256:abc\"");   // a handler tagged its answer, then failed
                    api.respond(ex, status, Map.of("error", "boom"));
                    assertNull(ex.getResponseHeaders().getFirst("ETag"), "status " + status);
                    assertEquals(status, ex.status);
                }
                Probe ok = new Probe();
                ETags.set(ok, "\"sha256:abc\"");
                api.respond(ok, 200, Map.of("a", 1));
                assertEquals("\"sha256:abc\"", ok.getResponseHeaders().getFirst("ETag"), "a 200 keeps its tag");
            } finally {
                api.close();
            }
        }
    }

    private static final class Probe extends HttpExchange {
        int status = -1;
        private final Map<String, Object> attributes = new HashMap<>();
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();

        @Override public URI getRequestURI() { return URI.create("/api/v1/x"); }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return new ByteArrayOutputStream(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { status = rCode; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public void setStreams(InputStream i, OutputStream o) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
