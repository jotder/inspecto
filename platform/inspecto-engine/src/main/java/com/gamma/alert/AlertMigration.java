package com.gamma.alert;

import com.gamma.objects.ObjectAccess;
import com.gamma.workflow.ObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one-shot adoption of pre-slice-2 Alerts (MODULE-REORG-P7-INCIDENTS slice 2, 2026-10-07).
 *
 * <p>Until then every Alert {@link AlertService} fired was an {@code ALERT} <em>object</em> in the operational
 * object store, and those still-active rows carry the de-duplication state: without them a freshly upgraded Space
 * would re-open every Alert still being handled. At boot, <b>when the Alert store is empty and the ops module is
 * present</b>, each non-terminal ALERT object is copied into the store under its own id (so an Incident's existing
 * {@code ESCALATED_FROM} link, which names that id, still points at it) with its state and timestamps.
 *
 * <p>⛔ Never destructive: the object rows are left untouched (user data), and the migration is idempotent — a
 * non-empty store is never touched again, so a second boot adopts nothing. Resolved ALERT objects are history, not
 * state, and stay where they are. A Space with no ops module has nothing to adopt.
 */
public final class AlertMigration {

    private static final Logger log = LoggerFactory.getLogger(AlertMigration.class);

    /** {@code EventObjectBridge.GAP_RULE} / {@code IMBALANCE_RULE} — spelled here because the bridge is in the optional module. */
    private static final java.util.Set<String> BRIDGE_RULES = java.util.Set.of("sequence_gap", "conservation_imbalance");

    private AlertMigration() {}

    /** Adopt the still-active ALERT objects of {@code objects} into {@code store}; the number copied (0 when skipped). */
    public static int adopt(ObjectAccess objects, AlertStore store) {
        if (objects == null || store == null) return 0;
        try {
            if (store.size() > 0) return 0;
            List<Map<String, Object>> active = objects.activeDetail(ObjectType.ALERT);
            int copied = 0;
            for (Map<String, Object> o : active) {
                @SuppressWarnings("unchecked")
                Map<String, String> attrs = o.get("attributes") instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, String>) m) : Map.of();
                // The Event bridge's gap / imbalance ALERTs are not Alert Rule firings and stay objects (slice 2 report).
                if (BRIDGE_RULES.contains(attrs.get("rule"))) continue;
                store.insert(new AlertStore.Row(String.valueOf(o.get("id")), str(o.get("title")), str(o.get("description")),
                        str(o.get("severity")), str(o.get("correlationId")), attrs, str(o.get("status")),
                        o.get("createdAt") instanceof Number n ? n.longValue() : System.currentTimeMillis(), 0L, null, null, null));
                copied++;
            }
            if (copied > 0)
                log.info("[ALERTS] adopted {} still-active ALERT object(s) into the Alert store (the object rows are left untouched)", copied);
            return copied;
        } catch (RuntimeException e) {
            log.warn("[ALERTS] could not adopt the legacy ALERT objects — de-duplication of Alerts opened before this "
                    + "upgrade is lost, nothing else: {}", e.getMessage());
            return 0;
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
