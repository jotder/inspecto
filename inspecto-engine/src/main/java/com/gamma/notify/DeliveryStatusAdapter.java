package com.gamma.notify;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SPI for an <em>inbound</em> provider callback that reports what happened to a message we sent
 * (BACKLOG D8) — the counterpart to {@link NotificationChannel}, which sends. Discovered via
 * {@link java.util.ServiceLoader} exactly like the channels, so the lean Personal core ships none and a
 * Standard/Enterprise build adds one by dropping its module on the classpath.
 *
 * <p><b>Correlation is ours, not the provider's.</b> We mint a {@code deliveryId} and embed it in the
 * outbound message (an SMTP {@code Message-ID} of {@code <inspecto.{deliveryId}@{domain}>}, or an
 * {@code X-Inspecto-Delivery-Id} header on an outbound webhook); providers echo it back on bounce and
 * complaint. This avoids widening {@link NotificationChannel#deliver} to return a provider id, which
 * would break every implementor of a {@code @PublicApi} interface.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #verify} must <b>never throw</b> — a malformed signature is an untrusted caller, not a
 *       server fault, so it returns {@code false} and the route answers 403. It must also reject a
 *       <b>stale</b> timestamp: a valid signature is replayable forever otherwise, so signature
 *       verification alone is not replay protection.</li>
 *   <li>{@link #parse} runs only <b>after</b> a successful {@link #verify} — an unverified callback must
 *       never be able to influence delivery state, which would be a cheap denial-of-notification vector.</li>
 *   <li>Normalisation is the adapter's job: map the provider's vocabulary onto {@link DeliveryStatus},
 *       and emit {@link DeliveryStatus#UNKNOWN} with the raw payload for anything unrecognised rather
 *       than guessing or dropping it.</li>
 * </ul>
 *
 * @since 4.0.0
 */
@PublicApi(since = "4.0.0")
public interface DeliveryStatusAdapter {

    /** Stable id, used as the callback URL's last segment (e.g. {@code "sendgrid"}). */
    String id();

    /**
     * Whether {@code raw} genuinely came from this provider and is fresh.
     *
     * @param raw     the request body's <b>exact</b> bytes — never a re-serialisation, which would not
     *                reproduce the signed payload's key order or whitespace
     * @param headers request headers, lower-cased keys
     * @return {@code true} only if the signature verifies AND the timestamp is within the freshness
     *         window. Must not throw for any input.
     */
    boolean verify(byte[] raw, Map<String, String> headers);

    /** The normalised events in this payload (providers batch several per callback). */
    List<DeliveryEvent> parse(byte[] raw);

    /**
     * A VERIFIED message that carries no delivery events — a subscription handshake, or a replay the adapter
     * has already handled (D8-SES-SNS-1, decision D10). Runs after {@link #verify} and before {@link #parse};
     * when it returns a kind, the route answers {@code 200 {"control": kind}} and {@code parse} is skipped. The
     * adapter schedules any side effect itself and must not block the request on it. The default returns
     * empty, so adapters without control messages are unchanged.
     *
     * @since 4.0.0
     */
    default Optional<String> control(byte[] raw) { return Optional.empty(); }

    /**
     * Whether this adapter has the configuration it needs (a public key, a shared secret). An
     * unconfigured adapter is <b>inert</b> — the route answers 404 for it, rather than failing per
     * request — mirroring {@link NotificationChannel#configured()}.
     */
    default boolean configured() { return true; }
}
