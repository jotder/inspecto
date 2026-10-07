package com.gamma.alert;

import com.gamma.objects.ObjectAccess;
import com.gamma.workflow.ObjectType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AlertRecords} over an {@link AlertStore} for the Alerts and, when the operational-object module is
 * present, over {@link ObjectAccess} for the Incident half. No ops module: the Incident reads answer empty and the
 * Incident writes are no-ops — Personal keeps Alerts and no Incidents.
 */
final class StoredAlertRecords implements AlertRecords {

    /** The {@code LinkRelationship} name for an escalation edge — the enum itself lives in the module. */
    private static final String ESCALATED_FROM = "ESCALATED_FROM";

    private final AlertStore store;
    private final Optional<ObjectAccess> objects;

    StoredAlertRecords(AlertStore store, Optional<ObjectAccess> objects) {
        this.store = store;
        this.objects = objects;
    }

    @Override public boolean hasActiveAlert(String scope, String rule) { return store.hasActive(scope, rule); }

    @Override public Map<String, String> activeAlertIndex(String scope, String attribute) {
        return store.activeIndex(scope, attribute);
    }

    @Override public Map<String, String> activeIncidentIndex(String scope, String attribute) {
        return objects.map(o -> o.activeAttributeIndex(ObjectType.INCIDENT, scope, attribute)).orElse(Map.of());
    }

    @Override public String openAlert(Alert fired, String title, String message, String severity, String scope,
                                      Map<String, String> attributes) {
        return store.open(title, message, severity, scope, attributes, fired);
    }

    @Override public boolean resolveAlert(String alertId, String actor) { return store.transition(alertId, "resolve", actor); }

    @Override public boolean reopenIncident(String incidentId, String actor) {
        return objects.map(o -> o.transition(incidentId, "reopen", actor)).orElse(false);
    }

    @Override public void linkEscalation(String incidentId, String alertId, String actor) {
        store.linkIncident(alertId, incidentId);
        objects.ifPresent(o -> o.linkSubject(incidentId, ObjectType.ALERT, alertId, ESCALATED_FROM, actor));
    }

    @Override public List<Alert> recentFired(int limit) { return store.recentFired(limit); }
}
