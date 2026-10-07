package com.gamma.connect.notify;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatusAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Amazon SES delivery events arriving through an Amazon SNS HTTPS subscription (D8-SES-SNS-1). Design and
 * security review: {@code docs/archived-documents/plans-archive/ses-sns-adapter-design.md}; operator decisions D1–D11 accepted
 * 2026-09-28.
 *
 * <h3>{@link #verify} — every step fails closed, in this order</h3>
 * <ol>
 *   <li>The body is a flat SNS envelope ({@link SnsEnvelope#parse}).</li>
 *   <li>{@code SignatureVersion} is exactly {@code "2"} (SHA256withRSA). Version 1 (SHA-1) is refused (D4) and
 *       logged once per topic, so the operator learns to set the topic attribute.</li>
 *   <li>{@code TopicArn} is on the configured allowlist — BEFORE any certificate is looked up, so a caller who
 *       does not name one of our topics cannot cause an outbound request.</li>
 *   <li>{@code Timestamp} is at most {@code freshnessSeconds} old (default 3600: SNS retries resend the original
 *       publish time) and at most {@code futureSkewSeconds} ahead (default 300) — D6.</li>
 *   <li>The certificate comes from the {@link CertSource}, which validates {@code SigningCertURL} against the
 *       topic and checks the certificate's trust ({@link SnsCertTrust}); then the RSA signature is checked over
 *       {@link SnsEnvelope#stringToSign}.</li>
 * </ol>
 * {@code verify} never throws; any exception is a {@code false}.
 */
public final class SesSnsDeliveryStatusAdapter implements DeliveryStatusAdapter {

    public static final String ID = "ses";
    static final long DEFAULT_FRESHNESS_SECONDS = 3600;
    static final long DEFAULT_FUTURE_SKEW_SECONDS = 300;
    /** Recently seen MessageIds — sized for the freshness window (design §3.4). */
    static final int SEEN_CAPACITY = 10_000;

    private static final Logger log = LoggerFactory.getLogger(SesSnsDeliveryStatusAdapter.class);

    /**
     * Where a message's signing certificate comes from. The implementation validates the envelope's
     * {@code SigningCertURL} against its (allowlisted) {@code TopicArn} and the certificate's trust; it throws on
     * any refusal. It is the ONLY seam through which verification can reach the network.
     */
    interface CertSource {
        X509Certificate certificateFor(SnsEnvelope envelope) throws Exception;
    }

    /** What happens to a verified, first-seen {@code SubscriptionConfirmation}. Must return without blocking. */
    interface Confirmer {
        void subscriptionConfirmation(SnsEnvelope envelope);
    }

    private final Set<String> topicArns;
    private final CertSource certs;
    private final Confirmer confirmer;
    private final long freshnessSeconds;
    private final long futureSkewSeconds;
    private final Set<String> warnedV1 = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> seen = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_CAPACITY;
        }
    });

    /** Must never be set while this adapter is armed (design §3.2, T-S7): it would switch off TLS name checks. */
    static final String HOSTNAME_VERIFICATION_OFF = "jdk.internal.httpclient.disableHostnameVerification";

    /**
     * ServiceLoader constructor. System properties (the delivery-status adapters' idiom):
     * <ul>
     *   <li>{@code notify.deliverystatus.sns.topicArns} — REQUIRED, comma-separated; empty ⇒ inert (404)</li>
     *   <li>{@code notify.deliverystatus.sns.signingCert} — a local PEM; set ⇒ pinned mode, never fetches (D1)</li>
     *   <li>{@code notify.deliverystatus.sns.autoConfirm} — default {@code true} (D8)</li>
     *   <li>{@code notify.deliverystatus.sns.allowPrivateAddresses} — default {@code false}; for SNS VPC endpoints (D3)</li>
     *   <li>{@code notify.deliverystatus.sns.useProxy} — default {@code false}; the JVM proxy selector (D9)</li>
     *   <li>{@code notify.deliverystatus.sns.maxFetchesPerHour} — default 12</li>
     *   <li>{@code notify.deliverystatus.sns.freshnessSeconds} / {@code .futureSkewSeconds} — 3600 / 300 (D6)</li>
     * </ul>
     * An unreadable or untrusted pinned certificate, or {@value #HOSTNAME_VERIFICATION_OFF} being set, leaves the
     * adapter inert and says why in the log — it fails closed, never open.
     */
    public SesSnsDeliveryStatusAdapter() {
        this(fromProperties());
    }

    private SesSnsDeliveryStatusAdapter(Wiring w) {
        this(w.topics(), w.certs(), w.confirmer(),
                Long.getLong("notify.deliverystatus.sns.freshnessSeconds", DEFAULT_FRESHNESS_SECONDS),
                Long.getLong("notify.deliverystatus.sns.futureSkewSeconds", DEFAULT_FUTURE_SKEW_SECONDS));
    }

    private record Wiring(Set<String> topics, CertSource certs, Confirmer confirmer) {}

    private static Wiring fromProperties() {
        Set<String> topics = new java.util.LinkedHashSet<>();
        for (String t : System.getProperty("notify.deliverystatus.sns.topicArns", "").split(",")) {
            if (!t.isBlank()) topics.add(t.trim());
        }
        if (topics.isEmpty()) return new Wiring(Set.of(), null, null);
        if (System.getProperty(HOSTNAME_VERIFICATION_OFF) != null) {
            log.error("SES/SNS adapter NOT armed: -D{} is set, which would disable TLS hostname verification",
                    HOSTNAME_VERIFICATION_OFF);
            return new Wiring(Set.of(), null, null);
        }
        try {
            for (String t : topics) SnsSigningCerts.expectedHost(t);   // a malformed ARN is a config error
            SnsCertTrust trust = SnsCertTrust.jvmDefault();
            List<X509Certificate> pinned = null;
            String pem = System.getProperty("notify.deliverystatus.sns.signingCert");
            if (pem != null && !pem.isBlank()) {
                pinned = SnsSigningCerts.parsePem(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(pem.trim())));
                // step 10 at boot, against the first topic's host: an untrusted pin never arms
                trust.check(pinned.get(0), pinned.subList(1, pinned.size()),
                        SnsSigningCerts.expectedHost(topics.iterator().next()), System.currentTimeMillis());
            }
            SnsSigningCerts.Budget budget = new SnsSigningCerts.Budget(
                    Integer.getInteger("notify.deliverystatus.sns.maxFetchesPerHour",
                            SnsSigningCerts.DEFAULT_MAX_FETCHES_PER_HOUR), System::currentTimeMillis);
            SnsHttpsFetcher fetcher = SnsHttpsFetcher.system(
                    Boolean.getBoolean("notify.deliverystatus.sns.allowPrivateAddresses"),
                    Boolean.getBoolean("notify.deliverystatus.sns.useProxy"));
            return new Wiring(topics, new SnsSigningCerts(fetcher, trust, pinned, budget, System::currentTimeMillis),
                    // its own budget (review finding 1): made-up certificate URLs cannot starve confirmations
                    new SnsSubscriptionConfirmer(fetcher, new SnsSigningCerts.Budget(SnsSubscriptionConfirmer.QUEUE,
                            System::currentTimeMillis),
                            Boolean.parseBoolean(System.getProperty("notify.deliverystatus.sns.autoConfirm", "true")),
                            SnsSubscriptionConfirmer.boundedExecutor()));
        } catch (Exception e) {
            log.error("SES/SNS adapter NOT armed: {}", e.getMessage());
            return new Wiring(Set.of(), null, null);
        }
    }

    SesSnsDeliveryStatusAdapter(Set<String> topicArns, CertSource certs, Confirmer confirmer, long freshnessSeconds,
                                long futureSkewSeconds) {
        this.topicArns = topicArns == null ? Set.of() : Set.copyOf(topicArns);
        this.certs = certs;
        this.confirmer = confirmer;
        this.freshnessSeconds = freshnessSeconds > 0 ? freshnessSeconds : DEFAULT_FRESHNESS_SECONDS;
        this.futureSkewSeconds = futureSkewSeconds >= 0 ? futureSkewSeconds : DEFAULT_FUTURE_SKEW_SECONDS;
    }

    @Override
    public String id() {
        return ID;
    }

    /** Inert without a TopicArn allowlist and a certificate source — its callback URL then answers 404. */
    @Override
    public boolean configured() {
        return !topicArns.isEmpty() && certs != null && confirmer != null;
    }

    @Override
    public boolean verify(byte[] raw, Map<String, String> headers) {
        try {
            if (!configured()) return false;
            SnsEnvelope env = SnsEnvelope.parse(raw);
            if (env == null) return false;
            if (!"2".equals(env.signatureVersion())) {
                if ("1".equals(env.signatureVersion()) && topicArns.contains(env.topicArn())
                        && warnedV1.add(env.topicArn())) {
                    log.warn("SNS topic {} sends SignatureVersion 1 (SHA-1), which is refused: set the topic "
                            + "attribute SignatureVersion=2", env.topicArn());
                }
                return false;
            }
            if (!topicArns.contains(env.topicArn())) return false;
            if (!fresh(env.timestamp())) return false;
            X509Certificate cert = certs.certificateFor(env);
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(cert.getPublicKey());
            s.update(env.stringToSign().getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(env.signature()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Records {@code messageId} as seen; {@code true} when it already was — a replay (or an SNS retry of a
     * message we handled). Called only for a VERIFIED envelope, so a forger cannot poison the set.
     */
    boolean duplicate(String messageId) {
        return seen.put(messageId, Boolean.TRUE) != null;
    }

    private boolean fresh(String timestamp) {
        long then = Instant.parse(timestamp).getEpochSecond();
        long now = System.currentTimeMillis() / 1000;
        return now - then <= freshnessSeconds && then - now <= futureSkewSeconds;
    }

    /**
     * After {@link #verify}: a replayed {@code MessageId} is {@code "duplicate"} and does no work (design §3.4 — it
     * spares the RSA work and, above all, a second confirmation GET; receipt integrity does not depend on it,
     * because the first observation of a status wins). A {@code SubscriptionConfirmation} is handed to the
     * {@link Confirmer}, which must not block; an {@code UnsubscribeConfirmation} is audited and NEVER
     * re-subscribes. A {@code Notification} is not a control message.
     */
    @Override
    public Optional<String> control(byte[] raw) {
        SnsEnvelope env = SnsEnvelope.parse(raw);
        if (env == null) return Optional.empty();
        if (duplicate(env.messageId())) return Optional.of("duplicate");
        switch (env.type()) {
            case SnsEnvelope.SUBSCRIPTION_CONFIRMATION -> {
                confirmer.subscriptionConfirmation(env);
                return Optional.of(SnsEnvelope.SUBSCRIPTION_CONFIRMATION);
            }
            case SnsEnvelope.UNSUBSCRIBE_CONFIRMATION -> {
                audit("sns.unsubscribe-confirmation", "SNS confirmed that our subscription to " + env.topicArn()
                        + " was removed; it is not re-subscribed automatically", env.topicArn(), null);
                return Optional.of(SnsEnvelope.UNSUBSCRIBE_CONFIRMATION);
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /** The SES event in a verified {@code Notification}'s {@code Message} (design §4.3, {@link SesEventMapper}). */
    @Override
    public List<DeliveryEvent> parse(byte[] raw) {
        SnsEnvelope env = SnsEnvelope.parse(raw);
        if (env == null || !SnsEnvelope.NOTIFICATION.equals(env.type())) return List.of();
        return SesEventMapper.map(env.message());
    }

    /** Best-effort AUDIT row, like every audit emit in the product. {@code token} is recorded only when given (D8). */
    static void audit(String action, String message, String topicArn, String token) {
        try {
            EventLog events = EventLog.current();
            if (events == null) return;
            Event.Builder b = Event.builder(EventType.AUDIT).source("audit").message(message)
                    .actor("sns").actorType("system").action(action).actionCategory("operation")
                    .attr("adapter", ID).attr("topicArn", topicArn);
            if (token != null) b.attr("token", token);
            events.emit(b);
        } catch (RuntimeException ignored) {
            // the audit trail is best effort; the log line is the fallback
        }
        log.info("{}: {}", action, message);
    }
}
