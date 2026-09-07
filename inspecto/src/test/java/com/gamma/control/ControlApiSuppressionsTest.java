package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.notify.DbDeliveryReceiptStore;
import com.gamma.notify.DeliveryReceipt;
import com.gamma.notify.DeliveryStatus;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `D8-SUPPRESS-1`'s operator surface over real HTTP — one case per gate of
 * {@code GET/DELETE /notifications/suppressions}.
 *
 * <p>⚠ The interesting half is not the happy path but the DISARMED deployment: with the default
 * in-memory receipt store, suppression cannot act, and the routes have to say so rather than reporting an
 * empty list (which reads identically to "nothing is suppressed") or a success that changed nothing.
 */
class ControlApiSuppressionsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @AfterEach
    void clearBackend() {
        System.clearProperty("delivery.receipts.backend");
        System.clearProperty("delivery.receipts.db.url");
    }

    /**
     * @param durable when true, point the receipt family at a real DuckDB file so suppression arms —
     *                the property must be set BEFORE construction, since the store is opened there.
     */
    private Ctx open(Path dir, boolean durable) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        if (durable) {
            System.setProperty("delivery.receipts.backend", "duckdb");
            System.setProperty("delivery.receipts.db.url",
                    "jdbc:duckdb:" + dir.resolve("receipts.db").toAbsolutePath().toString().replace('\\', '/'));
        }
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** Seed a hard-bounced destination straight through the store the service opened. */
    private static void seedHardBounce(CollectorService svc, String target, long at) {
        DeliveryReceipt r = svc.deliveryReceipts().add(
                new DeliveryReceipt(null, "n1", "c1", target, at, Map.of(), null, false));
        svc.deliveryReceipts().stamp(r.deliveryId(), DeliveryStatus.BOUNCED_HARD, at, "550 no such user");
    }

    private HttpResponse<String> send(int port, String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    // ---- GET: the read surface -------------------------------------------------------------------

    @Test
    void listsTheSuppressedDestinationsWithTheReason(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertTrue(c.svc.deliveryReceipts().durable(), "the durable store is the one under test");
            seedHardBounce(c.svc, "gone@x.com", System.currentTimeMillis());

            HttpResponse<String> r = send(c.port, "GET", "/notifications/suppressions");
            assertEquals(200, r.statusCode(), r.body());
            // ⚠ A 2xx /api/v1 body is the envelope {data, metadata, …} — but V1Body.of ALREADY peels
            // `data` off (it is the same unwrap the SPA's v1Interceptor does). Reaching for .get("data")
            // on top of it lands on null, which surfaces as an NPE reading like a handler bug.
            JsonNode data = json(r);
            assertTrue(data.get("armed").asBoolean());
            assertEquals(1, data.get("total").asInt());
            assertFalse(data.get("truncated").asBoolean());
            JsonNode first = data.get("suppressions").get(0);
            assertEquals("gone@x.com", first.get("target").asText());
            assertTrue(first.get("reason").asText().contains("hard-bounced"), first.toString());
        }
    }

    /**
     * 🔴 The case that matters most. Without a durable store suppression cannot act, and an empty list
     * would be indistinguishable from "nothing is suppressed" — so the route reports `armed: false`.
     */
    @Test
    void aDisarmedDeploymentSaysSoRatherThanReturningAnEmptyList(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            assertFalse(c.svc.deliveryReceipts().durable(), "the default store is in-memory");
            seedHardBounce(c.svc, "gone@x.com", System.currentTimeMillis());

            JsonNode data = json(send(c.port, "GET", "/notifications/suppressions"));
            assertFalse(data.get("armed").asBoolean(),
                    "the bounce is recorded but cannot be honoured — the operator must be able to tell");
            assertEquals(0, data.get("suppressions").size());
        }
    }

    @Test
    void theListingIsBoundedAndReportsTheTrueTotal(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < 5; i++) seedHardBounce(c.svc, "gone" + i + "@x.com", now);

            JsonNode data = json(send(c.port, "GET", "/notifications/suppressions?limit=2"));
            assertEquals(2, data.get("suppressions").size(), "the page is capped");
            assertEquals(5, data.get("total").asInt(), "…and the TRUE total is reported, not the page size");
            assertTrue(data.get("truncated").asBoolean());
        }
    }

    // ---- DELETE: gates ---------------------------------------------------------------------------

    @Test
    void aBlankTargetIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(422, send(c.port, "DELETE", "/notifications/suppressions").statusCode());
            assertEquals(422, send(c.port, "DELETE", "/notifications/suppressions?target=%20%20").statusCode());
        }
    }

    /** A store that cannot hold an override must refuse — reporting success would report no change. */
    @Test
    void unsuppressingWithoutADurableStoreIs409(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            HttpResponse<String> r = send(c.port, "DELETE", "/notifications/suppressions?target=gone@x.com");
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(json(r).at("/error/message").asText().contains("delivery.receipts.backend"),
                    "the refusal names the property that would fix it");
        }
    }

    @Test
    void unsuppressingForgivesTheAddressAndLeavesTheEvidence(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            long bouncedAt = System.currentTimeMillis();
            seedHardBounce(c.svc, "gone@x.com", bouncedAt);
            assertEquals(1, json(send(c.port, "GET", "/notifications/suppressions")).get("total").asInt());

            assertEquals(200, send(c.port, "DELETE", "/notifications/suppressions?target=gone@x.com")
                    .statusCode());

            assertEquals(0, json(send(c.port, "GET", "/notifications/suppressions")).get("total").asInt(),
                    "no longer suppressed");
            // 🔴 …and the receipt survives. Forgiving is not forgetting: the audit trail must still show
            // the address bounced and that someone chose to deliver to it again.
            assertTrue(c.svc.deliveryReceipts()
                    .latestWithStatus("gone@x.com", DeliveryStatus.BOUNCED_HARD).isPresent());
        }
    }

    /**
     * 🔴 "Cleared by the next suppressing event" — the half that stops an override permanently masking a
     * genuinely dead destination. The override forgives history up to its own timestamp and nothing after.
     */
    @Test
    void aLaterBounceReSuppressesAnAddressThatWasForgiven(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            seedHardBounce(c.svc, "gone@x.com", System.currentTimeMillis() - 60_000L);
            assertEquals(200, send(c.port, "DELETE", "/notifications/suppressions?target=gone@x.com")
                    .statusCode());
            assertEquals(0, json(send(c.port, "GET", "/notifications/suppressions")).get("total").asInt());

            // The address is tried again and bounces again, AFTER the override.
            seedHardBounce(c.svc, "gone@x.com", System.currentTimeMillis() + 60_000L);

            assertEquals(1, json(send(c.port, "GET", "/notifications/suppressions")).get("total").asInt(),
                    "a bounce after the override is not forgiven");
        }
    }
}
