package com.gamma.notify.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.notify.Notification;
import com.gamma.notify.NotificationChannel;

import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Webhook delivery channel — POSTs each notification as JSON ({@link Notification#toMap()} shape, the
 * same document the {@code /notifications} API serves) to a configured URL, through {@link WebhookEgress}: the
 * host is resolved once, every address is checked against the egress policy less the current Space's allowlist,
 * and the connect is pinned to the checked address ({@code WEBHOOK-EGRESS-POLICY-1}). Inert until configured.
 *
 * <p>Configuration (system properties, the engine's config idiom for operational backends):
 * <ul>
 *   <li>{@code notify.webhook.url} — the target URL; unset ⇒ the channel is {@linkplain #configured()
 *       not configured} and never invoked.</li>
 *   <li>{@code notify.webhook.token} — optional bearer token sent as {@code Authorization: Bearer …}.</li>
 *   <li>{@code notify.webhook.timeout.seconds} — per-request timeout (default 10).</li>
 * </ul>
 *
 * <p>A non-2xx response is a delivery failure (thrown, logged and isolated per notification by
 * {@link com.gamma.notify.NotificationService}); there is no retry — notifications are best-effort and
 * the in-app feed remains the durable record.
 *
 * @since 4.0.0
 */
public final class WebhookChannel implements NotificationChannel {

    /** Preference-grid channel id. */
    public static final String ID = "webhook";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url;
    private final String token;
    private final Duration timeout;
    private final SSLSocketFactory tls = (SSLSocketFactory) SSLSocketFactory.getDefault();

    /** ServiceLoader constructor: reads {@code notify.webhook.*} system properties. */
    public WebhookChannel() {
        this(System.getProperty("notify.webhook.url"),
             System.getProperty("notify.webhook.token"),
             Long.getLong("notify.webhook.timeout.seconds", 10L));
    }

    WebhookChannel(String url, String token, long timeoutSeconds) {
        this.url = url == null || url.isBlank() ? null : url.trim();
        this.token = token == null || token.isBlank() ? null : token.trim();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override public String id() { return ID; }

    @Override public boolean configured() { return url != null; }

    @Override
    public void deliver(Notification n) throws Exception {
        post(n, null);
    }

    /** Deliver with a D8 correlation id in {@code X-Inspecto-Delivery-Id} so callbacks can be matched. */
    @Override
    public void deliver(Notification n, String target, String deliveryId) throws Exception {
        post(n, deliveryId);
    }

    private void post(Notification n, String deliveryId) throws Exception {
        WebhookEgress.post(tls, URI.create(url), token, timeout, JSON.writeValueAsString(n.toMap()),
                deliveryId != null && !deliveryId.isBlank()
                        ? Map.of("X-Inspecto-Delivery-Id", deliveryId) : Map.of());
    }
}
