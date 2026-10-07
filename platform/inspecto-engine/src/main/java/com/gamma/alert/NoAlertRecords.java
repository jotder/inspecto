package com.gamma.alert;

import com.gamma.objects.IncidentAccess;

import java.util.Map;
import java.util.Optional;

/**
 * The events-only {@link AlertRecords}: no operational-object module on this build (Personal), so no Alert or
 * Incident is recorded. Reads answer empty/false, writes are no-ops. Replaces the {@code objects == null}
 * guards {@link AlertService} used to carry; observable behaviour is identical (in-memory fired ring, the
 * ALERT_FIRED event and the Signal are all still produced by the service itself).
 */
final class NoAlertRecords implements AlertRecords {

    static final NoAlertRecords INSTANCE = new NoAlertRecords();

    /** The matching {@link IncidentAccess}: nothing is ever opened. */
    static final IncidentAccess NO_INCIDENTS = (title, message, severity, scope, attributes, dedupeAttribute) -> Optional.empty();

    private NoAlertRecords() {}

    @Override public boolean hasActiveAlert(String scope, String rule) { return false; }
    @Override public Map<String, String> activeAlertIndex(String scope, String attribute) { return Map.of(); }
    @Override public Map<String, String> activeIncidentIndex(String scope, String attribute) { return Map.of(); }
    @Override public String openAlert(String title, String message, String severity, String scope,
                                      Map<String, String> attributes) { return null; }
    @Override public boolean resolveAlert(String alertId, String actor) { return false; }
    @Override public boolean reopenIncident(String incidentId, String actor) { return false; }
    @Override public void linkEscalation(String incidentId, String alertId, String actor) { }
}
