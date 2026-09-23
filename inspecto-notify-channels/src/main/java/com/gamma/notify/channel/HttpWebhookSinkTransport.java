package com.gamma.notify.channel;

import com.gamma.pipeline.exec.WebhookSinkTransport;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * The {@code sink.webhook} node's wire — the Professional/Enterprise provider of the engine's
 * {@link WebhookSinkTransport} SPI, registered through {@code META-INF/services} in this module only, so
 * Personal (which bundles no {@code inspecto-notify-channels}) has none and the sink refuses naming the edition.
 *
 * <p>It is {@link WebhookChannel}'s POST ({@link WebhookChannel#send}), not a second HTTP client: same JSON
 * content type, same bearer header, same "non-2xx is a failure", same JDK {@link HttpClient} with redirects
 * left at {@code NEVER}. Retries, the idempotency key and the Connection/egress rules are the engine's
 * ({@code WebhookSink}) — this class only sends.
 */
public final class HttpWebhookSinkTransport implements WebhookSinkTransport {

    /** Connect timeout — {@link WebhookChannel}'s default; the per-request timeout comes from the Connection. */
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public void post(URI url, String bearerToken, Duration timeout, String jsonBody, Map<String, String> headers)
            throws Exception {
        WebhookChannel.send(client, url, bearerToken, timeout, jsonBody, headers);
    }
}
