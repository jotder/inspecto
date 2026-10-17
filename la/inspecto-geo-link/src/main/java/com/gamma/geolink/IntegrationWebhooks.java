package com.gamma.geolink;

import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.auth.secrets.SecretResolver;
import com.gamma.control.HostContext;
import com.gamma.control.ModuleSettings;
import com.gamma.event.EventLog;
import com.gamma.la.api.LaIntegration;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.OutboundPort;
import com.gamma.la.core.OutboundPorts;
import com.gamma.service.SpaceContext;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import org.slf4j.MDC;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * <b>Signed event webhooks</b> of Link Analysis third-party integration (LA-EDITIONS-1 phase 6): Investigation created,
 * Draft promoted, an Alert fired on an Investigation rule, and approval needed (a four-eyes request) - sent to the Space's
 * {@code webhooks.connection} when its {@link LaIntegration} subscribes to the event.
 *
 * <p><b>The event bridge, reused.</b> A process-wide {@link EventLog#addTap tap} (the seam the Exchange's Signal forwarder
 * uses), so every Space's ledger is seen - including Alerts the engine fires off a request thread. The notification
 * webhook channel does not fit (one JVM-wide URL, unsigned, no retry), so delivery goes through the same
 * {@link OutboundPort} as the case hand-off: the Connection rules, the Space's egress allowlist with a pinned address, no
 * redirects and the Collector retry policy.
 *
 * <p><b>Signature.</b> {@code X-Inspecto-Signature: sha256=<hex>} = HMAC-SHA256 under the secret that
 * {@code webhooks.signingSecret} (a {@code ${…}} reference, resolved at send time) names, over
 * {@code <X-Inspecto-Timestamp> + "." + <body>}. A receiver recomputes it, compares in constant time and refuses a
 * timestamp outside its tolerance (replay). {@code X-Inspecto-Delivery-Id} and {@code Idempotency-Key} carry the source
 * event's id, identical on every retry, so a receiver drops a repeat.
 *
 * <p><b>Masking.</b> A webhook carries NO entity id - only Investigation, Draft, request and rule names and counts - so
 * there is nothing for the Space's masking mode to act on; the payload says so. A receiver reads the Investigation through
 * the API, where masking applies.
 *
 * <p><b>Audit.</b> Every delivery writes {@link LinkEventTypes#LINK_WEBHOOK_DELIVERED} or {@link LinkEventTypes#LINK_WEBHOOK_FAILED}
 * on the same Space's ledger. Delivery is asynchronous (a virtual thread per event): it never slows or fails the act it reports.
 */
public final class IntegrationWebhooks implements BiConsumer<EventLog, Event>, RouteModule {

    static final String SIGNATURE_HEADER = "X-Inspecto-Signature";
    static final String TIMESTAMP_HEADER = "X-Inspecto-Timestamp";
    static final String DELIVERY_HEADER = "X-Inspecto-Delivery-Id";
    static final String EVENT_HEADER = "X-Inspecto-Event";

    /** Attributes a webhook may carry - names, ids of Link Analysis records and counts; never an entity id. */
    private static final List<String> CARRIED = List.of("investigationId", "dataset", "draftId", "requestId", "fromStep", "toStep",
            "steps", "promotedBy", "approvedBy", "rule", "metric", "value", "severity", "budget", "maxFanOut", "exceeded");

    /** The one tap (installed once per JVM; it serves every registered API). */
    static final IntegrationWebhooks TAP = new IntegrationWebhooks();

    /** Where deliveries run - a virtual thread each; a test swaps in a direct executor. */
    static volatile Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    private final CopyOnWriteArrayList<WeakReference<ApiContext>> apis = new CopyOnWriteArrayList<>();

    /** ServiceLoader constructor (the {@link RouteModule} registration); the work is done by {@link #TAP}. */
    public IntegrationWebhooks() {}

    /** Registers no route: it attaches this API's Spaces to the tap. */
    @Override
    public void register(ApiContext api) {
        TAP.apis.removeIf(w -> w.get() == null);
        TAP.apis.add(new WeakReference<>(api));
        EventLog.addTap(TAP);
        api.onClose(() -> TAP.apis.removeIf(w -> w.get() == null || w.get() == api));
    }

    /** The webhook event an {@link Event} is, or {@code null}. */
    static String webhookEvent(Event e) {
        return switch (e.type()) {
            case LinkEventTypes.LINK_INVESTIGATION_CREATED -> "investigation.created";
            case LinkEventTypes.LINK_DRAFT_PROMOTED -> "draft.promoted";
            case LinkEventTypes.LINK_EXPANSION_REQUESTED -> "approval.needed";
            case EventType.ALERT_FIRED -> e.attributes().get("investigation") != null ? "alert.fired" : null;
            default -> null;
        };
    }

    @Override
    public void accept(EventLog log, Event event) {
        String name = webhookEvent(event);
        if (name == null) return;
        // LA-EDITIONS-1 ED-6: third-party integration is Standard and above, with the Investigation surface it reports on.
        if (!com.gamma.etl.EditionFeatures.present(com.gamma.etl.EditionFeatures.INVESTIGATIONS)) return;
        for (WeakReference<ApiContext> w : apis) {
            ApiContext api = w.get();
            if (api == null) continue;
            Optional<Path> root = rootOf(api, log);
            if (root.isEmpty()) continue;
            try {
                if (ModuleSettings.disabled(root.get()).contains("geoLink")) return;
                LaIntegration.Settings s = LaIntegration.read(root.get());
                if (s.webhooks() == null || !s.webhooks().events().contains(name)) return;
                Map<String, String> mdc = MDC.getCopyOfContextMap();
                executor.execute(() -> {
                    Map<String, String> before = MDC.getCopyOfContextMap();
                    if (mdc != null) MDC.setContextMap(mdc);
                    try {
                        deliver(log, root.get(), s, name, event);
                    } catch (RuntimeException never) {
                        // a delivery fault never escapes into the emitter
                    } finally {
                        if (before != null) MDC.setContextMap(before);
                        else MDC.clear();
                    }
                });
            } catch (RuntimeException neverBreakTheEmit) {
                // the tap is guarded by EventLog as well
            }
            return;
        }
    }

    /** The config root of the Space whose ledger {@code log} is, when it is one of {@code api}'s Spaces. */
    private static Optional<Path> rootOf(ApiContext api, EventLog log) {
        try {
            for (SpaceContext c : HostContext.of(api).spaces().all()) {
                if (c.service().eventLog() != log) continue;
                Path config = c.root().config();
                return Optional.ofNullable(config != null ? config : api.writeRoot());
            }
        } catch (RuntimeException closed) {
            // a closed API serves nothing
        }
        return Optional.empty();
    }

    /** Build, sign, send and audit one webhook. Package-private for the tests. */
    static void deliver(EventLog log, Path root, LaIntegration.Settings s, String name, Event event) {
        Map<String, String> a = event.attributes();
        String investigationId = a.getOrDefault("investigationId", a.get("investigation"));
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : CARRIED) if (a.get(k) != null) data.put(k, a.get(k));
        if (a.get("investigation") != null) data.put("investigationId", a.get("investigation"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", event.eventId());
        payload.put("event", name);
        payload.put("occurredAt", event.timestamp());
        payload.put("space", EventLog.currentSpaceId());
        payload.put("investigationId", investigationId);
        payload.put("actor", a.get(AuditAttrs.ACTOR));
        payload.put("data", data);
        payload.put("masking", "no entity ids are carried; read the Investigation through the API, where the Space's "
                + "masking mode applies");

        String secret;
        try {
            secret = SecretResolver.resolve(s.webhooks().signingSecret());
        } catch (RuntimeException unavailable) {
            secret = null;
        }
        if (secret == null || secret.isBlank()) {
            audit(log, false, name, event, investigationId, null, 0, "the signing secret reference does not resolve on this "
                    + "host — nothing was sent (a webhook is never sent unsigned)");
            return;
        }
        OutboundPort port = OutboundPorts.active().orElse(null);
        if (port == null) {
            audit(log, false, name, event, investigationId, null, 0, "outbound integration is not installed");
            return;
        }
        String body;
        try {
            body = ApiContext.JSON.writeValueAsString(payload);
        } catch (java.io.IOException e) {
            return;
        }
        String ts = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(EVENT_HEADER, name);
        headers.put(DELIVERY_HEADER, event.eventId());
        headers.put("Idempotency-Key", event.eventId());
        headers.put(TIMESTAMP_HEADER, ts);
        headers.put(SIGNATURE_HEADER, "sha256=" + sign(secret, ts, body));
        OutboundPort.Result r = port.send(root, s.webhooks().connection(), body, headers, null, s.retry());
        audit(log, r.delivered(), name, event, investigationId, r.status(), r.attempts(),
                r.delivered() ? null : (r.refused() ? "refused: " : "") + r.error());
    }

    /** HMAC-SHA256 of {@code timestamp + "." + body} under {@code secret}, lower-case hex. */
    static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static void audit(EventLog log, boolean ok, String name, Event source, String investigationId, Integer status,
                              int attempts, String reason) {
        try {
            Event.Builder b = Event.builder(ok ? LinkEventTypes.LINK_WEBHOOK_DELIVERED : LinkEventTypes.LINK_WEBHOOK_FAILED)
                    .source("inv").actor("system").actorType("system")
                    .action(ok ? "link.webhook.delivered" : "link.webhook.failed").actionCategory("integration")
                    .message((ok ? "link.webhook.delivered — " : "link.webhook.failed — ") + name + " " + source.eventId()
                            + (reason == null ? "" : ": " + reason))
                    .attr("event", name).attr("deliveryId", source.eventId())
                    .attr("investigationId", investigationId == null ? "" : investigationId)
                    .attr("status", status == null ? "" : String.valueOf(status)).attr("attempts", attempts);
            if (reason != null) b.attr("reason", reason);
            log.emit(b);
        } catch (RuntimeException ignored) {
            // best effort, like every audit emit
        }
    }
}
