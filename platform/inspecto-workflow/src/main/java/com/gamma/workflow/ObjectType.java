package com.gamma.workflow;

import java.util.Locale;

/**
 * The kind of {@link OperationalObject} — Layer 2 of the Operational Intelligence Platform
 * ({@code docs/superpowers/specs/2026-06-13-operational-intelligence-roadmap.md}). Unlike the
 * immutable {@code EVENT} layer (Phase 1), these are <b>mutable</b> objects with a lifecycle, so they
 * live in a table store rather than rolling Parquet (§0 of the roadmap).
 *
 * <p>One object table is keyed by this type. <b>Phase 2 introduces {@link #ALERT}</b>; {@link #INCIDENT}
 * (Phase 3), {@link #CASE} (Phase 4) and {@link #TASK} share the same table and machinery, so later
 * phases add lifecycle/links, not storage.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public enum ObjectType {
    /**
     * Legacy: Alerts left the object store in MODULE-REORG-P7 and live in the Alert-owned {@code AlertStore}; no code
     * writes an ALERT object any more (slice 2 moved the rule-fired Alerts, the ALERT residue retirement of
     * 2026-10-08 moved the Event bridge's gap / imbalance Alerts). The value stays ONLY for persisted data: a
     * deployed Space's object table still holds ALERT rows (the one-shot {@code AlertMigration} copies the active
     * ones and never deletes), the {@code ESCALATED_FROM} link names an Alert as {@code kind ALERT}, and the Alert
     * lifecycle is {@link Workflow#defaultFor Workflow.defaultFor(ALERT)}.
     *
     * <p><b>Retirement condition</b> (all three): (1) {@code AlertMigration} has shipped for one more release and is
     * removed; (2) object loading tolerates an unknown legacy type, i.e. {@code DbObjectStore} /
     * {@code InMemoryObjectStore} and the {@code ObjectRoutes} list / get load a row with an unrecognised
     * {@code type} inert and listed with a diagnostic, never rewritten and never dropped (today a stored {@code ALERT}
     * row parses only because this value exists); (3) the Alert lifecycle constants move into {@code com.gamma.alert}
     * and the SPA {@code GOVERNED_OBJECT_TYPES} / objects panels drop {@code ALERT}.
     */
    @Deprecated(forRemoval = true)
    ALERT,
    INCIDENT, CASE, TASK;

    /**
     * Parse a type name case-insensitively. {@code null}/blank returns {@code null} ("no constraint",
     * for query filters); an unrecognised non-blank value throws {@link IllegalArgumentException} so
     * callers can surface a 400 rather than silently matching everything.
     */
    public static ObjectType of(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown object type '" + s + "' (expected one of "
                    + java.util.Arrays.toString(values()) + ")");
        }
    }
}
