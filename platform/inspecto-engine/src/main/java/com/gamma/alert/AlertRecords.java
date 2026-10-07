package com.gamma.alert;

import com.gamma.objects.IncidentAccess;
import com.gamma.objects.ObjectAccess;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The port through which {@link AlertService} keeps its <b>persisted Alert records</b> and the Incident lookups
 * that sit beside them (MODULE-REORG-P7-INCIDENTS, slices 1 and 2, 2026-10-07).
 *
 * <p>Slice 1 made this the one seam. Slice 2 (operator decision 2026-10-07: Personal keeps Alert history and
 * restart-safe de-duplication) moved the Alert records <b>out of the generic object substrate</b> into the
 * Alert-owned {@link AlertStore}; {@link StoredAlertRecords} is the only implementation. The Incident half —
 * the active-Incident index, re-open, and the {@code ESCALATED_FROM} reference — still goes to the optional
 * operational-object module when it is present and is skipped when it is not (Personal has Alerts but no
 * Incidents).
 *
 * <p>Naming: not "store" (the {@link AlertStore} is the physical backend) and not "ledger" (the Signal / batch
 * ledgers).
 *
 * <p>⚠ Incident <em>promotion</em> is deliberately NOT here — it stays on
 * {@link com.gamma.objects.IncidentAccess}, the Platform Service a granted Run also uses.
 */
public interface AlertRecords {

    /** Records over {@code store}, with the Incident half over {@code objects} when the ops module is present. */
    static AlertRecords of(AlertStore store, Optional<ObjectAccess> objects) {
        return new StoredAlertRecords(store, objects);
    }

    /** The Incident promotion that pairs with {@link #of}: the Platform Service over the objects, or one that opens nothing. */
    static IncidentAccess incidentsOf(Optional<ObjectAccess> objects) {
        return objects.map(o -> IncidentAccess.over(() -> o)).orElse(NO_INCIDENTS);
    }

    /** Opens nothing — the Incident promotion on a build without the operational-object module. */
    IncidentAccess NO_INCIDENTS = (title, message, severity, scope, attributes, dedupeAttribute) -> Optional.empty();

    /** Whether a non-terminal Alert for {@code rule} exists in {@code scope}. */
    boolean hasActiveAlert(String scope, String rule);

    /** {@code attribute} value → id of every non-terminal Alert in {@code scope} carrying it (first wins). */
    Map<String, String> activeAlertIndex(String scope, String attribute);

    /** {@code attribute} value → id of every non-terminal INCIDENT in {@code scope} carrying it; empty without ops. */
    Map<String, String> activeIncidentIndex(String scope, String attribute);

    /** Persist one fired {@link Alert}; the new record's id. */
    String openAlert(Alert fired, String title, String message, String severity, String scope,
                     Map<String, String> attributes);

    /** Resolve an Alert as {@code actor}; {@code false} when unknown or not legal from its state. */
    boolean resolveAlert(String alertId, String actor);

    /** Acknowledge an Alert as {@code actor}; {@code false} when unknown or not legal from its state. */
    boolean acknowledgeAlert(String alertId, String actor);

    /** One stored Alert by id, in any state. */
    Optional<AlertStore.Row> findAlert(String alertId);

    /** The most recent stored Alerts that carry a fired {@link Alert}, newest first (any state). */
    List<AlertStore.Row> recentAlertRows(int limit);

    /** Re-open a (resolved) Incident as {@code actor}; {@code false} when unknown, not legal, or no ops module. */
    boolean reopenIncident(String incidentId, String actor);

    /**
     * Record that {@code incidentId} was escalated from {@code alertId}: the Alert row keeps the Incident id, and —
     * when the ops module is present — the Incident gets an {@code ESCALATED_FROM} reference to the Alert
     * ({@code kind ALERT + id}; the Alert is not an operational object, so the edge has no node behind it).
     */
    void linkEscalation(String incidentId, String alertId, String actor);

    /** The fired Alerts of the most recent records, newest first — re-seeds the in-memory ring after a restart. */
    List<Alert> recentFired(int limit);
}
