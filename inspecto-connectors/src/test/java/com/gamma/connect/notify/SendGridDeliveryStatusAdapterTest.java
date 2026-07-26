package com.gamma.connect.notify;

import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SendGrid Event Webhook adapter (D8 §4.3).
 *
 * <p>The signing scheme under test is <b>ECDSA P-256 / SHA-256 over {@code timestamp + rawBody}</b>, which
 * is what SendGrid actually uses — the D8 plan said Ed25519, and an Ed25519 implementation would have
 * rejected every genuine callback while passing any test written against itself. These vectors are signed
 * with a real P-256 key so the algorithm, not just the plumbing, is pinned.
 */
class SendGridDeliveryStatusAdapterTest {

    private static KeyPair keyPair;
    private static String publicKeyBase64;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = gen.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static SendGridDeliveryStatusAdapter adapter() {
        return new SendGridDeliveryStatusAdapter(publicKeyBase64, 300);
    }

    /** Sign exactly as SendGrid does: {@code timestamp || rawBody}, no separator, base64 DER out. */
    private static String sign(PrivateKey key, String timestamp, byte[] raw) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(timestamp.getBytes(StandardCharsets.UTF_8));
        signer.update(raw);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    private static Map<String, String> headers(String sig, String ts) {
        return Map.of("x-twilio-email-event-webhook-signature", sig,
                "x-twilio-email-event-webhook-timestamp", ts);
    }

    private static byte[] payload(String event) {
        return ("[{\"smtp-id\":\"<inspecto.abc123@x.com>\",\"event\":\"" + event
                + "\",\"timestamp\":1700000000}]").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aKnownGoodVectorVerifies() throws Exception {
        byte[] raw = payload("delivered");
        String ts = now();

        assertTrue(adapter().verify(raw, headers(sign(keyPair.getPrivate(), ts, raw), ts)));
    }

    @Test
    void aTamperedBodyFails() throws Exception {
        byte[] raw = payload("delivered");
        String ts = now();
        String sig = sign(keyPair.getPrivate(), ts, raw);

        assertFalse(adapter().verify(payload("bounce"), headers(sig, ts)));
    }

    @Test
    void anotherKeysSignatureFails() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] raw = payload("delivered");
        String ts = now();

        assertFalse(adapter().verify(raw, headers(sign(gen.generateKeyPair().getPrivate(), ts, raw), ts)));
    }

    @Test
    void aStaleTimestampFailsEvenWithAGenuineSignature() throws Exception {
        byte[] raw = payload("delivered");
        String stale = String.valueOf(System.currentTimeMillis() / 1000 - 3600);

        assertFalse(adapter().verify(raw, headers(sign(keyPair.getPrivate(), stale, raw), stale)),
                "signature verification alone is not replay protection");
    }

    @Test
    void aMissingHeaderFailsWithoutThrowing() throws Exception {
        byte[] raw = payload("delivered");
        String ts = now();

        assertFalse(adapter().verify(raw, Map.of()));
        assertFalse(adapter().verify(raw, Map.of("x-twilio-email-event-webhook-timestamp", ts)));
        assertFalse(adapter().verify(raw, headers("not-base64-!!", ts)),
                "garbage from an untrusted caller is a 403, never a 500");
    }

    @Test
    void anAbsentOrUnparseableKeyLeavesTheAdapterInert() throws Exception {
        byte[] raw = payload("delivered");
        String ts = now();
        String sig = sign(keyPair.getPrivate(), ts, raw);

        assertFalse(new SendGridDeliveryStatusAdapter(null, 300).configured());
        assertFalse(new SendGridDeliveryStatusAdapter("not-a-key", 300).configured(),
                "a bad key is inert, not a per-request failure");
        assertFalse(new SendGridDeliveryStatusAdapter("not-a-key", 300).verify(raw, headers(sig, ts)));
        assertTrue(adapter().configured());
    }

    @Test
    void sendGridsVocabularyIsNormalisedIncludingTheHardSoftSplit() {
        byte[] raw = ("[{\"smtp-id\":\"<inspecto.d1@x.com>\",\"event\":\"delivered\",\"timestamp\":1700000000},"
                + "{\"smtp-id\":\"<inspecto.d2@x.com>\",\"event\":\"bounce\",\"timestamp\":1700000001},"
                + "{\"smtp-id\":\"<inspecto.d3@x.com>\",\"event\":\"bounce\",\"type\":\"blocked\",\"timestamp\":1700000002},"
                + "{\"smtp-id\":\"<inspecto.d4@x.com>\",\"event\":\"deferred\",\"timestamp\":1700000003},"
                + "{\"smtp-id\":\"<inspecto.d5@x.com>\",\"event\":\"spamreport\",\"timestamp\":1700000004}]")
                .getBytes(StandardCharsets.UTF_8);

        List<DeliveryEvent> events = adapter().parse(raw);

        assertEquals(5, events.size());
        assertEquals(List.of("d1", "d2", "d3", "d4", "d5"),
                events.stream().map(DeliveryEvent::deliveryId).toList());
        assertEquals(DeliveryStatus.DELIVERED, events.get(0).status());
        assertEquals(DeliveryStatus.BOUNCED_HARD, events.get(1).status());
        assertEquals(DeliveryStatus.BOUNCED_SOFT, events.get(2).status(),
                "a 'blocked' bounce is transient — treating it as hard would mark a good address dead");
        assertEquals(DeliveryStatus.BOUNCED_SOFT, events.get(3).status(), "deferred is transient");
        assertEquals(DeliveryStatus.COMPLAINED, events.get(4).status());
        assertEquals(1700000000_000L, events.get(0).ts(), "SendGrid timestamps are seconds; we store millis");
    }

    @Test
    void anUnknownEventTypeYieldsUnknownWithItsPayload() {
        List<DeliveryEvent> events = adapter().parse(payload("group_unsubscribe"));

        assertEquals(1, events.size(), "an unrecognised event is recorded, not dropped");
        assertEquals(DeliveryStatus.UNKNOWN, events.get(0).status());
        assertTrue(events.get(0).providerRaw().contains("group_unsubscribe"));
    }

    @Test
    void eventsForMessagesThatAreNotOursAreIgnored() {
        byte[] raw = ("[{\"smtp-id\":\"<someone-elses.id@other.com>\",\"event\":\"delivered\",\"timestamp\":1700000000},"
                + "{\"event\":\"delivered\",\"timestamp\":1700000000}]").getBytes(StandardCharsets.UTF_8);

        assertTrue(adapter().parse(raw).isEmpty(),
                "no correlation id of ours ⇒ nothing to attach the status to");
    }

    @Test
    void aMalformedPayloadYieldsNoEventsRatherThanThrowing() {
        assertTrue(adapter().parse("not json".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(adapter().parse("{\"event\":\"delivered\"}".getBytes(StandardCharsets.UTF_8)).isEmpty(),
                "an object, not the expected array");
    }

    @Test
    void theMessageIdWeMintRoundTrips() {
        // The outbound half of the correlation, as SmtpEmailChannel writes it.
        String deliveryId = "0f9b2c4d5e6f7a8b";

        assertEquals(deliveryId,
                DeliveryIds.fromMessageId("<inspecto." + deliveryId + "@inspecto.local>"));
        assertNull(DeliveryIds.fromMessageId("<generated.by.javamail@host>"));
        assertNull(DeliveryIds.fromMessageId(null));
    }
}
