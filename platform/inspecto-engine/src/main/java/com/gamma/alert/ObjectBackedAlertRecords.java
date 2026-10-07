package com.gamma.alert;

import com.gamma.objects.ObjectAccess;
import com.gamma.workflow.ObjectType;

import java.util.Map;

/** {@link AlertRecords} over the engine's {@link ObjectAccess} seam — what AlertService did directly before. */
final class ObjectBackedAlertRecords implements AlertRecords {

    /** The {@code LinkRelationship} name for an escalation edge — the enum itself lives in the module. */
    private static final String ESCALATED_FROM = "ESCALATED_FROM";

    private final ObjectAccess objects;

    ObjectBackedAlertRecords(ObjectAccess objects) {
        this.objects = objects;
    }

    @Override public boolean hasActiveAlert(String scope, String rule) {
        return objects.hasActiveMatching(ObjectType.ALERT, scope, Map.of("rule", rule));
    }
    @Override public Map<String, String> activeAlertIndex(String scope, String attribute) {
        return objects.activeAttributeIndex(ObjectType.ALERT, scope, attribute);
    }
    @Override public Map<String, String> activeIncidentIndex(String scope, String attribute) {
        return objects.activeAttributeIndex(ObjectType.INCIDENT, scope, attribute);
    }
    @Override public String openAlert(String title, String message, String severity, String scope,
                                      Map<String, String> attributes) {
        return objects.open(ObjectType.ALERT, title, message, severity, scope, attributes);
    }
    @Override public boolean resolveAlert(String alertId, String actor) {
        return objects.transition(alertId, "resolve", actor);
    }
    @Override public boolean reopenIncident(String incidentId, String actor) {
        return objects.transition(incidentId, "reopen", actor);
    }
    @Override public void linkEscalation(String incidentId, String alertId, String actor) {
        objects.link(incidentId, alertId, ESCALATED_FROM, actor);
    }
}
