package com.gamma.alert;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a Space keeps its <b>Alert records</b> (MODULE-REORG-P7-INCIDENTS slice 2, 2026-10-07): the durable
 * state behind {@link AlertRecords} — one row per Alert that {@link AlertService} opened, with the
 * {@link AlertLifecycle} ({@code OPEN → ACKNOWLEDGED → RESOLVED}), and the fired {@link Alert}
 * itself so {@code GET /alerts} history survives a restart.
 *
 * <p>This is the persistence half only (GLOSSARY: <em>store</em> = the physical backend). The Incident lookups
 * that sit beside an Alert stay on the port's implementation ({@link StoredAlertRecords}). Two backends:
 * {@link InMemoryAlertStore} (tests, explicit {@code -Dalerts.backend=memory}) and {@link DbAlertStore}
 * (DuckDB per Space by default, PostgreSQL through {@code -Dinspecto.db=postgres}) — the OperationalDb family
 * {@code ALERTS}.
 *
 * <p>⚠ Alerts are available on EVERY edition, Personal included; this store does not depend on the optional
 * operational-object module.
 */
public interface AlertStore extends AutoCloseable {

    /** One stored Alert. {@code fired} is {@code null} for a row adopted from a legacy ALERT object. */
    record Row(String id, String title, String message, String severity, String scope,
               Map<String, String> attributes, String state, long openedAt, long closedAt, String closedBy,
               String incidentId, Alert fired) {
        public Row {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            scope = scope == null ? "" : scope;
        }
    }

    /** Persist {@code row} as given (its id, state and timestamps are the caller's). */
    void insert(Row row);

    /**
     * Move {@code alertId} through {@code action} ({@code ack} / {@code resolve}) as {@code actor}; {@code false} when
     * unknown or illegal. The actor of the move that closed the Alert is kept ({@link Row#closedBy}) as the audit trail.
     */
    boolean transition(String alertId, String action, String actor);

    /** Every non-terminal Alert in {@code scope}, newest first (the ObjectService order; {@code null} scope reads as the empty scope). */
    List<Row> active(String scope);

    /** Every non-terminal Alert of the Space, newest first (the one-shot migration and diagnostics). */
    List<Row> allActive();

    /** Link {@code alertId} to the Incident that was escalated from it. */
    void linkIncident(String alertId, String incidentId);

    /** One Alert row by id (any state), or empty when unknown. */
    Optional<Row> get(String alertId);

    /** The most recent rows that carry a fired {@link Alert}, newest first (any state). */
    List<Row> recentRows(int limit);

    /** The fired Alerts of the most recent records, newest first — what re-seeds the in-memory ring after a restart. */
    default List<Alert> recentFired(int limit) {
        return recentRows(limit).stream().map(Row::fired).toList();
    }

    /** How many Alert rows the store holds (any state). */
    long size();

    @Override
    default void close() {}

    // ── derived reads — one definition shared by every backend ────────────────────────────────────────

    /** Whether a non-terminal Alert for {@code rule} exists in {@code scope}. */
    default boolean hasActive(String scope, String rule) {
        return active(scope).stream().anyMatch(r -> rule.equals(r.attributes().get("rule")));
    }

    /** {@code attribute} value → id of every non-terminal Alert in {@code scope} carrying it (first wins). */
    default Map<String, String> activeIndex(String scope, String attribute) {
        if (attribute == null || attribute.isBlank()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Row r : active(scope)) {
            String v = r.attributes().get(attribute);
            if (v != null && !v.isBlank()) out.putIfAbsent(v, r.id());
        }
        return out;
    }

    /** Open one Alert now: a fresh id, state {@code OPEN}. Returns the id. */
    default String open(String title, String message, String severity, String scope,
                        Map<String, String> attributes, Alert fired) {
        String id = "ALERT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        insert(new Row(id, title, message, severity, scope, attributes, AlertLifecycle.initialState(),
                System.currentTimeMillis(), 0L, null, null, fired));
        return id;
    }
}
