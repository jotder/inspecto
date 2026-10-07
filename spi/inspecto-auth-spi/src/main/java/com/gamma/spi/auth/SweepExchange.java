package com.gamma.spi.auth;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

/**
 * The inert exchange a caller-less run hands the PDP ({@code RowScope.visibleAs}) so an {@link AccessDecider}, whose
 * contract is "judge this exchange", has something to read its request-scoped facts from (the bound config root).
 * It carries no socket, no body and no headers: every transport method is a no-op or an empty answer. Request-scoped
 * attributes live in {@link RequestAttrs}' per-exchange scope as for any request, and the caller drops that scope
 * when it is done. Package-private on purpose: nothing else may fabricate an exchange.
 */
public final class SweepExchange extends HttpExchange {
    private final Headers none = new Headers();
    private final URI uri;

    public SweepExchange(String path) {
        this.uri = URI.create(path);
    }

    @Override public Headers getRequestHeaders() { return none; }
    @Override public Headers getResponseHeaders() { return new Headers(); }
    @Override public URI getRequestURI() { return uri; }
    @Override public String getRequestMethod() { return "GET"; }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public void close() { }
    @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
    @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
    @Override public void sendResponseHeaders(int rCode, long responseLength) { }
    @Override public InetSocketAddress getRemoteAddress() { return null; }
    @Override public int getResponseCode() { return -1; }
    @Override public InetSocketAddress getLocalAddress() { return null; }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public Object getAttribute(String name) { return null; }
    @Override public void setAttribute(String name, Object value) { }
    @Override public void setStreams(InputStream i, OutputStream o) { }
    @Override public HttpPrincipal getPrincipal() { return null; }
}
