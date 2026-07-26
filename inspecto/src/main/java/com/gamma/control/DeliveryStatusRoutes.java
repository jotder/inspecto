package com.gamma.control;

import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryReceipt;
import com.gamma.notify.DeliveryReceiptStore;
import com.gamma.notify.DeliveryStatus;
import com.gamma.notify.DeliveryStatusAdapter;
import com.sun.net.httpserver.HttpExchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Inbound delivery-status callbacks (BACKLOG D8) — a provider telling us what happened to an email we
 * sent, plus the read surface over the resulting {@link DeliveryReceipt}s.
 *
 * <h3>The callback URL</h3>
 * {@code POST /api/v1/public/delivery-status/{adapterId}}. Note the {@code /api/v1} prefix: it is
 * <b>required</b> even though the route is "public". {@code routeDispatch} rejects anything that is
 * neither {@code /api/v1} nor an infra probe before route matching happens, so a bare
 * {@code /public/delivery-status/…} request 404s. This is the same shape as the {@code /public/dashboards}
 * precedent, and it is what an operator must paste into the provider's console.
 *
 * <h3>Gate order — fail-closed, verification before every write</h3>
 * <ol>
 *   <li>Unknown <b>or unconfigured</b> adapter → <b>404</b>. Identical responses for both, deliberately:
 *       a different error for "exists but unconfigured" would enumerate which adapters this deployment
 *       has.</li>
 *   <li>Signature absent, invalid, or stale → <b>403</b>, nothing written, an audit record made. An
 *       unverified callback must never be able to mark a destination dead — that would be a cheap
 *       denial-of-notification vector, which is the whole reason verification precedes the write.</li>
 *   <li>Payload that yields no usable events → <b>422</b>.</li>
 *   <li>Every {@code deliveryId} unknown → <b>202 accepted</b>, not an error. Receipts are prunable, and
 *       providers retry forever on a non-2xx: rejecting a callback for a receipt we already forgot would
 *       buy an infinite retry loop and no information.</li>
 *   <li>Otherwise → <b>200</b> with what was stamped.</li>
 * </ol>
 */
final class DeliveryStatusRoutes implements RouteModule {

    /** Adapters are configured from system properties, so discovery once per API instance is correct. */
    private final List<DeliveryStatusAdapter> adapters = discoverAdapters();

    private static List<DeliveryStatusAdapter> discoverAdapters() {
        List<DeliveryStatusAdapter> found = new ArrayList<>();
        for (DeliveryStatusAdapter a : ServiceLoader.load(DeliveryStatusAdapter.class)) {
            if (a.configured()) found.add(a);
        }
        return List.copyOf(found);
    }

    @Override
    public void register(ApiContext api) {
        api.post("/public/delivery-status/([^/]+)", (e, m) -> callback(api, e, ApiContext.name(m)));
        // The read surface: receipts + their statuses. An authoring capability, matching how
        // notification-rule shipped (backend + HTTP only, no UI editor).
        api.get("/notifications/deliveries", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> deliveries(api, e)));
    }

    private Object callback(ApiContext api, HttpExchange e, String adapterId) throws Exception {
        DeliveryStatusAdapter adapter = adapterById(adapterId);
        if (adapter == null) throw new ApiException(404, "no delivery-status adapter '" + adapterId + "'");

        // rawBody, never body(): the provider signed these exact bytes, and a re-serialised map would not
        // reproduce their key order or whitespace. Parsing happens inside the adapter, after verification.
        byte[] raw = api.rawBody(e);
        if (!adapter.verify(raw, headers(e))) {
            // The audit record D8 asks for comes free: ControlApi.dispatch records every request with its
            // status, so a rejected callback lands in the trail as a 403 on this path. Nothing is written
            // to any receipt before this point.
            throw new ApiException(403, "delivery-status callback signature rejected");
        }

        List<DeliveryEvent> events = adapter.parse(raw);
        if (events.isEmpty()) throw new ApiException(422, "no delivery-status events in payload");

        DeliveryReceiptStore receipts = api.service().deliveryReceipts();
        List<Map<String, Object>> stamped = new ArrayList<>();
        int unknown = 0;
        for (DeliveryEvent event : events) {
            var updated = receipts.stamp(event.deliveryId(), event.status(), event.ts(), event.providerRaw());
            if (updated.isEmpty()) {
                unknown++;
                continue;
            }
            stamped.add(Map.of("deliveryId", event.deliveryId(),
                    "status", event.status().name(),
                    "ts", event.ts()));
        }
        if (stamped.isEmpty()) {
            // Verified, well-formed, but every receipt is gone. Accept it: 2xx stops the provider retrying.
            return ApiContext.respondJson(e, 202,
                    Map.of("accepted", events.size(), "stamped", 0, "unknown", unknown));
        }
        return Map.of("accepted", events.size(), "stamped", stamped.size(), "unknown", unknown,
                "events", stamped);
    }

    private Object deliveries(ApiContext api, HttpExchange e) {
        DeliveryReceiptStore receipts = api.service().deliveryReceipts();
        String notificationId = ApiContext.query(e, "notificationId");
        List<DeliveryReceipt> found = notificationId == null || notificationId.isBlank()
                ? receipts.recent(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 100))
                : receipts.forNotification(notificationId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeliveryReceipt r : found) rows.add(toMap(r));
        return Map.of("deliveries", rows);
    }

    private static Map<String, Object> toMap(DeliveryReceipt r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deliveryId", r.deliveryId());
        out.put("notificationId", r.notificationId());
        out.put("channelConfigId", r.channelConfigId());
        out.put("target", r.target());
        out.put("sentAt", r.sentAt());
        out.put("digest", r.digest());
        out.put("hardBounced", r.hardBounced());
        // Every observed status with its own timestamp — a delivered-then-complained message shows both,
        // which is the whole point of the map (D8 §3).
        Map<String, Object> statusAt = new LinkedHashMap<>();
        for (Map.Entry<DeliveryStatus, Long> entry : r.statusAt().entrySet()) {
            statusAt.put(entry.getKey().name(), entry.getValue());
        }
        out.put("statusAt", statusAt);
        if (r.providerRaw() != null) out.put("providerRaw", r.providerRaw());
        return out;
    }

    private DeliveryStatusAdapter adapterById(String id) {
        if (id == null) return null;
        for (DeliveryStatusAdapter a : adapters) {
            if (a.id().equalsIgnoreCase(id)) return a;
        }
        return null;
    }

    /** Request headers flattened to first-value-wins with lower-cased keys, as the SPI specifies. */
    private static Map<String, String> headers(HttpExchange e) {
        Map<String, String> out = new LinkedHashMap<>();
        e.getRequestHeaders().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                out.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        return out;
    }
}
