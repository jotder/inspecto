package com.gamma.connect.notify;

import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatus;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.util.List;

/**
 * An SES event (the {@code Message} of a VERIFIED SNS {@code Notification}) → our {@link DeliveryEvent}
 * (D8-SES-SNS-1, design §4.3). Reads SES event publishing ({@code eventType}) and the legacy feedback
 * notifications ({@code notificationType}).
 *
 * <table>
 *   <tr><td>{@code Delivery}</td><td>DELIVERED</td></tr>
 *   <tr><td>{@code Bounce} Permanent</td><td>BOUNCED_HARD</td></tr>
 *   <tr><td>{@code Bounce} Transient / Undetermined</td><td>BOUNCED_SOFT — an unknown is never treated as permanent,
 *       which would mark a good address dead; the existing soft-bounce retry picks it up</td></tr>
 *   <tr><td>{@code Complaint}</td><td>COMPLAINED</td></tr>
 *   <tr><td>{@code Reject}, {@code Rendering Failure}</td><td>BOUNCED_HARD — the message never left SES</td></tr>
 *   <tr><td>{@code DeliveryDelay}</td><td>UNKNOWN (D11): SES is still retrying itself, and a soft bounce would make
 *       our retry resend alongside it</td></tr>
 *   <tr><td>anything else ({@code Send}, {@code Open}, {@code Click}, …)</td><td>UNKNOWN with the raw payload</td></tr>
 * </table>
 *
 * <p><b>Correlation (D5, provisional).</b> Our id is read from the {@code Message-ID} we set outbound
 * ({@code <inspecto.{id}@…>}): first {@code mail.commonHeaders.messageId}, then a {@code Message-ID} entry of
 * {@code mail.headers}. Whether SES echoes OUR {@code Message-ID} there, rather than its own, is the live check
 * owed to a real account — the fixtures are built from AWS's documentation. An event that carries none of ours
 * is still answered 2xx (SNS retries anything else): it becomes an UNKNOWN event under a {@code ses:}-prefixed id
 * no receipt can have, which the route reports as unknown (202).
 */
final class SesEventMapper {

    private SesEventMapper() {}

    static List<DeliveryEvent> map(String message) {
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(message);
            if (!root.isJsonObject()) return List.of();
            o = root.getAsJsonObject();
        } catch (Exception e) {
            return List.of();
        }
        JsonObject mail = obj(o, "mail");
        String kind = str(o, "eventType");
        if (kind == null) kind = str(o, "notificationType");
        DeliveryStatus status = statusOf(kind, o);
        String id = ours(mail);
        if (id == null) {
            String sesId = mail == null ? null : str(mail, "messageId");
            return List.of(new DeliveryEvent("ses:" + (sesId == null ? "unidentified" : sesId),
                    DeliveryStatus.UNKNOWN, timestamp(o, kind, mail), message));
        }
        return List.of(new DeliveryEvent(id, status, timestamp(o, kind, mail),
                status == DeliveryStatus.UNKNOWN ? message : null));
    }

    static DeliveryStatus statusOf(String kind, JsonObject o) {
        if (kind == null) return DeliveryStatus.UNKNOWN;
        return switch (kind) {
            case "Delivery" -> DeliveryStatus.DELIVERED;
            case "Bounce" -> "Permanent".equals(str(obj(o, "bounce"), "bounceType"))
                    ? DeliveryStatus.BOUNCED_HARD : DeliveryStatus.BOUNCED_SOFT;
            case "Complaint" -> DeliveryStatus.COMPLAINED;
            case "Reject", "Rendering Failure", "RenderingFailure" -> DeliveryStatus.BOUNCED_HARD;
            default -> DeliveryStatus.UNKNOWN;   // DeliveryDelay (D11), Send, Open, Click, Subscription, …
        };
    }

    private static String ours(JsonObject mail) {
        if (mail == null) return null;
        String id = DeliveryIds.fromMessageId(str(obj(mail, "commonHeaders"), "messageId"));
        if (id != null) return id;
        JsonElement headers = mail.get("headers");
        if (headers != null && headers.isJsonArray()) {
            for (JsonElement h : headers.getAsJsonArray()) {
                if (h.isJsonObject() && "message-id".equalsIgnoreCase(str(h.getAsJsonObject(), "name"))) {
                    id = DeliveryIds.fromMessageId(str(h.getAsJsonObject(), "value"));
                    if (id != null) return id;
                }
            }
        }
        return null;
    }

    /** The event's own time (e.g. {@code bounce.timestamp}), else the send time, else now. */
    private static long timestamp(JsonObject o, String kind, JsonObject mail) {
        String section = kind == null ? null : switch (kind) {
            case "Bounce" -> "bounce";
            case "Complaint" -> "complaint";
            case "Delivery" -> "delivery";
            case "DeliveryDelay" -> "deliveryDelay";
            case "Open" -> "open";
            case "Click" -> "click";
            default -> null;
        };
        for (String ts : new String[] { section == null ? null : str(obj(o, section), "timestamp"), str(mail, "timestamp") }) {
            if (ts == null) continue;
            try {
                return Instant.parse(ts).toEpochMilli();
            } catch (Exception ignored) {
                // fall through to the next candidate
            }
        }
        return 0;   // DeliveryEvent defaults a non-positive ts to now
    }

    private static JsonObject obj(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
