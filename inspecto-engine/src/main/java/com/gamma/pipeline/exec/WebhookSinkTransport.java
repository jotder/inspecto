package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * <b>The outbound-HTTP seam of the {@code sink.webhook} node.</b> One call = one POST of one JSON body.
 * {@link WebhookSink} owns everything around it — resolving the Connection, the token reference, batching,
 * the idempotency key and the {@code RetryPolicy} — so an implementation is only the wire.
 *
 * <p>⛔ <b>The engine ships NO implementation, by design</b> (EDG-01): Personal registers zero outbound
 * transports, exactly as it registers zero {@code NotificationChannel}s. The one provider lives in
 * {@code inspecto-notify-channels} (Professional and Enterprise only), beside the webhook notification
 * channel whose client it reuses. With no provider on the classpath a {@code sink.webhook} write refuses
 * and names the edition — never a silent skip.
 *
 * <p>Contract: a non-2xx response, a timeout or an I/O failure MUST throw (the caller retries, then fails
 * the branch). Redirects must NOT be followed — the Connection names the only host rows may reach.
 */
@PublicApi(since = "4.0.0")
public interface WebhookSinkTransport {

    /**
     * POST {@code jsonBody} ({@code Content-Type: application/json}) to {@code url}.
     *
     * @param bearerToken the resolved token for {@code Authorization: Bearer …}, or {@code null} for none
     * @param headers     extra request headers (the idempotency key); never {@code null}
     */
    void post(URI url, String bearerToken, Duration timeout, String jsonBody, Map<String, String> headers)
            throws Exception;
}
