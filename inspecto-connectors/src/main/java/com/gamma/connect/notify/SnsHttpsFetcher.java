package com.gamma.connect.notify;

import com.gamma.pipeline.exec.EgressPolicy;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 🔒 The one outbound GET of the SNS adapter (D8-SES-SNS-1, design §3.2 steps 8–9) — the signing certificate and
 * the subscription confirmation both go through here, and nothing else in the adapter opens a socket.
 *
 * <p><b>Direct (the default).</b> The host passes {@link EgressPolicy#checkHost}; it is resolved ONCE and EVERY
 * address is checked by {@link EgressPolicy#resolve} — loopback, link-local (the {@code 169.254.169.254} metadata
 * service), this host, multicast, unspecified and their IPv4-embedding IPv6 forms are always refused; private and
 * CGNAT ranges are refused unless the operator opts in for SNS VPC endpoints (D3), and even then
 * {@code fd00:ec2::254} (the IPv6 metadata address, which sits in the liftable ULA range) stays refused. The
 * socket then connects to THE CHECKED ADDRESS, so a second DNS answer cannot swap it (rebinding); TLS is given the
 * host name for SNI and {@code HTTPS} endpoint identification against the JVM trust store, so a rebound name would
 * also have to present a trusted certificate for {@code sns.<region>.amazonaws.com}. There is no trust-all or
 * skip-verify option, and there never will be.
 *
 * <p><b>Through the JVM's proxy</b> only when {@code notify.deliverystatus.sns.useProxy=true} (D9): the JDK
 * {@code HttpClient} with {@link ProxySelector#getDefault()}. The proxy resolves the name, so the address check
 * cannot run here; the ARN-derived host and TLS verification are the whole defence on that path.
 *
 * <p>Both paths: connect timeout 3 s, a hard 5 s deadline on the WHOLE exchange (a watchdog closes the socket, so a
 * server dribbling a byte per second cannot hold the thread — per-read timeouts alone would not stop that), no
 * redirects (a 3xx is a failure, never followed), only {@code 200}, and a body cap of 16 KiB: a longer body is
 * refused without reading the rest.
 */
final class SnsHttpsFetcher implements SnsSigningCerts.Fetcher {

    /** A deliberate refusal (policy, status, size, deadline) — as opposed to a transport failure, which may pass. */
    static final class Refused extends IOException {
        Refused(String message) { super(message); }
    }

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration DEADLINE = Duration.ofSeconds(5);
    static final int BODY_CAP = 16 * 1024;
    private static final int HEADER_CAP = 16 * 1024;
    private static final InetAddress IPV6_METADATA = InetAddress.ofLiteral("fd00:ec2::254");

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sns-fetch-deadline");
        t.setDaemon(true);
        return t;
    });

    private final SSLSocketFactory tls;
    private final EgressPolicy.Resolver resolver;
    /** Checked address → where to connect. {@code (a) -> a:443} in production; a test maps it to its stub. */
    private final Function<InetAddress, InetSocketAddress> dial;
    private final boolean allowPrivate;
    private final boolean useProxy;
    private final Duration deadline;

    SnsHttpsFetcher(SSLSocketFactory tls, EgressPolicy.Resolver resolver, Function<InetAddress, InetSocketAddress> dial,
                    boolean allowPrivate, boolean useProxy, Duration deadline) {
        this.tls = tls;
        this.resolver = resolver;
        this.dial = dial;
        this.allowPrivate = allowPrivate;
        this.useProxy = useProxy;
        this.deadline = deadline;
    }

    /** Production wiring: the JVM's TLS defaults and resolver, port 443. */
    static SnsHttpsFetcher system(boolean allowPrivate, boolean useProxy) {
        return new SnsHttpsFetcher((SSLSocketFactory) SSLSocketFactory.getDefault(), EgressPolicy.SYSTEM,
                a -> new InetSocketAddress(a, 443), allowPrivate, useProxy, DEADLINE);
    }

    @Override
    public Fetched get(URI url) throws IOException {
        if (!"https".equals(url.getScheme()) || url.getRawUserInfo() != null || url.getHost() == null)
            throw new Refused("refused: not a plain https URL");
        String host = url.getHost().toLowerCase(Locale.ROOT);
        try {
            EgressPolicy.checkHost(host);
        } catch (IllegalArgumentException refused) {
            throw new Refused("refused: " + refused.getMessage());
        }
        return useProxy ? viaProxy(url) : direct(url, host);
    }

    private Fetched direct(URI url, String host) throws IOException {
        // Resolved ONCE; every answer checked by the egress policy's own classes (EgressPolicy.resolve's rule),
        // plus the IPv6 metadata address, which sits in the ULA range the D3 opt-in would otherwise lift.
        EgressPolicy.Allowlist allow = allowPrivate ? EgressPolicy.Allowlist.of(List.of(host)) : EgressPolicy.Allowlist.EMPTY;
        InetAddress[] all;
        try {
            all = resolver.resolve(host);
        } catch (java.net.UnknownHostException e) {
            throw new Refused("refused: " + host + " does not resolve");
        }
        if (all == null || all.length == 0) throw new Refused("refused: " + host + " does not resolve");
        for (InetAddress a : all) {
            if (IPV6_METADATA.equals(a)) throw new Refused("refused: " + host + " resolves to the metadata address");
            String cls = EgressPolicy.deniedClass(a);
            if (cls != null && !allow.permits(host, a, cls))
                throw new Refused("refused: " + host + " resolves to " + a.getHostAddress() + ", a " + cls + " address");
        }
        InetAddress to = all[0];
        Socket raw = new Socket();
        ScheduledFuture<?> guard = WATCHDOG.schedule(() -> closeQuietly(raw), deadline.toMillis(), TimeUnit.MILLISECONDS);
        try {
            raw.connect(dial.apply(to), (int) Math.min(CONNECT_TIMEOUT.toMillis(), deadline.toMillis()));
            raw.setSoTimeout((int) deadline.toMillis());
            SSLSocket ssl = (SSLSocket) tls.createSocket(raw, host, 443, true);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            p.setServerNames(List.of(new SNIHostName(host)));
            ssl.setSSLParameters(p);
            ssl.startHandshake();
            String target = url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
            ssl.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: " + host + "\r\nAccept: */*\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ssl.getOutputStream().flush();
            byte[] body = readResponse(ssl.getInputStream());
            return new Fetched(body, chainOf(ssl.getSession().getPeerCertificates()));
        } catch (IOException e) {
            if (guard.isDone()) throw new Refused("refused: the exchange exceeded " + deadline.toMillis() + " ms");
            throw e;
        } finally {
            guard.cancel(false);
            closeQuietly(raw);
        }
    }

    /**
     * The D9 path. The WHOLE exchange — headers and capped body — runs as one future bounded by the deadline
     * ({@code HttpRequest.timeout} alone covers only the headers); on expiry the client is shut down, which aborts
     * the exchange, and the client is closed either way so no selector thread outlives the call (review finding 2).
     */
    private Fetched viaProxy(URI url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(url).timeout(deadline).GET().build();
        try (HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT).build()) {
            var exchange = client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).thenApply(r -> {
                try (InputStream in = r.body()) {
                    if (r.statusCode() != 200)
                        throw new Refused("refused: HTTP " + r.statusCode() + " (redirects are never followed)");
                    long declared = r.headers().firstValueAsLong("content-length").orElse(-1);
                    if (declared > BODY_CAP) throw new Refused("refused: body of " + declared + " bytes is over the cap");
                    byte[] body = readCapped(in);
                    List<X509Certificate> chain = List.of();
                    if (r.sslSession().isPresent()) chain = chainOf(r.sslSession().get().getPeerCertificates());
                    return new Fetched(body, chain);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            try {
                return exchange.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                client.shutdownNow();
                throw new Refused("refused: the exchange exceeded " + deadline.toMillis() + " ms");
            } catch (InterruptedException e) {
                client.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause() instanceof java.io.UncheckedIOException u ? u.getCause() : e.getCause();
                throw c instanceof IOException io ? io : new IOException(String.valueOf(c));
            }
        }
    }

    /** Status line, headers (bounded), then a 200's body within the cap — Content-Length, chunked or to EOF. */
    private static byte[] readResponse(InputStream in) throws IOException {
        int[] budget = { HEADER_CAP };
        String status = line(in, budget);
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new Refused("refused: not an HTTP response");
        if (!"200".equals(parts[1])) throw new Refused("refused: HTTP " + parts[1] + " (redirects are never followed)");
        long length = -1;
        boolean chunked = false;
        for (String h = line(in, budget); !h.isEmpty(); h = line(in, budget)) {
            int c = h.indexOf(':');
            if (c < 0) continue;
            String k = h.substring(0, c).trim().toLowerCase(Locale.ROOT), v = h.substring(c + 1).trim();
            if (k.equals("content-length")) {
                try {
                    length = Long.parseLong(v);
                } catch (NumberFormatException e) {
                    throw new Refused("refused: bad Content-Length");
                }
            } else if (k.equals("transfer-encoding")) {
                chunked = v.toLowerCase(Locale.ROOT).contains("chunked");
            }
        }
        if (length > BODY_CAP) throw new Refused("refused: body of " + length + " bytes is over the cap");
        if (!chunked) return length >= 0 ? readExactly(in, (int) length) : readCapped(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = line(in, budget);
            int semi = sizeLine.indexOf(';');
            int size;
            try {
                size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
            } catch (NumberFormatException e) {
                throw new Refused("refused: bad chunk size");
            }
            if (size < 0 || out.size() + (long) size > BODY_CAP) throw new Refused("refused: body is over the cap");
            if (size == 0) return out.toByteArray();
            out.write(readExactly(in, size));
            line(in, budget);
        }
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] b = in.readNBytes(n);
        if (b.length != n) throw new Refused("refused: truncated body");
        return b;
    }

    /** Up to {@link #BODY_CAP} bytes to EOF; one byte more is a refusal, and nothing further is read. */
    private static byte[] readCapped(InputStream in) throws IOException {
        byte[] b = in.readNBytes(BODY_CAP + 1);
        if (b.length > BODY_CAP) throw new Refused("refused: body is over the cap");
        return b;
    }

    private static String line(InputStream in, int[] budget) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c = in.read(); ; c = in.read()) {
            if (c < 0) throw new Refused("refused: truncated response");
            if (--budget[0] < 0) throw new Refused("refused: response headers are over the cap");
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
        }
    }

    private static List<X509Certificate> chainOf(Certificate[] peer) {
        List<X509Certificate> out = new ArrayList<>();
        if (peer != null) for (Certificate c : peer) if (c instanceof X509Certificate x) out.add(x);
        return out;
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // closing a finished or abandoned exchange
        }
    }
}
