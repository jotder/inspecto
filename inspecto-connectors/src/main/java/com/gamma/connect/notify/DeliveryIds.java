package com.gamma.connect.notify;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers for the {@link com.gamma.notify.DeliveryStatusAdapter} implementations (BACKLOG D8):
 * pulling our own correlation id back out of a provider payload, and the replay window every adapter
 * must enforce.
 *
 * @since 4.0.0
 */
final class DeliveryIds {

    /** Default replay window. A valid signature is replayable forever without a freshness check. */
    static final long DEFAULT_FRESHNESS_SECONDS = 300;

    /**
     * Our outbound SMTP {@code Message-ID} shape — {@code <inspecto.{deliveryId}@{domain}>}. Providers
     * echo this back (SendGrid as {@code smtp-id}), which is how the round trip closes without capturing
     * any provider-side id.
     */
    private static final Pattern MESSAGE_ID = Pattern.compile("inspecto\\.([A-Za-z0-9]+)@");

    private DeliveryIds() {
    }

    /** The delivery id embedded in a provider's echoed message id, or {@code null} if it is not ours. */
    static String fromMessageId(String messageId) {
        if (messageId == null) return null;
        Matcher m = MESSAGE_ID.matcher(messageId);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Whether {@code tsSeconds} is inside the replay window either side of now. Both directions matter:
     * a far-future timestamp is as much a forgery signal as a stale one.
     */
    static boolean fresh(long tsSeconds, long windowSeconds) {
        if (tsSeconds <= 0) return false;
        long skew = Math.abs(System.currentTimeMillis() / 1000 - tsSeconds);
        return skew <= windowSeconds;
    }

    /** Case-insensitive header lookup — header casing is not guaranteed by any provider. */
    static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        String direct = headers.get(name);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
