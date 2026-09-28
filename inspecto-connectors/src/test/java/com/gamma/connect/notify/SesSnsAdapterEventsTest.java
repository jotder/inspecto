package com.gamma.connect.notify;

import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatus;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D8-SES-SNS-1 slice S2 — the {@code control} path (D10) and the SES → {@link DeliveryStatus} mapping (§4.3,
 * D11), driven by the S0 fixtures through the adapter as the route calls it: {@code verify}, then
 * {@code control}, then {@code parse}.
 */
class SesSnsAdapterEventsTest {

    private final List<SnsEnvelope> confirmations = new CopyOnWriteArrayList<>();

    private SesSnsDeliveryStatusAdapter adapter() throws Exception {
        X509Certificate cert = SnsTestPki.signer().cert();
        return new SesSnsDeliveryStatusAdapter(Set.of(SnsFixtures.TOPIC), env -> cert, confirmations::add, 3600, 300);
    }

    private static DeliveryEvent only(SesSnsDeliveryStatusAdapter a, String ses) throws Exception {
        // each SNS publish has its own MessageId; the fixtures share one, which the adapter would call a replay
        byte[] raw = SnsFixtures.sign(SnsFixtures.notification(ses), "2", SnsTestPki.signer().key(),
                e -> e.addProperty("MessageId", java.util.UUID.randomUUID().toString()));
        assertTrue(a.verify(raw, Map.of()), ses);
        assertEquals(Optional.empty(), a.control(raw), "a Notification is not a control message");
        List<DeliveryEvent> events = a.parse(raw);
        assertEquals(1, events.size(), ses);
        return events.get(0);
    }

    // ---- T-S8: every row of §4.3 ------------------------------------------------------------------------------

    @Test
    void everySesEventMapsAsDesigned() throws Exception {
        var a = adapter();
        Map<String, DeliveryStatus> expected = Map.ofEntries(
                Map.entry("delivery", DeliveryStatus.DELIVERED),
                Map.entry("bounce-permanent", DeliveryStatus.BOUNCED_HARD),
                Map.entry("legacy-bounce-permanent", DeliveryStatus.BOUNCED_HARD),
                Map.entry("bounce-transient", DeliveryStatus.BOUNCED_SOFT),
                Map.entry("bounce-undetermined", DeliveryStatus.BOUNCED_SOFT),
                Map.entry("complaint", DeliveryStatus.COMPLAINED),
                Map.entry("reject", DeliveryStatus.BOUNCED_HARD),
                Map.entry("rendering-failure", DeliveryStatus.BOUNCED_HARD),
                Map.entry("delivery-delay", DeliveryStatus.UNKNOWN),   // D11
                Map.entry("open", DeliveryStatus.UNKNOWN));
        for (var e : expected.entrySet()) {
            DeliveryEvent ev = only(a, e.getKey());
            assertEquals(e.getValue(), ev.status(), e.getKey());
            assertEquals(SnsFixtures.DELIVERY_ID, ev.deliveryId(), e.getKey() + ": correlated by our Message-ID");
            assertEquals(ev.status() == DeliveryStatus.UNKNOWN, ev.providerRaw() != null,
                    e.getKey() + ": only an UNKNOWN carries its raw payload");
        }
        assertEquals(java.time.Instant.parse("2026-09-28T11:59:59.000Z").toEpochMilli(),
                only(a, "bounce-permanent").ts(), "the event's own time, not the publish time");
    }

    @Test
    void anSesEventThatIsNotOursIsAnUnknownUnderAnIdNoReceiptHas() throws Exception {
        var a = adapter();
        JsonObject env = SnsFixtures.notification("bounce-permanent");
        env.addProperty("Message", env.get("Message").getAsString().replace("inspecto.d0a1b2c3", "someone-else"));
        byte[] raw = SnsFixtures.sign(env);
        assertTrue(a.verify(raw, Map.of()));
        List<DeliveryEvent> events = a.parse(raw);
        assertEquals(1, events.size(), "never empty — an empty parse is a 422, and SNS retries a non-2xx");
        assertTrue(events.get(0).deliveryId().startsWith("ses:"));
        assertEquals(DeliveryStatus.UNKNOWN, events.get(0).status());
    }

    @Test
    void theMessageIdIsAlsoReadFromTheRawHeadersWhenCommonHeadersLackIt() throws Exception {
        var a = adapter();
        JsonObject env = SnsFixtures.notification("complaint");
        env.addProperty("Message", env.get("Message").getAsString()
                .replace("\"messageId\":\"<inspecto.d0a1b2c3@example.test>\"", "\"messageId\":\"<ses-own@email.amazonses.com>\""));
        byte[] raw = SnsFixtures.sign(env);
        assertTrue(a.verify(raw, Map.of()));
        assertEquals(SnsFixtures.DELIVERY_ID, a.parse(raw).get(0).deliveryId());
    }

    // ---- control messages (D10) and T-S6 de-duplication -------------------------------------------------------

    @Test
    void aSubscriptionConfirmationIsAControlMessageHandedToTheConfirmerOnce() throws Exception {
        var a = adapter();
        byte[] raw = SnsFixtures.sign(SnsFixtures.envelope("subscription-confirmation"));
        assertTrue(a.verify(raw, Map.of()));
        assertEquals(Optional.of("SubscriptionConfirmation"), a.control(raw));
        assertEquals(1, confirmations.size());
        assertEquals(List.of(), a.parse(raw), "a control message carries no delivery events");

        assertTrue(a.verify(raw, Map.of()), "a replay inside the window still verifies…");
        assertEquals(Optional.of("duplicate"), a.control(raw), "…and is a duplicate");
        assertEquals(1, confirmations.size(), "a replayed SubscriptionConfirmation causes no second confirmation");
    }

    @Test
    void anUnsubscribeConfirmationIsAcknowledgedAndNeverReSubscribes() throws Exception {
        var a = adapter();
        byte[] raw = SnsFixtures.sign(SnsFixtures.envelope("unsubscribe-confirmation"));
        assertTrue(a.verify(raw, Map.of()));
        assertEquals(Optional.of("UnsubscribeConfirmation"), a.control(raw));
        assertEquals(List.of(), confirmations);
    }

    @Test
    void aReplayedNotificationIsADuplicateAndDoesNoWork() throws Exception {
        var a = adapter();
        byte[] raw = SnsFixtures.sign(SnsFixtures.notification("bounce-permanent"));
        assertEquals(Optional.empty(), a.control(raw), "control: first sight is not a duplicate");
        assertEquals(Optional.of("duplicate"), a.control(raw));
    }
}
