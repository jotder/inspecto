package com.gamma.alert;

import com.gamma.objects.IncidentAccess;
import com.gamma.objects.ObjectAccess;

import java.util.Map;
import java.util.Optional;

/**
 * The port through which {@link AlertService} keeps its <b>persisted Alert records</b> and the Incident
 * lookups that sit beside them (MODULE-REORG-P7-INCIDENTS, slice 1, 2026-10-07).
 *
 * <p>Before this port {@link AlertService} held the generic {@code ObjectAccess} and answered "no operational
 * objects on this build" with a dozen {@code objects == null} guards. Now there is one seam with two
 * implementations: {@link ObjectBackedAlertRecords} (the ops module's objects, behaviour unchanged) and
 * {@link NoAlertRecords} (events-only: every read answers empty and every write is a no-op). The target
 * architecture moves ALERT out of the generic object substrate so Incidents can be extracted; this port is
 * the single place that move will land. Nothing about persistence changed in slice 1.
 *
 * <p>Naming: not "store" (GLOSSARY: the physical backend) and not "ledger" (the Signal / batch ledgers).
 *
 * <p>⚠ Incident <em>promotion</em> is deliberately NOT here — it stays on
 * {@link com.gamma.objects.IncidentAccess}, the Platform Service a granted Run also uses. This port holds only
 * the reads/links around it that a relapse needs (the active Incident for a key, re-open, link).
 */
public interface AlertRecords {

    /** The records over the ops module's objects when it is present, else the events-only no-op ({@link NoAlertRecords}). */
    static AlertRecords of(Optional<ObjectAccess> objects) {
        return objects.<AlertRecords>map(ObjectBackedAlertRecords::new).orElse(NoAlertRecords.INSTANCE);
    }

    /** The Incident promotion that pairs with {@link #of}: the Platform Service over the same objects, or one that opens nothing. */
    static IncidentAccess incidentsOf(Optional<ObjectAccess> objects) {
        return objects.map(o -> IncidentAccess.over(() -> o)).orElse(NoAlertRecords.NO_INCIDENTS);
    }

    /** Whether a non-terminal ALERT for {@code rule} exists in {@code scope}. */
    boolean hasActiveAlert(String scope, String rule);

    /** {@code attribute} value → id of every non-terminal ALERT in {@code scope} carrying it (first wins). */
    Map<String, String> activeAlertIndex(String scope, String attribute);

    /** As {@link #activeAlertIndex} for non-terminal INCIDENTs. */
    Map<String, String> activeIncidentIndex(String scope, String attribute);

    /** Persist one fired Alert; the new record's id (null when nothing is recorded). */
    String openAlert(String title, String message, String severity, String scope, Map<String, String> attributes);

    /** Resolve an Alert as {@code actor}; {@code false} when unknown or not legal from its state. */
    boolean resolveAlert(String alertId, String actor);

    /** Re-open a (resolved) Incident as {@code actor}; {@code false} when unknown or not legal. */
    boolean reopenIncident(String incidentId, String actor);

    /** Record that {@code incidentId} was escalated from {@code alertId} ({@code Incident ESCALATED_FROM Alert}). */
    void linkEscalation(String incidentId, String alertId, String actor);
}
