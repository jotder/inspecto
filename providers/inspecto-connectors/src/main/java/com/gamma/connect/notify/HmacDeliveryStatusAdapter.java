package com.gamma.connect.notify;

import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatus;
import com.gamma.notify.DeliveryStatusAdapter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Generic HMAC-SHA256 delivery-status adapter (BACKLOG D8) — for Postmark-style providers and
 * self-hosted relays, and the seam an operator can point anything at without us writing a per-provider
 * adapter.
 *
 * <p>Configuration (system properties):
 * <ul>
 *   <li>{@code notify.deliverystatus.hmac.secret} — required shared secret. Unset ⇒
 *       {@linkplain #configured() not configured} and the callback URL answers 404.</li>
 *   <li>{@code notify.deliverystatus.hmac.freshnessSeconds} — replay window, default
 *       {@value DeliveryIds#DEFAULT_FRESHNESS_SECONDS}.</li>
 * </ul>
 *
 * <p><b>The signed payload is {@code timestamp + "." + rawBody}</b>, not the body alone. Signing only the
 * body would leave the timestamp attacker-controlled, which would defeat the freshness check it exists to
 * enable — the replay window has to cover a value the signature commits to. Comparison is constant-time
 * via {@link MessageDigest#isEqual}, the {@code ShareTokens} precedent.
 *
 * <p>Expected payload: a JSON array of {@code {deliveryId, status, ts}} objects, where {@code status} is
 * one of {@code delivered} / {@code bounce_hard} / {@code bounce_soft} / {@code complaint}. Anything else
 * is recorded {@link DeliveryStatus#UNKNOWN} with its raw payload.
 *
 * @since 4.0.0
 */
public final class HmacDeliveryStatusAdapter implements DeliveryStatusAdapter {

    public static final String ID = "hmac";

    private static final String SIGNATURE_HEADER = "X-Inspecto-Signature";
    private static final String TIMESTAMP_HEADER = "X-Inspecto-Timestamp";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final long freshnessSeconds;

    /** ServiceLoader constructor: reads {@code notify.deliverystatus.hmac.*} system properties. */
    public HmacDeliveryStatusAdapter() {
        this(System.getProperty("notify.deliverystatus.hmac.secret"),
             Long.getLong("notify.deliverystatus.hmac.freshnessSeconds",
                     DeliveryIds.DEFAULT_FRESHNESS_SECONDS));
    }

    HmacDeliveryStatusAdapter(String secret, long freshnessSeconds) {
        this.secret = secret == null || secret.isBlank()
                ? null
                : secret.trim().getBytes(StandardCharsets.UTF_8);
        this.freshnessSeconds = freshnessSeconds > 0 ? freshnessSeconds : DeliveryIds.DEFAULT_FRESHNESS_SECONDS;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean configured() {
        return secret != null;
    }

    @Override
    public boolean verify(byte[] raw, Map<String, String> headers) {
        if (secret == null || raw == null) return false;
        String given = DeliveryIds.header(headers, SIGNATURE_HEADER);
        String ts = DeliveryIds.header(headers, TIMESTAMP_HEADER);
        if (given == null || given.isBlank() || ts == null || ts.isBlank()) return false;
        if (!DeliveryIds.fresh(DeliveryIds.parseLong(ts), freshnessSeconds)) return false;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            mac.update(ts.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(raw);
            byte[] expected = mac.doFinal();
            byte[] provided = HexFormat.of().parseHex(given.trim().toLowerCase(java.util.Locale.ROOT));
            return MessageDigest.isEqual(expected, provided);   // constant-time (ShareTokens precedent)
        } catch (Exception e) {
            return false;   // includes a non-hex signature — untrusted input, never a 500
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
            String deliveryId = string(o, "deliveryId");
            if (deliveryId == null || deliveryId.isBlank()) continue;
            DeliveryStatus status = statusOf(string(o, "status"));
            long ts = DeliveryIds.parseLong(string(o, "ts"));
            events.add(new DeliveryEvent(deliveryId, status, ts,
                    status == DeliveryStatus.UNKNOWN ? o.toString() : null));
        }
        return events;
    }

    private static DeliveryStatus statusOf(String status) {
        if (status == null) return DeliveryStatus.UNKNOWN;
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "delivered" -> DeliveryStatus.DELIVERED;
            case "bounce_hard", "hard_bounce" -> DeliveryStatus.BOUNCED_HARD;
            case "bounce_soft", "soft_bounce" -> DeliveryStatus.BOUNCED_SOFT;
            case "complaint", "spam" -> DeliveryStatus.COMPLAINED;
            default -> DeliveryStatus.UNKNOWN;
        };
    }

    private static String string(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
