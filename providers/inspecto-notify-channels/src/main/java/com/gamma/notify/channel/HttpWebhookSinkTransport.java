package com.gamma.notify.channel;

import com.gamma.pipeline.exec.WebhookSinkTransport;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * The {@code sink.webhook} node's wire — the Professional/Enterprise provider of the engine's
 * {@link WebhookSinkTransport} SPI, registered through {@code META-INF/services} in this module only, so
 * Personal (which bundles no {@code inspecto-notify-channels}) has none and the sink refuses naming the edition.
 *
 * <p>It is {@link WebhookChannel}'s POST ({@link WebhookEgress#post}), not a second HTTP client: same JSON
 * content type, same bearer header, same "non-2xx is a failure", no redirects — and the same egress policy
 * ({@code WEBHOOK-EGRESS-POLICY-1}): resolved once, every address checked against the deny-by-default policy less
 * the Space's allowlist, the connect pinned to the checked address. Retries, the idempotency key and the
 * Connection rules are the engine's ({@code WebhookSink}).
 */
public final class HttpWebhookSinkTransport implements WebhookSinkTransport {

    /** The egress-checked, pinned POST ({@link WebhookEgress}) — the Space's allowlist applies. */
    @Override
    public void post(URI url, String bearerToken, Duration timeout, String jsonBody, Map<String, String> headers)
            throws Exception {
        WebhookEgress.post(tls, url, bearerToken, timeout, jsonBody, headers);
    }

    /** The TLS layer of {@link #exchange}; the platform default, or a test's. */
    private final javax.net.ssl.SSLSocketFactory tls;

    public HttpWebhookSinkTransport() {
        this((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault());
    }

    HttpWebhookSinkTransport(javax.net.ssl.SSLSocketFactory tls) {
        this.tls = tls;
    }

    /** The Action Request wire: {@link PinnedHttp} — connect to the checked address, verify TLS against the name. */
    @Override
    public Response exchange(String method, URI url, java.net.InetAddress connectTo, String bearerToken,
                             Duration timeout, String jsonBody, Map<String, String> headers, int excerptCap)
            throws Exception {
        return PinnedHttp.exchange(tls, method, url, connectTo, bearerToken, timeout, jsonBody, headers, excerptCap);
    }
}
