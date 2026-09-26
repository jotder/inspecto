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

    /**
     * One answered request — its status and at most {@code excerptCap} characters of its body. ⚠ Never a
     * header: a receiver's headers can echo a credential.
     */
    record Response(int status, String bodyExcerpt) {}

    /**
     * Send {@code jsonBody} with {@code method} (POST / PUT / PATCH) and return the answer, WHATEVER its status —
     * the Action Request dispatcher ({@code ASSURE-ACTION-REQUESTS-1}) records every attempt's response. Only an
     * I/O failure or a timeout throws. Redirects are NOT followed: a 3xx comes back as itself.
     *
     * <p>🔴 <b>Pinned</b> (verification finding 1): the transport connects to {@code connectTo} — the address
     * {@link EgressPolicy#resolve} checked — and NEVER resolves {@code url}'s host itself, so DNS rebinding cannot
     * swap the address between the check and the connect. The host name still travels as the {@code Host}
     * header, the TLS SNI and the name the server certificate is verified against.
     *
     * <p>The default refuses, so a transport that implements only {@link #post} fails closed rather than
     * reporting a status it never saw.
     */
    default Response exchange(String method, URI url, java.net.InetAddress connectTo, String bearerToken,
                              Duration timeout, String jsonBody, Map<String, String> headers, int excerptCap)
            throws Exception {
        throw new UnsupportedOperationException("this outbound transport implements POST delivery only");
    }
}
