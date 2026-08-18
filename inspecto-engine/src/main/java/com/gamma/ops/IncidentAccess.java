package com.gamma.ops;

import com.gamma.api.PublicApi;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Platform Service {@code incidents} (platform-services S1-4): open a managed
 * {@link ObjectType#INCIDENT} under the active-object convention — while a non-terminal Incident
 * for the same scope carries the same dedupe-attribute value, a duplicate is suppressed (an
 * operator handling one breach is never handed a clone). Granted to a Run via a Job Type's
 * {@code requires: [incidents]} declaration; the engine itself is the first consumer —
 * {@link com.gamma.alert.AlertService}'s high-severity promotion opens through this same path.
 *
 * <h3>Dry-run contract (plan §3.4)</h3>
 * Under a dry run the framework substitutes a recording stand-in: {@link #openIncident} logs the
 * would-be Incident to the RunLog and opens nothing, returning empty.
 *
 * @since 5.1.0
 */
@PublicApi(since = "5.1.0")
public interface IncidentAccess {

    /**
     * Open an Incident for {@code scope} (the correlation id) unless one is already active with the
     * same value for {@code dedupeAttribute} — the caller-supplied business key inside
     * {@code attributes} (e.g. {@code "rule"} for alert promotion).
     *
     * @return the opened Incident, or empty when suppressed as a duplicate (or under a dry run)
     */
    Optional<OperationalObject> openIncident(String title, String message, String severity,
                                             String scope, Map<String, String> attributes,
                                             String dedupeAttribute);

    /** The production implementation over an {@link ObjectService}, resolved lazily so boot wiring
     *  can register the service before the Object Engine is constructed. */
    static IncidentAccess over(Supplier<ObjectService> objects) {
        return (title, message, severity, scope, attributes, dedupeAttribute) -> {
            ObjectService svc = objects.get();
            // ⚠ No dedupe VALUE means no dedupe — never a match. `Objects.equals(null, null)` is true, so
            // comparing an absent key against another Incident that also lacks it made two unrelated
            // Incidents in the same scope look like duplicates and SILENTLY swallowed the second. Nothing in
            // this contract obliges a caller to put `dedupeAttribute` in `attributes`, and the engine's own
            // two callers only avoid it by always populating "rule". An extra Incident an operator can close
            // beats an Incident that never opened.
            String key = dedupeAttribute == null ? null : attributes.get(dedupeAttribute);
            boolean active = key != null && !key.isBlank()
                    && svc.active(ObjectType.INCIDENT, scope).stream()
                        .anyMatch(o -> key.equals(o.attributes().get(dedupeAttribute)));
            if (active) return Optional.empty();
            return Optional.of(svc.open(ObjectType.INCIDENT, title, message, severity, scope, attributes));
        };
    }
}
