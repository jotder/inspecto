package com.gamma.notify.channel;

import com.gamma.pipeline.exec.WebhookSinkTransport;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One HTTP/1.1 request over a socket CONNECTED TO A GIVEN, ALREADY-CHECKED ADDRESS — the Action Request wire
 * ({@code ASSURE-ACTION-REQUESTS-1}, verification finding 1). The JDK {@code HttpClient} always resolves the URI's
 * host itself, so between the egress check and the connect a DNS answer could change (rebinding); this client
 * never resolves anything. For {@code https} the TLS layer is given the URI's HOST NAME, so SNI carries it and
 * the server certificate is verified against it ({@code endpointIdentificationAlgorithm = HTTPS}) — pinning the
 * address does not weaken TLS. The {@code Host} header is the URI's authority.
 *
 * <p>Deliberately small: one request, {@code Connection: close}, no redirects (a 3xx comes back as itself), no
 * compression; the body is read only up to the excerpt cap (plain, {@code Content-Length} or chunked). A header
 * value carrying CR or LF is refused, so no caller-supplied value can split the request.
 */
final class PinnedHttp {

    private PinnedHttp() {}

    static WebhookSinkTransport.Response exchange(SSLSocketFactory tls, String method, URI url, InetAddress connectTo,
                                                  String token, Duration timeout, String json,
                                                  Map<String, String> headers, int cap) throws IOException {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http"))
            throw new IOException("unsupported scheme '" + url.getScheme() + "'");
        String host = url.getHost();
        if (host == null || url.getUserInfo() != null) throw new IOException("the URL has no plain host");
        String bareHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        int port = url.getPort() > 0 ? url.getPort() : scheme.equals("https") ? 443 : 80;
        int millis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis()));

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        StringBuilder req = new StringBuilder();
        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        if (url.getRawQuery() != null) path += "?" + url.getRawQuery();
        req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        boolean defaultPort = url.getPort() <= 0;
        header(req, "Host", host + (defaultPort ? "" : ":" + port));
        header(req, "Content-Type", "application/json");
        header(req, "Content-Length", Integer.toString(body.length));
        header(req, "Connection", "close");
        if (token != null) header(req, "Authorization", "Bearer " + token);
        for (Map.Entry<String, String> h : headers.entrySet()) header(req, h.getKey(), h.getValue());
        req.append("\r\n");

        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(connectTo, port), millis);
            raw.setSoTimeout(millis);
            Socket s = raw;
            if (scheme.equals("https")) {
                SSLSocket ssl = (SSLSocket) tls.createSocket(raw, bareHost, port, true);
                SSLParameters p = ssl.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                if (!com.gamma.pipeline.exec.EgressPolicy.isIpLiteral(bareHost))
                    p.setServerNames(List.of(new SNIHostName(bareHost)));
                ssl.setSSLParameters(p);
                ssl.startHandshake();
                s = ssl;
            }
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return read(new BufferedInputStream(s.getInputStream()), cap);
        } finally {
            try {
                raw.close();
            } catch (IOException ignored) {
                // closing a finished exchange
            }
        }
    }

    private static void header(StringBuilder req, String name, String value) throws IOException {
        if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || name.indexOf(':') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
            throw new IOException("refused a header carrying CR/LF: " + name);
        req.append(name).append(": ").append(value).append("\r\n");
    }

    private static WebhookSinkTransport.Response read(InputStream in, int cap) throws IOException {
        String status = line(in);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("not an HTTP response");
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("not an HTTP status line");
        }
        long length = -1;
        boolean chunked = false;
        for (String h = line(in); !h.isEmpty(); h = line(in)) {
            int c = h.indexOf(':');
            if (c < 0) continue;
            String k = h.substring(0, c).trim().toLowerCase(Locale.ROOT), v = h.substring(c + 1).trim();
            if (k.equals("content-length")) {
                try {
                    length = Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                    length = -1;
                }
            } else if (k.equals("transfer-encoding") && v.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }
        ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
        int limit = Math.max(0, cap) * 4 + 4;   // bytes enough for cap chars of UTF-8
        if (code / 100 == 1 || code == 204 || code == 304) {
            // no body
        } else if (chunked) {
            while (bodyOut.size() < limit) {
                String sizeLine = line(in);
                int semi = sizeLine.indexOf(';');
                int size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
                if (size == 0) break;
                copy(in, bodyOut, Math.min(size, limit - bodyOut.size()));
                if (bodyOut.size() >= limit) break;
                line(in);
            }
        } else {
            copy(in, bodyOut, length < 0 ? limit : (int) Math.min(length, limit));
        }
        String text = bodyOut.toString(StandardCharsets.UTF_8);
        return new WebhookSinkTransport.Response(code, text.length() > cap ? text.substring(0, Math.max(0, cap)) : text);
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int n) throws IOException {
        byte[] buf = new byte[4096];
        while (n > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, n));
            if (r < 0) return;
            out.write(buf, 0, r);
            n -= r;
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c = in.read(); c >= 0; c = in.read()) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16_384) throw new IOException("an HTTP header line is over 16 KiB");
        }
        return sb.toString();
    }
}
