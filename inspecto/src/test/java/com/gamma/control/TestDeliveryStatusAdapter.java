package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.notify.DeliveryEvent;
import com.gamma.notify.DeliveryStatus;
import com.gamma.notify.DeliveryStatusAdapter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Test-only {@link DeliveryStatusAdapter} on the ServiceLoader path, driving
 * {@link ControlApiDeliveryStatusTest}. The real adapters live in {@code inspecto-connectors}, which is
 * deliberately not a dependency of this module — the route's contract is with the SPI, not with any
 * provider, and this stub keeps the HTTP gates testable without importing one.
 *
 * <p><b>Inert unless {@code -Dtest.deliverystatus.enabled} is set</b>, and {@link #configured()} is read
 * once when {@code DeliveryStatusRoutes} is constructed — so the property must be set before
 * {@code new ControlApi(...)}. Being inert by default is what keeps this harmless to every other
 * {@code ControlApi*Test} in the module.
 */
public final class TestDeliveryStatusAdapter implements DeliveryStatusAdapter {

    /** Set this to make the adapter discoverable; read at {@code ControlApi} construction time. */
    public static final String ENABLED_PROPERTY = "test.deliverystatus.enabled";

    /** The signature this stub accepts, in {@code X-Test-Signature}. */
    public static final String GOOD_SIGNATURE = "good";

    /** The exact bytes the last {@code verify} was handed — the rawBody byte-identity assertion. */
    public static volatile byte[] lastRaw;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String id() {
        return "test";
    }

    @Override
    public boolean configured() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    @Override
    public boolean verify(byte[] raw, Map<String, String> headers) {
        lastRaw = raw;
        return GOOD_SIGNATURE.equals(headers.get("x-test-signature"));
    }

    @Override
    public List<DeliveryEvent> parse(byte[] raw) {
        List<DeliveryEvent> events = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(new String(raw, StandardCharsets.UTF_8));
            if (!root.isArray()) return events;
            for (JsonNode e : root) {
                String deliveryId = e.path("deliveryId").asText(null);
                if (deliveryId == null || deliveryId.isBlank()) continue;
                DeliveryStatus status;
                try {
                    status = DeliveryStatus.valueOf(e.path("status").asText("UNKNOWN"));
                } catch (IllegalArgumentException ex) {
                    status = DeliveryStatus.UNKNOWN;
                }
                events.add(new DeliveryEvent(deliveryId, status, e.path("ts").asLong(0),
                        status == DeliveryStatus.UNKNOWN ? e.toString() : null));
            }
        } catch (Exception ex) {
            return List.of();   // the route turns an empty list into a 422
        }
        return events;
    }
}
