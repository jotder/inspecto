package com.gamma.acquire.connectors;

import com.gamma.pipeline.exec.EgressPolicy;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

/**
 * One HTTP/1.1 exchange of an already-built (and already-signed) {@link HttpRequest} over a socket CONNECTED TO A
 * GIVEN, ALREADY-CHECKED ADDRESS — the object-store half of the egress policy ({@link EgressPolicy}). The JDK
 * {@code HttpClient} always resolves the URI's host itself, so a DNS answer could change between the check and the
 * connect; this never resolves anything. For {@code https} the TLS layer is given the URI's HOST NAME, so SNI
 * carries it and the certificate is verified against it ({@code endpointIdentificationAlgorithm = HTTPS}).
 *
 * <p>The {@code Host} header is {@link AwsSigV4#hostHeader} — the exact value SigV4 signed. The request body is
 * streamed from the request's own {@link HttpRequest.BodyPublisher} (so a file PUT is never buffered), and the
 * response body is returned as a stream over the socket ({@code Content-Length}, chunked, or to EOF) — closing it
 * closes the connection. {@code Connection: close}, no redirects (a 3xx comes back as itself), no compression; a
 * header name or value carrying CR or LF is refused. Unlike {@code PinnedHttp} in {@code inspecto-notify-channels}
 * (capped JSON POST excerpts) this must carry any method, signed headers and whole objects, so it is its own class.
 */
final class PinnedObjectStoreHttp {

    private PinnedObjectStoreHttp() {}

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    /** The socket read timeout when neither the request nor the Connection ({@code read_timeout_ms}) sets one. */
    static final int DEFAULT_READ_TIMEOUT_MS = 120_000;
    /** Response header limits: a server cannot make us buffer an unbounded header block. */
    static final int MAX_HEADERS = 200;
    static final int MAX_HEADER_BYTES = 64 * 1024;

    /**
     * @param readTimeoutMs the socket read timeout when the request sets none (the Connection's, else
     *                      {@link #DEFAULT_READ_TIMEOUT_MS})
     */
    static HttpResponse<InputStream> send(SSLSocketFactory tls, HttpRequest req, InetAddress connectTo, int readTimeoutMs)
            throws IOException, InterruptedException {
        URI url = req.uri();
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http"))
            throw new IOException("unsupported scheme '" + url.getScheme() + "'");
        String host = url.getHost();
        if (host == null || url.getUserInfo() != null) throw new IOException("the URL has no plain host");
        String bareHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        int port = url.getPort() > 0 ? url.getPort() : scheme.equals("https") ? 443 : 80;
        String method = req.method();

        long length = req.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(0L);
        if (length < 0) throw new IOException("a request body of unknown length is not supported");

        StringBuilder head = new StringBuilder();
        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        if (url.getRawQuery() != null) path += "?" + url.getRawQuery();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        header(head, "Host", AwsSigV4.hostHeader(url));
        if (length > 0 || !(method.equals("GET") || method.equals("HEAD") || method.equals("DELETE")))
            header(head, "Content-Length", Long.toString(length));
        header(head, "Connection", "close");
        for (Map.Entry<String, List<String>> h : req.headers().map().entrySet())
            for (String v : h.getValue()) header(head, h.getKey(), v);
        head.append("\r\n");

        Socket raw = new Socket();
        boolean handedOff = false;
        try {
            raw.connect(new InetSocketAddress(connectTo, port), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(req.timeout().map(d -> (int) Math.min(Integer.MAX_VALUE, Math.max(1, d.toMillis())))
                    .orElse(readTimeoutMs > 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS));
            Socket s = raw;
            if (scheme.equals("https")) {
                SSLSocket ssl = (SSLSocket) tls.createSocket(raw, bareHost, port, true);
                SSLParameters p = ssl.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                if (!EgressPolicy.isIpLiteral(bareHost)) p.setServerNames(List.of(new SNIHostName(bareHost)));
                ssl.setSSLParameters(p);
                ssl.startHandshake();
                s = ssl;
            }
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (length > 0) writeBody(req.bodyPublisher().get(), out);
            out.flush();

            InputStream in = new BufferedInputStream(s.getInputStream());
            int status;
            Map<String, List<String>> headers;
            do {   // a 1xx is an interim response: its header block is read and dropped, the real one follows
                status = status(line(in));
                headers = headers(in);
            } while (status / 100 == 1);
            HttpHeaders hh = HttpHeaders.of(headers, (k, v) -> true);
            InputStream body;
            if (method.equals("HEAD") || status == 204 || status == 304) {
                body = InputStream.nullInputStream();
            } else if (hh.firstValue("transfer-encoding").map(v -> v.toLowerCase(Locale.ROOT).contains("chunked")).orElse(false)) {
                body = new Chunked(in);
            } else {
                long cl = hh.firstValueAsLong("content-length").orElse(-1L);
                body = cl < 0 ? in : new Bounded(in, cl);
            }
            Socket owned = raw;
            InputStream closing = new FilterInputStream(body) {
                @Override public void close() throws IOException {
                    owned.close();
                }
            };
            handedOff = true;
            return new Response<>(status, hh, closing, req);
        } finally {
            if (!handedOff) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // the exchange already failed
                }
            }
        }
    }

    /** The same response with a different body — for callers that read the stream whole. */
    static <T> HttpResponse<T> withBody(HttpResponse<?> r, T body) {
        return new Response<>(r.statusCode(), r.headers(), body, r.request());
    }

    private record Response<T>(int statusCode, HttpHeaders headers, T body, HttpRequest request)
            implements HttpResponse<T> {
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /** Stream the publisher's buffers into {@code out}, one at a time (backpressure), waiting for completion. */
    private static void writeBody(HttpRequest.BodyPublisher pub, OutputStream out) throws IOException, InterruptedException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        pub.subscribe(new Flow.Subscriber<ByteBuffer>() {
            private Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription s) {
                sub = s;
                s.request(1);
            }
            @Override public void onNext(ByteBuffer b) {
                try {
                    byte[] chunk = new byte[b.remaining()];
                    b.get(chunk);
                    out.write(chunk);
                    sub.request(1);
                } catch (IOException e) {
                    sub.cancel();
                    done.completeExceptionally(e);
                }
            }
            @Override public void onError(Throwable t) { done.completeExceptionally(t); }
            @Override public void onComplete() { done.complete(null); }
        });
        try {
            done.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("the request body failed: " + e.getCause(), e.getCause());
        }
    }

    static void header(StringBuilder req, String name, String value) throws IOException {
        if (name.indexOf(':') >= 0 || hasControl(name, false) || hasControl(value, true))
            throw new IOException("refused a header carrying a control character (CR, LF, NUL, …): " + name);
        req.append(name).append(": ").append(value).append("\r\n");
    }

    /** Whether {@code s} carries a C0 control or DEL — HTAB allowed only where {@code tabOk} (a value). */
    private static boolean hasControl(String s, boolean tabOk) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && !(tabOk && c == '\t')) || c == 0x7f) return true;
        }
        return false;
    }

    /** One header block, bounded by {@link #MAX_HEADERS} lines and {@link #MAX_HEADER_BYTES} bytes. */
    private static Map<String, List<String>> headers(InputStream in) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int count = 0;
        long bytes = 0;
        for (String h = line(in); !h.isEmpty(); h = line(in)) {
            if (++count > MAX_HEADERS) throw new IOException("the response has more than " + MAX_HEADERS + " headers");
            bytes += h.length() + 2;
            if (bytes > MAX_HEADER_BYTES) throw new IOException("the response headers exceed " + MAX_HEADER_BYTES + " bytes");
            int c = h.indexOf(':');
            if (c > 0)
                headers.computeIfAbsent(h.substring(0, c).trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                        .add(h.substring(c + 1).trim());
        }
        return headers;
    }

    private static int status(String line) throws IOException {
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("not an HTTP response");
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP status line");
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c = in.read(); ; c = in.read()) {
            if (c < 0) {
                if (sb.isEmpty()) throw new IOException("the connection closed mid-response");
                break;
            }
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16_384) throw new IOException("an HTTP header line is over 16 KiB");
        }
        return sb.toString();
    }

    /** A body of exactly {@code remaining} bytes. */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long remaining;

        Bounded(InputStream in, long remaining) {
            this.in = in;
            this.remaining = remaining;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int c = in.read();
            if (c < 0) throw new IOException("the connection closed " + remaining + " bytes short of Content-Length");
            remaining--;
            return c;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, remaining));
            if (r < 0) throw new IOException("the connection closed " + remaining + " bytes short of Content-Length");
            remaining -= r;
            return r;
        }
    }

    /** A {@code Transfer-Encoding: chunked} body, decoded; trailers are read and dropped. */
    private static final class Chunked extends InputStream {
        private final InputStream in;
        private long left;
        private boolean eof;

        Chunked(InputStream in) {
            this.in = in;
        }

        private boolean next() throws IOException {
            if (eof) return false;
            if (left > 0) return true;
            String size = line(in);
            if (size.isEmpty()) size = line(in);   // the CRLF after the previous chunk's data
            int semi = size.indexOf(';');
            try {
                left = Long.parseLong((semi < 0 ? size : size.substring(0, semi)).trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("a malformed chunk size '" + size + "'");
            }
            if (left < 0) throw new IOException("a negative chunk size '" + size + "'");
            if (left == 0) {
                for (String t = line(in); !t.isEmpty(); t = line(in)) { /* trailers */ }
                eof = true;
                return false;
            }
            return true;
        }

        @Override public int read() throws IOException {
            if (!next()) return -1;
            int c = in.read();
            if (c < 0) throw new IOException("the connection closed mid-chunk");
            left--;
            return c;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!next()) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r < 0) throw new IOException("the connection closed mid-chunk");
            left -= r;
            return r;
        }
    }
}
