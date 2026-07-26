package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.notify.DeliveryReceipt;
import com.gamma.notify.DeliveryReceiptStore;
import com.gamma.notify.DeliveryStatus;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D8 inbound delivery-status callbacks over real HTTP — one case per gate of
 * {@link DeliveryStatusRoutes}: unknown/unconfigured adapter 404, unverified 403 <b>with nothing
 * written</b>, empty parse 422, all-unknown ids 202, and the happy path's additive stamping. Plus the
 * raw-body seam the signature check rests on, and the {@code /notifications/deliveries} read surface.
 *
 * <p>The adapter under test is {@link TestDeliveryStatusAdapter} on the ServiceLoader path — the real
 * ones live in {@code inspecto-connectors} and are unit-tested there. What this class owns is the route.
 */
class ControlApiDeliveryStatusTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void disableTheAdapter() {
        System.clearProperty(TestDeliveryStatusAdapter.ENABLED_PROPERTY);
        TestDeliveryStatusAdapter.lastRaw = null;
    }

    /** The property must be set <em>before</em> construction: routes discover configured adapters once. */
    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        System.setProperty(TestDeliveryStatusAdapter.ENABLED_PROPERTY, "true");
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private static DeliveryReceipt seed(CollectorService svc, String deliveryId, String notificationId) {
        return svc.deliveryReceipts().add(new DeliveryReceipt(deliveryId, notificationId, "c1",
                "ops@x.com", System.currentTimeMillis(), Map.of(), null, false));
    }

    private HttpResponse<String> callback(int port, String adapterId, String body, String signature)
            throws Exception {
        HttpRequest.Builder b = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/api/v1/public/delivery-status/" + adapterId))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (signature != null) b.header("X-Test-Signature", signature);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1" + path)).GET().build(), BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    private static String event(String deliveryId, DeliveryStatus status, long ts) {
        return "[{\"deliveryId\":\"" + deliveryId + "\",\"status\":\"" + status + "\",\"ts\":" + ts + "}]";
    }

    // ---- gate 1: unknown or unconfigured adapter -------------------------------------------------

    @Test
    void anUnknownAndAnUnconfiguredAdapterAreIndistinguishable(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> unknown = callback(c.port, "no-such-provider", "[]", "good");
            HttpResponse<String> unconfigured = callback(c.port, "test-inert", "[]", "good");

            assertEquals(404, unknown.statusCode());
            assertEquals(404, unconfigured.statusCode(),
                    "an adapter with no key configured is inert, not a different error");
            // Same shape and same message-modulo-the-id: nothing here reveals which adapters exist.
            assertEquals(json(unknown).at("/error/errorCode"), json(unconfigured).at("/error/errorCode"));
        }
    }

    // ---- gate 2: signature ------------------------------------------------------------------------

    @Test
    void anUnverifiedCallbackIs403AndWritesNothing(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c.svc, "d1", "n1");
            String hardBounce = event("d1", DeliveryStatus.BOUNCED_HARD, 2000L);

            assertEquals(403, callback(c.port, "test", hardBounce, "wrong").statusCode());
            assertEquals(403, callback(c.port, "test", hardBounce, null).statusCode(), "absent signature");

            DeliveryReceipt after = c.svc.deliveryReceipts().get("d1").orElseThrow();
            assertTrue(after.statusAt().isEmpty(),
                    "verification precedes every write — an unverified caller must not be able to mark a "
                            + "destination dead, which would be a cheap denial-of-notification vector");
            assertFalse(after.hardBounced());
        }
    }

    // ---- gate 3: nothing usable in the payload ----------------------------------------------------

    @Test
    void aVerifiedPayloadWithNoUsableEventsIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(422, callback(c.port, "test", "[]", "good").statusCode());
            assertEquals(422, callback(c.port, "test", "not json at all", "good").statusCode());
            assertEquals(422, callback(c.port, "test", "{\"deliveryId\":\"d1\"}", "good").statusCode(),
                    "an object where the provider sends an array");
        }
    }

    // ---- gate 4: every id unknown -----------------------------------------------------------------

    @Test
    void aCallbackForAForgottenReceiptIsAcceptedNotRejected(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r =
                    callback(c.port, "test", event("pruned-away", DeliveryStatus.DELIVERED, 2000L), "good");

            assertEquals(202, r.statusCode(),
                    "receipts are prunable and providers retry forever on a non-2xx — rejecting this would "
                            + "buy an infinite retry loop and no information");
            assertEquals(0, json(r).get("stamped").asInt());
            assertEquals(1, json(r).get("unknown").asInt());
        }
    }

    // ---- gate 5: the happy path -------------------------------------------------------------------

    @Test
    void aVerifiedCallbackStampsTheReceiptAndReportsWhatItDid(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c.svc, "d1", "n1");

            HttpResponse<String> r =
                    callback(c.port, "test", event("d1", DeliveryStatus.DELIVERED, 2000L), "good");

            assertEquals(200, r.statusCode());
            JsonNode body = json(r);
            assertEquals(1, body.get("stamped").asInt());
            assertEquals(0, body.get("unknown").asInt());
            assertEquals("d1", body.get("events").get(0).get("deliveryId").asText());
            assertEquals("DELIVERED", body.get("events").get(0).get("status").asText());

            assertEquals(2000L, c.svc.deliveryReceipts().get("d1").orElseThrow()
                    .statusAt().get(DeliveryStatus.DELIVERED));
        }
    }

    @Test
    void deliveredThenComplainedBothSurviveAcrossTwoCallbacks(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c.svc, "d1", "n1");

            assertEquals(200, callback(c.port, "test",
                    event("d1", DeliveryStatus.DELIVERED, 2000L), "good").statusCode());
            assertEquals(200, callback(c.port, "test",
                    event("d1", DeliveryStatus.COMPLAINED, 3000L), "good").statusCode());

            // The spam-button sequence D8 §3 exists for: the later status must not erase the earlier one.
            JsonNode row = json(get(c.port, "/notifications/deliveries?notificationId=n1"))
                    .get("deliveries").get(0);
            assertEquals(2000L, row.at("/statusAt/DELIVERED").asLong());
            assertEquals(3000L, row.at("/statusAt/COMPLAINED").asLong());
            assertFalse(row.get("hardBounced").asBoolean());
        }
    }

    @Test
    void aBatchedCallbackStampsWhatItCanAndCountsTheRest(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c.svc, "d1", "n1");
            String batch = "[{\"deliveryId\":\"d1\",\"status\":\"BOUNCED_HARD\",\"ts\":2000},"
                    + "{\"deliveryId\":\"gone\",\"status\":\"DELIVERED\",\"ts\":2000}]";

            JsonNode body = json(callback(c.port, "test", batch, "good"));

            assertEquals(2, body.get("accepted").asInt());
            assertEquals(1, body.get("stamped").asInt());
            assertEquals(1, body.get("unknown").asInt(), "a partial batch is still a 200, not a failure");
            assertTrue(c.svc.deliveryReceipts().get("d1").orElseThrow().hardBounced());
        }
    }

    @Test
    void anUnclassifiedEventIsRecordedWithItsRawPayload(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c.svc, "d1", "n1");

            assertEquals(200, callback(c.port, "test",
                    "[{\"deliveryId\":\"d1\",\"status\":\"quarantined\",\"ts\":2000}]", "good").statusCode());

            JsonNode row = json(get(c.port, "/notifications/deliveries")).get("deliveries").get(0);
            assertTrue(row.at("/statusAt/UNKNOWN").asLong() > 0, "recorded, not dropped and not guessed at");
            assertTrue(row.get("providerRaw").asText().contains("quarantined"), row.toString());
        }
    }

    // ---- the raw-body seam (§4.1) -----------------------------------------------------------------

    @Test
    void theAdapterSeesTheExactBytesThatArrived(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            seed(c.svc, "d1", "n1");
            // Key order and spacing chosen so no re-serialisation of a parsed Map could reproduce them —
            // which is precisely why a signature cannot be verified against body().
            String odd = "[ {\"ts\":2000,  \"status\":\"DELIVERED\",\n\"deliveryId\":\"d1\"} ]";

            assertEquals(200, callback(c.port, "test", odd, "good").statusCode());

            assertArrayEquals(odd.getBytes(StandardCharsets.UTF_8), TestDeliveryStatusAdapter.lastRaw,
                    "rawBody hands over the request bytes byte-identically, whitespace and key order intact");
            assertEquals(2000L, c.svc.deliveryReceipts().get("d1").orElseThrow()
                    .statusAt().get(DeliveryStatus.DELIVERED),
                    "and the handler still parsed them afterwards — the single-read stream is cached");
        }
    }

    // ---- the read surface (§4.6) ------------------------------------------------------------------

    @Test
    void theReadSurfaceListsReceiptsNewestFirstAndFiltersByNotification(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            DeliveryReceiptStore store = c.svc.deliveryReceipts();
            store.add(new DeliveryReceipt("old", "n1", "c1", "a@x.com", 1000L, Map.of(), null, false));
            store.add(new DeliveryReceipt("new", "n1", "c1", "b@x.com", 3000L, Map.of(), null, false));
            store.add(new DeliveryReceipt("other", "n2", "c2", "c@x.com", 2000L, Map.of(), null, true));

            JsonNode all = json(get(c.port, "/notifications/deliveries")).get("deliveries");
            assertEquals(3, all.size());
            assertEquals("new", all.get(0).get("deliveryId").asText(), "newest send first");
            assertEquals("b@x.com", all.get(0).get("target").asText());
            assertTrue(all.get(0).get("statusAt").isObject(), "statusAt is always present, empty until stamped");

            JsonNode filtered = json(get(c.port, "/notifications/deliveries?notificationId=n2"))
                    .get("deliveries");
            assertEquals(1, filtered.size());
            assertEquals("other", filtered.get(0).get("deliveryId").asText());
            assertTrue(filtered.get(0).get("digest").asBoolean(), "the digest asymmetry is visible to a reader");

            assertEquals(1, json(get(c.port, "/notifications/deliveries?limit=1")).get("deliveries").size());
        }
    }
}
