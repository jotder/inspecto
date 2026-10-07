package com.gamma.connect.notify;

import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatus;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generic HMAC delivery-status adapter (D8 §4.3): signature, replay window, and normalisation.
 *
 * <p>Every negative case asserts a {@code false} return rather than an exception — the SPI contract is
 * that {@code verify} never throws, because an untrusted caller must produce a 403, never a 500.
 */
class HmacDeliveryStatusAdapterTest {

    private static final String SECRET = "s3cr3t";

    private static HmacDeliveryStatusAdapter adapter() {
        return new HmacDeliveryStatusAdapter(SECRET, 300);
    }

    private static String sign(String secret, String timestamp, byte[] raw) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) '.');
        mac.update(raw);
        return HexFormat.of().formatHex(mac.doFinal());
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    private static Map<String, String> headers(String sig, String ts) {
        return Map.of("x-inspecto-signature", sig, "x-inspecto-timestamp", ts);
    }

    @Test
    void aKnownGoodVectorVerifies() throws Exception {
        byte[] raw = "[{\"deliveryId\":\"d1\",\"status\":\"delivered\"}]".getBytes(StandardCharsets.UTF_8);
        String ts = now();

        assertTrue(adapter().verify(raw, headers(sign(SECRET, ts, raw), ts)));
    }

    @Test
    void aTamperedBodyFails() throws Exception {
        byte[] raw = "[{\"deliveryId\":\"d1\",\"status\":\"delivered\"}]".getBytes(StandardCharsets.UTF_8);
        String ts = now();
        String sig = sign(SECRET, ts, raw);
        byte[] tampered = "[{\"deliveryId\":\"d2\",\"status\":\"delivered\"}]".getBytes(StandardCharsets.UTF_8);

        assertFalse(adapter().verify(tampered, headers(sig, ts)));
    }

    @Test
    void aWrongSecretFails() throws Exception {
        byte[] raw = "[]".getBytes(StandardCharsets.UTF_8);
        String ts = now();

        assertFalse(adapter().verify(raw, headers(sign("not-the-secret", ts, raw), ts)));
    }

    @Test
    void aStaleTimestampFailsEvenWithAGenuineSignature() throws Exception {
        byte[] raw = "[]".getBytes(StandardCharsets.UTF_8);
        String stale = String.valueOf(System.currentTimeMillis() / 1000 - 3600);

        // The signature really is ours — this is exactly the replay a signature check alone cannot stop.
        assertFalse(adapter().verify(raw, headers(sign(SECRET, stale, raw), stale)),
                "a valid signature is replayable forever without a freshness window");
    }

    @Test
    void aFarFutureTimestampAlsoFails() throws Exception {
        byte[] raw = "[]".getBytes(StandardCharsets.UTF_8);
        String future = String.valueOf(System.currentTimeMillis() / 1000 + 3600);

        assertFalse(adapter().verify(raw, headers(sign(SECRET, future, raw), future)),
                "skew is checked both ways — a far-future stamp is a forgery signal too");
    }

    @Test
    void aMissingHeaderFailsWithoutThrowing() throws Exception {
        byte[] raw = "[]".getBytes(StandardCharsets.UTF_8);
        String ts = now();

        assertFalse(adapter().verify(raw, Map.of()));
        assertFalse(adapter().verify(raw, Map.of("x-inspecto-timestamp", ts)), "signature missing");
        assertFalse(adapter().verify(raw, Map.of("x-inspecto-signature", sign(SECRET, ts, raw))),
                "timestamp missing");
        assertFalse(adapter().verify(raw, null));
    }

    @Test
    void aNonHexSignatureIsRejectedNotAnError() {
        byte[] raw = "[]".getBytes(StandardCharsets.UTF_8);

        assertFalse(adapter().verify(raw, headers("zzzz-not-hex", now())),
                "garbage from an untrusted caller is a 403, never a 500");
    }

    @Test
    void headerCasingDoesNotMatter() throws Exception {
        byte[] raw = "[]".getBytes(StandardCharsets.UTF_8);
        String ts = now();

        assertTrue(adapter().verify(raw,
                Map.of("X-Inspecto-Signature", sign(SECRET, ts, raw), "X-Inspecto-Timestamp", ts)),
                "no provider guarantees header casing");
    }

    @Test
    void anUnsetSecretLeavesTheAdapterInert() throws Exception {
        HmacDeliveryStatusAdapter unconfigured = new HmacDeliveryStatusAdapter(null, 300);
        byte[] raw = "[]".getBytes(StandardCharsets.UTF_8);

        assertFalse(unconfigured.configured(), "no secret ⇒ the callback URL answers 404");
        assertFalse(unconfigured.verify(raw, headers(sign(SECRET, now(), raw), now())),
                "and it verifies nothing — inert, not permissive");
        assertTrue(adapter().configured());
    }

    @Test
    void everyStatusVocabularyIsNormalised() {
        byte[] raw = ("[{\"deliveryId\":\"d1\",\"status\":\"delivered\",\"ts\":\"1000\"},"
                + "{\"deliveryId\":\"d2\",\"status\":\"bounce_hard\",\"ts\":\"2000\"},"
                + "{\"deliveryId\":\"d3\",\"status\":\"soft_bounce\",\"ts\":\"3000\"},"
                + "{\"deliveryId\":\"d4\",\"status\":\"complaint\",\"ts\":\"4000\"}]")
                .getBytes(StandardCharsets.UTF_8);

        List<DeliveryEvent> events = adapter().parse(raw);

        assertEquals(4, events.size());
        assertEquals(DeliveryStatus.DELIVERED, events.get(0).status());
        assertEquals(1000L, events.get(0).ts());
        assertEquals(DeliveryStatus.BOUNCED_HARD, events.get(1).status());
        assertEquals(DeliveryStatus.BOUNCED_SOFT, events.get(2).status(), "soft_bounce is an accepted spelling");
        assertEquals(DeliveryStatus.COMPLAINED, events.get(3).status());
        assertTrue(events.stream().allMatch(e -> e.providerRaw() == null),
                "a classified event needs no raw payload kept");
    }

    @Test
    void anUnknownStatusIsRecordedWithItsPayloadNotDropped() {
        byte[] raw = "[{\"deliveryId\":\"d1\",\"status\":\"quarantined\",\"ts\":\"1000\"}]"
                .getBytes(StandardCharsets.UTF_8);

        List<DeliveryEvent> events = adapter().parse(raw);

        assertEquals(1, events.size(), "a new provider event type must never vanish");
        assertEquals(DeliveryStatus.UNKNOWN, events.get(0).status(), "and must never be guessed at");
        assertTrue(events.get(0).providerRaw().contains("quarantined"), events.get(0).providerRaw());
    }

    @Test
    void malformedOrIdlessPayloadsYieldNoEventsRatherThanThrowing() {
        // The route turns an empty event list into a 422; none of these may reach it as an exception.
        assertTrue(adapter().parse("not json at all".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(adapter().parse("{\"deliveryId\":\"d1\"}".getBytes(StandardCharsets.UTF_8)).isEmpty(),
                "an object, not the expected array");
        assertTrue(adapter().parse("[{\"status\":\"delivered\"}]".getBytes(StandardCharsets.UTF_8)).isEmpty(),
                "no deliveryId ⇒ nothing to correlate to");
        assertTrue(adapter().parse("[]".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void anEventWithNoTimestampIsStampedOnArrival() {
        long before = System.currentTimeMillis();

        List<DeliveryEvent> events =
                adapter().parse("[{\"deliveryId\":\"d1\",\"status\":\"delivered\"}]"
                        .getBytes(StandardCharsets.UTF_8));

        assertEquals(1, events.size());
        assertTrue(events.get(0).ts() >= before, "a missing provider timestamp falls back to now, never 0");
    }
}
