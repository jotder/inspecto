package com.gamma.connect.notify;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One Amazon SNS HTTP/S message envelope (D8-SES-SNS-1, design §2) and the exact string SNS signs for it.
 *
 * <p><b>Parsed before anything is authenticated</b>, so it is deliberately narrow (design §3.8): a streaming
 * read of ONE flat JSON object whose values are all strings. A nested object or array anywhere, a duplicate key,
 * a non-string value or an unknown {@code Type} makes {@link #parse} return {@code null} — so attacker JSON never
 * builds a tree, and nesting depth is bounded at one.
 *
 * <p>String to sign: {@code key\nvalue\n} for each key in a fixed order — {@code Message, MessageId, Subject
 * (only when present), Timestamp, TopicArn, Type} for a {@code Notification}; {@code Message, MessageId,
 * SubscribeURL, Timestamp, Token, TopicArn, Type} for the two confirmation types. {@code SigningCertURL} and
 * {@code SignatureVersion} are NOT signed, which is why the certificate URL is validated against the (signed)
 * {@code TopicArn} rather than trusted.
 */
final class SnsEnvelope {

    static final String NOTIFICATION = "Notification";
    static final String SUBSCRIPTION_CONFIRMATION = "SubscriptionConfirmation";
    static final String UNSUBSCRIBE_CONFIRMATION = "UnsubscribeConfirmation";
    private static final Set<String> TYPES = Set.of(NOTIFICATION, SUBSCRIPTION_CONFIRMATION, UNSUBSCRIBE_CONFIRMATION);

    private static final List<String> NOTIFICATION_KEYS =
            List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type");
    private static final List<String> CONFIRMATION_KEYS =
            List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");
    /** Keys an envelope of each kind MUST carry (Subject is optional on a Notification). */
    private static final List<String> COMMON_REQUIRED =
            List.of("Type", "MessageId", "TopicArn", "Message", "Timestamp", "SignatureVersion", "Signature", "SigningCertURL");

    private final Map<String, String> fields;

    private SnsEnvelope(Map<String, String> fields) {
        this.fields = fields;
    }

    /** The envelope, or {@code null} when {@code raw} is not a well-formed SNS envelope. Never throws. */
    static SnsEnvelope parse(byte[] raw) {
        if (raw == null) return null;
        Map<String, String> f = new HashMap<>();
        try (JsonReader r = new JsonReader(new StringReader(new String(raw, StandardCharsets.UTF_8)))) {
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                JsonToken t = r.peek();
                String v;
                if (t == JsonToken.STRING) v = r.nextString();
                else if (t == JsonToken.NULL) { r.nextNull(); v = null; }
                else return null;   // nested object/array, number, boolean: not an SNS envelope
                if (f.containsKey(k)) return null;   // a duplicate key is ambiguous — which one was signed?
                f.put(k, v);
            }
            r.endObject();
            if (r.peek() != JsonToken.END_DOCUMENT) return null;
        } catch (Exception e) {
            return null;
        }
        for (String k : COMMON_REQUIRED) if (f.get(k) == null) return null;
        String type = f.get("Type");
        if (!TYPES.contains(type)) return null;
        if (!NOTIFICATION.equals(type) && (f.get("Token") == null || f.get("SubscribeURL") == null)) return null;
        return new SnsEnvelope(f);
    }

    String type() { return fields.get("Type"); }
    String messageId() { return fields.get("MessageId"); }
    String topicArn() { return fields.get("TopicArn"); }
    String message() { return fields.get("Message"); }
    String timestamp() { return fields.get("Timestamp"); }
    String token() { return fields.get("Token"); }
    String subscribeUrl() { return fields.get("SubscribeURL"); }
    String signatureVersion() { return fields.get("SignatureVersion"); }
    String signature() { return fields.get("Signature"); }
    String signingCertUrl() { return fields.get("SigningCertURL"); }

    /** The exact bytes SNS signed for this envelope's {@code Type}. */
    String stringToSign() {
        StringBuilder sb = new StringBuilder();
        for (String k : NOTIFICATION.equals(type()) ? NOTIFICATION_KEYS : CONFIRMATION_KEYS) {
            String v = fields.get(k);
            if (v == null) continue;   // only Subject may be absent (or JSON null) — required keys were checked
            sb.append(k).append('\n').append(v).append('\n');
        }
        return sb.toString();
    }
}
