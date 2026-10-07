package com.gamma.control;

import com.gamma.audit.AuditAttrs;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.mask.EvidenceMasker;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Read-time masking of audit rows (decision D-P8, operator 2026-10-06: <b>mask on read, no read-audit yet</b>).
 *
 * <p>An {@code AUDIT} / {@code ACCESS_DENIED} row served to a caller WITHOUT {@link #UNMASK_CAPABILITY} has every
 * classified value replaced by the Space's {@code masked:<16 hex>} token ({@link EvidenceMasker#tokenFor}, the same
 * key and token as Risk Score evidence). A value is classified when its attribute or payload key names a column
 * the Space's Dataset registry classifies {@link EvidenceMasker#SENSITIVE} — the one resolver
 * ({@link EvidenceMasker#sensitiveColumns}: registry {@code columns[].classification} plus schema lineage). A masked
 * value is also replaced where it stands in the row's {@code message}. Fail closed: when any Dataset's lineage
 * cannot be traced, every attribute outside the platform's own {@link AuditAttrs} keys, and every payload value,
 * is masked.
 *
 * <p>⛔ Stored rows stay RAW: the hash chain is over raw content, so {@code /audit/verify} and the anchors are
 * untouched. Reads of the audit trail are NOT themselves audited (deferred by D-P8). Other event types pass through.
 * A request with no Subject (Personal, or no auth module) is unrestricted, as every {@code withCapability} gate is.
 */
public final class AuditReadMasking {

    /** The existing unmask capability (Link Analysis reveal, D-U6) — reused so one grant means "may see raw keys". */
    public static final String UNMASK_CAPABILITY = "canRevealLinkEntities";

    /** The shortest masked value also replaced inside the free-text message (shorter ones would garble prose). */
    static final int MIN_INLINE = 4;

    private AuditReadMasking() {}

    /** The row view for this request: identity for a privileged caller, else the masked copy (JSON and CSV alike). */
    public static UnaryOperator<Event> forRequest(ApiContext api, HttpExchange ex) {
        boolean privileged = ApiContext.subject(ex).map(s -> s.capabilities().contains(UNMASK_CAPABILITY)).orElse(true);
        Path root = api.writeRoot();
        if (privileged || root == null) return UnaryOperator.identity();
        Set<String> classified = classifiedColumns(new ComponentStore(root.resolve("registry")), new ViewStore(root.resolve("views")));
        if (classified.isEmpty()) return UnaryOperator.identity();
        EvidenceMasker masker = EvidenceMasker.forSpace(root);
        return e -> mask(e, classified, masker::tokenFor);
    }

    /** Every Dataset's sensitive columns, lower-cased; {@link EvidenceMasker#UNKNOWN_LINEAGE} when any is untraceable. */
    static Set<String> classifiedColumns(ComponentStore registry, ViewStore views) {
        Set<String> out = new TreeSet<>();
        for (ComponentRegistry.Component c : registry.list("dataset"))
            out.addAll(EvidenceMasker.sensitiveColumns(registry, views, c.name()));
        return out;
    }

    static Event mask(Event e, Set<String> classified, Function<String, String> token) {
        if (!EventType.AUDIT.equals(e.type()) && !EventType.ACCESS_DENIED.equals(e.type())) return e;
        boolean unknown = classified.contains(EvidenceMasker.UNKNOWN_LINEAGE);
        Map<String, String> replaced = new LinkedHashMap<>();
        Map<String, String> attrs = new LinkedHashMap<>();
        e.attributes().forEach((k, v) -> {
            boolean hit = classified.contains(k.toLowerCase(Locale.ROOT)) || unknown && !AuditAttrs.ALL.contains(k);
            attrs.put(k, hit && v != null ? replaced.computeIfAbsent(v, token) : v);
        });
        Map<String, Object> payload = new LinkedHashMap<>();
        e.payload().forEach((k, v) -> {
            boolean hit = classified.contains(k.toLowerCase(Locale.ROOT)) || unknown;
            payload.put(k, hit && v != null ? replaced.computeIfAbsent(String.valueOf(v), token) : v);
        });
        String msg = e.message();
        if (msg != null)
            for (Map.Entry<String, String> r : replaced.entrySet())
                if (r.getKey().length() >= MIN_INLINE) msg = msg.replace(r.getKey(), r.getValue());
        return new Event(e.eventId(), e.ts(), e.level(), e.type(), e.source(), e.pipeline(), e.correlationId(), msg,
                java.util.Collections.unmodifiableMap(attrs), java.util.Collections.unmodifiableMap(payload));
    }
}
