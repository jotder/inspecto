package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.pipeline.exec.EgressAllowlist;
import com.gamma.pipeline.exec.EgressPolicy;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The HTTP plumbing shared by the three object-store connectors — {@link S3Connector},
 * {@link GcsConnector} and {@link AzureBlobConnector} (JAVA-5).
 *
 * <p>Each carried its own byte-identical {@code execute} / {@code executeStreaming} pair, differing in
 * exactly two things: the provider name in the error message, and whether the request was built by
 * {@code signed(...)} (SigV4, Shared Key) or {@code authed(...)} (a bearer token). Both variables are
 * lifted out — the label into the constructor, the request build into {@link #request} — so the
 * response contract itself lives in one place.
 *
 * <p>That contract is worth centralising for two reasons the copies made easy to get wrong:
 * <ul>
 *   <li><b>Any non-2xx is a failure</b>, checked as {@code status / 100 != 2} rather than against a
 *       list of codes, so a provider returning an unusual success or error code cannot slip through.</li>
 *   <li><b>An {@link InterruptedException} re-sets the thread's interrupt flag</b> before it is
 *       rethrown as an {@link AcquisitionException}. Swallowing that flag is invisible in tests and
 *       breaks cancellation of a long fetch — precisely the kind of detail a fourth copy would drop.</li>
 * </ul>
 *
 * <p><b>Egress policy</b> ({@code OBJECT-STORE-EGRESS-POLICY}, 2026-09-27): every request goes through {@link #send},
 * the same rule as Action Requests and both webhooks — the Connection host must pass {@link EgressPolicy#checkHost};
 * it is resolved ONCE per request and EVERY address is checked against the deny-by-default policy less the current
 * Space's allowlist ({@link EgressAllowlist#forCurrentSpace()}); the signed request is then sent by
 * {@link PinnedObjectStoreHttp} to the CHECKED address, with the name kept for {@code Host} (the signed value), SNI
 * and certificate verification. A refusal dials nothing. <b>Redirects are never followed</b>: S3's 301/307 region
 * redirects name a different host, which a SigV4 signature (it covers {@code host}) would not survive anyway, and
 * GCS / Azure do not redirect these APIs — so a 3xx is an ordinary non-2xx failure, never a second, unchecked dial.
 *
 * <p>⚠ The XML helpers ({@code parseXml}/{@code text}/{@code unquote}/{@code escapeXml}) stay duplicated
 * in {@link S3Connector} and {@link AzureBlobConnector} on purpose: GCS speaks JSON, so they are shared
 * by two of three, not three of three, and they belong to the wire FORMAT rather than to this transport.
 */
abstract class AbstractHttpObjectStoreConnector {

    /** Provider name as it appears in error messages ({@code "S3"}, {@code "GCS"}, {@code "Azure"}). */
    private final String provider;

    /** {@code scheme://host[:port]}, no path. */
    protected final URI endpoint;

    /** Name → addresses; a test swaps it. */
    static volatile EgressPolicy.Resolver resolver = EgressPolicy.SYSTEM;
    /** The allowlist in force; the current Space's in production. */
    static volatile Supplier<EgressPolicy.Allowlist> allowlist = EgressAllowlist::forCurrentSpace;
    /**
     * Test seam ONLY: the address actually dialled for a checked one — identity in production. A test gives a name
     * a private address (loopback is never allowlistable) and maps that address to its in-process server.
     */
    static volatile UnaryOperator<InetAddress> dial = UnaryOperator.identity();
    /** The TLS socket factory; the platform default in production, a test trusts its own certificate. */
    static volatile Supplier<SSLSocketFactory> tls = () -> (SSLSocketFactory) SSLSocketFactory.getDefault();

    protected AbstractHttpObjectStoreConnector(String provider, URI endpoint) {
        this.provider = provider;
        this.endpoint = endpoint;
    }

    /**
     * Send one built request under the egress policy (see the class doc) and return its response with the body
     * as a stream over the connection — the caller closes it. A refusal is an {@link IOException} naming why.
     */
    protected HttpResponse<InputStream> send(HttpRequest req) throws IOException, InterruptedException {
        String host = req.uri().getHost();
        if (host == null || req.uri().getUserInfo() != null)
            throw new IOException("egress refused: '" + req.uri() + "' has no plain host");
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress to;
        try {
            EgressPolicy.checkHost(bare);
            to = EgressPolicy.resolve(bare, allowlist.get(), resolver);
        } catch (IllegalArgumentException | EgressPolicy.Refused refused) {
            throw new IOException("egress refused: " + refused.getMessage(), refused);
        }
        return PinnedObjectStoreHttp.send(tls.get(), req, dial.apply(to));
    }

    /** {@link #send} with the (small) body read whole and the connection closed. */
    protected HttpResponse<byte[]> sendReadingBody(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<InputStream> resp = send(req);
        try (InputStream in = resp.body()) {
            return PinnedObjectStoreHttp.withBody(resp, in.readAllBytes());
        }
    }

    /**
     * Build the provider-authenticated request — SigV4 for S3, Shared Key for Azure, a bearer token for
     * GCS. The only thing that differs between the three transports.
     */
    protected abstract HttpRequest request(String method, String encodedPath, Map<String, String> query,
                                           Map<String, String> headers, byte[] body) throws IOException;

    /** Execute a request whose (small) body is read whole — listings, metadata, tagging. */
    protected HttpResponse<byte[]> execute(String method, String path, Map<String, String> query,
                                           Map<String, String> headers, byte[] body, String what)
            throws AcquisitionException {
        try {
            HttpResponse<byte[]> resp = sendReadingBody(request(method, path, query, headers, body));
            if (resp.statusCode() / 100 != 2)
                throw new AcquisitionException(provider + " " + what + " failed: HTTP " + resp.statusCode()
                        + errorDetail(resp.body()));
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AcquisitionException(provider + " " + what + " failed on " + endpoint.getHost()
                    + ": " + e.getMessage(), e);
        }
    }

    /** Execute a GET whose body should stream (object/blob reads). */
    protected HttpResponse<InputStream> executeStreaming(String path, Map<String, String> query,
                                                         Map<String, String> headers, String what)
            throws AcquisitionException {
        try {
            HttpResponse<InputStream> resp = send(request("GET", path, query, headers, null));
            if (resp.statusCode() / 100 != 2) {
                byte[] err;
                try (InputStream in = resp.body()) { err = in.readNBytes(2048); }
                throw new AcquisitionException(provider + " " + what + " failed: HTTP " + resp.statusCode()
                        + errorDetail(err));
            }
            return resp;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AcquisitionException(provider + " " + what + " failed on " + endpoint.getHost()
                    + ": " + e.getMessage(), e);
        }
    }

    // ── shared parsing helpers ───────────────────────────────────────────────────

    /** The first 500 bytes of an error body, whitespace-collapsed, as a message suffix ({@code ""} if none). */
    protected static String errorDetail(byte[] body) {
        if (body == null || body.length == 0) return "";
        String s = new String(body, 0, Math.min(body.length, 500), StandardCharsets.UTF_8);
        return " — " + s.replaceAll("\s+", " ").trim();
    }

    /** The last segment of a relative key/blob name. */
    protected static String nameOf(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }

    /** Join two path fragments with exactly one slash; a blank left side yields {@code b} unchanged. */
    protected static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        String left = a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
        return left + "/" + (b.startsWith("/") ? b.substring(1) : b);
    }

    /** Parse a listing's numeric field, falling back to {@code dflt} for absent or non-numeric values. */
    protected static long parseLong(String s, long dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; }
    }
}
