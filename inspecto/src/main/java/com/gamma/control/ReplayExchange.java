package com.gamma.control;

import com.gamma.spi.auth.Subject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * An in-process re-run of a held request (`ASSURE-MAKER-CHECKER-1`): the approver's exchange with the
 * AUTHOR's method, path and body, whose response is captured instead of sent. Approving a Pending Change
 * replays the route it was proposed through, so every gate that route runs — its capability, its content
 * validation, its conflict checks, its side effects — runs again at apply time, against the Space as it is now.
 *
 * <p>Everything identity-shaped comes from the approver's exchange (the peer address, the local address,
 * the HTTP context); the request-scoped attributes, Subject included, are copied by
 * {@link ControlApi#replay}, never shared, so nothing the replay stamps reaches the approve request.
 */
final class ReplayExchange extends HttpExchange {

    private final HttpExchange outer;
    private final String method;
    private final URI uri;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final ByteArrayInputStream in;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int status = -1;

    ReplayExchange(HttpExchange outer, String method, String v1Path, byte[] body, Map<String, String> headers) {
        this.outer = outer;
        this.method = method;
        this.uri = URI.create(v1Path);
        this.in = new ByteArrayInputStream(body == null ? new byte[0] : body);
        requestHeaders.set("Content-Type", "application/json");
        headers.forEach(requestHeaders::set);
    }

    /** The captured status ({@code -1} when the route sent nothing). */
    int status() { return status; }

    /** The captured response body as UTF-8 text. */
    String body() { return out.toString(StandardCharsets.UTF_8); }

    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return uri; }
    @Override public String getRequestMethod() { return method; }
    @Override public HttpContext getHttpContext() { return outer.getHttpContext(); }
    @Override public void close() { }
    @Override public InputStream getRequestBody() { return in; }
    @Override public OutputStream getResponseBody() { return out; }
    @Override public void sendResponseHeaders(int rCode, long responseLength) { status = rCode; }
    @Override public InetSocketAddress getRemoteAddress() { return outer.getRemoteAddress(); }
    @Override public int getResponseCode() { return status; }
    @Override public InetSocketAddress getLocalAddress() { return outer.getLocalAddress(); }
    @Override public String getProtocol() { return outer.getProtocol(); }
    @Override public Object getAttribute(String name) { return null; }
    @Override public void setAttribute(String name, Object value) { }
    @Override public void setStreams(InputStream i, OutputStream o) { }
    @Override public HttpPrincipal getPrincipal() { return outer.getPrincipal(); }
}
