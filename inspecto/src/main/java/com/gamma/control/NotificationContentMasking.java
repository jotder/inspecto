package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.mask.EvidenceMasker;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The "notification content is ALWAYS masked" policy (OBJECT-READ-MASKING-RESIDUALS-1 residual 2). A notification is
 * rendered ONCE from an {@link Event} and then goes to recipients that cannot be capability-checked (mail, webhook,
 * the shared in-app feed read by many), so the Event is masked as a caller WITHOUT
 * {@link AuditReadMasking#UNMASK_CAPABILITY} would see it BEFORE any title, body or per-channel template renders from it
 * (the {@code ReportRowMasking} precedent). Reuses the read-time helpers, no second masking logic: classified AUDIT
 * values ({@link AuditReadMasking#mask}), the {@code OBJECT_OPENED} title ({@link AuditReadMasking#maskOpened}, no
 * object lookup, so a legacy title fails closed) and {@code key.<column>} / {@code entityKey} attributes
 * ({@link ObjectReadMasking}, fail closed when the event names no {@code dataset}). Wired into
 * {@code NotificationService.contentMasking}. A Space with no write root has no registry to classify against and is left as is.
 */
public final class NotificationContentMasking {

    private NotificationContentMasking() {}

    /** The always-masking Event view for a Space whose config root is {@code configRoot} (resolved per event, so a late registry edit applies). */
    public static UnaryOperator<Event> of(Supplier<Path> configRoot) {
        return e -> {
            Path root = configRoot.get();
            return root == null ? e : mask(e, root);
        };
    }

    static Event mask(Event e, Path root) {
        EvidenceMasker masker = EvidenceMasker.forSpace(root);
        Set<String> classified = AuditReadMasking.classifiedColumns(
                new ComponentStore(root.resolve("registry")), new ViewStore(root.resolve("views")));
        ObjectReadMasking objects = ObjectReadMasking.of(root);
        Event m = classified.isEmpty() ? e : AuditReadMasking.mask(e, classified, masker::tokenFor);
        m = AuditReadMasking.maskOpened(m, objects, id -> Optional.empty());
        return maskKeys(m, objects, masker);
    }

    /** {@code key.<column>} / {@code entityKey} in the attributes (and payload), and the raw values inline in the message. */
    @SuppressWarnings("unchecked")
    private static Event maskKeys(Event e, ObjectReadMasking objects, EvidenceMasker masker) {
        Map<String, String> attrs = e.attributes();
        if (attrs.keySet().stream().noneMatch(k -> k.startsWith("key.") || k.equals("entityKey"))) return e;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", e.message());
        if (e.correlationId() != null) row.put("correlationId", e.correlationId());
        row.put("attributes", attrs);
        Map<String, Object> out = objects.maskObject(row);
        Map<String, Object> payload = new LinkedHashMap<>();
        e.payload().forEach((k, v) -> payload.put(k, v != null && objects.hides(attrs, k) ? masker.tokenFor(String.valueOf(v)) : v));
        return new Event(e.eventId(), e.ts(), e.level(), e.type(), e.source(), e.pipeline(),
                (String) out.getOrDefault("correlationId", e.correlationId()), (String) out.get("title"),
                (Map<String, String>) out.get("attributes"), java.util.Collections.unmodifiableMap(payload));
    }
}
