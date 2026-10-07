package com.gamma.connect.notify;

import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatus;
import com.gamma.notify.DeliveryStatusAdapter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * SendGrid Event Webhook adapter (BACKLOG D8) — verifies SendGrid's signed event webhook and normalises
 * its event vocabulary onto {@link DeliveryStatus}.
 *
 * <p><b>Algorithm correction (2026-07-26).</b> The D8 plan specified <b>Ed25519</b>. SendGrid's Signed
 * Event Webhook actually signs with <b>ECDSA over P-256 (secp256r1) with SHA-256</b>, so an Ed25519
 * implementation would reject every genuine callback — a failure that only shows up against the real
 * provider. Both are pure JDK, so the correction costs nothing. The signed payload is
 * {@code timestamp + rawBody} (concatenated, no separator), the signature header is base64 DER, and the
 * configured public key is a base64 X.509 {@code SubjectPublicKeyInfo}, both exactly as SendGrid presents
 * them in its console.
 *
 * <p>Configuration (system properties, the engine's config idiom for operational backends):
 * <ul>
 *   <li>{@code notify.deliverystatus.sendgrid.publicKey} — required, base64 X.509 EC public key. Unset ⇒
 *       the adapter is {@linkplain #configured() not configured} and its callback URL answers 404.</li>
 *   <li>{@code notify.deliverystatus.sendgrid.freshnessSeconds} — replay window, default
 *       {@value DeliveryIds#DEFAULT_FRESHNESS_SECONDS}.</li>
 * </ul>
 *
 * <p>Correlation uses the {@code smtp-id} SendGrid echoes back, which carries the {@code Message-ID} we
 * set outbound — we never capture a provider-side id.
 *
 * @since 4.0.0
 */
public final class SendGridDeliveryStatusAdapter implements DeliveryStatusAdapter {

    public static final String ID = "sendgrid";

    private static final String SIGNATURE_HEADER = "X-Twilio-Email-Event-Webhook-Signature";
    private static final String TIMESTAMP_HEADER = "X-Twilio-Email-Event-Webhook-Timestamp";

    private final PublicKey publicKey;
    private final long freshnessSeconds;

    /** ServiceLoader constructor: reads {@code notify.deliverystatus.sendgrid.*} system properties. */
    public SendGridDeliveryStatusAdapter() {
        this(System.getProperty("notify.deliverystatus.sendgrid.publicKey"),
             Long.getLong("notify.deliverystatus.sendgrid.freshnessSeconds",
                     DeliveryIds.DEFAULT_FRESHNESS_SECONDS));
    }

    SendGridDeliveryStatusAdapter(String base64PublicKey, long freshnessSeconds) {
        this.publicKey = parseKey(base64PublicKey);
        this.freshnessSeconds = freshnessSeconds > 0 ? freshnessSeconds : DeliveryIds.DEFAULT_FRESHNESS_SECONDS;
    }

    /** An unparseable or absent key leaves the adapter inert rather than failing per request. */
    private static PublicKey parseKey(String base64PublicKey) {
        if (base64PublicKey == null || base64PublicKey.isBlank()) return null;
        try {
            byte[] der = Base64.getDecoder().decode(base64PublicKey.trim());
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean configured() {
        return publicKey != null;
    }

    @Override
    public boolean verify(byte[] raw, Map<String, String> headers) {
        // Never throws: a malformed signature is an untrusted caller, not a server fault (403, not 500).
        if (publicKey == null || raw == null) return false;
        String sig = DeliveryIds.header(headers, SIGNATURE_HEADER);
        String ts = DeliveryIds.header(headers, TIMESTAMP_HEADER);
        if (sig == null || sig.isBlank() || ts == null || ts.isBlank()) return false;
        // Freshness first — the timestamp is part of the signed payload, so a replay cannot alter it, but
        // a genuine old signature is still replayable without this check.
        if (!DeliveryIds.fresh(DeliveryIds.parseLong(ts), freshnessSeconds)) return false;
        try {
            ByteArrayOutputStream signed = new ByteArrayOutputStream();
            signed.write(ts.getBytes(StandardCharsets.UTF_8));
            signed.write(raw);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(signed.toByteArray());
            return verifier.verify(Base64.getDecoder().decode(sig.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<DeliveryEvent> parse(byte[] raw) {
        List<DeliveryEvent> events = new ArrayList<>();
        JsonArray array;
        try {
            JsonElement root = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) return events;   // caller answers 422
            array = root.getAsJsonArray();
        } catch (Exception e) {
            return events;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject o = element.getAsJsonObject();
            String deliveryId = DeliveryIds.fromMessageId(string(o, "smtp-id"));
            if (deliveryId == null) continue;   // not one of ours — silently ignore, not our concern
            String kind = string(o, "event");
            DeliveryStatus status = statusOf(kind, string(o, "type"));
            long ts = DeliveryIds.parseLong(string(o, "timestamp")) * 1000;
            events.add(new DeliveryEvent(deliveryId, status, ts,
                    status == DeliveryStatus.UNKNOWN ? o.toString() : null));
        }
        return events;
    }

    /**
     * SendGrid's vocabulary → ours. The hard/soft split comes from the payload's own {@code type}:
     * {@code bounce} is a permanent failure, {@code blocked} is transient (a reputation block or a
     * temporarily unavailable mailbox), so treating them alike would mark a good address dead.
     * Anything unrecognised is {@link DeliveryStatus#UNKNOWN} with its raw payload — never a guess.
     */
    private static DeliveryStatus statusOf(String event, String type) {
        if (event == null) return DeliveryStatus.UNKNOWN;
        return switch (event) {
            case "delivered" -> DeliveryStatus.DELIVERED;
            case "spamreport" -> DeliveryStatus.COMPLAINED;
            case "bounce", "dropped" -> "blocked".equalsIgnoreCase(type)
                    ? DeliveryStatus.BOUNCED_SOFT
                    : DeliveryStatus.BOUNCED_HARD;
            case "deferred" -> DeliveryStatus.BOUNCED_SOFT;
            default -> DeliveryStatus.UNKNOWN;
        };
    }

    private static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
