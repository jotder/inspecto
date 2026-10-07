package com.gamma.connect.notify;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * The recorded SNS/SES fixture set (D8-SES-SNS-1 slice S0), re-signed at test time.
 *
 * <p><b>Provenance.</b> {@code src/test/resources/sns/*.json} are the three SNS envelope shapes
 * ({@code SubscriptionConfirmation}, {@code Notification}, {@code UnsubscribeConfirmation}) and
 * {@code sns/ses/*.json} the SES event-publishing payloads (Bounce Permanent / Transient / Undetermined,
 * Complaint, Delivery, DeliveryDelay, Reject, Rendering Failure, Open, and one legacy {@code notificationType}
 * feedback notification), all CONSTRUCTED from the shapes in AWS's public SNS and SES documentation — no AWS
 * account was available (operator, 2026-09-28), so none is a capture. Addresses are {@code example.test}, the
 * account id is AWS's documentation placeholder, and the files carry an EMPTY {@code Signature}.
 *
 * <p>{@link #sign} fills {@code Timestamp} (now, unless a test overrides it), {@code SigningCertURL} and a real
 * signature made with a key {@link SnsTestPki} minted a moment earlier, so verification runs end to end. The
 * string to sign is built HERE, independently of {@code SnsEnvelope}, from AWS's documented key order — two
 * implementations of the same rule, so a shared mistake cannot make both agree.
 */
final class SnsFixtures {

    static final String TOPIC = "arn:aws:sns:us-east-1:123456789012:inspecto-ses-events";
    static final String CERT_URL =
            "https://sns.us-east-1.amazonaws.com/SimpleNotificationService-0000000000000000000000000000000a.pem";
    /** The delivery id the SES fixtures carry in {@code <inspecto.{id}@example.test>}. */
    static final String DELIVERY_ID = "d0a1b2c3";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private SnsFixtures() {}

    static JsonObject envelope(String name) throws Exception {
        return JsonParser.parseString(resource("sns/" + name + ".json")).getAsJsonObject();
    }

    /** A {@code Notification} envelope carrying the SES payload {@code sns/ses/<ses>.json} as its Message. */
    static JsonObject notification(String ses) throws Exception {
        JsonObject env = envelope("notification");
        env.addProperty("Message", JsonParser.parseString(resource("sns/ses/" + ses + ".json")).toString());
        return env;
    }

    /** Sign {@code env} as version 2 with the default signer; returns the exact request bytes. */
    static byte[] sign(JsonObject env) throws Exception {
        return sign(env, "2", SnsTestPki.signer().key(), e -> {});
    }

    /** Sign with a chosen version and key; {@code tweak} runs on the envelope first (after Timestamp=now). */
    static byte[] sign(JsonObject env, String version, PrivateKey key, Consumer<JsonObject> tweak) throws Exception {
        env.addProperty("Timestamp", Instant.now().toString());
        env.addProperty("SigningCertURL", CERT_URL);
        env.addProperty("SignatureVersion", version);
        tweak.accept(env);
        Signature s = Signature.getInstance("1".equals(version) ? "SHA1withRSA" : "SHA256withRSA");
        s.initSign(key);
        s.update(stringToSign(env).getBytes(StandardCharsets.UTF_8));
        env.addProperty("Signature", Base64.getEncoder().encodeToString(s.sign()));
        return GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    /** AWS's documented string to sign: {@code key\nvalue\n} for each present key, in this fixed order. */
    static String stringToSign(JsonObject env) {
        String type = env.get("Type").getAsString();
        List<String> keys = "Notification".equals(type)
                ? List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type")
                : List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (!env.has(k) || env.get(k).isJsonNull()) continue;
            sb.append(k).append('\n').append(env.get(k).getAsString()).append('\n');
        }
        return sb.toString();
    }

    static String resource(String path) throws Exception {
        try (InputStream in = SnsFixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing fixture " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
